package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Role;
import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多对多关联映射测试（25-27 讲）：用户 <-> 角色，靠中间表 user_role 维护。
 *
 * 核心记住两点：
 * - 维护方是带 @JoinTable 的 User#getRoles()；只有改它才会写中间表。
 * - 集合用 Set；cascade 只给 PERSIST/MERGE，不要给 REMOVE。
 * 数据全部在本事务内构造，方法结束回滚。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("多对多关联映射（25-27 讲）")
class ManyToManyMappingTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager em;

    private User newUser(String username) {
        return userRepository.save(User.of(username, "多对多用户", username + "@example.com", 30, 1));
    }

    /**
     * 关联插入（26 讲）：用户和角色都是新对象。
     *
     * 注意事项：
     * - 往维护方集合 add（user.getRoles().add(role)）；中间表 user_role 由 JPA 自动插入。
     * - User.roles 配了 cascade = PERSIST，所以新角色会一并入库，无需先存 role。
     * - 若不配级联去 add 一个未持久化的 role，会抛 TransientObjectException / TransientPropertyValueException。
     */
    @Test
    @DisplayName("新增关联：两边都是新对象（cascade PERSIST 一并入库）")
    void insert_newRoleWithUser() {
        User user = newUser("mm_new");
        user.getRoles().add(new Role("ADMIN"));   // 维护方 add

        userRepository.save(user);                // cascade PERSIST -> 角色 + 中间表
        em.flush();
        em.clear();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getRoles()).extracting(Role::getName).containsExactly("ADMIN");
    }

    /**
     * 插入已保存的关联数据（27 讲）：角色已存在数据库。
     *
     * 注意事项：
     * - 只需把已存在的角色（查出来，受托管）挂到维护方集合上，持久化 User 即可。
     * - 不要 new 一个同 name 的新 Role 去 add：会命中唯一约束 / 造成"角色重复"。
     * - 中间表只多一行，role 表不会新增。
     */
    @Test
    @DisplayName("插入已存在角色：查出来挂到维护方集合即可")
    void insert_existingRole() {
        roleRepository.save(new Role("USER"));
        User user = newUser("mm_existing");

        Role managed = roleRepository.findByName("USER").orElseThrow();
        user.getRoles().add(managed);
        userRepository.save(user);
        em.flush();
        em.clear();

        assertThat(userRepository.findById(user.getId()).orElseThrow()
                .getRoles()).extracting(Role::getName).containsExactly("USER");
        assertThat(roleRepository.existsByName("USER")).isTrue(); // 没有重复创建
    }

    /**
     * 关键：被维护方（mappedBy）改变不会写中间表（27 讲重点）。
     *
     * 注意事项：
     * - role.getUsers().add(user) 改的是 mappedBy 那一端，Hibernate 会忽略。
     * - 只有维护方 @JoinTable 那端（User.roles）的改动才落库。
     * - 双向多对多只保留一个维护方，避免"谁说了算"的歧义。
     */
    @Test
    @DisplayName("坑：改被维护方（mappedBy）无效，只有维护方写中间表")
    void inverseSideChange_doesNotWriteJoinTable() {
        User user = newUser("mm_inverse");
        Role role = roleRepository.save(new Role("GUEST"));

        // 改被维护方：不会写 user_role
        role.getUsers().add(user);
        roleRepository.save(role);
        em.flush();
        em.clear();

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRoles()).isEmpty();

        // 改维护方才生效
        User managed = userRepository.findById(user.getId()).orElseThrow();
        managed.getRoles().add(roleRepository.findByName("GUEST").orElseThrow());
        userRepository.save(managed);
        em.flush();
        em.clear();

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRoles()).hasSize(1);
    }

    /**
     * 集合用 Set 而非 List（26 讲）：重复添加同一个角色只保留一份。
     *
     * 注意事项：
     * - 用 List 且重复 add，会产生重复的中间表行，甚至主键冲突（中间表复合主键 user_id+role_id）。
     * - Set 去重依赖正确的 equals/hashCode（Role 按 name 判等）；否则两个"同 name"对象仍会各插一行。
     */
    @Test
    @DisplayName("Set 去重：重复 add 同一个角色只留一行")
    void set_dedupDuplicateAdd() {
        User user = newUser("mm_dedup");
        Role role = roleRepository.save(new Role("DUP"));
        Role same = roleRepository.findByName("DUP").orElseThrow();

        user.getRoles().add(role);
        user.getRoles().add(same);   // 按 name 判等，相等 -> Set 不重复
        userRepository.save(user);
        em.flush();
        em.clear();

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRoles()).hasSize(1);
    }

    /**
     * 删除关联（25 讲）：先解除关联，再删角色本体。
     *
     * 注意事项：
     * - 多对多不要配 CascadeType.REMOVE：否则删用户会把角色一起删掉（角色可能被其他用户共用）。
     * - 删除仍被引用的角色：先从维护方集合 remove（清中间表行），flush 后再删角色，避免外键约束报错。
     * - 删除用 removeFromCollection 而不是直接 delete 角色，能保证中间表同步清理。
     */
    @Test
    @DisplayName("删除：先解除关联，再删角色")
    void removeThenDelete_role() {
        User user = newUser("mm_delete");
        Role role = roleRepository.save(new Role("TEMP"));
        user.getRoles().add(role);
        userRepository.save(user);
        em.flush();

        // 解除关联（维护方）
        user.getRoles().remove(role);
        userRepository.save(user);
        em.flush();
        em.clear();

        // 再删除角色本体
        roleRepository.deleteById(role.getId());
        em.flush();

        assertThat(roleRepository.findById(role.getId())).isEmpty();
    }

    /**
     * 删除用户不应连累角色（25 讲：级联慎用）。
     *
     * 注意事项：
     * - 因为 roles 未配 REMOVE，删用户只会删掉中间表里的关联行，role 表不受影响。
     * - 若把 User.roles 配成 cascade = ALL/REMOVE，删用户会级联删除这些角色 → 生产事故高发点。
     */
    @Test
    @DisplayName("级联慎用：删用户，角色仍保留")
    void deleteUser_doesNotDeleteRole() {
        Role role = roleRepository.save(new Role("KEEP"));
        User user = newUser("mm_deluser");
        user.getRoles().add(role);
        userRepository.save(user);
        em.flush();

        userRepository.delete(user);
        em.flush();
        em.clear();

        assertThat(roleRepository.existsByName("KEEP")).isTrue(); // 角色安然无恙
    }
}
