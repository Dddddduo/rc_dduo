package com.dduo.notify.delivery;

import com.dduo.notify.model.NotificationRecord;

/**
 * 单次投递的结果。三类终局:
 * - SUCCESS            收到 2xx
 * - RETRYABLE          超时/IO 异常/408/429/5xx,按退避重试
 * - PERMANENT          其余 4xx,重试无意义(对方明确拒绝请求本身),直接死信
 */
public class DeliveryResult {

    public enum Kind {
        SUCCESS, RETRYABLE, PERMANENT
    }

    private final Kind kind;
    private final String detail;

    private DeliveryResult(Kind kind, String detail) {
        this.kind = kind;
        this.detail = detail;
    }

    public static DeliveryResult success(int statusCode) {
        return new DeliveryResult(Kind.SUCCESS, "HTTP " + statusCode);
    }

    public static DeliveryResult retryable(String detail) {
        return new DeliveryResult(Kind.RETRYABLE, detail);
    }

    public static DeliveryResult permanent(String detail) {
        return new DeliveryResult(Kind.PERMANENT, detail);
    }

    public Kind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isSuccess() {
        return kind == Kind.SUCCESS;
    }

    @Override
    public String toString() {
        return kind + ": " + detail;
    }
}
