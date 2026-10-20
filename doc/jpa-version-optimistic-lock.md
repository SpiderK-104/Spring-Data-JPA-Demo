# @Version 乐观锁说明

## 一句话

`@Version` 是 JPA 的**乐观锁**：给实体加一个版本号字段，更新时把版本号作为条件，谁先用旧版本提交谁失败，从而避免“后写覆盖先写”的丢失更新。

## 为什么需要它

两个用户同时打开同一条记录：A 读到 age=20，B 也读到 age=20。A 改成 21 提交，B 改成 22 提交。
没有乐观锁时，B 会把 A 的修改**悄悄覆盖**（丢失更新）。
加上 `@Version` 后，B 提交时发现版本已变，直接被拒绝，由业务决定重试还是提示。

## 原理

实体加字段：

```java
@Version
@Column(name = "version")
private Long version;
```

Hibernate 每次 `UPDATE` 会自动变成：

```sql
UPDATE tb_user
SET nickname = ?, version = version + 1
WHERE id = ? AND version = ?
```

- `WHERE` 里的 version 是**本次读取时的旧版本**。
- 影响行数为 0（说明别人已经改过）→ 抛 `jakarta.persistence.OptimisticLockException`。
- 影响行数为 1 → 正常提交，版本 +1。

## 自动维护规则

- 插入（INSERT）后版本初始化为 **0**，不需要你赋值。
- 每次成功 `UPDATE` 自动 **+1**，也不需要你赋值。
- **不要**在业务代码里 `setVersion(...)`，会破坏版本链。

## 支持的类型

`int` / `Integer` / `long` / `Long` / `short` / `Short` / `java.sql.Timestamp`。
最常用 `Long`（本项目即如此）。

## 什么时候抛异常

不是“一改就抛”，而是**在 flush / commit 时**校验：

- 同一事务内普通修改、flush 正常。
- 冲突发生在真正执行 `UPDATE ... WHERE version = ?` 且影响 0 行时。
- 因此：只有把改动**提交**出去，版本冲突才可能暴露。

抛出的异常：

- JPA 原生：`jakarta.persistence.OptimisticLockException`
- Spring 在事务/仓储边界通常会翻译成：`org.springframework.orm.ObjectOptimisticLockingFailureException`

## 冲突了怎么处理

1. **重试**：重新读取最新数据，再改一次（适合自动化、可重入的场景）。
2. **提示用户**：告诉用户“数据已被他人修改，请刷新后重试”。
3. **业务合并**：按字段粒度合并冲突（复杂，慎用）。

注意：乐观锁只保证“提交时版本一致”，不负责加锁排队，所以高冲突场景可能频繁失败，必要时改用悲观锁。

## 常见坑

- **批量 JPQL / 原生 SQL 不递增版本**：`@Modifying` 的 `update/delete` 或 `nativeQuery` 直接改库，绕过持久化上下文，不会检查也不会自增 version。乐观锁对这行“形同虚设”。要么手动写 `version = version + 1`，要么别混用。
- **手动 setVersion**：会伪造版本，导致校验错乱。
- **以为 save/merge 一定抛**：要拿到“旧版本快照”再保存才会冲突；`merge` 会先读当前行，行为依赖具体场景，测试要以实际抛出为准。
- **版本字段当业务字段用**：它只服务于并发控制，不要拿来做“修订号”之类业务语义。
- **集合关系**：普通 `@OneToMany/@ManyToMany` 集合的增删是否参与版本，取决于集合是否被版本控制；不要默认“改集合一定 +1”。

## 与悲观锁对比

| | 乐观锁 @Version | 悲观锁 SELECT ... FOR UPDATE |
|---|---|---|
| 加锁时机 | 提交时校验 | 读取时加锁 |
| 并发度 | 高（不阻塞读） | 低（会阻塞） |
| 冲突表现 | 抛 OptimisticLockException | 等待锁 / 超时 |
| 适用 | 冲突少、读多写少 | 冲突多、强一致 |

## 本项目示例

- `entity/User.java` 上的 `@Version Long version`，对应 `tb_user.version`。
- `repository/OptimisticLockingTest.java`：
  - 版本号插入为 0、更新 +1；
  - 两个会话并发修改，旧版本提交抛 `OptimisticLockException`；
  - 批量/原生更新不递增版本（乐观锁失效的坑）；
  - 冲突后重新读取最新版本可重试成功。
