# 1 Spring JPA 懒加载与事务边界笔记

> 面向小白，从一个真实报错讲起：为什么 Hibernate 会说 "no Session"，
> 这类问题怎么发生的、怎么解决、怎么避免。

---

## 1.1 故事的开始：一条真实的报错日志

某天在控制台看到这么一条日志：

```
org.hibernate.LazyInitializationException:
failed to lazily initialize a collection of role:
cn.pqctech.ucsp.data.monitoring.entity.NotificationChannelEntity.applications,
could not initialize proxy - no Session

  at org.hibernate.collection.internal.AbstractPersistentCollection.throwLazyInitializationException(...)
  at org.hibernate.collection.internal.AbstractPersistentCollection...initialize(...)
  at org.hibernate.collection.internal.AbstractPersistentCollection.read(...)
  at org.hibernate.collection.internal.PersistentSet.iterator(PersistentSet.java:188)
  at cn.pqctech.ucsp.service.monitoring.NotificationMapper.channel(NotificationMapper.java:37)
```

看到这句 **`could not initialize proxy - no Session`** 就该明白：
我们想用数据（访问某个字段），但数据库会话（Session）已经不在了。

这是 Spring + JPA 项目里最容易踩的坑之一。下面拆开讲。

---

## 1.2 前置概念：三个名词先混个脸熟

### 1.2.1 实体（Entity）

JPA 把数据库表映射成 Java 对象。比如：

```java
@Table(name = "notification_channels")
public class NotificationChannelEntity {
    private String id;
    private String name;
    // 一个渠道关联了哪些应用（多对多）
    @ManyToMany(fetch = FetchType.LAZY)
    private Set<PlatformAppEntity> applications;
}
```

数据库里"渠道"和"应用"是两张表，通过中间表关联。

### 1.2.2 会话 / 持久化上下文（Session / Persistence Context）

可以理解成"JPA 和数据库打交道的工作台"。

- 你要查数据，得先有这个工作台；
- 实体对象必须挂靠在某个工作台上才"活着"；
- 工作台没了，实体就变成"游离态"（Detached），再想偷偷补查数据就不行了。

### 1.2.3 懒加载（Lazy Loading）

`fetch = FetchType.LAZY` 的含义：

> 我 `findById()` 查渠道时，**先不查**它关联的应用列表。
> 等你真的去遍历 `applications` 的那一刻，我再补发一条 SQL 去数据库查。

这是 Hibernate 的"省事"设计：减少无谓查询。

---

## 1.3 关键机制：事务（Transaction）决定会话的生死

Spring 里默认的约定：

```
一个事务  =  一个持久化上下文（Session）

进入 @Transactional 方法  →  Session 打开
方法正常返回 / 抛异常     →  Session 关闭（提交 / 回滚）
```

所以：

- **在事务里**：实体有靠山，懒加载能"随时补查"。
- **出了事务**：Session 关了，实体变成游离态。再想补查 → 报错。

那个报错翻译成人话就是：

> 我的 Session（工作台）都关了，你还要我再去查一次数据库？抱歉，查不了。

---

## 1.4 复盘：这次事故是怎么一步步发生的

回到报错的代码链路：

```
Controller 收到请求
  → ManualNotificationPushService.push(...)        ← 普通 Service 方法，没有 @Transactional
     → notificationRepository.findEligibleGlobal() ← 原生 SQL 查询，拿到实体列表
     → NotificationMapper.channels(...)            ← 把实体转成 DTO
        → channel(entity)                           ← 循环遍历
           → entity.getApplications()               ← ⚡ 撞雷了
```

### 1.4.1 第 1 步：绕过了事务

`push()` 是个普通 `@Service` 方法，Controller 直接调用，**没有 `@Transactional`**。
于是整个链路里没有任何事务边界 → 数据库的 Session 只存在于"单次查询"的生命周期里。

### 1.4.2 第 2 步：原生查询返回的是"半成品"实体

`findEligibleGlobal()` 等类似：

```java
@Query(value = "SELECT * FROM notification_channels WHERE ...", nativeQuery = true)
List<NotificationChannelEntity> findEligibleGlobal();
```

查询结束、方法返回的那一瞬间，Session 就关闭了。
而实体的 `applications` 字段还没有加载，只是一个"懒加载代理占位符"。

### 1.4.3 第 3 步：在会话之外试图触发加载

`NotificationMapper.channel()` 里这样写：

```java
for (PlatformAppEntity application : entity.getApplications()) {  // 第 37 行
    applicationIds.add(application.getId());
}
```

这一行 `for` 循环会触发 Hibernate 去"补查 applications"，
但 Session 早就关了 → 抛 `LazyInitializationException`。

