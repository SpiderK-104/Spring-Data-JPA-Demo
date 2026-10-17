# pqc-ucsp-data 的 PostgreSQL / SQL 用法系统分析

> 分析范围：`pqc-ucsp-data` 模块的全部 Repository（`src/main/java/cn/pqctech/ucsp/data/**`）
> 与其配套的 DDL（`src/main/resources/db/changelog/**`）。
> 目标：把散落在 80+ 个 Java 仓储里的 SQL 用法归纳成一份可直接查阅的 PostgreSQL 参考。

---

## 1. 总览

### 1.1 规模

| 项 | 数量 |
|---|---|
| Java 文件总数 | 149 |
| Repository / JpaRepository 文件 | 82 |
| Entity 文件 | 51 |
| `@Query` 注解总数 | 186（分布在 37 个文件） |
| 其中 `nativeQuery = true` | 68 |
| 其中 JPQL | 118 |
| `EntityManager.createNativeQuery(...)` 调用点 | 102（多为动态拼装 / 返回实体行） |
| `@Modifying` | 46 |
| `@Lock(PESSIMISTIC_WRITE)` | 6 |
| `@EntityGraph` | 11 |
| Liquibase changeset | 95（87 个 changelog 文件） |

**结论：SQL 形态三分天下**——约 1/3 派生查询（Spring Data 方法名）、1/3 JPQL、1/3 原生 PostgreSQL；
但**所有涉及行锁、幂等写、JSONB、聚合统计的语句都走原生 SQL**，JPQL 只承担简单 CRUD 与状态流转。

### 1.2 运行环境

```yaml
# pqc-ucsp-http/src/main/resources/application-prod.yml
spring:
  datasource:
    url: ${UCSP_DB_URL:jdbc:postgresql://172.16.0.141:5432/ucsp}
    driver-class-name: org.postgresql.Driver
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate          # 只校验、不生成
    properties.hibernate.jdbc.time_zone: Asia/Shanghai
  liquibase:
    change-log: classpath:/db/changelog/db.changelog-master.xml
    enabled: false                # 生产关闭，DDL 由外部执行
```

| 配置项 | 值 |
|---|---|
| 数据库 | PostgreSQL（`org.postgresql.Driver`） |
| Hibernate Dialect | 未显式配置 → 自动探测为 `PostgreSQL10Dialect`（日志 `HHH000400`，测试中显式指定） |
| `ddl-auto` | `validate` —— schema 完全由 Liquibase/手工 DDL 拥有 |
| 迁移工具 | **Liquibase**（`liquibase-core`，`pqc-ucsp-data/pom.xml`），Flyway 全仓库 0 处 |
| 时间戳约定 | 无时区 `TIMESTAMP`；Java 侧以 UTC 墙钟落库、读回 `Instant`（`ApplicationControlRepository.toInstant`） |
| 技术栈 | Spring Boot 2.7.15 / Java 8 |

> `pqc-ucsp-data` 自身没有 `application*.yml`，`src/main/resources` 只有 `db/changelog/**`；
> 可运行模块是 `pqc-ucsp-http`，PostgreSQL 驱动也声明在该模块的 `pom.xml`。

---

## 2. SQL 的三种使用形态与分工

### 2.1 派生查询（Derived Query）—— 只用于简单过滤

```java
// system/SystemSettingJpaRepository
findByKindAndNamespaceOrderBySortOrderAscKeyAsc(String kind, String namespace);
// identity/UcspPasswordHistoryJpaRepository
findByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(Long userId);
// initialization/TlcpCertificatePairJpaRepository
findFirstByIdentityCodeAndStatus(String identityCode, TlcpCertificatePairStatus status);
```

特点：全部是单表、无锁、无聚合；涉及 `deleted`/`status` 软删除条件时也常被 `@Query` 取代。

### 2.2 JPQL —— 状态流转与乐观锁（CAS）

```java
// initialization/InitializationFlowJpaRepository：乐观锁状态机
@Query("update InitializationFlowEntity f set f.state=:nextState,"
     + "f.workingKeyId=:workingKeyId,f.activeMaterialVersion=:activeMaterialVersion,"
     + "f.version=f.version+1,f.lastErrorCode=null,f.lastErrorAt=null,f.lastErrorNodeId=null,"
     + "f.updatedAt=:updatedAt where f.singletonId=:singletonId and f.state=:expectedState "
     + "and f.version=:expectedVersion")
```

```java
// application/ApplicationBindingJpaRepository：条件计数 / 存在性
@Query("select count(d) from ManagedDeviceEntity d where d.deleted=false "
     + "and d.status = 'ACTIVE' and d.capability = :capability "
     + "and not exists (select m.id from DeviceGroupMemberEntity m "
     + "  where m.deviceId=d.id and m.active=true and m.deleted=false)")
```

JPQL 中可移植的函数：`coalesce()`、`lower()`、`concat()`、`case when`、`count/distinct/in/exists`。

### 2.3 原生 PostgreSQL —— 六类必须 native 的场景

| 场景 | 原因 | 代表位置 |
|---|---|---|
| 行锁抢占队列 | 需要 `FOR UPDATE SKIP LOCKED` | `NotificationDeliveryJpaRepository.claimDue`、`SecureExportJpaRepository.claim`、`BindingSyncRepository.claimDue`、`DeviceCommandRepository.pollCommands` |
| 幂等 / Upsert | 需要 `ON CONFLICT`、`RETURNING` | `DeviceMetricSnapshotRepository.upsertSnapshot`、`DeviceServiceStatusRepository.upsert`、`DeviceErrorLogRepository.insertIfAbsent` |
| JSONB 读写 | JPQL 不认 `->`/`::jsonb`/`jsonb_array_elements` | `StatisticsJpaRepository.rebuild*`、`DeviceCommandRepository` |
| 跨表聚合统计 | 需要 `LATERAL`、`generate_series`、`date_trunc` | `StatisticsJpaRepository.*Dashboard/*Trend`、`OrganizationOverviewRepository` |
| 批量清理 | 需要 `DELETE ... WHERE id IN (SELECT ... LIMIT n)` | `AlertInstanceJpaRepository.deleteResolvedBefore` 等 5 处 |
| 咨询锁 / 目录表 | `pg_advisory_xact_lock`、`pg_constraint` | `InitializationFlowRepository`、`UserAccountRepository` |

