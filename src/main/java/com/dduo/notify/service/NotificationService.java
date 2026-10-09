package com.dduo.notify.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dduo.notify.config.NotificationProperties;
import com.dduo.notify.delivery.TemplateRenderer;
import com.dduo.notify.model.DeliveryStatus;
import com.dduo.notify.model.NotificationRecord;
import com.dduo.notify.store.NotificationRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 提交入口:校验 -> 按供应商配置渲染出"最终 HTTP 请求快照" -> 落库即返回。
 *
 * 这里刻意"只写库、不投递":入库成功 == 通知已受理,
 * 业务方最轻的诉求("不关心返回值,只要送达")在这里被满足——
 * 慢的、会失败的部分全部交给异步 dispatcher。
 */
@Service
public class NotificationService {

    private final NotificationRecordRepository repository;
    private final NotificationProperties props;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRecordRepository repository,
                               NotificationProperties props,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public enum SubmitOutcome {
        /** 新受理 */
        ACCEPTED,
        /** 幂等命中:相同 (eventType, bizId) 已存在,直接返回原记录 */
        DUPLICATE
    }

    public static class SubmitResult {
        public final SubmitOutcome outcome;
        public final NotificationRecord record;

        SubmitResult(SubmitOutcome outcome, NotificationRecord record) {
            this.outcome = outcome;
            this.record = record;
        }
    }

    /**
     * @param eventType 事件类型,决定投递到哪个供应商
     * @param bizId     业务幂等键(如订单号)
     * @param payload   业务负载
     */
    public SubmitResult submit(String eventType, String bizId, Map<String, Object> payload) {
        NotificationProperties.TargetConfig target = props.getTargets().get(eventType);
        if (target == null) {
            throw new NotificationServiceException(
                    NotificationServiceException.Code.UNKNOWN_EVENT_TYPE,
                    "no target configured for eventType: " + eventType);
        }

        // 幂等先查:命中直接返回(唯一索引仍兜底并发写)
        NotificationRecord existing = repository.findByEventAndBizId(eventType, bizId);
        if (existing != null) {
            return new SubmitResult(SubmitOutcome.DUPLICATE, existing);
        }

        Map<String, Object> flat = flatten(payload == null ? new LinkedHashMap<>() : payload, "");

        NotificationRecord r = new NotificationRecord();
        r.setRecordUid(UUID.randomUUID().toString());
        r.setEventType(eventType);
        r.setBizId(bizId);
        r.setPayload(toJson(payload));
        r.setUrl(TemplateRenderer.render(target.getUrl(), flat));
        r.setMethod(target.getMethod());
        try {
            r.setHeaders(toJson(TemplateRenderer.renderHeaders(target.getHeaders(), flat)));
            r.setBody(TemplateRenderer.render(target.getBodyTemplate(), flat));
        } catch (IllegalArgumentException e) {
            throw new NotificationServiceException(
                    NotificationServiceException.Code.INVALID_PAYLOAD, e.getMessage());
        }
        r.setStatus(DeliveryStatus.PENDING);
        r.setVersion(0);

        try {
            NotificationRecord saved = repository.insert(r);
            return new SubmitResult(SubmitOutcome.ACCEPTED, saved);
        } catch (DuplicateKeyException e) {
            // 并发重复提交:唯一索引挡住了,把对手插进去的那条返回
            NotificationRecord other = repository.findByEventAndBizId(eventType, bizId);
            if (other != null) {
                return new SubmitResult(SubmitOutcome.DUPLICATE, other);
            }
            throw e;
        }
    }

    public NotificationRecord find(String recordUid) {
        return repository.findByUid(recordUid);
    }

    public boolean replay(String recordUid) {
        return repository.replay(recordUid) == 1;
    }

    public Map<DeliveryStatus, Long> stats() {
        Map<DeliveryStatus, Long> m = new LinkedHashMap<>();
        for (DeliveryStatus s : DeliveryStatus.values()) {
            m.put(s, repository.countByStatus(s));
        }
        return m;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    /**
     * 把嵌套负载扁平化成 {user.id: 1, items[0].sku: A} 形式供模板取用。
     * 只做点号/下标路径的只读取值——不支持赋值表达式,天然防模板注入。
     */
    static Map<String, Object> flatten(Map<String, Object> payload, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto(payload, prefix, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(Object node, String path, Map<String, Object> out) {
        if (node instanceof Map) {
            ((Map<String, Object>) node).forEach((k, v) -> flattenInto(v, path.isEmpty() ? k : path + "." + k, out));
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                flattenInto(list.get(i), path + "[" + i + "]", out);
            }
        } else if (!path.isEmpty()) {
            out.put(path, node);
        }
    }
}
