package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("JPA 基础增删改查测试")
@Slf4j
//@TestInstance(TestInstance.Lifecycle.PER_CLASS) // 关键：测试实例在类生命周期只创建一次
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserCrudTest {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;


    @Test
    @DisplayName("CrudRepository save()方法")
    // @Commit：提交事务，将数据库操作生效.@SpringbootTest 每个@Test方法都运行在一个事务里，
    // 测试结束后，会自动回滚（rollback)，所以要在下面添加 @Commit 提交事务
    @Commit
    void testInsert() {
        User user = new User();
        user.setUsername("test_user");
        user.setNickname("测试用户");
        user.setEmail("test@example.com");
        user.setAge(18);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("User saved: {}", saved);
    }

    @Test
//    @Commit
    @DisplayName("CrudRepository SaveAll方法")
    void testSaveAll() {
        User user1 = new User();
        user1.setUsername("test_user1");
        user1.setNickname("测试用户1");
        user1.setEmail("test@example.com1");
        user1.setAge(18);
        user1.setStatus(1);
        user1.setCreatedAt(LocalDateTime.now());
        user1.setUpdatedAt(LocalDateTime.now());
        User user2 = new User();
        user2.setUsername("test_user2");
        user2.setNickname("测试用户2");
        user2.setEmail("test@example.com2");
        user2.setAge(19);
        user2.setStatus(1);
        user2.setCreatedAt(LocalDateTime.now());
        user2.setUpdatedAt(LocalDateTime.now());
        List<User> userList = new ArrayList<>();
        userList.add(user1);
        userList.add(user2);
        userRepository.saveAll(userList);

        log.info("用户列表已保存: {}", userList);

    }

    @Test
    @DisplayName("CrudRepository findById()方法")
    void find() {
        User user = userRepository.findById(22L).orElseThrow(() -> new IllegalArgumentException("用户ID为22的用户不存在。"));
        log.info("User found: {}", user);
    }


    @Test
    @DisplayName("existsById 测试")
    void existsById() {
        boolean exists = userRepository.existsById(22L);
        System.out.println("exists: " + exists);

    }

    //无条件删除所有用户数据
    @Test
    @Commit
    @DisplayName("deleteAll 测试")
    void deleteAll() {
        userRepository.deleteAll();
        log.info("所有用户已删除");

    }

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
        if (opt.isEmpty()) {
            log.error("alice not found");
            throw new NoSuchElementException("alice not found");
        }
        // 输出获得的用户
        //  User user = opt.get();
        //  System.out.println(user);

        assertThat(opt).isPresent();

        User alice = opt.get();
        // 事务内：由 EntityManager 管理 -> Persistent
        // 在同一事务内，再次 findById 返回同一对象（1级缓存）
        User same = userRepository.findById(alice.getId()).orElseThrow(() -> new IllegalArgumentException("alice not found"));
        assertThat(same).isSameAs(alice); // 同一个 Java 实例，印证 1级缓存
    }


    @Test
    @DisplayName("findAllByIds测试")
    void findAllById() {
        List<User> users = userRepository.findAllById(List.of(1L, 2L));
//        System.out.println("users: " + users);
        log.info("users: {}", users);

        long userCount = userRepository.count();
        log.info("无条件查询记录总数: {}", userCount);

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
    @DisplayName("派生查询：existsByUsername / countByStatus")
    void testExistsAndCount() {
        assertThat(userRepository.existsByUsername("alice")).isTrue();
        assertThat(userRepository.existsByUsername("nobody")).isFalse();
        // init.sql 中 status=1 共 8 条，status=0 共 2 条
        assertThat(userRepository.countByStatus(1)).isEqualTo(8);
        assertThat(userRepository.countByStatus(0)).isEqualTo(2);
    }
}