**源码里明确写出的“为什么用 native”注释（节选）：**

- `DeviceCommandRepository.pollCommands`：「抢占和批量超时需要同一事务中的行锁，因此继续使用 PostgreSQL 原生 SQL。」
- `ApplicationAccessRepository`：「普通读取和写入使用 JPA EntityManager，幂等与批量清理保留 PostgreSQL 原生 SQL。」
- `ApplicationSecretMaterialRepository.listActiveKeys`：「机构过滤走 native SQL，Spring Data 不会把排序属性名（`updatedAt`）翻译成列名（`updated_at`），因此这里只传分页参数。」
- `ApplicationControlRepository.currentVersion`：「绑定以原生 SQL 递增应用版本，不回写会话内实体，同一事务内再用 JPQL 重取只会命中一级缓存的旧版本。」
- `OrganizationOverviewRepository`：「均为跨表 native SQL。」

---

## 3. PostgreSQL 专有语法索引

### 3.1 并发控制

| 语法 | 出现位置（方法 → 文件） |
|---|---|
| `FOR UPDATE SKIP LOCKED` | `claimDue` → `NotificationDeliveryJpaRepository`；`claim` → `SecureExportJpaRepository`；`claimDue` → `BindingSyncRepository`；`pollCommands` → `DeviceCommandRepository`；`findAllOpenForRollback` / `findExpiredForRollback` → `NetworkChangeJpaRepository` |
| CTE 内 `FOR UPDATE ... LIMIT` + `UPDATE ... FROM picked` + `RETURNING` | `claimDue`（投递）、`claimDue`（绑定同步）、`claim`（导出） |
| native `FOR UPDATE` | `applyResult` / `alignReplayedResult` → `DeviceCommandRepository`；`advanceStreamCursor` → `DeviceAccessRepository`；`updateHsmKeyAccessPassword` → `ApplicationControlRepository` |
| `@Lock(PESSIMISTIC_WRITE)`（Hibernate 生成 `FOR UPDATE`） | `findForUpdate` → `ApplicationControlJpaRepository` / `ManagedDeviceJpaRepository` / `DeviceGroupJpaRepository`；`findFirstByFingerprintAndStatusIn` → `AlertInstanceJpaRepository`；`ApprovalRequestJpaRepository`、`UcspUserAccountJpaRepository` |
| `SELECT 1 FROM pg_advisory_xact_lock(6143845920955844948)` | `InitializationFlowRepository.initialize/completeInitialization`、`UserAccountRepository`（同一把锁，key = `0x55435350494E4954` = "UCSPINIT"） |
| 乐观锁 CAS（`WHERE version=:version`、`f.version=f.version+1`） | JPQL 侧：`InitializationFlowJpaRepository`、`TlcpCertificatePairJpaRepository`；native 侧：`ApplicationBindingRepository.incrementBindingVersion`（`RETURNING binding_version`） |
| 命令行锁 + 唯一键保证幂等 | `DeviceCommandRepository.applyResult` |

### 3.2 Upsert / 幂等写

| 变体 | 出现位置 |
|---|---|
| `ON CONFLICT (...) DO NOTHING` | `NotificationDeliveryJpaRepository.enqueue`（通道+告警+事件+版本唯一键）、`DeviceErrorLogRepository.insertIfAbsent`、`ApplicationAccessRepository.claimNonce`、`DeviceAccessRepository.activateDevice`、`SelfTestRunJpaRepository.recoverStale` |
| `ON CONFLICT (...) DO UPDATE ... = EXCLUDED.x` | `DeviceMetricSnapshotRepository.upsertSnapshot/upsertWatermark`、`StatisticsJpaRepository.rebuildApplicationMinutes/rebuildHigher/upsertApplicationAggregate/upsertWatermark`、`NotificationRepository.assignOrganization` |
| `DO UPDATE ... WHERE <guard>`（条件 upsert） | `DeviceServiceStatusRepository.upsert`（同纪元只接受更大序号，跨纪元以接收时间更晚者为准） |
| `GREATEST(..., EXCLUDED...)` 单调推进 | `DeviceMetricSnapshotRepository.upsertWatermark`、`DeviceAccessRepository.advanceStreamCursor`、`StatisticsJpaRepository.upsertWatermark` |
| `ON CONFLICT DO NOTHING RETURNING id` | `DeviceAccessRepository.telemetryInsertReturningId` |
| `ON CONFLICT (device_id, stream_type, stream_epoch) DO UPDATE ... GREATEST(..., EXCLUDED...)` | `DeviceAccessRepository.advanceStreamCursor` |

### 3.3 JSONB

| 用法 | 位置 |
|---|---|
| `columnDefinition = "jsonb"` + `@ColumnTransformer(read = "parameters::text", write = "?::jsonb")` | `DeviceCommandEntity.parameters` |
| `columnDefinition = "jsonb default '{}'"` | `DeviceErrorLogEntity.attributes`、`DeviceServiceStatusEntity.attributes` |
| `CAST(:param AS jsonb)` / `CAST(:param AS JSONB)` | `DeviceCommandRepository.refreshCommand`、`DeviceAccessRepository.telemetryInsertReturningId`、`DeviceServiceStatusRepository.upsert`、`DeviceErrorLogRepository.insertIfAbsent` |
| `LEFT JOIN LATERAL jsonb_array_elements(...) ON TRUE` | `StatisticsJpaRepository.rebuildDeviceMinutes` / `rebuildApplicationMinutes`（各 2 层展开） |
| `jsonb_typeof` / `jsonb_array_length` / `jsonb_build_array` | 同上（把「可能是对象也可能是数组」的 payload 归一化成数组） |
| `->` / `->>` 取值 + `CAST(... AS bigint/double precision/text)` | 所有遥测聚合 SQL |
| `CAST(parameters AS text) LIKE :needle` | `DeviceCommandRepository.appendNeedles/hasActiveCommandContaining/hasSucceededCommandContaining/findLatestContaining/expireOtherActiveContaining` |

