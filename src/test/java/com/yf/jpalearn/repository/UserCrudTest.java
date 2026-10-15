package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("JPA 基础增删改查测试")
class UserCrudTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("新增：new 实体 -> save() 后变为 Persistent")
    void testSave() {
        // Transient：刚 new 出来，还未与 Persistence Context 关联
        User user = User.of("test_user", "测试用户", "test@example.com", 18, 1);
        assertThat(user.getId()).isNull(); // 刚创建时 id 为 null

        // save()：如果 id 为 null（isNew 判断）通常走 persist
        User saved = userRepository.save(user);

        // 保存后：id 被赋值，实体已进入 Persistent（托管）状态
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUsername()).isEqualTo("test_user");
    }

    @Test
    @DisplayName("查询：findById 返回的实体在事务内是 Persistent")
    @Transactional
    void testFind() {
        // 从 init.sql 已有数据中查找 alice
        Optional<User> opt = userRepository.findByUsername("alice");
        assertThat(opt).isPresent();

        User alice = opt.get();
        // 事务内：由 EntityManager 管理 -> Persistent
        // 在同一事务内，再次 findById 返回同一对象（1级缓存）
        User same = userRepository.findById(alice.getId()).orElseThrow();
        assertThat(same).isSameAs(alice); // 同一个 Java 实例，印证 1级缓存
    }

    @Test
    @DisplayName("更新：在事务内修改 Persistent 实体，事务提交时自动 flush（Dirty Checking）")
    @Transactional
    void testUpdateByDirtyChecking() {
        User alice = userRepository.findByUsername("alice").orElseThrow();
        String oldNickname = alice.getNickname();

        // 直接修改托管态（Persistent）字段
        alice.setNickname("爱丽丝_改名");

        // 无需调用 save()：事务提交前，Persistence Context 会检测字段变化 -> 自动生成 UPDATE
        em.flush();
        em.clear();

        // 重新加载验证
        User reloaded = userRepository.findByUsername("alice").orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("爱丽丝_改名");
        assertThat(reloaded.getNickname()).isNotEqualTo(oldNickname);
    }

    @Test
    @DisplayName("删除：delete 后标记为 Removed，事务提交时执行 DELETE")
    @Transactional
    void testDelete() {
        long before = userRepository.count();
        User bob = userRepository.findByUsername("bob").orElseThrow();

        // 删除托管实体 -> 进入 Removed 状态
        userRepository.delete(bob);

        // flush 后验证
        em.flush();

        long after = userRepository.count();
        assertThat(after).isEqualTo(before - 1);
        assertThat(userRepository.findByUsername("bob")).isEmpty();
    }

    @Test
    @DisplayName("save 对 Detached 实体的处理：通常走 merge，返回新的 Managed 实体")
    @Transactional
    void testSaveDetached() {
        User alice = userRepository.findByUsername("alice").orElseThrow();
        // 事务内：alice 是 Persistent

        // 脱离当前 Persistence Context
        em.detach(alice);
        // 此时 alice 变成 Detached（游离态）

        // 修改 Detached 对象的字段
        alice.setNickname("alice_detached_edited");

        // save() 遇到有 ID 的实体且当前 PC 中不存在时，会执行 merge
        // merge 会复制 Detached 对象的属性到一个新的 Persistent 实体，并返回该新实体
        User merged = userRepository.save(alice);

        // merged 与 alice 不是同一个实例
        assertThat(merged).isNotSameAs(alice);

        em.flush();
        em.clear();

        User reloaded = userRepository.findByUsername("alice").orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("alice_detached_edited");
    }

    @Test
    @DisplayName("自定义 JPQL 更新：updateStatus 需配合 @Modifying + @Transactional")
    @Transactional
    void testCustomUpdate() {
        User dave = userRepository.findByUsername("dave").orElseThrow();
        assertThat(dave.getStatus()).isEqualTo(1);

        int affected = userRepository.updateStatus(dave.getId(), 0);
        assertThat(affected).isEqualTo(1);

        em.flush();
        em.clear();

        User reloaded = userRepository.findById(dave.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(0);
    }
}
