package com.dduo.notify.api.dto;

import java.time.Instant;

import com.dduo.notify.model.NotificationRecord;

/** 对外的记录视图,隐藏内部字段(version/nextRetryAt 等调度细节)。 */
public class RecordView {

    private String recordUid;
    private String eventType;
    private String bizId;
    private String status;
    private int retryCount;
    private String targetUrl;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    public static RecordView of(NotificationRecord r) {
        RecordView v = new RecordView();
        v.recordUid = r.getRecordUid();
        v.eventType = r.getEventType();
        v.bizId = r.getBizId();
        v.status = r.getStatus().name();
        v.retryCount = r.getRetryCount();
        v.targetUrl = r.getUrl();
        v.lastError = r.getLastError();
        v.createdAt = r.getCreatedAt();
        v.updatedAt = r.getUpdatedAt();
        return v;
    }

    public String getRecordUid() {
        return recordUid;
    }

    public String getEventType() {
        return eventType;
    }

    public String getBizId() {
        return bizId;
    }

    public String getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