> **踩坑记录（源码注释）**：
> 「PostgreSQL `jsonb::text` 是 `"key": "value"`（冒号后有空格），Jackson 落库原文是 `"key":"value"`。
> 补偿按紧凑片段 LIKE 会永远找不到，于是每轮都 insert。」→ 代码里 `spacedJsonField()` 会同时生成两种 needle。

### 3.4 时间、序列与日历

| 用法 | 位置 |
|---|---|
| `date_trunc('minute'|'hour'|'day'|'month', ...)` | 所有聚合重建与 Dashboard/Trend 查询 |
| `NOW() - (:days - 1) * INTERVAL '1 day'` | `organizationDashboard`、`applicationDashboard`、`*Trend`、`applicationOnboardingTrend` |
| `timezone('UTC', to_timestamp(... / 1000.0))` | `rebuildDeviceMinutes`、`rebuildApplicationMinutes`（毫秒 epoch → UTC 时间戳） |
| `generate_series(start, end, step)` 日历脊柱 | `StatisticsJpaRepository.applicationOnboardingTrend` |
| `generate_series(1, 63)` 找空缺序号 | `ApplicationControlRepository.nextOrgSequence`（机构内应用序号 1–63） |
| `EXTRACT(EPOCH FROM (:a - started_at))` + `GREATEST` | `SelfTestRunJpaRepository.recoverStale`（算超时秒数） |
| `NULLS LAST` | `OrganizationOverviewRepository.listApplications`（`org_seq_no` 可空排序） |

### 3.5 分页、批量删除与子查询

```sql
-- 分页（native 侧统一 LIMIT/OFFSET 参数）
... ORDER BY p.org_seq_no NULLS LAST, c.application_id LIMIT :limit OFFSET :offset

-- 批量清理：子查询 + ORDER BY + LIMIT（5 处同款）
DELETE FROM ucsp_notification_delivery
WHERE id IN (SELECT id FROM ucsp_notification_delivery
             WHERE status IN ('SUCCEEDED','FAILED') AND completed_at < :cutoff
             ORDER BY completed_at LIMIT :limit)
```

同类：`AlertInstanceJpaRepository.deleteResolvedBefore`、`StatisticsJpaRepository.purgeTelemetry`、
`AuditLogManagementJpaRepository.purgeAuditedBefore`、`StatisticsJpaRepository.purgeAggregates`（行值 `IN (subquery)`）。

其他常见写法：

- **可空参数技巧**：`(:scopeOrg IS NULL OR p.organization_id = :scopeOrg)`（`ApplicationStatisticsRepository` 全部 4 个 count）、
  `(:eventType is null or e.eventType=:eventType)`（JPQL 侧同款）。
- **`ORDER BY CASE ... END`** 优先级排序：`AlertRuleJpaRepository.findEffective`（DEVICE > DEVICE_TYPE > ALL）。
- **`SUM(CASE WHEN ...) / GROUP BY`** 条件聚合：`ApplicationStatisticsRepository.listServiceDeviceStatuses`。
- **`NOT EXISTS` / `EXISTS`**：`ManagedDeviceJpaRepository`（可用设备探测）、`countRunningServices`。
- **`SELECT DISTINCT c.* ... LEFT JOIN ... ORDER BY`**：`NotificationChannelJpaRepository` / `EmailChannelJpaRepository` 的四类路由查询。
- **动态 `IN` 列表**：`ApplicationControlRepository.appendStatusFilter` 生成 `IN (:status0,:status1,...)`。

### 3.6 类型强转

`CAST(x AS ...)` 是本项目的统一风格（**全仓库几乎不用 `::` 运算符做参数转换**）：

```sql
CAST(:latencyMillis AS bigint)      -- 参数显式定型
CAST(:detailCode    AS varchar)
CAST(:attributes    AS jsonb)
CAST(r.target_id    AS bigint)      -- 文本目标 ID 关联应用表
CAST(ROUND(CAST(m.cpu_percent AS numeric)) AS bigint)
CAST(a.subject_id   AS BIGINT)      -- subject_id 是字符串列，join 前强转
CAST(:password      AS varchar)     -- UPDATE 里的写入定型
CAST(parameters     AS text)        -- jsonb 转文本供 LIKE
```

> **踩坑记录（源码注释）**：「PostgreSQL 会把未标明类型的 `null` 参数绑成 `bytea`，
> `CAST(bytea AS INTEGER)` 直接失败，须显式声明类型。」
> → 出现于 `DeviceAccessRepository.completeOnboardingAttempt`、`DeviceCommandRepository.applyResult`、
> `DeviceServiceStatusRepository.upsert`。Java 侧相应地用 `StandardBasicTypes.*` 显式绑定。

---

## 4. 代表性 pgSQL 合集（按主题）

以下语句全部来自 `pqc-ucsp-data` 真实代码，`:param` 为 Spring Data 命名参数。

### 4.1 队列抢占：`SKIP LOCKED` + `UPDATE ... FROM` + `RETURNING`

```sql
-- NotificationDeliveryJpaRepository.claimDue（投递箱多线程/多节点抢任务）
WITH picked AS (
  SELECT id FROM ucsp_notification_delivery
  WHERE channel_id = :channelId
    AND status IN ('PENDING','RETRY_WAIT')
    AND next_attempt_at <= :now
  ORDER BY next_attempt_at, id
  FOR UPDATE SKIP LOCKED
  LIMIT :limit
)
UPDATE ucsp_notification_delivery d
SET status = 'SENDING',
    attempt_count = d.attempt_count + 1,
    last_attempt_at = :now
FROM picked
WHERE d.id = picked.id
RETURNING d.*
```

