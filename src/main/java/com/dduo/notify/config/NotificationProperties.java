package com.dduo.notify.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部运行参数集中在 `notify.*` 配置下。
 * targets:事件类型 -> 供应商适配配置(URL/Method/Header/Body 模板),
 * 新增供应商 = 新增一段配置,不改核心代码。
 */
@ConfigurationProperties(prefix = "notify")
public class NotificationProperties {

    private final Dispatch dispatch = new Dispatch();
    private final Delivery delivery = new Delivery();
    private Map<String, TargetConfig> targets = new LinkedHashMap<>();

    public Dispatch getDispatch() {
        return dispatch;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public Map<String, TargetConfig> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, TargetConfig> targets) {
        this.targets = targets;
    }

    public static class Dispatch {
        private boolean enabled = true;
        private long pollIntervalMs = 1000;
        private int batchSize = 50;
        private int maxThreads = 8;
        private int queueCapacity = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Delivery {
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
        /** 最多重试次数(首次投递不计),总尝试次数 = maxRetry + 1 */
        private int maxRetry = 5;
        private long initialBackoffMs = 5000;
        private double backoffMultiplier = 2.0;
        private long maxBackoffMs = 300_000;
        /** 退避抖动:random(默认,防重试风暴)/ none(确定性,测试用) */
        private String jitter = "random";
        /** SENDING 状态超过该时长视为卡死(进程崩溃遗留),扫描重置 */
        private long stuckTimeoutMs = 300_000;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getMaxRetry() {
            return maxRetry;
        }

        public void setMaxRetry(int maxRetry) {
            this.maxRetry = maxRetry;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }

        public String getJitter() {
            return jitter;
        }

        public void setJitter(String jitter) {
            this.jitter = jitter;
        }

        public long getStuckTimeoutMs() {
            return stuckTimeoutMs;
        }

        public void setStuckTimeoutMs(long stuckTimeoutMs) {
            this.stuckTimeoutMs = stuckTimeoutMs;
        }
    }

    public static class TargetConfig {
        private String url;
        private String method = "POST";
        /** header 值支持 {{payload.x}} 模板占位符 */
        private Map<String, String> headers = new LinkedHashMap<>();
        /** body 模板,null 表示无 body(GET) */
        private String bodyTemplate;

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

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBodyTemplate() {
            return bodyTemplate;
        }

        public void setBodyTemplate(String bodyTemplate) {
            this.bodyTemplate = bodyTemplate;
        }
    }
}
