package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Employee;
import com.yf.jpalearn.entity.EmployeeDetail;
import com.yf.jpalearn.entity.User;
import com.yf.jpalearn.entity.UserProfile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一对一关联映射测试（20-22 讲）。
 * <p>
 * 覆盖：单向一对一（21）、双向一对一（22）以及"两边都要维护"的坑。
 * 数据全部在本事务内构造，方法结束回滚。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("一对一关联映射（20-22 讲）")
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
     * <p>
     * 注意事项：
     * <ul>
     *   <li>方向是<b>单向</b>的：User 能拿到 Profile，Profile <b>不能</b>反向导航到 User。</li>
     *   <li>外键在<b>持有引用</b>的一方（User）；@JoinColumn 决定列名，unique = true 保证一对一。</li>
     *   <li>cascade = ALL：保存/删除 User 会连带 Profile；1:1 里这个级联通常合理。</li>
     *   <li>默认 FetchType 是 EAGER，这里显式改成 LAZY；注意事务外访问会抛 LazyInitializationException。</li>
     * </ul>
     */
    @Test
    @DisplayName("单向一对一：User -> UserProfile（外键在 tb_user.profile_id）")
    void unidirectional_userOwnsProfile() {
        User user = User.of("uni_user", "单向用户", "uni@example.com", 20, 1);
        user.setProfile(new UserProfile("热爱 JPA", "13800000000"));

        User saved = userRepository.save(user); // cascade=ALL 一并插入 user_profile
        assertThat(saved.getProfile().getId()).isNotNull();

        em.flush();
        em.clear();
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getProfile().getBio()).isEqualTo("热爱 JPA");
        // 反向不可导航：UserProfile 里没有 user 字段（见实体注释）
    }

    /**
     * 双向一对一（22 讲）：Employee &lt;-&gt; EmployeeDetail，维护方是 Employee（外键 detail_id）。
     * <p>
     * 注意事项（重点）：
     * <ul>
     *   <li>双向必须用 {@code mappedBy} 指明"被维护方"，否则两边各建一个外键，产生冗余列。</li>
     *   <li>必须<b>手动维护两边</b>（{@code emp.bindDetail(d)} 内部同时 d.setEmployee(emp)）；
     *       只设被维护方那一边，数据库里的外键<b>不会</b>更新（见下面第一段）。</li>
     *   <li>双向还容易触发 N+1：加载多个 Employee 时逐个补查 Detail；用 {@code @EntityGraph} / join fetch 预加载。</li>
     * </ul>
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
