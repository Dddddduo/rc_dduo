package com.dduo.notify.model;

import java.time.Instant;

/**
 * 一条通知投递记录(outbox 行)。
 * url/method/headers/body 是提交时按供应商配置渲染好的"最终请求快照",
 * 之后配置即使变更,已入库记录仍按快照投递,保证行为可预期、可回放。
 */
public class NotificationRecord {

    private long id;
    /** 对外暴露的记录 ID(UUID),业务方用它查询/重投 */
    private String recordUid;
    private String eventType;
    /** 业务幂等键,与 eventType 组成唯一索引 */
    private String bizId;
    /** 原始业务负载(JSON 字符串),保留用于审计与重渲染 */
    private String payload;

    // ---- 渲染后的 HTTP 请求快照 ----
    private String url;
    private String method;
    /** 渲染后的 header,JSON 字符串 */
    private String headers;
    private String body;

    private DeliveryStatus status;
    /** 已使用的重试次数(首次投递不算重试) */
    private int retryCount;
    /** 下次可投递时间(epoch millis),0 表示立即可投 */
    private long nextRetryAt;
    private String lastError;
    /** 乐观锁版本,claim 时递增,防止并发/过期写回 */
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getRecordUid() {
        return recordUid;
    }

    public void setRecordUid(String recordUid) {
        this.recordUid = recordUid;
    }

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

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(long nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
