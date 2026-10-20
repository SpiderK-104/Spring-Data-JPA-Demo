package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Version 乐观锁并发测试。
 *
 * 背景：User 有 @Version Long version 字段，tb_user 也有 version 列。
 *
 * 要点：
 * - 数据库层面：每次 UPDATE 变成 UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?
 * - 匹配不到行（版本已被别人改过）时，Hibernate 抛 OptimisticLockException。
 * - 版本号由 Hibernate 自动维护：insert 后为 0，每次成功 update +1，业务代码不要手动赋值。
 *
 * 为什么关掉测试事务（NOT_SUPPORTED）：
 * 要模拟两个会话各自提交、跨事务观察版本变化，必须真正提交；测试自带事务会整体回滚，
 * 看不到跨会话效果。所以这里用 TransactionTemplate 手工控制边界，并在 @AfterEach 清理数据。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("@Version 乐观锁并发测试")
class OptimisticLockingTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private String username;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        username = "ver_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        // 关掉了测试事务，数据不会自动回滚，手工清理
        tx.execute(status -> {
            em.createNativeQuery("delete from tb_user where username = :u")
                    .setParameter("u", username)
                    .executeUpdate();
            return null;
        });
    }

    private Long createUser() {
        return tx.execute(status -> userRepository
                .save(User.of(username, "并发用户", username + "@example.com", 30, 1))
                .getId());
    }

    /**
     * 场景：版本号自动维护。
     *
     * 注意事项：
     * - 插入后 version = 0，由 Hibernate 初始化，不需要赋初值。
     * - 每次成功提交的更新，version 自动 +1。
     * - 业务代码不要 setVersion，否则会破坏版本链。
     */
    @Test
    @DisplayName("版本号：插入为 0，每次更新自动 +1")
    void versionStartsAtZeroAndIncrements() {
        Long id = createUser();

        Long v0 = tx.execute(status -> em.find(User.class, id).getVersion());
        assertThat(v0).isEqualTo(0L);

        tx.execute(status -> {
            User u = em.find(User.class, id);
            u.setNickname("第一次修改");
            return null;
        });

        Long v1 = tx.execute(status -> em.find(User.class, id).getVersion());
        assertThat(v1).isEqualTo(1L);
    }

    /**
     * 场景：两个会话并发修改同一条记录（经典的丢失更新场景）。
     *
     * 注意事项：
     * - 会话A 读到 version=0 后提交，得到一个游离态快照。
     * - 会话B 先修改并提交，数据库 version 变成 1。
     * - 会话A 再拿旧快照（version=0）保存：UPDATE ... WHERE id=? AND version=0 匹配 0 行，
     *   Hibernate 抛 OptimisticLockException（Spring 在事务/仓储边界通常翻译成
     *   ObjectOptimisticLockingFailureException）。
     * - 关键：@Version 让“后提交的旧数据”失败，而不是默默覆盖别人。
     */
    @Test
    @DisplayName("并发冲突：旧版本更新会抛 OptimisticLockException")
    void staleUpdateThrowsOptimisticLock() {
        Long id = createUser();

        User stale = tx.execute(status -> em.find(User.class, id)); // 会话A 的快照，version=0
        assertThat(stale.getVersion()).isEqualTo(0L);

        // 会话B 先改并提交，version -> 1
        tx.execute(status -> {
            User u = em.find(User.class, id);
            u.setNickname("会话B的修改");
            return null;
        });

        // 会话A 拿旧版本再改
        stale.setNickname("会话A的修改");
        assertThatThrownBy(() -> tx.execute(status -> {
            em.merge(stale);
            return null;
        })).isInstanceOf(OptimisticLockException.class);
    }

    /**
     * 场景：批量 JPQL / 原生 SQL 更新绕过 @Version。
     *
     * 注意事项：
     * - @Modifying 的 update/delete 或原生 SQL 直接改库，不经过持久化上下文，
     *   不会触发版本自增，也不做版本校验。
     * - 结果：version 停在原值，乐观锁对这行“形同虚设”，之后再按旧版本更新也不会冲突。
     * - 所以批量更新要么手动写 version = version + 1，要么别和乐观锁混用。
     */
    @Test
    @DisplayName("坑：批量/原生更新不递增版本，乐观锁失效")
    void bulkUpdateBypassesVersion() {
        Long id = createUser();

        tx.execute(status -> {
            em.createNativeQuery("update tb_user set nickname = :n where id = :id")
                    .setParameter("n", "批量改的")
                    .setParameter("id", id)
                    .executeUpdate();
            return null;
        });

        Long versionAfterBulk = tx.execute(status -> em.find(User.class, id).getVersion());
        assertThat(versionAfterBulk).isEqualTo(0L); // 没有递增
        String nicknameAfterBulk = tx.execute(status -> em.find(User.class, id).getNickname());
        assertThat(nicknameAfterBulk).isEqualTo("批量改的");
    }

    /**
     * 场景：冲突后的正确恢复方式——重新读取最新版本再改。
     *
     * 注意事项：
     * - 发生乐观锁冲突后，旧快照已作废，不能直接拿它重试。
     * - 必须重新查询拿到最新 version，再基于新数据修改提交。
     * - 生产里通常把“读-改-提交”包成一个可重试的操作（有限次数）。
     */
    @Test
    @DisplayName("冲突处理：重新读取最新版本后可重试成功")
    void retryWithFreshVersionSucceeds() {
        Long id = createUser();
        // 先制造一次版本前进
        tx.execute(status -> {
            User u = em.find(User.class, id);
            u.setNickname("会话B的修改");
            return null;
        });

        // 重试：重新读取最新数据后修改
        tx.execute(status -> {
            User fresh = em.find(User.class, id);
            fresh.setNickname("重试成功");
            return null;
        });

        String nickname = tx.execute(status -> em.find(User.class, id).getNickname());
        assertThat(nickname).isEqualTo("重试成功");
    }
}
