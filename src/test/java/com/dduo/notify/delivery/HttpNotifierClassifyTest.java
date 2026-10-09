package com.dduo.notify.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败分类学单元测试(HttpNotifier.classify)。
 * 这是重试纪律的核心判定表,必须有独立、穷举的单元测试——
 * 集成测试只能证明"某条路径走得通",分类表要求"每个码都判得对"。
 */
class HttpNotifierClassifyTest {

    @Nested
    @DisplayName("2xx → SUCCESS")
    class Success {

        @ParameterizedTest
        @ValueSource(ints = {200, 201, 202, 204, 299})
        void anyTwoXxIsSuccess(int status) {
            assertEquals(DeliveryResult.Kind.SUCCESS, HttpNotifier.classify(status, "ok").getKind());
        }

        @Test
        void detailCarriesStatusCode() {
            assertEquals("HTTP 200", HttpNotifier.classify(200, null).getDetail());
        }
    }

    @Nested
    @DisplayName("暂态失败 → RETRYABLE(值得退避后再试)")
    class Retryable {

        @ParameterizedTest
        @ValueSource(ints = {408, 429, 500, 502, 503, 504, 599})
        void timeoutThrottleAndServerErrorAreRetryable(int status) {
            assertEquals(DeliveryResult.Kind.RETRYABLE, HttpNotifier.classify(status, "err").getKind(),
                    () -> "status " + status + " 应判为暂态:对方过载/限流/故障,请求本身没错");
        }

        @Test
        void retryableDetailKeepsResponseBodyForDebugging() {
            String d = HttpNotifier.classify(503, "Service Unavailable").getDetail();
            assertTrue(d.startsWith("HTTP 503") && d.contains("resp=Service Unavailable"), d);
        }
    }

    @Nested
    @DisplayName("永久失败 → PERMANENT(重试无意义,直接死信)")
    class Permanent {

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 403, 404, 409, 422})
        void otherFourXxIsPermanent(int status) {
            assertEquals(DeliveryResult.Kind.PERMANENT, HttpNotifier.classify(status, "rejected").getKind(),
                    () -> "status " + status + " 是供应商对请求本身的拒绝,重试只是浪费双方配额");
        }

        @Test
        void oneXxBoundaryIsPermanentNotSuccess() {
            // 100-199 不属于 2xx 成功语义,也不在暂态白名单 → 按永久处理,绝不能误判 SUCCESS
            assertEquals(DeliveryResult.Kind.PERMANENT, HttpNotifier.classify(100, null).getKind());
        }

        @Test
        void longResponseBodyIsTruncatedToKeepLastErrorReadable() {
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                huge.append("0123456789");
            }
            String detail = HttpNotifier.classify(500, huge.toString()).getDetail();
            // "HTTP 500, resp=" 前缀 + 恰好 200 字符的响应体切片
            assertTrue(detail.contains("resp="), detail);
            assertEquals("HTTP 500, resp=".length() + 200, detail.length(),
                    "lastError 要进库给人读,必须截断超长响应体");
        }

        @Test
        void emptyBodyKeepsDetailClean() {
            assertEquals("HTTP 404", HttpNotifier.classify(404, "").getDetail());
            assertEquals("HTTP 404", HttpNotifier.classify(404, null).getDetail());
        }
    }

    /** 分类表必须与 README §8 逐行一致;改动 classify 时这个文件是唯一的守门人。 */
    @Test
    @DisplayName("三条终局互斥且穷尽:每个状态码必有且只有一个归属")
    void classificationIsTotalAndDisjoint() {
        for (int status = 100; status < 600; status++) {
            DeliveryResult.Kind kind = HttpNotifier.classify(status, null).getKind();
            boolean twoXx = status >= 200 && status < 300;
            boolean transientHint = status == 408 || status == 429 || status >= 500;
            boolean fourXx = status >= 400 && status < 500;
            if (twoXx) {
                assertEquals(DeliveryResult.Kind.SUCCESS, kind, "status " + status);
            } else if (transientHint) {
                assertEquals(DeliveryResult.Kind.RETRYABLE, kind, "status " + status);
            } else if (fourXx) {
                assertEquals(DeliveryResult.Kind.PERMANENT, kind, "status " + status);
            }
        }
    }
}