```sql
-- SecureExportJpaRepository.claim（同一模板，导出任务）
WITH picked AS (
  SELECT id FROM ucsp_secure_export
  WHERE node_id = :nodeId AND status = 'PENDING'
  ORDER BY created_at, id
  FOR UPDATE SKIP LOCKED
  LIMIT :limit
)
UPDATE ucsp_secure_export e
SET status = 'RUNNING', started_at = :now
FROM picked WHERE e.id = picked.id
RETURNING e.*
```

```sql
-- DeviceCommandRepository.pollCommands（先批量超时，再抢指令，再盖投递时间）
UPDATE ucsp_device_command SET status='TIMED_OUT', completed_at=:now, updated_at=:now
WHERE device_id=:deviceId AND deleted=FALSE
  AND status IN ('PENDING','ACCEPTED','RUNNING') AND expires_at <= :now;

SELECT request_id, device_id, command_type, CAST(parameters AS text) AS parameters, ...
FROM ucsp_device_command
WHERE device_id=:deviceId AND deleted=FALSE
  AND status IN ('PENDING','ACCEPTED','RUNNING') AND expires_at > :now
ORDER BY created_at, request_id
LIMIT :maximum
FOR UPDATE SKIP LOCKED;

UPDATE ucsp_device_command
SET delivered_at = COALESCE(delivered_at, :now), updated_at = :now
WHERE request_id = :requestId AND deleted = FALSE;
```

### 4.2 幂等与 Upsert

```sql
-- NotificationDeliveryJpaRepository.enqueue：四列联合唯一 → 重复通知只入队一次
INSERT INTO ucsp_notification_delivery(id, channel_id, channel_type, alert_id, lifecycle_event,
    alert_state_version, severity, alert_status, title, message, object_type, object_id,
    alert_source, dimension, last_value, first_occurred_at, last_occurred_at,
    status, attempt_count, next_attempt_at, created_at)
VALUES (:id, :channelId, :channelType, :alertId, :lifecycleEvent, :alertStateVersion, :severity,
        :alertStatus, :title, :message, :objectType, :objectId, :alertSource, :dimension,
        :lastValue, :firstOccurredAt, :lastOccurredAt, 'PENDING', 0, :nextAttemptAt, :createdAt)
ON CONFLICT (channel_id, alert_id, lifecycle_event, alert_state_version) DO NOTHING
```

```sql
-- DeviceServiceStatusRepository.upsert：条件 upsert，拒绝乱序旧值
INSERT INTO ucsp_device_service_status(device_id, stream_epoch, sequence, status,
    latency_millis, detail_code, attributes, observed_at, received_at)
VALUES (:deviceId, :streamEpoch, :sequence, :status,
        CAST(:latencyMillis AS bigint), CAST(:detailCode AS varchar), CAST(:attributes AS jsonb),
        :observedAt, :receivedAt)
ON CONFLICT (device_id) DO UPDATE SET
  stream_epoch = EXCLUDED.stream_epoch,
  sequence     = EXCLUDED.sequence,
  status       = EXCLUDED.status,
  latency_millis = EXCLUDED.latency_millis,
  detail_code  = EXCLUDED.detail_code,
  attributes   = EXCLUDED.attributes,
  observed_at  = EXCLUDED.observed_at,
  received_at  = EXCLUDED.received_at
WHERE (ucsp_device_service_status.stream_epoch = EXCLUDED.stream_epoch
       AND ucsp_device_service_status.sequence < EXCLUDED.sequence)
   OR (ucsp_device_service_status.stream_epoch <> EXCLUDED.stream_epoch
       AND ucsp_device_service_status.received_at < EXCLUDED.received_at)
```

```sql
-- DeviceMetricSnapshotRepository.upsertWatermark：水位线单调推进
INSERT INTO ucsp_device_watermark(stream_type, last_telemetry_id, updated_at)
VALUES (:streamType, :telemetryId, :now)
ON CONFLICT (stream_type) DO UPDATE SET
  last_telemetry_id = GREATEST(ucsp_device_watermark.last_telemetry_id, EXCLUDED.last_telemetry_id),
  updated_at        = EXCLUDED.updated_at
```

```sql
-- ApplicationBindingRepository.incrementBindingVersion：CAS 递增并读回新版本
UPDATE ucsp_application_control
SET binding_version = binding_version + 1, version = version + 1, updated_at = :now
WHERE application_id = :applicationId AND version = :version AND lifecycle_status = 'ACTIVE'
RETURNING binding_version          -- 无结果 = 版本已被他人改动 → 抛 APPLICATION_VERSION_CHANGED
```

```sql
-- ApplicationControlRepository.insertPlatform：写入即取自增主键
INSERT INTO pqc_platform_application
  (app_name, app_pin, status, remark, deleted, organization_id, org_seq_no, create_time, update_time)
VALUES (:name, :appPin, 'DISABLED', :remark, FALSE, :orgId, :seqNo, :now, :now)
RETURNING id
```

### 4.3 JSONB 遥测展开与聚合（最复杂的一段 SQL）

