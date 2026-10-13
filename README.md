# rc_dduo — 外部 HTTP 通知可靠投递服务

[![CI](https://github.com/Dddddduo/rc_dduo/actions/workflows/ci.yml/badge.svg)](https://github.com/Dddddduo/rc_dduo/actions/workflows/ci.yml)
![Java 8](https://img.shields.io/badge/Java-8-orange)
![Spring Boot 2.7.18](https://img.shields.io/badge/Spring%20Boot-2.7.18-green)
![Tests 43/43](https://img.shields.io/badge/tests-43%2F43-brightgreen)
![License MIT](https://img.shields.io/badge/license-MIT-blue)

面试作业 MVP:接收多个业务系统提交的外部通知请求,**尽可能可靠地投递**到不同供应商的 API(URL / Header / Body 各不相同)。业务方不关心返回值,只要求送达。

技术栈:Spring Boot 2.7.18 / Java 8 / H2(可平滑替换 MySQL)/ 纯 JdbcTemplate。

| 交付物 | 状态 |
|---|---|
| 可运行 MVP 代码 | ✅ |
| 自动化测试 | ✅ 43/43 通过(单元 + 端到端真实 HTTP) |
| 全流程演示脚本 | ✅ `scripts/demo.sh`,已实跑验证 |
| 崩溃恢复(kill -9)演示 | ✅ 实测不丢消息 |
| 设计文档(本 README) | ✅ |
| 设计说明要求三问(边界/语义/取舍与演进) | ✅ 专节直答(见「三问直答」) |
| [AI 使用说明](AI-USAGE.md) | ✅ |

---

## 0. 结论(TL;DR)

这道题的本质不是"发 HTTP 请求",而是**在不可靠的世界里对不可靠的第三方做投递**。我的全部设计围绕一个第一性原则:

> **消息必须先活过进程重启,才谈得上可靠投递。**

由此推出整套方案:

1. **DB Outbox**:落库 == 受理。慢的、会失败的部分全部异步化,任何崩溃都不丢已受理的消息(内存队列在这个演示上直接阵亡)。
2. **诚实的 at-least-once + 幂等键**:对第三方 API,exactly-once 是伪命题,我选择声明真相并把去重工具(`bizId` 透传)交给下游;`(event_type, biz_id)` 唯一索引在入口挡掉重复提交。
3. **配置驱动适配**:新增供应商 = 加一段 YAML,核心代码零改动;渲染快照入库,投递器是无业务的纯执行器。
4. **状态机 + 乐观锁 claim**:并发正确性建立在数据库行级原子性上,多实例安全,不引入分布式锁中间件。
5. **重试有纪律**:暂态失败指数退避 + 全抖动(防重试风暴);永久失败(4xx)直接死信不浪费配额;耗尽进死信可人工重投;崩溃遗留的 SENDING 由超时扫描自动回收。

**验证结论**:43 个测试全绿;demo 脚本五步(渲染送达/幂等/503 自动恢复/400 死信重投/统计)全部按预期通过;两次 `kill -9` 实战(供应商故障期与重启后)累计 3 条在途消息全部自动续投、零丢失。

**已知边界(不回避)**:at-least-once 意味着"请求已发出、供应商已处理、但回执未及写库"的崩溃窗口内存在重复投递可能——这是语义定义本身,不是实现缺陷;缓解手段(幂等键透传)已内置。

题目「设计说明要求」的三问(系统边界 / 投递语义与失败处理 / 过度设计与演进)在下一节**逐问直答**,推导细节在引用的章节里。

---

## 设计说明要求:三问直答

> 本节按题目要求逐条正面回答,只给结论和理由;每条结论都能在后文找到机制与实测出处。

### Q1 系统边界

**选择在本系统中解决的问题**——就这四个,这是本系统责任链的全部:

1. **受理不丢**:业务方提交被受理后,绝不因进程/宿主故障消失(outbox:落库==受理,§4.1);
2. **投递有纪律**:供应商失败时自动恢复,且重试有分类、有节奏、有预算(§4.5、§8);
3. **失败可发现、可挽救**:投不进去的进死信,可查询、可人工重投(DEAD + `/replay` + `/stats`,§3.3);
4. **多供应商差异被隔离**:新增供应商 = 加一段 YAML,核心代码零改动(§4.3)。

**明确选择不解决的问题**,以及为什么:

| 不解决 | 为什么 |
|---|---|
| 订单是否成立、业务正确性 | 那是业务方的判断;本系统只负责"把已发生的事实送达" |
| 供应商侧的重复消费**执行** | 我能透传 `bizId`(做了),但去重权在对方系统里——别人的系统不在我的边界内 |
| 投递顺序 | 需求是"送达"不是"按序送达";全局顺序与并发投递互斥,硬做要牺牲吞吐与可靠性 |
| 鉴权 / 加密 / 密钥管理 | 内部服务,安全边界在网络层与网关;当前量级做了是成本不是收益(§9 已列演进) |
| 毫秒级时效 | 轮询秒级延迟是"送达"语义下的刻意取舍;要毫秒级走 §9 的 CDC 演进 |
| 与供应商的业务对账 | 系统保证"记录即所发"(渲染快照落库,§4.3),对账属业务层 |
| 万级 TPS 吞吐 | 内部量级不存在该压力;MQ 是吞吐解法,不是可靠性解法(§4.1) |

**边界的判断标准**(一句话,所有取舍都从它推出):本系统承诺"**每一条已落库的请求,最终都会以供应商可接受的形式到达,或进入死信并被人发现**";不承诺"何时到达、供应商收到后做什么"。超出这句话的问题,不属于本系统的边界。

### Q2 投递语义与失败处理

**语义选择:at-least-once + 幂等键透传。** 不选另外两个各有一条硬理由:

- **at-most-once**:失败即丢,直接违背题目第一目标;
- **exactly-once**:需要对端与我有跨系统事务——对第三方 API 不存在这个东西。"请求已发出、对方已处理、回执在路上丢了"的窗口永远存在,重试即重复、不重试即丢失,二者必居其一。**声称做得到 either 的都在撒谎**,所以我把语义名字写在文档里而不是藏起来;
- 可行的诚实等价物 = 投递侧承诺至少一次 + 入口 `(event_type, biz_id)` 唯一索引挡重复受理 + `bizId` 渲染进 Header/Body 透传给下游去重(§4.2)。

**外部系统失败/长期不可用的处理策略**(按失败形态,每种都有实测):

| 失败形态 | 策略 | 终态与验证 |
|---|---|---|
| 暂态:超时/连接失败/408/429/5xx | 指数退避 + 全抖动,自动重试(预算内) | 供应商恢复后自动 SUCCESS,无人工(§7.2 step 3) |
| 永久:其余 4xx / headers 快照损坏 | **不重试**,一步 DEAD,`retryCount=0`,`lastError` 留现场 | demo step 4 实测 |
| 重试耗尽 | DEAD + `/replay` 人工出口 | 重投→SUCCESS,最终 `DEAD:0` 零积压(§7.2) |
| **长期不可用**(超出重试预算) | 不硬扛:死信把问题**显式化**,`/stats` 暴露积压;本系统自保——超时 3s/5s + 有界线程池 + CallerRuns 背压,**受理侧保持可用**(DB 就是缓冲池),降级行为可预测 | §4.6、§7.4 |
| 自己进程崩溃 | resetStuck 超时回收 SENDING | 两次 kill -9 实测零丢失(§7.3) |

对"长期不可用"的设计态度一句话:**把"系统自己解决不了的问题"转化为"人看得见、有出口的问题",而不是让系统持续烧资源去掩盖它。**

### Q3 过度设计与演进

**我认为过度、并明确不采纳的 AI 方案建议**(完整记录见 [AI-USAGE §2](AI-USAGE.md)):Kafka 削峰、Resilience4j 熔断、SpEL/Groovy 模板引擎、MyBatis/JPA、同步返回投递结果、Redis/ZK 分布式锁。否决标准是统一的:**方案提供的能力,要么在本场景需求里不存在(需求侧),要么已被手里更便宜的手段等价覆盖(成本侧)。** 例:MQ——削峰对应的消息量瓶颈不存在,可靠性已被 outbox 解决,剩下全是部署依赖、故障面和运维心智。

**关于不引入中间件**(题目允许使用并要求说明取舍):本系统未引入任何 MQ/调度框架。可靠投递的四个子问题——持久队列、重试定时、并发互斥、状态推进——分别被"一张表 + 两条原子 SQL + `@Scheduled`"等价解决(§4.1、§4.4、§4.5);引入中间件会让每个子问题多一处要解释的语义和多一个故障源。**替代方案的切换点也是设计时留的**:当轮询积压或需要多消费方广播时,把"poll 触发"换成"broker/CDC 触发"即可,表与状态机不动——届时 Kafka 的引入才有对应的问题支撑。

**第一版之后,流量/复杂度显著增长时的演进路径**(每条带触发信号,不是愿望清单):

| 触发信号 | 演进动作 | 为什么改动可控 |
|---|---|---|
| 轮询延迟不可接受 / 出现积压 | poll → CDC/binlog 事件驱动 | 渲染快照与状态机不变(§4.1) |
| 某家慢供应商拖垮全局 | per-target 线程池 + 限速 + 熔断 | `HttpNotifier` 已是零业务纯执行器,在外围加隔离即可 |
| 单 DB 写压力持续走高 | 多实例部署 → 分库/归档 | 乐观锁 claim 天然多实例安全,并发代码零改动(§4.4) |
| 死信积压开始无人看 | Micrometer 指标 + DEAD 告警 | 纯加法,不触核心 |
| 供应商数量/变更频率上升 | 配置入库 + 校验 + 灰度 | `targets` 已是单 struct,入库只是换存储 |

---

## 1. 需求理解

题目里真正约束设计的是三句话:

| 需求原话 | 工程含义 |
|---|---|
| "接收多个业务系统的通知请求" | 对内一个统一 API;业务方与供应商解耦,业务方不知道也不需要知道供应商是谁 |
| "尽可能可靠地投递到不同供应商" | 可靠性的敌人是:进程崩溃、供应商宕机/超时、重复发送。需要持久化、重试、幂等三件套 |
| "业务方不关心返回值,只要求送达" | 接口是**异步语义**:提交即受理(202),投递在后台完成;同步等待供应商反而会把故障传染给业务方 |

一个容易被忽略的点:"不同供应商 URL/Header/Body 都不同"意味着**适配差异是需求的核心变量**。如果每接一家供应商就改一次代码,这个系统无法长期存活——所以适配必须是配置,而不是代码。

## 2. 架构总览

模式:**DB Outbox(持久化优先)+ 异步投递 + 指数退避重试 + 死信**。

```
业务系统 A ─┐
业务系统 B ─┼─ POST /api/v1/notifications ─► [ 校验/幂等检查 ]
业务系统 C ─┘        │ (立即返回 202)
                    ▼
        ┌─────────────────────────────┐
        │ notification_record (DB)     │  ← 落库 == 受理成功
        │ 原始 payload + 渲染好的       │     消息从此活过任何进程重启
        │ 最终 HTTP 请求快照 + 状态机   │
        └──────────────┬──────────────┘
                       │ Dispatcher 定时轮询(到期记录)
                       │ 乐观锁 claim(条件 UPDATE)
                       ▼
              [ 有界线程池 · 并发投递 ]
                       │
        ┌──────────────┼──────────────────┐
        ▼              ▼                  ▼
   2xx → SUCCESS   4xx(除408/429)     超时/连接失败/
                   → DEAD(永久失败)    408/429/5xx → RETRY_WAIT
                                          │ 指数退避+抖动,到期再投
                                          ▼
                                     超过 maxRetry → DEAD(死信)
                                                    │ 人工修复后
                                                    ▼
                                     POST /{uid}/replay 重投 → PENDING
```

状态机:

```
PENDING ──claim──► SENDING ──2xx──► SUCCESS
   ▲                 │
   │                 ├──4xx 永久──► DEAD
   │                 │
   └──resetStuck◄────┤ (进程崩溃遗留,超时自动回收)
                     │
                     └──可重试失败──► RETRY_WAIT ──退避到期──► (重新 claim)
                                         │
                                         └──重试耗尽──► DEAD ──replay──► PENDING
```

一次投递的完整时序(以"提交订单扣减通知"为例):

```
业务系统            Controller        Service            DB                Dispatcher      供应商
   │  POST(eventType,   │                 │                 │                    │            │
   │  bizId, payload)   │──校验+幂等+渲染──►│──INSERT PENDING──►│                    │            │
   │◄───── 202 ─────────│◄─ 落库即返回 ────│                 │   poll: 到期记录◄──│            │
   │                    │                 │                 │◄──claim(乐观锁)────│            │
   │                    │                 │                 │                    │──HTTP快照──►│
   │  (想查随时 GET uid)  │                 │                 │◄──markSuccess/─────│◄─── 2xx/4xx ─│
   │                    │                 │                 │   FAILURE/DEAD     │            │
```

## 3. API(以下请求/响应全部为真实执行抓取,非手写示意)

### 3.1 提交通知 `POST /api/v1/notifications`

```json
{
  "eventType": "inventory-order",
  "bizId": "ORD-3001",
  "payload": { "orderId": "ORD-3001", "items": [{ "sku": "SKU-A", "quantity": 2 }] }
}
```

**新受理 → 202**(落库即返回,不等供应商):

```json
{
  "recordUid": "a18239b5-49cb-452d-b90d-404463ee80ca",
  "eventType": "crm-payment", "bizId": "CAP-001",
  "status": "PENDING", "retryCount": 0,
  "targetUrl": "http://localhost:8080/_mock/notify",
  "lastError": null,
  "createdAt": "2026-10-09T10:25:48.208Z", "updatedAt": "2026-10-09T10:25:48.208Z"
}
```

**幂等命中 → 200**(相同 `(eventType, bizId)` 再次提交,返回**原记录同一 recordUid**,绝不产生第二条):

```json
{ "recordUid": "a18239b5-49cb-452d-b90d-404463ee80ca", "status": "PENDING", ... }
```

> 202 与 200 的区别就是业务语义:202 = 新消息开始投递;200 = 你提交的就是这条,它已在系统里。业务方重试提交永远安全。

### 3.2 错误契约

自定义错误统一为 `{"code": "...", "message": "..."}`;`bizId/eventType/payload` 缺失走 Bean Validation 的标准 400。以下均为真实响应:

| 场景 | 码 | 真实响应体 |
|---|---|---|
| 未配置的事件类型 | 400 | `{"code":"UNKNOWN_EVENT_TYPE","message":"no target configured for eventType: no-such"}` |
| payload 缺模板所需字段 | 400 | `{"code":"INVALID_PAYLOAD","message":"placeholder not found in payload: payload.orderId"}` |
| 必填字段缺失 | 400 | Spring 标准错误体(`status/error/path`) |
| recordUid 不存在 | 404 | 空体 |
| replay 的目标不是 DEAD | 404 | 空体(非死信不允许重投,防误操作) |

`INVALID_PAYLOAD` 体现**快速失败**原则:残缺请求在入口就被拒绝,永远不会有一个缺字段的 HTTP 请求打到供应商那里去。

### 3.3 查询与其他端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/notifications/{recordUid}` | 状态查询。真实响应(SUCCESS 后):`{"recordUid":"a182…","status":"SUCCESS","retryCount":0,"lastError":null,"updatedAt":"…48.606Z"}` |
| POST | `/api/v1/notifications/{recordUid}/replay` | 死信人工重投:仅允许 DEAD → PENDING |
| GET | `/api/v1/notifications/stats` | 真实响应:`{"PENDING":0,"SENDING":0,"RETRY_WAIT":0,"SUCCESS":1,"DEAD":0}` |
| — | `/_mock/*` | 演示/测试假供应商,**仅 demo/test profile 装配**,生产构建天然不含 |

`/_mock` 能力:`fail-mode?on=true` 切 503、`reject-mode?on=true` 切 400、`GET /received` 查看收到的真实请求、`POST /reset` 清空。

## 4. 关键设计决策与取舍

这是我认为这份作业最值得讲的部分。每条给出:选了什么、放弃了什么、代价是什么。

### 4.1 DB Outbox,而不是内存队列 / MQ

**选**:落库 == 受理,DB 本身就是那个不会丢的队列。
**弃**:BlockingQueue(宕机丢在途消息,直接违背第一目标);Kafka/RocketMQ(多一个部署依赖,而可靠性已被 outbox 解决,MQ 解决的是吞吐问题——内部量级 1s 轮询 batch 50 完全够用)。
**代价**:秒级轮询延迟、DB 扫描压力。对"通知送达"这类秒级容忍的场景值得。
**演进**:吞吐上来后把"轮询"换成 binlog/CDC/broker 通知,表和状态机一行不改——这个迁移路径是设计时留好的,不是事后补丁。

### 4.2 诚实的语义:at-least-once + 幂等键

exactly-once 对第三方 API **做不到**:"HTTP 请求发出"与"对方处理成功"之间永远有一个崩溃窗口(对方处理成功了但回执没收到,重试即重复)。任何声称做得到的设计都在撒谎,面试里这是送命题也是表现机会——我选择明写语义。

三层配合:
- 入口:`(event_type, biz_id)` 唯一索引 + 先查后插双保险,并发重复提交也只有一个赢(败者拿到赢家的记录返回);
- 出口:`bizId` 通过配置模板渲染进 Header/Body 透传给供应商,下游拿它去重即可;
- 文档:明写"若供应商不具备幂等能力,极端窗口下可能重复"。

### 4.3 配置驱动的多供应商适配

`notify.targets[eventType]` 一段配置 = 一个供应商(URL/Method/Header 模板/Body 模板),占位符 `{{payload.xxx}}` 支持点号路径(`customer.email`)与数组下标(`items[0].sku`)。

**弃**:模板引擎(SpEL/Groovy/Velocity)。"配置即代码"意味着改配置的人能执行代码——注入面不可接受。自研 15 行渲染器只做只读取值,天然无注入;且占位符缺值**快速失败**,绝不把 `null` 或残缺 JSON 发给供应商。
**关键手法**:渲染在**提交时**完成、快照写进 DB。于是投递器是零业务的纯执行器(读快照→发 HTTP→写结果),配置变更不影响在途记录;排障时表里那行就是当时真实发出的请求——这一条在演示里直接可视(§7.2 step 1)。

### 4.4 并发正确性:状态机 + 乐观锁,不引分布式锁

两条原子 SQL 承担全部并发安全:

```sql
-- claim:PENDING/RETRY_WAIT -> SENDING。version 不匹配 = 被别人抢先,直接跳过
UPDATE notification_record SET status='SENDING', version=version+1, updated_at=?
 WHERE id=? AND version=? AND status IN ('PENDING','RETRY_WAIT');

-- 状态回写:只允许"我 claim 的那次"写回(version 是 claim 后的本地值),
-- 防止上一轮过期的 worker 把别人正在投的记录改掉
UPDATE ... WHERE id=? AND version=? AND status='SENDING';
```

**弃**:Redis/ZK 分布式锁。数据库行级原子性就在手边,多一个中间件多一个故障源;claim 失败跳过、下轮重来,不需要锁续期/看门狗这类复杂度。**未来多实例部署不需要改一行并发代码**——这是这套设计里我认为最划算的一笔。

### 4.5 重试纪律:分类 + 指数退避 + 全抖动 + 死信

- **先分类再决定怎么重试**:超时/连接失败/`408`/`429`/`5xx` = 暂态,退避后再试;其余 4xx = 供应商压根拒绝这个请求,重试只是浪费双方资源,**直接进死信**(`retryCount=0`,现场保留 `lastError`);
- 退避 `5s × 2^n` 封顶 5min(全部可配),并叠加**全抖动**(在 `[0, 退避值]` 内随机取值):供应商 503 时往往积压大批消息,纯指数退避会让所有重试在恢复瞬间扎堆打过去、把它再打死(retry storm)。抖动是分布式重试的标准答案(AWS "jitter backoff");
- 超过 `maxRetry`(默认 5,即最多 6 次尝试)进 DEAD,`/replay` 人工重投——**重试有预算,死信有出口**,系统不会无限自我消耗;
- 可测性:`jitter: none` 配置让测试/演示时序完全确定——一次真实的偶发测试失败如何靠这个设计变成确定性排查,见 [AI-USAGE.md §4](AI-USAGE.md)。

### 4.6 过载保护:三件套,不引熔断器

连接/读超时(3s/5s)、有界线程池 + 有界队列 + **CallerRunsPolicy**(队列满时让轮询线程亲自跑,天然减速形成背压)、单轮批次上限 50。

**弃**:Resilience4j 熔断。对第三方 API 有意义的熔断必须按供应商独立实例(半开探测、独立阈值),配置与心智成本超出 MVP 边界;而最坏情况已被死信 + `/stats` 兜住。**演进**:加 per-target CircuitBreaker,不动状态机。

### 4.7 崩溃恢复:resetStuck 兜底

进程被 kill -9 在投递中途,记录会永远停在 SENDING。每个轮询周期顺手执行:

```sql
UPDATE notification_record SET status='PENDING', next_retry_at=0
 WHERE status='SENDING' AND updated_at < now - stuckTimeout;
```

这是"持久化优先"叙事最后的兜底:即使异常路径把状态写丢了,超时后消息自动回到队列。实测见 §7.3。

**诚实的边界**:若供应商不具备幂等能力,崩溃前"请求已发出且被供应商处理、但回执未及写库"的极端窗口内,恢复后会产生一次重复投递——这是 at-least-once 的定义本身,不是实现缺陷。

### 4.8 工程取舍汇总

| 维度 | 选择 | 放弃 | 代价 |
|---|---|---|---|
| 队列 | DB 即队列(outbox) | 内存队列 / MQ | 秒级延迟、扫描压力 |
| 投递语义 | at-least-once + 幂等键 | exactly-once 幻觉 | 极端窗口可能重复(明写) |
| 适配 | YAML 配置 + 快照 | 配置后台 / 模板引擎 | 配置变更需发布/重启 |
| 并发 | 乐观锁 claim | 分布式锁 | 单 DB 瓶颈(容量问题非正确性问题) |
| 重试 | 分类 + 退避 + 抖动 + 死信 | 无限重试 / 一律重试 | 永久错误要人工介入(replay 已备) |
| 保护 | 超时+有界池+批量上限 | 熔断限流 | 供应商雪崩时重试配额消耗(死信兜底) |
| 持久层 | 纯 JdbcTemplate | ORM | 手写 SQL(换来逐行可讲的正确性) |

## 5. 数据模型与配置

### 5.1 表(单表)

`notification_record`:

| 列 | 作用 |
|---|---|
| `record_uid` (UNIQUE) | 对外 ID,UUID |
| `event_type` + `biz_id` | **唯一索引** `uk_event_biz`:业务幂等锚点 |
| `payload` | 原始业务负载(排障/重渲染用) |
| `url/method/headers/body` | **提交时渲染好的最终 HTTP 请求快照** |
| `status` | 状态机;`retry_count` / `next_retry_at`:重试现场;`last_error`:最后一次失败原因 |
| `version` | 乐观锁 |
| `created_at/updated_at` | 审计;`updated_at` 同时是 stuck 判定依据 |

索引:`idx_status_retry(status, next_retry_at)` 覆盖轮询热路径。

### 5.2 关键配置(`notify.*`)

| 项 | 默认 | 说明 |
|---|---|---|
| `dispatch.poll-interval-ms / batch-size / max-threads / queue-capacity` | 1000 / 50 / 8 / 200 | 轮询节奏与并发容量 |
| `delivery.max-retry` | 5 | 不含首次,总尝试 = 6 |
| `delivery.initial-backoff-ms / multiplier / max-backoff-ms` | 5000 / 2.0 / 300000 | 指数退避曲线 |
| `delivery.jitter` | `random` | `none` 为确定性(测试/演示用) |
| `delivery.stuck-timeout-ms` | 300000 | SENDING 超时回收阈值 |
| `delivery.connect/read-timeout-ms` | 3000 / 5000 | 供应商侧过载保护 |
| `targets[eventType]` | — | url/method/headers/body-template |

## 6. 快速开始

要求 **JDK 8**;Maven 无需安装——仓库自带 wrapper。

```bash
./mvnw test                                     # 43 个测试
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # 端口 8080
bash scripts/demo.sh                            # 另开终端,一键全流程演示
```

换真实供应商 = 改 `application.yml` 的 `targets`。换 MySQL = 改 datasource 三行。

仓库自带 GitHub Actions CI(`.github/workflows/ci.yml`):push 到 `main` 与所有 PR 自动在 Temurin JDK 8 上跑 `./mvnw test`,顶部的 CI 徽章就是该 workflow 的实时状态。

## 7. 验证结果(实际执行记录,非纸面推演)

### 7.1 自动化测试(43/43,`./mvnw test`)

| 测试类 | 数 | 覆盖 |
|---|---|---|
| `TemplateRendererTest` | 5 | 占位符渲染、嵌套路径、数组下标、缺值快速失败 |
| `BackoffPolicyTest` | 3 | 指数退避边界、cap、jitter=none 确定性 |
| `BackoffPolicyBoundaryTest` | 4 | jitter 配置解析(拼错安全降级为 RANDOM)、曲线起点、默认策略确有抖动 |
| `HttpNotifierClassifyTest` | 24 | 失败分类表逐码穷举(2xx/408/429/5xx/其余 4xx/1xx 边界)、lastError 截断、**分类表全覆盖守卫测试**(100–599 每个码都有唯一归属) |
| `NotificationLifecycleIntegrationTest` | 7 | 端到端真实 HTTP(进程内 mock 供应商):happy path(并断言供应商收到的 Header/Body 渲染正确)→ 暂态失败→RETRY_WAIT→恢复后成功→ 重试耗尽→DEAD→replay→SUCCESS→ 永久失败→直接 DEAD 且 `retryCount=0`→ 幂等重复提交只剩一条记录→ 崩溃恢复(SENDING 超时回收、补投一次、不重复成功)→ 未知事件类型拒绝 |

测试设计原则:**断言面向状态机而非巧合**——集成测试关自动轮询、手动 `poll()` 推进状态,每个断言都能指明"在哪一步、因哪个机制"到达该状态;`jitter: none` 保证时间窗确定。同一套断言连跑多次全绿,不是挑出来的一次好结果。

### 7.2 `demo.sh` 实跑结果(逐步,均为观察到的真实输出)

1. **多供应商渲染送达**:三类事件提交 → mock 收到三条**按各自供应商模板渲染**的请求:`authorization: Bearer crm-token-xyz` + `{"contactId":"a@b.com",...}`(嵌套路径生效);`x-warehouse-token` + `{"sku":"SKU-A","quantity":"2"}`(数组下标生效);`x-event-id: AD-1001`(Header 模板生效)。
2. **幂等**:重复提交 `(ad-register, AD-1001)` → HTTP 200 返回原记录(同一 recordUid)`status=SUCCESS`,表内仍只有 1 条。
3. **暂态故障自愈**:开 503 → 每 6s 取一次快照,观察到 `retryCount` 沿退避序列(1s/2s/4s/8s/16s)逐步递增、状态 RETRY_WAIT;关故障后**自动送达 SUCCESS**,全程无人工干预。
4. **永久失败与死信重投**:开 400 → 直接 DEAD(`retryCount=0`,`lastError="HTTP 400, resp={\"mock\":\"rejected\"}"`);修复供应商 → `POST /{uid}/replay` → SUCCESS。
5. **统计**:演示结束 `{"SUCCESS":5,"PENDING":0,"SENDING":0,"RETRY_WAIT":0,"DEAD":0}`——死信全部被重投消化,系统回到零积压。

### 7.3 kill -9 崩溃恢复实测(真进程、真文件库)

真实执行两次,均零丢失:

- **场景 A(受理即崩溃)**:进程 `kill -9`,重启后从 H2 文件库读回全部记录,`/stats` 计数与崩溃前一致——**已受理的消息活在 DB 里,不活在进程里**;
- **场景 B(在途崩溃)**:提交 3 条事件后供应商开 503,记录停在 RETRY_WAIT,`kill -9`;重启后 3 条在途消息自动继续退避重试,供应商恢复后**三条全部送达 SUCCESS**(mock 侧核对 `CRASH2-*` 请求数 = 3,无多投),最终 `{"SUCCESS":13,"RETRY_WAIT":0}`。

换内存队列,这个演示不可能通过——这就是 outbox 的第一因。

### 7.4 能力边界(量级判断,供评审校准)

- 吞吐上限 ≈ `batch-size × 并发线程 / 单轮耗时`:默认 50 × 8 线程、供应商 P99 < 1s 时,稳态约 **数百 TPS**,轮询积压时靠加大 batch/threads 线性扩展——瓶颈在 DB 扫描,`idx_status_retry` 已覆盖热路径;
- 端到端延迟下限 = 轮询间隔(生产默认 1s):对"通知送达"场景可容忍;要毫秒级就走 §9 的 CDC 演进;
- 单 DB 是容量瓶颈不是正确性瓶颈:多实例水平扩展不需要改任何并发代码(§4.4)。

## 8. 失败分类学(代码即文档)

`HttpNotifier.classify`:

```
2xx                 → SUCCESS
408 / 429 / 5xx     → RETRYABLE(含 connect/read 超时、连接被拒、DNS 失败)
其余 4xx            → PERMANENT(直接死信,不消耗重试配额)
headers 快照损坏     → PERMANENT(自身配置错误,重试无意义)
未预期异常          → RETRYABLE + resetStuck 双重兜底
```

## 9. 局限与演进路径(想过但有意不做的)

| 当前边界 | 演进 | 为什么 MVP 不做 |
|---|---|---|
| 1s 轮询延迟 | binlog/CDC/broker 通知,表与状态机不变 | 通知场景秒级可容忍;MQ 是吞吐问题非可靠性问题 |
| 全局单线程池 | per-target 独立线程池/限速,避免一家慢拖死全部 | 多一套配置与隔离模型,MVP 量级不需要 |
| 无熔断 | Resilience4j per-target | 死信+统计已兜住最坏情况 |
| 指标只有 /stats | Micrometer/Prometheus + 告警 | 属运维配置,不改系统设计 |
| 配置静态发布 | 配置入库 + 缓存刷新 | 低频变更用文件配置可 review、可回滚 |
| 无鉴权 | 内部网络隔离 + 静态 key;接统一网关 | 内部服务 MVP,安全边界是网络层 |
| 单 DB | 分库分表/归档(投递完成 N 天后搬 history 表) | 容量问题,与正确性无关 |

## 10. 项目结构

```
src/main/java/com/dduo/notify/
  api/       NotificationController、dto/SubmitRequest|RecordView、MockSupplierController(profile 隔离)
  service/   NotificationService(校验→幂等→渲染→落库)、NotificationServiceException
  store/     NotificationRecordRepository(原子 claim / 状态回写 / resetStuck / replay,纯 JDBC)
  dispatch/  DeliveryDispatcher(轮询→claim→派发)、BackoffPolicy(指数退避+抖动)、线程池配置
  delivery/  HttpNotifier(发快照+结果分类)、TemplateRenderer、DeliveryResult
  model/     NotificationRecord、DeliveryStatus
  config/    NotificationProperties(targets 绑定)
src/main/resources/  schema.sql、application.yml、application-demo.yml
src/test/            单元 + 端到端集成测试(application-test.yml 确定性配置)
scripts/demo.sh      五步全流程演示
.github/workflows/ci.yml  GitHub Actions:push/PR 自动跑 ./mvnw test(Temurin JDK 8)
LICENSE              MIT
```

## 11. AI 协作说明

见 [AI-USAGE.md](AI-USAGE.md):AI 协助点、被我否掉的 7 条建议及原因、我自己拍板的 7 个关键决策、一次真实排查复盘。

---

## 12. 总结论

### 12.1 交付要求逐项对照

| 作业要求 | 交付位置 | 证据 |
|---|---|---|
| 接收多个业务系统的通知请求 | `POST /api/v1/notifications`(§3) | 三类事件实跑通过;202/200/400/404 全契约真实抓取 |
| 尽可能可靠地投递到不同供应商 | Outbox + 状态机 + 退避重试 + 死信(§2/§4) | 43/43 测试;503 自愈、400 死信、kill -9 零丢失实测(§7) |
| 不同供应商 URL/Header/Body 不同 | `notify.targets` 配置 + 模板渲染(§4.3) | mock 收到的三条请求各自渲染正确(§7.2 step1) |
| 业务方不关心返回值、只要求送达 | 异步受理:落库即 202,投递在后台 | 幂等重复提交返回原记录,绝不重复投递(§7.2 step2) |
| 设计文档:理解/架构/决策与取舍 | 本 README §1/§2/§4 | 7 条决策均含"选/弃/代价" |
| 设计说明:系统边界 | 「三问直答」Q1 | 解决 4 类 / 明确不解决 7 类,各附理由与判断标准 |
| 设计说明:投递语义与失败处理 | 「三问直答」Q2 | 语义选择 + 两种不选的理由;5 类失败形态各配机制与实测 |
| 设计说明:过度设计判断与演进 | 「三问直答」Q3 | 6 条 AI 建议判为过度并否决;演进表每条带触发信号 |
| 中间件取舍说明 | 「三问直答」Q3 + §4.1 | 明写"未引入"的理由、等价方案、以及何时该引入 |
| MVP 代码 | 本仓库 | JDK 8 + `./mvnw`,两条命令可复现全部验证 |
| AI 使用说明 | [AI-USAGE.md](AI-USAGE.md) | 协助/否决/人拍板/翻车复盘,四节完整 |

### 12.2 这个项目回答的核心问题

> "尽可能可靠"到底可不可验证?

我的回答:可靠性不是形容词,是可执行清单。每一条承诺都有对应的机制和一条打到它的测试——

| 可靠性承诺 | 机制 | 打到它的验证 |
|---|---|---|
| 不丢已受理的消息 | 落库 == 受理(outbox) | kill -9 实测,重启后记录完好(§7.3) |
| 不丢在途的消息 | resetStuck 超时回收 | SENDING 卡死 → 自动回队补投,一次且仅一次 |
| 供应商宕机后能自愈 | 暂态分类 + 指数退避 + 抖动 | 503 → RETRY_WAIT → 恢复后自动 SUCCESS,无人工 |
| 不浪费资源重试注定失败的请求 | 永久失败直接死信 | 400 → 一步 DEAD,`retryCount=0` |
| 死信有出口 | `/replay` 人工重投 | DEAD → replay → SUCCESS 实测 |
| 重复提交不产生重复投递 | 唯一索引 + 先查后插 | 重复提交返回原 recordUid,表内仍 1 条 |
| 多实例并发不互相踩 | 乐观锁 claim + version 回写 | claim/markSuccess 两条原子 SQL 的全部测试路径 |

### 12.3 如果面试官只读一段

我会让他读 §0:这道题我把它做成了一台**状态机驱动的可靠投递机**——第一因是"消息必须活过进程重启",全部机制是它的推论;语义上我只承诺 at-least-once 并给出幂等工具,因为对第三方 API 承诺 exactly-once 的简历应该直接淘汰;每一条可靠性承诺都有对应的测试和实测数据,不是架构图上的形容词。已知边界全部明写(§0、§4.7、§9)——我不需要一个完美系统,我需要一个我知道在哪里会坏、坏了怎么办、且验证过它确实能自愈的系统。
