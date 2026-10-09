package com.dduo.notify.delivery;

import java.util.Map;

import com.dduo.notify.config.NotificationProperties;
import com.dduo.notify.model.NotificationRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 执行一次真实的 HTTP 投递:直接发送记录里存好的"请求快照",
 * 不做任何业务判断——适配差异已在提交时渲染完成,这里只负责"送达"。
 */
@Component
public class HttpNotifier {

    private static final Logger log = LoggerFactory.getLogger(HttpNotifier.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpNotifier(NotificationProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getDelivery().getConnectTimeoutMs());
        factory.setReadTimeout(props.getDelivery().getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
        // 4xx/5xx 不抛异常,统一交给 classify() 判定,避免用异常做流程控制
        this.restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
    }

    public DeliveryResult deliver(NotificationRecord record) {
        HttpHeaders headers = new HttpHeaders();
        try {
            Map<String, String> map = objectMapper.readValue(
                    record.getHeaders(), new TypeReference<Map<String, String>>() {
                    });
            map.forEach(headers::set);
        } catch (Exception e) {
            return DeliveryResult.permanent("bad headers snapshot: " + e.getMessage());
        }
        if (headers.getContentType() == null && record.getBody() != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        HttpEntity<String> entity = new HttpEntity<>(record.getBody(), headers);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    record.getUrl(), HttpMethod.resolve(record.getMethod()), entity, String.class);
            return classify(resp.getStatusCodeValue(), resp.getBody());
        } catch (ResourceAccessException e) {
            // 连接/读取超时、DNS 失败、连接被拒:典型可重试
            return DeliveryResult.retryable("IO failure: " + rootMessage(e));
        } catch (HttpStatusCodeException e) {
            return classify(e.getRawStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("unexpected delivery error for {}", record.getRecordUid(), e);
            return DeliveryResult.retryable("unexpected: " + rootMessage(e));
        }
    }

    /**
     * 结果分类:
     * - 2xx 成功
     * - 408/429/5xx 可重试(对方可能过载/限流,值得再试)
     * - 其余 4xx 永久失败(请求本身被拒,重试只是浪费)
     * 供应商语义差异(比如有的把 404 当暂态)属于配置层问题,MVP 采用业界默认语义。
     */
    static DeliveryResult classify(int status, String respBody) {
        String brief = "HTTP " + status + (respBody == null || respBody.isEmpty() ? ""
                : ", resp=" + (respBody.length() > 200 ? respBody.substring(0, 200) : respBody));
        if (status >= 200 && status < 300) {
            return DeliveryResult.success(status);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return DeliveryResult.retryable(brief);
        }
        return DeliveryResult.permanent(brief);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
