package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Employee;
import com.yf.jpalearn.entity.EmployeeDetail;
import com.yf.jpalearn.entity.User;
import com.yf.jpalearn.entity.UserProfile;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.Commit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一对一关联映射测试（20-22 讲）。
 *
 * 覆盖：单向一对一（21）、双向一对一（22）以及"两边都要维护"的坑。
 * 数据全部在本事务内构造，方法结束回滚。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("一对一关联映射")
@Slf4j
class OneToOneMappingTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private EmployeeDetailRepository employeeDetailRepository;
    @Autowired
    private EntityManager em;

    /**
     * 单向一对一（21 讲）：只有 User 持有 UserProfile，外键 profile_id 落在 tb_user 上。
     *
     * 注意事项：
     *
     * 方向是单向的：User 能拿到 Profile，Profile 不能反向导航到 User。
     * 外键在持有引用的一方（User）；@JoinColumn 决定列名，unique = true 保证一对一。
     * cascade = ALL：保存/删除 User 会连带 Profile；1:1 里这个级联通常合理。
     * 默认 FetchType 是 EAGER，这里显式改成 LAZY；注意事务外访问会抛 LazyInitializationException。
     *
     */
    @Test
    @Commit
    @DisplayName("单向一对一插入：User -> UserProfile（外键在 tb_user.profile_id）")
    void unidirectional_userOwnsProfile() {
        User user = User.of("uni_user", "单向用户", "uni@example.com", 20, 1);
        user.setProfile(new UserProfile("热爱 JPA", "13800000000"));

        User saved = userRepository.save(user); // cascade=ALL 一并插入 user_profile
        em.flush();
        em.clear();
    }


    @Test
    @Commit
    void deleteCascade() {
        User u = User.of("ASIAC", "级联", "casc@example.com", 20, 1);
        u.setProfile(new UserProfile("bio", "138"));
        u = userRepository.save(u);
        Long profileId = u.getProfile().getId();   // 记下 profileId

        log.info("删除 User {} 后，Profile {} 也会被删除", u, u.getProfile().getId());
        userRepository.deleteById(u.getId());
        em.flush();
        em.clear();
        assertThat(userProfileRepository.findById(profileId)).isEmpty(); // 这时才会删
    }

    /**
     * 双向一对一（22 讲）：Employee <-> EmployeeDetail，维护方是 Employee（外键 detail_id）。
     *
     * 注意事项（重点）：
     *
     * 双向必须用 mappedBy 指明"被维护方"，否则两边各建一个外键，产生冗余列。
     * 必须手动维护两边（emp.bindDetail(d) 内部同时 d.setEmployee(emp)）；
     * 只设被维护方那一边，数据库里的外键不会更新（见下面第一段）。
     * 双向还容易触发 N+1：加载多个 Employee 时逐个补查 Detail；用 @EntityGraph / join fetch 预加载。
     *
     */
    @Test
    @DisplayName("双向一对一：Employee <-> EmployeeDetail（两边都必须 set）")
    void bidirectional_bothSidesMustBeSet() {
        // —— 错误做法：只设置了被维护方（mappedBy 那一端）
        EmployeeDetail d1 = new EmployeeDetail("地址-A", "备注-A");
        Employee e1 = new Employee("只设一边");
        d1.setEmployee(e1);              // 被维护方，不会写 employee.detail_id
        employeeRepository.save(e1);
        employeeDetailRepository.save(d1);
        em.flush();
        em.clear();

        // 维护方（Employee）的 detail_id 仍然为空 -> 关系没落库
        assertThat(employeeRepository.findById(e1.getId()).orElseThrow().getDetail()).isNull();

        // —— 正确做法：维护方持有外键，两边都要设
        EmployeeDetail d2 = new EmployeeDetail("地址-B", "备注-B");
        Employee e2 = new Employee("两边都设");
        e2.bindDetail(d2);               // this.detail = d2; d2.setEmployee(this)
        employeeRepository.save(e2);     // cascade=ALL 保存 d2
        em.flush();
        em.clear();

        Employee r2 = employeeRepository.findById(e2.getId()).orElseThrow();
        assertThat(r2.getDetail()).isNotNull();
        // 反向导航也能拿到
        assertThat(employeeDetailRepository.findById(r2.getDetail().getId()).orElseThrow()
                .getEmployee().getId()).isEqualTo(e2.getId());
    }
}
