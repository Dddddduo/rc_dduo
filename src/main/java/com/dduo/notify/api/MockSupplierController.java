package com.dduo.notify.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示/集成测试用的"假供应商",仅在 demo profile 下装配。
 * 提供三个开关模拟供应商行为:
 * - POST /_mock/fail-mode?on=true   收到请求返回 503(触发重试)
 * - POST /_mock/reject-mode?on=true 返回 400(触发永久失败/死信)
 * - GET  /_mock/received            查看收到过的请求(验证渲染与送达)
 * - POST /_mock/reset               清空
 */
@RestController
@RequestMapping("/_mock")
@Profile({"demo", "test"})
public class MockSupplierController {

    private final List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failMode = new AtomicBoolean(false);
    private final AtomicBoolean rejectMode = new AtomicBoolean(false);
    private final AtomicInteger callCount = new AtomicInteger();

    @PostMapping("/notify")
    public ResponseEntity<Map<String, Object>> notifyEndpoint(
            @RequestBody(required = false) String body,
            @RequestHeader Map<String, String> headers) {
        int n = callCount.incrementAndGet();
        Map<String, Object> receivedEntry = new LinkedHashMap<>();
        receivedEntry.put("seq", n);
        receivedEntry.put("headers", headers);
        receivedEntry.put("body", body);
        received.add(receivedEntry);

        if (rejectMode.get()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp("rejected"));
        }
        if (failMode.get()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(resp("unavailable"));
        }
        return ResponseEntity.ok(resp("ok"));
    }

    private static Map<String, Object> resp(String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mock", state);
        return m;
    }

    @PostMapping("/fail-mode")
    public String setFailMode(@RequestParam boolean on) {
        failMode.set(on);
        return "failMode=" + on;
    }

    @PostMapping("/reject-mode")
    public String setRejectMode(@RequestParam boolean on) {
        rejectMode.set(on);
        return "rejectMode=" + on;
    }

    @GetMapping("/received")
    public List<Map<String, Object>> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    @PostMapping("/reset")
    public String reset() {
        received.clear();
        failMode.set(false);
        rejectMode.set(false);
        callCount.set(0);
        return "reset";
    }
}