```sql
-- StatisticsJpaRepository.rebuildDeviceMinutes
-- 遥测 payload → 逐层展开 entries[] / callStat[] → 按分钟聚合落表
WITH decoded AS (
  SELECT device_id,
         timezone('UTC', to_timestamp(
             CAST(payload->>'windowStartEpochMillis' AS double precision) / 1000.0)) AS event_time,
         payload
  FROM ucsp_device_telemetry
  WHERE stream_type = 'INVOCATION_STAT'
    AND received_at >= :scanFrom AND received_at < :scanTo
),
apps AS (
  SELECT d.device_id, d.event_time, d.payload, e.app
  FROM decoded d
  LEFT JOIN LATERAL jsonb_array_elements(
      CASE WHEN jsonb_typeof(d.payload->'entries') = 'array'
            AND jsonb_array_length(d.payload->'entries') > 0
           THEN d.payload->'entries'
           ELSE jsonb_build_array(d.payload) END) AS e(app) ON TRUE
),
exploded AS (
  SELECT a.device_id, a.event_time, a.payload, a.app, c.elem
  FROM apps a
  LEFT JOIN LATERAL jsonb_array_elements(
      CASE WHEN jsonb_typeof(a.app->'callStat') = 'array'
            AND jsonb_array_length(a.app->'callStat') > 0
           THEN a.app->'callStat'
           ELSE jsonb_build_array(a.app) END) AS c(elem) ON TRUE
)
INSERT INTO ucsp_invocation_aggregate(source, granularity, bucket_start, subject_id, capability,
    operation, execution_mode, success_count, failure_count, total_duration_millis,
    avg_duration_millis, max_duration_millis, min_duration_millis, updated_at)
SELECT 'DEVICE','MINUTE', date_trunc('minute', event_time), device_id,
       COALESCE(elem->>'capability', app->>'capability', payload->>'capability'),
       COALESCE(elem->>'methodName', app->>'methodName',
                payload->>'methodName', payload->>'operation'),
       'REMOTE',
       SUM(CAST(elem->>'successCount' AS bigint)),
       SUM(CAST(elem->>'failureCount' AS bigint)),
       SUM(CAST(COALESCE(elem->>'totalDurationMillis', app->>'totalDurationMillis',
                         payload->>'totalDurationMillis', ...) AS bigint)),
       CASE WHEN SUM(CAST(COALESCE(elem->>'totalCount','0') AS bigint)) > 0 THEN
            SUM(CAST(COALESCE(elem->>'avgDurationMillis','0') AS bigint)
              * CAST(COALESCE(elem->>'totalCount','0') AS bigint))
          / SUM(CAST(COALESCE(elem->>'totalCount','0') AS bigint))
       ELSE 0 END,
       MAX(CAST(COALESCE(elem->>'maxDurationMillis','0') AS bigint)),
       MIN(CAST(COALESCE(elem->>'minDurationMillis','0') AS bigint)),
       :now
FROM exploded
WHERE event_time >= :fromTime AND event_time < :toTime
GROUP BY date_trunc('minute', event_time), device_id, <capability>, <operation>
```

应用级版本（`rebuildApplicationMinutes`）额外带：
`COALESCE((SELECT id FROM pqc_platform_application WHERE app_name = a.app->>'appName' ... LIMIT 1), '-1')`
以及 `ON CONFLICT (source, granularity, bucket_start, subject_id, capability, operation, execution_mode)
DO UPDATE SET ... = EXCLUDED....`。

### 4.4 时间序列：日历脊柱 `generate_series`

```sql
-- StatisticsJpaRepository.applicationOnboardingTrend
SELECT CAST(d.day AS date) AS day,
       COUNT(p.id) AS createdCount,
       (SELECT COUNT(a.id) FROM pqc_platform_application a
         WHERE a.deleted = FALSE AND a.create_time < d.day + INTERVAL '1 day') AS totalCount
FROM generate_series(date_trunc('day', NOW() - (:days - 1) * INTERVAL '1 day'),
                     date_trunc('day', NOW()), INTERVAL '1 day') AS d(day)
LEFT JOIN pqc_platform_application p
       ON p.create_time >= d.day AND p.create_time < d.day + INTERVAL '1 day'
      AND p.deleted = FALSE
GROUP BY d.day
ORDER BY d.day
```

```sql
-- ApplicationControlRepository.nextOrgSequence：机构内找 1..63 的最小空位
SELECT COALESCE(MIN(n), 0)
FROM generate_series(1, 63) AS n
WHERE n NOT IN (SELECT org_seq_no FROM pqc_platform_application
                WHERE organization_id = :orgId AND deleted = FALSE AND org_seq_no IS NOT NULL)
```

```sql
-- StatisticsJpaRepository.organizationDashboard（CTE + 条件聚合，节选）
WITH org_apps AS (
  SELECT p.id AS app_id FROM pqc_platform_application p
  WHERE p.organization_id = :orgId AND p.deleted = FALSE
)
SELECT COALESCE(SUM(CASE WHEN a.bucket_start = date_trunc('day', NOW())
                         THEN a.success_count + a.failure_count ELSE 0 END), 0) AS todayCount,
       COALESCE(SUM(CASE WHEN a.bucket_start >= date_trunc('month', NOW()) - INTERVAL '1 month'
                           AND a.bucket_start < date_trunc('month', NOW())
                         THEN a.success_count + a.failure_count ELSE 0 END), 0) AS lastMonthCount,
       ...
FROM ucsp_invocation_aggregate a
JOIN org_apps ON org_apps.app_id = CAST(a.subject_id AS BIGINT)
WHERE a.source = 'APPLICATION' AND a.granularity = 'DAY'
```

### 4.5 告警规则命中与路由

```sql
-- AlertRuleJpaRepository.findEffective：作用域优先级排序后取第一条
SELECT r.* FROM ucsp_alert_rule r
WHERE r.enabled = TRUE AND r.source = :source AND r.metric = :metric
  AND ((r.scope_type='DEVICE' AND r.scope_id = :deviceId)
    OR (r.scope_type='DEVICE_TYPE' AND r.scope_id =
        (SELECT product_type FROM ucsp_managed_device WHERE id = :deviceId))
    OR (r.scope_type='DEVICE_TYPE' AND r.scope_id = 'ALL'))
ORDER BY CASE WHEN r.scope_type='DEVICE' THEN 0
              WHEN r.scope_type='DEVICE_TYPE' AND r.scope_id<>'ALL' THEN 1
              ELSE 2 END
LIMIT 1
```