> 一句话：**没开事务，查完就关会话，却在关掉后再去碰懒加载的字段。**

---

## 1.5 为什么其他代码没报错？（对比一下）

项目中同样有地方调 `NotificationMapper.channels(...)`，
但比如这两个就安全：

```java
// 1) 查询用了 @EntityGraph，把 applications 一起查出来了
@EntityGraph(attributePaths = "applications")
List<NotificationChannelEntity> findAllDetailed();

// 2) 仓库方法带 @Transactional(readOnly = true)，
//    查询 + 集合初始化都在同一个事务内完成
@Transactional(readOnly = true)
List<NotificationChannelEntity> listChannels() {
    return notificationRepository.findAllDetailed();
}
```

因为 `applications` 在事务内就已经被真正加载了（不再是代理），
之后实体脱离事务也能正常读——数据已经在内存里了。

对比就能看出本次事故的两个必要条件：

1. **查询发生在事务外**（没有 `@Transactional`）；
2. **DTO 转换强制触发懒加载**（转码时访问了 `applications`）。

---

## 1.6 解决方案有哪些？（按推荐顺序）

### 1.6.1 方案 A：没必要转全量 DTO，就别碰懒加载字段（本项目采用）

手工推送入箱只需要渠道的 `id`、`minimumSeverity`、`channelType` 三个标量字段，
根本不需要 `applications` 这个集合。

于是直接对实体操作、只取标量：

```java
private int enqueueDingTalk(List<NotificationChannelEntity> channels, ...) {
    for (NotificationChannelEntity channel : channels) {
        if (!severity.atLeast(AlertSeverity.valueOf(channel.getMinimumSeverity()))) {
            continue;
        }
        repository.enqueue(..., channel.getId(), channel.getChannelType(), ...);
    }
    return queued;
}
```

- 完全不触发懒加载 → 零报错。
- 不额外查关联表 → 性能更好。

> 原则：**实体只在事务层内部流转，出了事务就转成 DTO 或只取标量。**

### 1.6.2 方案 B：如果确实需要关联数据，用 @EntityGraph 在事务内预加载

```java
@Repository
public interface NotificationChannelJpaRepository extends JpaRepository<...> {
    @EntityGraph(attributePaths = "applications")
    @Query(value = "SELECT * FROM notification_channels WHERE ...", nativeQuery = true)
    List<NotificationChannelEntity> findEligibleGlobal();
}
```

> 原生 SQL 的 @EntityGraph 配置比较特殊，更常见的做法是改用 JPQL 派生查询，
> Spring Data JPA 的派生方法可以直接挂在 @EntityGraph 上。

### 1.6.3 方案 C：给调用方法加事务边界

```java
@Transactional
public ManualNotificationPushResultDTO push(...) {
    ...
}
```

把整个"查询 + 转 DTO"放进同一个事务，Session 存活期间完成所有访问。

> 注意：事务最好是"读事务"（`readOnly = true`）更规范，
> 而且不能为了这个问题无限拉长事务（占用连接、阻塞风险）。

### 1.6.4 方案 D：不在事务外做 DTO 映射，丢给仓库层

把"查询 + 转 DTO"封装到 `@Transactional(readOnly = true)` 的仓库方法里，
Service 拿到的是现成 DTO，事务外不碰实体。

---

## 1.7 这几个方案里包含的通用原则

1. **数据别在事务外裸奔**：实体是事务的"私有物"，跨了事务最好只传值（DTO/标量）。
2. **要关联数据，就在事务内提前一次性加载**（@EntityGraph / join fetch）。
3. **DTO 转换尽量在事务内完成**，别把转换器留到 Controller/Service 层做。
4. **能不用懒加载字段就别用**：功能不需要的数据，查出来也是浪费。

---

## 1.8 自查清单（写代码时过一遍）

- [ ] 这个方法有事务边界吗？（`@Transactional` 或外层调用者已包事务）
- [ ] 方法返回实体后，还会在外层访问它的集合字段吗？
- [ ] 原生查询返回的实体，后面会转成 DTO 并遍历关联集合吗？
- [ ] 如果确实需要关联数据，有没有用 `@EntityGraph` / `join fetch` 预加载？

只要日常遵守"**事务内加载，事务外只读/只传标量**"，这个坑基本不会再踩。

---

## 1.9 一句话总结

> JPA 的懒加载是"用到才查"，但"查"必须有活着的 Session；
> 事务就是 Session 的生命线。
> 事务结束还去碰懒加载字段 → `LazyInitializationException`（no Session）。
> 解法就两条路：**让访问发生在事务内**，或者**干脆不访问那个字段**。