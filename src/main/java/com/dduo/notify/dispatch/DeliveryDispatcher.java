package com.dduo.notify.dispatch;

import java.util.List;

import com.dduo.notify.config.NotificationProperties;
import com.dduo.notify.delivery.DeliveryResult;
import com.dduo.notify.delivery.HttpNotifier;
import com.dduo.notify.model.NotificationRecord;
import com.dduo.notify.store.NotificationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 投递调度器:轮询 DB 中到期的记录,抢占后交给投递线程池执行。
 *
 * 为什么是"DB 轮询 + 乐观锁 claim"而不是内存队列:
 * 消息必须先持久化才谈可靠,内存队列(BMQ/BlockingQueue)在进程重启时丢在途消息,
 * 而 DB 本身就是队列——重启后扫描(见 resetStuck)即可恢复,且天然支持多实例
 * (claim 的条件 UPDATE 保证同一行只有一个实例能投)。
 * MVP 用固定间隔轮询;吞吐上来后的演进路径是 DB 变更通知/broker 驱动拉取,不需要改表结构。
 */
@Component
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final NotificationRecordRepository repository;
    private final HttpNotifier notifier;
    private final NotificationProperties props;
    private final BackoffPolicy backoff;
    private final Executor executor;

    public DeliveryDispatcher(NotificationRecordRepository repository,
                              HttpNotifier notifier,
                              NotificationProperties props,
                              Executor deliveryExecutor) {
        this.repository = repository;
        this.notifier = notifier;
        this.props = props;
        this.executor = deliveryExecutor;
        NotificationProperties.Delivery d = props.getDelivery();
        BackoffPolicy.Jitter jitter = "none".equalsIgnoreCase(d.getJitter())
                ? BackoffPolicy.Jitter.NONE : BackoffPolicy.Jitter.RANDOM;
        this.backoff = new BackoffPolicy(d.getInitialBackoffMs(), d.getBackoffMultiplier(),
                d.getMaxBackoffMs(), jitter);
    }

    /** 定时入口:开关关闭时什么都不做 */
    @Scheduled(fixedDelayString = "${notify.dispatch.poll-interval-ms:1000}")
    public void scheduledPoll() {
        if (props.getDispatch().isEnabled()) {
            poll();
        }
    }

    /** 一轮完整的"回收 -> 拉取 -> 抢占 -> 派发",对测试与手工触发开放 */
    public void poll() {
        long now = System.currentTimeMillis();

        // 崩溃恢复:每个轮询周期顺手回收卡死的 SENDING(进程被打断/线程异常遗留)
        int reset = repository.resetStuck(now - props.getDelivery().getStuckTimeoutMs());
        if (reset > 0) {
            log.info("reset {} stuck SENDING record(s) back to PENDING", reset);
        }

        List<NotificationRecord> due = repository.findDue(now, props.getDispatch().getBatchSize());
        for (NotificationRecord candidate : due) {
            // 抢占失败说明别的 worker(或上一轮)已经拿走,跳过
            if (!repository.claim(candidate)) {
                continue;
            }
            // claim 成功后 version 已 +1;同步回本地对象,后续状态写回带 version 防过期写
            candidate.setVersion(candidate.getVersion() + 1);
            executor.execute(() -> attempt(candidate));
        }
    }

    void attempt(NotificationRecord record) {
        try {
            DeliveryResult result = notifier.deliver(record);
            if (result.isSuccess()) {
                repository.markSuccess(record);
                log.info("delivered record={} event={} biz={}", record.getRecordUid(),
                        record.getEventType(), record.getBizId());
                return;
            }
            if (result.getKind() == DeliveryResult.Kind.PERMANENT) {
                repository.markDead(record, result.getDetail());
                log.warn("permanent failure, moved to DEAD: record={} {}", record.getRecordUid(), result);
                return;
            }
            // 可重试失败
            NotificationProperties.Delivery d = props.getDelivery();
            int attempt = record.getRetryCount() + 1;
            boolean exhausted = attempt > d.getMaxRetry();
            record.setRetryCount(attempt);
            long wait = backoff.backoffMillis(attempt);
            // 耗尽时 next_retry_at 已无意义,写 0 保持行可读
            repository.markFailure(record, exhausted ? 0 : System.currentTimeMillis() + wait,
                    result.getDetail(), exhausted);
            if (exhausted) {
                log.warn("retry exhausted, moved to DEAD: record={} attempts={} last={}",
                        record.getRecordUid(), attempt, result);
            } else {
                log.info("retry scheduled in {}ms: record={} attempt={} {}", wait,
                        record.getRecordUid(), attempt, result);
            }
        } catch (Exception e) {
            // 兜底:任何未预料异常都不能让记录永远停在 SENDING——
            // 即使这里写库也失败,resetStuck 也会在超时后回收
            log.error("delivery crashed for record={}", record.getRecordUid(), e);
            try {
                NotificationProperties.Delivery d = props.getDelivery();
                int attempt = record.getRetryCount() + 1;
                boolean exhausted = attempt > d.getMaxRetry();
                record.setRetryCount(attempt);
                repository.markFailure(record, exhausted ? 0
                                : System.currentTimeMillis() + backoff.backoffMillis(attempt),
                        "dispatcher exception: " + e.getMessage(), exhausted);
            } catch (Exception inner) {
                log.error("failed to persist failure state for {}", record.getRecordUid(), inner);
            }
        }
    }
}