```sql
-- NotificationChannelJpaRepository.findEligibleForDevice：全局 scope 或绑定关系命中
SELECT DISTINCT c.* FROM ucsp_notification_channel c
LEFT JOIN ucsp_notification_channel_application b ON b.channel_id = c.id
WHERE c.enabled = TRUE AND c.deleted = FALSE
  AND (c.scope_type = 'GLOBAL'
    OR EXISTS (SELECT 1 FROM ucsp_application_device ad
               WHERE ad.application_id = b.application_id
                 AND ad.deleted = FALSE AND ad.device_id = :deviceId))
ORDER BY c.created_at, c.id
```

### 4.6 分片清理

```sql
-- AlertInstanceJpaRepository.deleteResolvedBefore
DELETE FROM ucsp_alert_instance
WHERE id IN (SELECT id FROM ucsp_alert_instance
             WHERE status='RESOLVED' AND resolved_at < :cutoff
             ORDER BY resolved_at LIMIT :limit)

-- ApplicationAccessRepository.purgeExpiredNonces：CTE + DELETE ... USING（复合键关联）
WITH expired AS (
  SELECT credential_id, nonce FROM ucsp_application_nonce
  WHERE expires_at < :before
  ORDER BY expires_at, credential_id, nonce
  LIMIT :limit
)
DELETE FROM ucsp_application_nonce n
USING expired e
WHERE n.credential_id = e.credential_id AND n.nonce = e.nonce
```

### 4.7 机构总览（跨表 JOIN）

```sql
-- OrganizationOverviewRepository.listDeviceRuntime
SELECT ad.application_id, ad.device_id, d.name, d.capability, d.product_type,
       d.hosting_type, d.business_port, d.business_protocol, ad.created_at,
       d.observed_status, d.updated_at AS service_observed_at,
       m.observed_at AS metric_observed_at,
       CAST(ROUND(CAST(m.cpu_percent AS numeric)) AS bigint) AS cpu_percent,
       m.memory_used_bytes, m.memory_total_bytes, m.disk_used_bytes, m.disk_total_bytes
FROM ucsp_application_device ad
JOIN ucsp_managed_device d ON d.id = ad.device_id
LEFT JOIN ucsp_device_metric_snapshot m
       ON m.device_id = ad.device_id AND m.stream_type = 'SYSTEM_METRIC' AND m.deleted = FALSE
WHERE ad.application_id IN :applicationIds AND ad.deleted = FALSE
  AND d.deleted = FALSE AND d.management_status <> 'DELETED'
  AND d.capability = 'HSM'
ORDER BY ad.application_id, ad.device_id
```

### 4.8 幂等结果回报（锁 + 唯一键）

```sql
-- DeviceCommandRepository.applyResult 的四步
SELECT status, progress_percent, result_code, sanitized_result, completed_at
FROM ucsp_device_command_result_event
WHERE request_id = :requestId AND report_request_id = :reportRequestId;

SELECT request_id, device_id, ... FROM ucsp_device_command
WHERE request_id = :requestId AND device_id = :deviceId AND deleted = FALSE FOR UPDATE;

INSERT INTO ucsp_device_command_result_event(request_id, report_request_id, status,
    progress_percent, result_code, sanitized_result, completed_at, reported_at)
VALUES (:requestId, :reportRequestId, :status, :progress, :resultCode, :result,
        :completedAt, :reportedAt);          -- 唯一键冲突 → 判定 CONFLICT/DUPLICATE

UPDATE ucsp_device_command SET status=:status, progress_percent=:progress,
    result_code=:resultCode, sanitized_result=:result, completed_at=:completedAt,
    updated_at=:reportedAt
WHERE request_id = :requestId AND deleted = FALSE;
```

### 4.9 会话级互斥：咨询锁

```sql
-- InitializationFlowRepository / UserAccountRepository（同一把锁）
SELECT 1 FROM pg_advisory_xact_lock(6143845920955844948)   -- 0x55435350494E4954 = 'UCSPINIT'
```

---

## 5. 实体映射层与 PostgreSQL 类型的对应

```java
// deviceaccess/entity/DeviceCommandEntity.java
@Column(name = "parameters", nullable = false, columnDefinition = "jsonb")
@ColumnTransformer(read = "parameters::text", write = "?::jsonb")
private String parameters;

// monitoring/entity/DeviceErrorLogEntity.java / DeviceServiceStatusEntity.java
@Column(name = "attributes", columnDefinition = "jsonb default '{}'")
private String attributes;

// 长文本（PEM 链、密文、公钥、请求体）统一 TEXT 而非 VARCHAR/BYTEA
@Column(name = "sign_certificate_chain", nullable = false, columnDefinition = "text")
@Column(name = "protected_secret", nullable = false, columnDefinition = "TEXT")
@Column(name = "request_content", columnDefinition = "text")
```

要点：

- **JSONB 列**靠 `columnDefinition` + `@ColumnTransformer` 打通：Java 侧是 `String`，
  读出时走 `parameters::text`（因此读到的是带空格的 `"key": "value"` 形式），写入时 `?::jsonb`。
- **二进制材料（密钥/证书/密文）全部存 `TEXT`**（base64 / PEM），仓库中 `bytea` 出现 0 次。
- **主键**统一 `BIGINT GENERATED BY DEFAULT AS IDENTITY`（DDL 侧 35 处），无 `serial`/`nextval`。
- **时间**统一无时区 `TIMESTAMP`（201 处），0 处 `timestamptz`。
- **软删除** `deleted BOOLEAN DEFAULT FALSE` 是几乎所有索引与查询的前置条件。

---

## 6. DDL 侧（Liquibase）的 PostgreSQL 用法

`pqc-ucsp-data/src/main/resources/db/changelog/`：master（XSD 4.9，80 个 `<include>`）+ `p1…p26` + `comments/`，
共 87 文件 / 95 changeset / 约 255 KB。**绝大多数 changeset 是裸 SQL**（109 个 `<sql>`/`<sqlFile>`，仅 1 个 `<createTable>`）。

