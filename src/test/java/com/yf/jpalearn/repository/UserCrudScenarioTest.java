package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 综合增删改查场景测试。
 * <p>
 * 参照 doc/pgsql-usage.md 的实战场景做"简化但不改核心"的落地：
 * 派生 CRUD + 分页、ON CONFLICT 幂等 Upsert、INSERT ... RETURNING、
 * FOR UPDATE SKIP LOCKED 抢任务、分批 DELETE ... LIMIT、CAS 乐观锁、悲观锁读。
 * <p>
 * {@code @DataJpaTest} 默认每个方法跑在一个会回滚的事务里，因此不会污染 init.sql 的数据。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserNativeRepository.class)
@DisplayName("综合 CRUD 场景（参照 pgsql-usage.md）")
class UserCrudScenarioTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserNativeRepository userNativeRepository;

    @Autowired
    private EntityManager em;

    // ---------------------------------------------------------------- Create

    @Test
    @DisplayName("C-1 新增：Repository.save 走 persist，主键回填")
    void create_byRepositorySave() {
        User user = User.of("zhangsan", "张三", "zhangsan@example.com", 18, 1);

        User saved = userRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L); // @Version 初始为 0
        assertThat(userRepository.findByUsername("zhangsan")).isPresent();
    }

    @Test
    @DisplayName("C-2 新增：原生 INSERT ... RETURNING id 一次拿到自增主键")
    void create_byNativeReturningId() {
        Long id = userNativeRepository.insertReturningId("trinity", "崔尼蒂", 25, 1);

        assertThat(id).isNotNull();
        em.clear(); // 清一级缓存，确保真的从库里读
        assertThat(userRepository.findById(id)).isPresent();
    }

    // ----------------------------------------------------------------- Read

    @Test
    @DisplayName("R-1 读取：findById / findByUsername / exists / count")
    void read_basic() {
        Optional<User> alice = userRepository.findByUsername("alice");

        assertThat(alice).isPresent();
        assertThat(alice.get().getNickname()).isEqualTo("爱丽丝");
        assertThat(userRepository.findById(alice.get().getId())).isPresent();
        assertThat(userRepository.existsByUsername("alice")).isTrue();
        assertThat(userRepository.countByStatus(0)).isEqualTo(2); // charlie / frank
    }

    @Test
    @DisplayName("R-2 分页 + 排序：Page 带总数，Slice 只看有没有下一页")
    void read_pagingAndSort() {
        Pageable pageable = PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "age"));
        Page<User> page = userRepository.findAll(pageable);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(10);
        // age 降序：frank(35) 应排在首位
        assertThat(page.getContent().get(0).getUsername()).isEqualTo("frank");

        Slice<User> slice = userRepository.findByStatusOrderByIdAsc(1, PageRequest.of(0, 4));
        assertThat(slice.getContent()).hasSize(4);
        assertThat(slice.hasNext()).isTrue();
    }

    // --------------------------------------------------------------- Update

    @Test
    @DisplayName("U-1 更新：修改托管态实体，事务提交时靠脏检查自动 UPDATE")
    void update_dirtyChecking() {
        User alice = userRepository.findByUsername("alice").orElseThrow();

        alice.setNickname("爱丽丝（脏检查改名）");

        em.flush();
        em.clear();

        assertThat(userRepository.findByUsername("alice").orElseThrow().getNickname())
                .isEqualTo("爱丽丝（脏检查改名）");
    }

    @Test
    @DisplayName("U-2 更新：ON CONFLICT 幂等 Upsert，重复写入不产生第二行")
    void update_upsertOnConflict() {
        userNativeRepository.upsert("neo", "尼奥", 26, 1);
        userNativeRepository.upsert("neo", "尼奥2", 27, 0); // 冲突 -> DO UPDATE

        em.clear();
        List<User> found = em.createQuery("select u from User u where u.username = 'neo'", User.class)
                .getResultList();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getNickname()).isEqualTo("尼奥2");
        assertThat(found.get(0).getAge()).isEqualTo(27);
        assertThat(found.get(0).getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("U-3 更新：CAS 乐观锁，版本不匹配则更新 0 行")
    void update_optimisticCas() {
        User alice = userRepository.findByUsername("alice").orElseThrow();
        long version = alice.getVersion();

        int affected = userNativeRepository.casRename("alice", "alice_cas", version);
        assertThat(affected).isEqualTo(1);

        // 用旧版本号再试：一级缓存里的 alice 版本没变，但库里已 +1 -> 命中 0 行
        int stale = userNativeRepository.casRename("alice", "alice_cas2", version);
        assertThat(stale).isZero();

        em.clear();
        User reloaded = userRepository.findByUsername("alice").orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("alice_cas");
        assertThat(reloaded.getVersion()).isEqualTo(version + 1);
    }

    // --------------------------------------------------------------- Delete

    @Test
    @DisplayName("D-1 删除：deleteById 后查不到")
    void delete_byId() {
        User bob = userRepository.findByUsername("bob").orElseThrow();

        userRepository.deleteById(bob.getId());
        userRepository.flush();
        em.clear();

        assertThat(userRepository.findById(bob.getId())).isEmpty();
    }

    @Test
    @DisplayName("D-2 删除：原生分批 DELETE ... LIMIT，一次只删一批")
    void delete_batchWithLimit() {
        long before = userRepository.count();

        int deleted = userNativeRepository.deleteDisabledBatch(1); // status=0 有 2 行，只删 1 行

        assertThat(deleted).isEqualTo(1);
        em.clear();
        assertThat(userRepository.count()).isEqualTo(before - 1);
    }

    // -------------------------------------------------- 并发/锁相关（简化）

    @Test
    @DisplayName("L-1 抢任务：FOR UPDATE SKIP LOCKED 捞出并标记为处理中")
    void claim_withSkipLocked() {
        List<User> claimed = userNativeRepository.claimPending(3);

        assertThat(claimed).hasSize(3);
        assertThat(claimed).allMatch(u -> u.getStatus() == 2);

        em.clear();
        Long processing = em.createQuery("select count(u) from User u where u.status = 2", Long.class)
                .getSingleResult();
        assertThat(processing).isEqualTo(3L);
    }

    @Test
    @DisplayName("L-2 悲观锁：SELECT ... FOR UPDATE 读到并锁定目标行")
    void lock_forUpdate() {
        Optional<User> alice = userNativeRepository.findByUsernameForUpdate("alice");

        assertThat(alice).isPresent();
        assertThat(alice.get().getUsername()).isEqualTo("alice");
    }
}
