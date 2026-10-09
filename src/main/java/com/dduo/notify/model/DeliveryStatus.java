package com.dduo.notify.model;

/**
 * 投递状态机:
 *
 *   PENDING ──claim──▶ SENDING ──2xx──▶ SUCCESS
 *      ▲                  │
 *      │                  ├─可重试失败─▶ RETRY_WAIT ──到期再claim──▶ SENDING
 *      │                  │
 *      └────replay────────┴─重试耗尽/永久失败(4xx)──▶ DEAD
 */
public enum DeliveryStatus {

    /** 已落库,等待首次投递 */
    PENDING,

    /** 已被 dispatcher 抢占,正在投递 */
    SENDING,

    /** 投递失败,等待退避到期后重试 */
    RETRY_WAIT,

    /** 投递成功 */
    SUCCESS,

    /** 死信:重试耗尽,或收到永久失败的 4xx */
    DEAD;
}
