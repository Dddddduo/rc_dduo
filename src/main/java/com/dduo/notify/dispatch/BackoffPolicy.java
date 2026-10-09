package com.dduo.notify.dispatch;

/**
 * 指数退避 + 全抖动(full jitter)。
 *
 * 第 n 次重试的等待区间是 [0, min(initial * multiplier^n, maxBackoff)],
 * 在区间内随机取值。抖动是刻意的:多个供应商同时故障时,
 * 纯指数退避会让所有重试在同一时刻扎堆打到刚恢复的供应商上(retry storm)。
 */
public class BackoffPolicy {

    private final long initialBackoffMs;
    private final double multiplier;
    private final long maxBackoffMs;
    private final Jitter jitter;

    public BackoffPolicy(long initialBackoffMs, double multiplier, long maxBackoffMs) {
        this(initialBackoffMs, multiplier, maxBackoffMs, Jitter.RANDOM);
    }

    BackoffPolicy(long initialBackoffMs, double multiplier, long maxBackoffMs, Jitter jitter) {
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
        this.maxBackoffMs = maxBackoffMs;
        this.jitter = jitter;
    }

    /**
     * @param failedAttempt 从 1 开始:第 1 次投递失败后计算"第 1 次重试"的等待时间
     * @return 需要等待的毫秒数
     */
    public long backoffMillis(int failedAttempt) {
        double capped = Math.min(maxBackoffMs, initialBackoffMs * Math.pow(multiplier, failedAttempt - 1));
        return jitter.apply(capped);
    }

    enum Jitter {
        NONE {
            @Override
            long apply(double capped) {
                return (long) capped;
            }
        },
        RANDOM {
            @Override
            long apply(double capped) {
                return (long) (Math.random() * capped);
            }
        };

        abstract long apply(double capped);
    }

    /** 供 Dispatcher 装配(见 DeliveryDispatcher 构造器) */
    public static Jitter jitterFrom(String name) {
        return "none".equalsIgnoreCase(name) ? Jitter.NONE : Jitter.RANDOM;
    }
}
