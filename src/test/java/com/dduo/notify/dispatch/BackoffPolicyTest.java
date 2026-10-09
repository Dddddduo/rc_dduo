package com.dduo.notify.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffPolicyTest {

    @Test
    void exponentialGrowthWithoutJitter() {
        BackoffPolicy p = new BackoffPolicy(1000, 2.0, 30_000, BackoffPolicy.Jitter.NONE);
        assertEquals(1000, p.backoffMillis(1));
        assertEquals(2000, p.backoffMillis(2));
        assertEquals(4000, p.backoffMillis(3));
    }

    @Test
    void cappedAtMaxBackoff() {
        BackoffPolicy p = new BackoffPolicy(1000, 2.0, 5000, BackoffPolicy.Jitter.NONE);
        assertEquals(5000, p.backoffMillis(10)); // 2^9 * 1000 远超上限,应被截断
    }

    @Test
    void fullJitterStaysInZeroToCapped() {
        BackoffPolicy p = new BackoffPolicy(4000, 2.0, 30_000);
        for (int i = 0; i < 200; i++) {
            long w = p.backoffMillis(1);
            assertTrue(w >= 0 && w <= 4000, "jitter out of range: " + w);
        }
    }
}
