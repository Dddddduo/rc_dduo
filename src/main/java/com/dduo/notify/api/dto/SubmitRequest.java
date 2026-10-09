package com.dduo.notify.api.dto;

import java.util.Map;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 业务方提交请求体。
 * 业务方只需要关心这三件事:通知哪个事件、业务键是什么、带上业务数据。
 */
public class SubmitRequest {

    @NotBlank
    private String eventType;

    @NotBlank
    private String bizId;

    @NotNull
    private Map<String, Object> payload;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
