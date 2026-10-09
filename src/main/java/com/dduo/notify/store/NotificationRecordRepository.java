package com.dduo.notify.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import com.dduo.notify.model.DeliveryStatus;
import com.dduo.notify.model.NotificationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 纯 JDBC 的 outbox 仓储,不引 ORM:
 * 这里的核心操作是两条"带条件的原子 UPDATE"(claim / 状态回写),
 * 用 SQL 直接表达比让 ORM 生成更清晰,也方便面试时逐行讲正确性。
 */
@Repository
public class NotificationRecordRepository {

    private static final RowMapper<NotificationRecord> MAPPER = (ResultSet rs, int i) -> {
        NotificationRecord r = new NotificationRecord();
        r.setId(rs.getLong("id"));
        r.setRecordUid(rs.getString("record_uid"));
        r.setEventType(rs.getString("event_type"));
        r.setBizId(rs.getString("biz_id"));
        r.setPayload(rs.getString("payload"));
        r.setUrl(rs.getString("url"));
        r.setMethod(rs.getString("method"));
        r.setHeaders(rs.getString("headers"));
        r.setBody(rs.getString("body"));
        r.setStatus(DeliveryStatus.valueOf(rs.getString("status")));
        r.setRetryCount(rs.getInt("retry_count"));
        r.setNextRetryAt(rs.getLong("next_retry_at"));
        r.setLastError(rs.getString("last_error"));
        r.setVersion(rs.getInt("version"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        r.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return r;
    };

    private final JdbcTemplate jdbc;

    public NotificationRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新记录入库,状态 PENDING、立即可投 */
    public NotificationRecord insert(NotificationRecord r) {
        String sql = "INSERT INTO notification_record "
                + "(record_uid, event_type, biz_id, payload, url, method, headers, body, status, "
                + " version, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.getRecordUid());
            ps.setString(2, r.getEventType());
            ps.setString(3, r.getBizId());
            ps.setString(4, r.getPayload());
            ps.setString(5, r.getUrl());
            ps.setString(6, r.getMethod());
            ps.setString(7, r.getHeaders());
            ps.setString(8, r.getBody());
            ps.setString(9, r.getStatus().name());
            ps.setInt(10, r.getVersion());
            ps.setTimestamp(11, java.sql.Timestamp.from(now));
            ps.setTimestamp(12, java.sql.Timestamp.from(now));
            return ps;
        }, keyHolder);
        r.setId(keyHolder.getKey().longValue());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    public NotificationRecord findByUid(String recordUid) {
        List<NotificationRecord> list = jdbc.query(
                "SELECT * FROM notification_record WHERE record_uid = ?", MAPPER, recordUid);
        return list.isEmpty() ? null : list.get(0);
    }

    public NotificationRecord findById(long id) {
        List<NotificationRecord> list = jdbc.query(
                "SELECT * FROM notification_record WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 (eventType, bizId) 查已有记录,用于幂等提交 */
    public NotificationRecord findByEventAndBizId(String eventType, String bizId) {
        List<NotificationRecord> list = jdbc.query(
                "SELECT * FROM notification_record WHERE event_type = ? AND biz_id = ?", MAPPER, eventType, bizId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 取一批"到期待投"的候选(不含 claim,claim 用 {@link #claim})。
     * 只做候选筛选,真正的并发安全靠 claim 的条件 UPDATE。
     */
    public List<NotificationRecord> findDue(long nowMillis, int limit) {
        String sql = "SELECT * FROM notification_record "
                + "WHERE status IN ('PENDING','RETRY_WAIT') AND next_retry_at <= ? "
                + "ORDER BY next_retry_at ASC, id ASC LIMIT ?";
        return jdbc.query(sql, MAPPER, nowMillis, limit);
    }

    /**
     * 原子抢占:PENDING/RETRY_WAIT -> SENDING。
     * 条件 UPDATE + version 递增,多实例下同一行只会被一个 worker 抢走;
     * 返回 false 表示被别人抢先,直接跳过即可。
     */
    public boolean claim(NotificationRecord r) {
        String sql = "UPDATE notification_record "
                + "SET status = 'SENDING', version = version + 1, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status IN ('PENDING','RETRY_WAIT')";
        return jdbc.update(sql, java.sql.Timestamp.from(Instant.now()), r.getId(), r.getVersion()) == 1;
    }

    /** 投递成功:SENDING -> SUCCESS(带 version 防过期写回;version 是 claim 后的本地值) */
    public void markSuccess(NotificationRecord r) {
        String sql = "UPDATE notification_record "
                + "SET status = 'SUCCESS', last_error = NULL, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status = 'SENDING'";
        jdbc.update(sql, java.sql.Timestamp.from(Instant.now()), r.getId(), r.getVersion());
    }

    /** 可重试失败:回到 RETRY_WAIT 并写入退避到期时间;重试耗尽则转 DEAD */
    public void markFailure(NotificationRecord r, long nextRetryAtMillis, String error, boolean exhausted) {
        String status = exhausted ? "DEAD" : "RETRY_WAIT";
        String sql = "UPDATE notification_record "
                + "SET status = ?, retry_count = ?, next_retry_at = ?, last_error = ?, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status = 'SENDING'";
        jdbc.update(sql, status, r.getRetryCount(), nextRetryAtMillis,
                truncate(error), java.sql.Timestamp.from(Instant.now()), r.getId(), r.getVersion());
    }

    /** 永久失败(如 4xx):不再重试,直接死信 */
    public void markDead(NotificationRecord r, String error) {
        String sql = "UPDATE notification_record "
                + "SET status = 'DEAD', last_error = ?, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status = 'SENDING'";
        jdbc.update(sql, truncate(error), java.sql.Timestamp.from(Instant.now()), r.getId(), r.getVersion());
    }

    /**
     * 崩溃恢复:把卡在 SENDING 超过 stuckTimeoutMs 的记录重置回 PENDING。
     * 覆盖两种情况:进程在投递中途被杀、worker 线程异常导致状态残留。
     */
    public int resetStuck(long stuckBeforeMillis) {
        String sql = "UPDATE notification_record "
                + "SET status = 'PENDING', next_retry_at = 0, updated_at = ? "
                + "WHERE status = 'SENDING' AND updated_at < ?";
        return jdbc.update(sql, java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.ofEpochMilli(stuckBeforeMillis)));
    }

    /** 死信人工重投:DEAD -> PENDING,重试计数清零 */
    public int replay(String recordUid) {
        String sql = "UPDATE notification_record "
                + "SET status = 'PENDING', retry_count = 0, next_retry_at = 0, last_error = NULL, "
                + "    version = version + 1, updated_at = ? "
                + "WHERE record_uid = ? AND status = 'DEAD'";
        return jdbc.update(sql, java.sql.Timestamp.from(Instant.now()), recordUid);
    }

    /** 测试辅助:重置全部记录重跑演示 */
    public List<NotificationRecord> listByStatus(DeliveryStatus status, int limit) {
        String sql = "SELECT * FROM notification_record WHERE status = ? ORDER BY id DESC LIMIT ?";
        return jdbc.query(sql, MAPPER, status.name(), limit);
    }

    public long countByStatus(DeliveryStatus status) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_record WHERE status = ?", Long.class, status.name());
        return c == null ? 0 : c;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
