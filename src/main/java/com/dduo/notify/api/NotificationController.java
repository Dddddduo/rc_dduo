package com.dduo.notify.api;

import java.util.Map;

import javax.validation.Valid;

import com.dduo.notify.api.dto.RecordView;
import com.dduo.notify.api.dto.SubmitRequest;
import com.dduo.notify.model.DeliveryStatus;
import com.dduo.notify.model.NotificationRecord;
import com.dduo.notify.service.NotificationService;
import com.dduo.notify.service.NotificationServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外 API。契约刻意保持"最小":业务方提交即成功(202),
 * 之后用 recordUid 查询最终投递状态;不承诺同步返回投递结果——
 * 这是"业务方不关心返回值,只要送达"这一需求在接口上的直接体现。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** 提交通知:落库即返回 202,投递由后台异步完成 */
    @PostMapping
    public ResponseEntity<RecordView> submit(@Valid @RequestBody SubmitRequest req) {
        NotificationService.SubmitResult result = service.submit(
                req.getEventType(), req.getBizId(), req.getPayload());
        HttpStatus status = result.outcome == NotificationService.SubmitOutcome.ACCEPTED
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(RecordView.of(result.record));
    }

    /** 查询投递状态 */
    @GetMapping("/{recordUid}")
    public ResponseEntity<RecordView> get(@PathVariable String recordUid) {
        NotificationRecord record = service.find(recordUid);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(RecordView.of(record));
    }

    /** 死信人工重投 */
    @PostMapping("/{recordUid}/replay")
    public ResponseEntity<Void> replay(@PathVariable String recordUid) {
        return service.replay(recordUid)
                ? ResponseEntity.accepted().build()
                : ResponseEntity.notFound().build();
    }

    /** 状态统计:死信数 > 0 就是运维要看的信号(MVP 用它代替告警系统) */
    @GetMapping("/stats")
    public Map<DeliveryStatus, Long> stats() {
        return service.stats();
    }

    @ExceptionHandler(NotificationServiceException.class)
    public ResponseEntity<Map<String, String>> handleServiceException(NotificationServiceException e) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("code", e.getCode().name());
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
