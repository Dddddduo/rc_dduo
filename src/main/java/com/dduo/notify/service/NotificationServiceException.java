package com.dduo.notify.service;

public class NotificationServiceException extends RuntimeException {

    public enum Code {
        /** 事件类型没有配置对应供应商适配 */
        UNKNOWN_EVENT_TYPE,
        /** 请求负载不是合法 JSON 对象 */
        INVALID_PAYLOAD
    }

    private final Code code;

    public NotificationServiceException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
