package com.dduo.notify.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BackoffPolicy 的装配边界:jitter 配置解析 + 曲线起点。
 * jitter 字符串来自 YAML("random"/"none"),拼错必须安全降级为 RANDOM,
 * 绝不能因为配置手误在生产里关掉抖动。
 */
class BackoffPolicyBoundaryTest {

    @Test
    void jitterFromParsesNoneCaseInsensitively() {
        assertEquals(BackoffPolicy.Jitter.NONE, BackoffPolicy.jitterFrom("none"));
        assertEquals(BackoffPolicy.Jitter.NONE, BackoffPolicy.jitterFrom("NONE"));
        assertEquals(BackoffPolicy.Jitter.NONE, BackoffPolicy.jitterFrom("None"));
    }

    @Test
    void jitterFromFallsBackToRandomForAnythingElse() {
        assertEquals(BackoffPolicy.Jitter.RANDOM, BackoffPolicy.jitterFrom("random"));
        assertEquals(BackoffPolicy.Jitter.RANDOM, BackoffPolicy.jitterFrom("radnom")); // 手误 → 保留抖动,安全的默认
        assertEquals(BackoffPolicy.Jitter.RANDOM, BackoffPolicy.jitterFrom(null));
        assertEquals(BackoffPolicy.Jitter.RANDOM, BackoffPolicy.jitterFrom(""));
    }

    @Test
    void firstRetryWaitsExactlyInitialBackoffWhenNoJitter() {
        BackoffPolicy p = new BackoffPolicy(5000, 2.0, 300_000, BackoffPolicy.Jitter.NONE);
        assertEquals(5000, p.backoffMillis(1), "failedAttempt=1 对应指数 0 次方,等待应恰为 initial");
    }

    @Test
    void defaultConstructorKeepsJitterOn() {
        // Dispatcher 生产路径用三参构造器:默认必须是 RANDOM,防重试风暴是刻意为之
        BackoffPolicy p = new BackoffPolicy(4000, 2.0, 30_000);
        long observedDistinct = java.util.stream.LongStream.range(0, 500)
                .map(i -> p.backoffMillis(3))
                .distinct()
                .count();
        org.junit.jupiter.api.Assertions.assertTrue(observedDistinct > 1,
                "默认策略若无抖动取值,500 次应全相同;出现多种取值才证明 RANDOM 生效");
    }
}
