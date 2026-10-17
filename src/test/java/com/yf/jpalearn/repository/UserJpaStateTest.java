package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("JPA 四种状态（Transient/Persistent/Detached/Removed）实战")
class UserJpaStateTest {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("Transient -> Persistent：em.persist()")
    @Transactional
    void testTransientToPersistent() {
        // 1. Transient（瞬时态）：new 出来，未与 EM 关联
        User user = User.of("neo", "尼奥", "neo@example.com", 26, 1);
        assertThat(user.getId()).isNull();

        // 2. persist：将实体纳入 Persistence Context，变为 Persistent（托管态）
        em.persist(user);

        // 此时实体受 EM 管理
        assertThat(user.getId()).isNotNull();

        // 3. flush 强制同步到 DB（观察 INSERT）
        em.flush();
    }

    @Test
    @DisplayName("Persistent 的特性：同一 ID 在同一事务返回同一对象（1级缓存）")
    @Transactional
    void testFirstLevelCache() {
        User user = User.of("trinity", "崔尼蒂", "trinity@example.com", 25, 1);
        em.persist(user);
        Long id = user.getId();

        // 从同一个 Persistence Context 查找同一主键
        User found = em.find(User.class, id);
        // isSameAs：同一个 Java 引用 -> 直接从 1级缓存取
        assertThat(found).isSameAs(user);
    }

    @Test
    @DisplayName("Persistent -> Detached：em.detach()")
    @Transactional
    void testPersistentToDetached() {
        User user = User.of("morpheus", "墨菲斯", "morpheus@example.com", 40, 1);
        em.persist(user);
        em.flush();

        // detach：将指定实体从 Persistence Context 移除 -> Detached（游离态）
        em.detach(user);

        // 此时修改 user 的字段，不再触发 Dirty Checking
        user.setNickname("墨菲斯_游离态修改");

        // flush：不会为 detached 实体生成 UPDATE
        em.flush();
    }

    @Test
    @DisplayName("Detached -> Persistent：em.merge()（返回新的 Managed 实体）")
    @Transactional
    void testDetachedToPersistentByMerge() {
        User user = User.of("smith", "史密斯", "smith@example.com", 35, 1);
        em.persist(user);
        em.flush();
        Long id = user.getId();

        // 先 detach
        em.detach(user);

        // 修改 Detached 对象
        user.setNickname("史密斯（被合并）");
        user.setAge(36);

        // merge：返回新的 Managed 实体
        User merged = em.merge(user);

        // 注意：merged != user
        assertThat(merged).isNotSameAs(user);
        assertThat(merged.getId()).isEqualTo(id);

        // flush 时会根据 merged 的属性生成 UPDATE
        em.flush();
    }

    @Test
    @DisplayName("清空上下文：em.clear() 会让所有 Persistent 实体变成 Detached")
    @Transactional
    void testClearMakesAllDetached() {
        User u1 = User.of("tank", "坦克", "tank@example.com", 30, 1);
        User u2 = User.of("dozer", "推土机", "dozer@example.com", 32, 1);
        em.persist(u1);
        em.persist(u2);
        em.flush();

        // clear：清空整个 Persistence Context
        em.clear();

        // 此时 u1、u2 都是 Detached
        u1.setNickname("tank_detached");
        u2.setNickname("dozer_detached");
        em.flush();
    }

    @Test
    @DisplayName("Transient 对象直接 merge：若无对应 ID，会创建新的 Persistent 实体")
    @Transactional
    void testMergeTransient() {
        // 一个全新的 Transient 对象（无 id）
        User transientUser = User.of("oracle", "先知", "oracle@example.com", 999, 1);
        assertThat(transientUser.getId()).isNull();

        // merge 一个 Transient 对象：会复制属性到新建的 Managed 实体并返回
        User managed = em.merge(transientUser);

        assertThat(managed).isNotSameAs(transientUser);
        assertThat(managed.getId()).isNotNull(); // 新增

        em.flush(); // 观察 INSERT
    }

    @Test
    @DisplayName("Persistent -> Removed：em.remove() 标记待删除，flush/提交时执行 DELETE")
    @Transactional
    void testPersistentToRemoved() {
        User user = User.of("cypher", "塞弗", "cypher@example.com", 28, 1);
        em.persist(user);
        em.flush();
        Long id = user.getId();

        // remove：标记为 Removed
        em.remove(user);

        // 真正删除发生在 flush 或事务 commit
        em.flush(); // 此时发出 DELETE

        // clear 后再查：应为空
        em.clear();
        User gone = em.find(User.class, id);
        assertThat(gone).isNull();
    }
}