### 6.1 列类型分布

| 类型 | 次数 | 说明 |
|---|---|---|
| `VARCHAR(n)` | 441 | 主力字符串 |
| `TIMESTAMP`（无时区） | 201 | 无 `timestamptz` |
| `BIGINT` | 177 | |
| `BOOLEAN` | 82 | 普遍 `DEFAULT FALSE` |
| `INT`/`SMALLINT` | 65 | |
| `TEXT` | 41 | PEM 链、JSON 文本、请求体 |
| **`JSONB`** | 19 | `DEFAULT '{}'::jsonb` + `jsonb_typeof` CHECK |
| `CHAR(n)` | 18 | `CHAR(64)` 摘要/指纹 |
| `UUID` | 1 | |
| `NUMERIC` / `BYTEA` / 数组 | 0 | 数值仅出现在 `CAST(... AS numeric)` |

```sql
payload JSONB NOT NULL,
attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
CONSTRAINT ck_ucsp_snmp_template_oids CHECK (jsonb_typeof(oids) = 'object')
```

### 6.2 约束与 CHECK（185 处 / 164 个不同表达式）

```sql
CONSTRAINT ck_ucsp_application_status
    CHECK (lifecycle_status IN ('PENDING','ACTIVE','DISABLED','DELETED'))
CONSTRAINT ck_ucsp_approval_decision
    CHECK ((status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status<>'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
CONSTRAINT ck_ucsp_managed_device_ports
    CHECK (business_port BETWEEN 1 AND 65535
       AND (management_port IS NULL OR management_port BETWEEN 1 AND 65535))
CONSTRAINT ck_ucsp_initialization_singleton CHECK (singleton_id = 1)
CONSTRAINT ck_ucsp_initialization_state CHECK (state IN (
    'NOT_STARTED','TRUST_ANCHORS_IMPORTED','PENDING_CA_ISSUE',
    'TLCP_IDENTITY_VALIDATED','TLCP_IDENTITY_ACTIVE','COMPLETED'))
```

### 6.3 索引（111 条，其中 **49 条部分索引**）

```sql
-- 软删除唯一（主流模式）
CREATE UNIQUE INDEX uk_platform_user_username
    ON pqc_platform_sys_user (username) WHERE deleted = FALSE;
CREATE UNIQUE INDEX uk_platform_user_username_casefold
    ON pqc_platform_sys_user (lower(username)) WHERE deleted = FALSE;   -- 函数索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_ucsp_application_device
    ON ucsp_application_device (application_id, device_id) WHERE deleted = FALSE;

-- 业务状态唯一
CREATE UNIQUE INDEX uk_ucsp_application_credential_current
    ON ucsp_application_credential (application_id) WHERE status='ACTIVE' AND expires_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ucsp_alert_rule_scope_active
    ON ucsp_alert_rule (source, metric, scope_type, scope_id) WHERE NOT deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ucsp_application_secret_material_active
    ON ucsp_application_secret_material (application_id, material_type, key_type) WHERE status='ACTIVE';

-- 部分非唯一索引
CREATE INDEX idx_ucsp_group_member_group
    ON ucsp_device_group_member (group_id) WHERE active = TRUE;

-- 机构内序号（唯一 + 部分 + 可空条件）
CREATE UNIQUE INDEX IF NOT EXISTS uk_pqc_platform_application_org_seq
    ON pqc_platform_application (organization_id, org_seq_no)
    WHERE deleted = FALSE AND org_seq_no IS NOT NULL;
```

> 全部为默认 btree 访问方法：**没有 `USING gin/gist/brin`、没有 `CREATE EXTENSION`、没有 `DISTINCT ON`、没有 `ILIKE`**。

### 6.4 幂等与目录级操作

```sql
-- 1 处 ON CONFLICT（种子数据，p7/002）
INSERT INTO ucsp_system_setting (...) VALUES (...)
ON CONFLICT (setting_kind, namespace, setting_key) DO NOTHING;

-- 批量删除全部外键（p8/002，dbms="postgresql"）—— 平台层不使用数据库外键
DO $$
DECLARE foreign_key RECORD;
BEGIN
  FOR foreign_key IN
    SELECT ns.nspname AS schema_name, tbl.relname AS table_name, con.conname AS constraint_name
    FROM pg_constraint con
    JOIN pg_class tbl ON tbl.oid = con.conrelid
    JOIN pg_namespace ns ON ns.oid = tbl.relnamespace
    WHERE con.contype = 'f' AND ns.nspname NOT IN ('pg_catalog','information_schema')
  LOOP
    EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT IF EXISTS %I',
                   foreign_key.schema_name, foreign_key.table_name, foreign_key.constraint_name);
  END LOOP;
END $$;

-- 条件加约束（p8/005）
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_ucsp_application_rotation_request') THEN
    ALTER TABLE ucsp_application_credential ADD CONSTRAINT ck_ucsp_application_rotation_request CHECK (...);
  END IF;
END $$;

-- UPDATE ... FROM 联合更新（p1/007 数据迁移）
UPDATE pqc_platform_init_status target
SET initialized = target.initialized OR legacy.initialized,
    status = CASE WHEN legacy.initialized THEN 'INITED' ELSE target.status END,
    init_time = CASE WHEN legacy.initialized
                     THEN COALESCE(target.init_time, legacy.initialized_at)
                     ELSE target.init_time END,
    update_time = CURRENT_TIMESTAMP
FROM ucsp_platform_initialization legacy
WHERE target.module = 'ucsp' AND target.deleted = FALSE AND legacy.singleton_id = 1;
```

其他 DDL 统计：`COMMENT ON` **989 条**（707 条集中在 `comments/001-table-column-comments.sql`）、
`IF NOT EXISTS` 116 次、`ALTER TABLE` 191 条、`CREATE TABLE IF NOT EXISTS` 12 条。
**全库不创建外键**（0 处 `REFERENCES`），完整性由 Java 仓储保证（CAS、`FOR UPDATE`、advisory lock）。

