package com.dduo.notify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.dduo.notify.api.MockSupplierController;
import com.dduo.notify.dispatch.DeliveryDispatcher;
import com.dduo.notify.model.DeliveryStatus;
import com.dduo.notify.model.NotificationRecord;
import com.dduo.notify.service.NotificationService;
import com.dduo.notify.service.NotificationServiceException;
import com.dduo.notify.store.NotificationRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端主链路验证(真实 HTTP:DEFINED_PORT 启动,打到进程内 mock 供应商):
 * 提交 -> 渲染 -> 投递 -> 失败退避 -> 重试成功 / 重试耗尽转死信 / 永久失败直接死信
 * -> 死信重投 -> 幂等 -> 崩溃恢复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
class NotificationLifecycleIntegrationTest {

    @Autowired
    private NotificationService service;

    @Autowired
    private DeliveryDispatcher dispatcher;

    @Autowired
    private NotificationRecordRepository repository;

    @Autowired
    private MockSupplierController mockSupplier;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        mockSupplier.reset();
        jdbc.update("DELETE FROM notification_record");
    }

    private static Map<String, Object> payload(String orderId) {
        Map<String, Object> p = new HashMap<>();
        p.put("orderId", orderId);
        p.put("userId", "u-" + orderId);
        return p;
    }

    /** 自动轮询在 test profile 关闭,由这里手动推进状态机,保证确定性 */
    private NotificationRecord awaitStatus(String uid, DeliveryStatus expected) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        NotificationRecord r = null;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.poll();
            r = service.find(uid);
            if (r != null && r.getStatus() == expected) {
                return r;
            }
            Thread.sleep(50);
        }
        assertNotNull(r);
        assertEquals(expected, r.getStatus(), "last error: " + r.getLastError());
        return r;
    }

    @Test
    void happyPath_deliveredSuccess() throws Exception {
        NotificationService.SubmitResult submitted = service.submit("ad-register", "ORD-1", payload("ORD-1"));
        assertEquals(NotificationService.SubmitOutcome.ACCEPTED, submitted.outcome);

        NotificationRecord done = awaitStatus(submitted.record.getRecordUid(), DeliveryStatus.SUCCESS);
        assertEquals(DeliveryStatus.SUCCESS, done.getStatus());
        assertEquals(0, done.getRetryCount());

        // 渲染正确性直达供应商:header 与 body 都替换了占位符
        List<Map<String, Object>> got = mockSupplier.received();
        assertEquals(1, got.size());
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) got.get(0).get("headers");
        String eventId = headers.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals("x-event-id"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        assertEquals("ORD-1", eventId);
        assertTrue(((String) got.get(0).get("body")).contains("\"userId\":\"u-ORD-1\""));
    }

    @Test
    void transientFailure_thenRetrySucceeds() throws Exception {
        mockSupplier.setFailMode(true); // 503 => 可重试
        NotificationService.SubmitResult submitted = service.submit("ad-register", "ORD-2", payload("ORD-2"));
        String uid = submitted.record.getRecordUid();

        NotificationRecord waiting = awaitStatus(uid, DeliveryStatus.RETRY_WAIT);
        assertEquals(1, waiting.getRetryCount());

        mockSupplier.setFailMode(false); // 供应商恢复
        awaitStatus(uid, DeliveryStatus.SUCCESS);
        // 供应商总共收到 2 次:第 1 次失败 + 第 2 次成功
        assertEquals(2, mockSupplier.received().size());
    }

    @Test
    void retryExhausted_movesToDead_thenReplaySucceeds() throws Exception {
        mockSupplier.setFailMode(true);
        NotificationService.SubmitResult submitted = service.submit("ad-register", "ORD-3", payload("ORD-3"));
        String uid = submitted.record.getRecordUid();

        NotificationRecord dead = awaitStatus(uid, DeliveryStatus.DEAD);
        // max-retry=3:首次 + 3 次重试 = 4 次尝试后放弃
        assertEquals(4, dead.getRetryCount());
        assertNotNull(dead.getLastError());

        mockSupplier.setFailMode(false);
        assertTrue(service.replay(uid));
        awaitStatus(uid, DeliveryStatus.SUCCESS);
    }

    @Test
    void permanentFailure_movesToDeadDirectly() throws Exception {
        mockSupplier.setRejectMode(true); // 400 => 永久失败
        NotificationService.SubmitResult submitted = service.submit("ad-register", "ORD-4", payload("ORD-4"));
        String uid = submitted.record.getRecordUid();

        NotificationRecord dead = awaitStatus(uid, DeliveryStatus.DEAD);
        // 永久失败不消耗重试次数
        assertEquals(0, dead.getRetryCount());
        assertTrue(dead.getLastError().contains("HTTP 400"));
    }

    @Test
    void duplicateSubmit_isIdempotent() {
        NotificationService.SubmitResult first = service.submit("ad-register", "ORD-5", payload("ORD-5"));
        NotificationService.SubmitResult again = service.submit("ad-register", "ORD-5", payload("ORD-5"));
        assertEquals(NotificationService.SubmitOutcome.DUPLICATE, again.outcome);
        assertEquals(first.record.getRecordUid(), again.record.getRecordUid());
        assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(*) FROM notification_record", Integer.class));
    }

    @Test
    void crashRecovery_stuckSendingIsReclaimedAndDelivered() throws Exception {
        NotificationService.SubmitResult submitted = service.submit("ad-register", "ORD-6", payload("ORD-6"));
        String uid = submitted.record.getRecordUid();
        NotificationRecord claimed = service.find(uid);

        // 模拟"进程在投递中途被杀":claim 成功后不再写回任何状态
        assertTrue(repository.claim(claimed));
        // 把 updated_at 人为推到 1 小时前,立即满足 stuck 判定(生产靠 stuck-timeout 自然到期)
        jdbc.update("UPDATE notification_record SET updated_at = ? WHERE record_uid = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), uid);

        // 崩溃的投递不会永远卡在 SENDING:被回收后最终送达
        NotificationRecord done = awaitStatus(uid, DeliveryStatus.SUCCESS);
        assertEquals(0, done.getRetryCount(), "被杀的那次尝试不应计为失败重试");
        assertEquals(1, mockSupplier.received().size(), "恢复后只补投一次,不重复成功");
    }

    @Test
    void unknownEventType_rejected() {
        try {
            service.submit("no-such-event", "ORD-7", payload("ORD-7"));
            fail("should reject");
        } catch (NotificationServiceException e) {
            assertEquals(NotificationServiceException.Code.UNKNOWN_EVENT_TYPE, e.getCode());
        }
    }
}