---

## 7. 已知坑与实践约定

| # | 约定 / 坑 | 出处 |
|---|---|---|
| 1 | **未定型的 `null` 参数会被绑成 `bytea`** → 所有可能为 null 的参数必须 `CAST(:p AS type)`，Java 侧用 `StandardBasicTypes.*` 绑定 | `DeviceAccessRepository`、`DeviceCommandRepository`、`DeviceServiceStatusRepository` |
| 2 | **`jsonb::text` 输出带空格**（`"key": "value"`），与 Jackson 原文（`"key":"value"`）不一致 → LIKE 检索需同时匹配两种形态 | `DeviceCommandRepository.spacedJsonField` |
| 3 | **native SQL 里属性名不翻译**（`updatedAt` ≠ `updated_at`）→ native 查询的排序/字段一律手写列名 | `ApplicationSecretMaterialRepository` |
| 4 | **一级缓存会遮蔽 native 写入** → 乐观锁版本要用原生标量查询读库内真实值 | `ApplicationControlRepository.currentVersion` |
| 5 | **`ON CONFLICT` 覆盖写要求窗口对齐**：先删目标粒度旧行再插，否则邻桶归并到边界前会删不掉 | `StatisticsJpaRepository.rebuildHigher` javadoc |
| 6 | **无时区时间戳统一按 UTC 墙钟落库**，读回时按 UTC 还原 `Instant` | `ApplicationControlRepository.toInstant` |
| 7 | **部分唯一索引让「软删除后复活」成为可能**：删除态不占唯一索引，重插不冲突 | `NotificationRepository.saveChannel` javadoc |
| 8 | **同一事务的锁顺序要一致**（`control → platform`），避免死锁 | `ApplicationControlRepository.updateHsmKeyAccessPassword` |
| 9 | **JPQL + native 混写时 `@Modifying(clear = true, flush = true)`** 用于让后续 JPQL 读到 native 写入 | `system`、`initialization`、`audit` 包共 23 处 |
| 10 | `ddl-auto=validate` + 生产 `liquibase.enabled=false` → **改表必须先改 changelog 并外部执行**，否则启动即校验失败 | `application-prod.yml` |

---

## 8. 仓储清单（按包）

| 包 | 文件数 | SQL 特征 |
|---|---|---|
| `monitoring` | 25 | 44 native `@Query` + 6 inline native；`SKIP LOCKED` 投递、jsonb 聚合重建、Dashboard/Trend、告警路由 |
| `device` | 20 | 以 JPQL 为主（27 条 `ManagedDevice` 查询），`@Lock` 乐观/悲观并发，`NOT EXISTS` 可用性探测 |
| `application` | 25 | 88 条 inline native（动态 `StringBuilder`）、`generate_series` 序号、`RETURNING id`、`IN` 动态列表 |
| `deviceaccess` | 5 | 设备接入 22 + 指令 15 条 native：行锁抢占、jsonb 参数、幂等结果回报 |
| `system` | 25 | 导出/自检/网络变更：`SKIP LOCKED` 认领、`EXTRACT(EPOCH)` 超时、`UPDATE ... FROM ... RETURNING` |
| `identity` | 12 | 几乎全 JPQL + `pg_advisory_xact_lock` |
| `organization` | 9 | 5 JPQL + 4 条跨表 native 总览聚合（`NULLS LAST`、双层 `CAST`） |
| `initialization` | 13 | JPQL 乐观锁状态机 + advisory lock |
| `audit` / `approval` / `devicetest` | 8 | 批量清理（`LIMIT` 子查询）、`PESSIMISTIC_WRITE`、`TEXT` 结果列 |

### 按 PostgreSQL 特性速查

| 特性 | 代表方法 |
|---|---|
| `FOR UPDATE SKIP LOCKED` | `NotificationDeliveryJpaRepository.claimDue`、`SecureExportJpaRepository.claim`、`BindingSyncRepository.claimDue`、`DeviceCommandRepository.pollCommands`、`NetworkChangeJpaRepository.find*ForRollback` |
| `ON CONFLICT DO UPDATE` | `DeviceServiceStatusRepository.upsert`、`DeviceMetricSnapshotRepository.upsertSnapshot`、`StatisticsJpaRepository.rebuild*` |
| `RETURNING` | `claimDue`、`insertPlatform`、`incrementBindingVersion`、`telemetryInsertReturningId` |
| `LATERAL` + `jsonb_array_elements` | `StatisticsJpaRepository.rebuildDeviceMinutes` / `rebuildApplicationMinutes` |
| `generate_series` | `applicationOnboardingTrend`、`nextOrgSequence` |
| `pg_advisory_xact_lock` | `InitializationFlowRepository`、`UserAccountRepository` |
| 分批 `DELETE ... LIMIT` | `deleteCompletedBefore`、`deleteResolvedBefore`、`purgeTelemetry`、`purgeAuditedBefore` |
| `NULLS LAST` / `ORDER BY CASE` | `OrganizationOverviewRepository.listApplications`、`AlertRuleJpaRepository.findEffective` |
| 部分唯一索引 | `uk_ucsp_application_device`、`uk_platform_user_username_casefold` 等 49 条 |

---

## 9. 相关文件

- DDL 全量导出：`docs/sql/pqc-ucsp-complete-ddl.sql`、`pqc-ucsp-seed-data.sql`、`pqc-ucsp-drop-all-tables.sql`
- Liquibase 主文件：`pqc-ucsp-data/src/main/resources/db/changelog/db.changelog-master.xml`
- 数据源配置：`pqc-ucsp-http/src/main/resources/application-prod.yml`
- 集成测试：`pqc-ucsp-http/src/test/.../Postgresql*IntegrationTest.java`（`UCSP_TEST_DB_URL` 环境变量门控）
