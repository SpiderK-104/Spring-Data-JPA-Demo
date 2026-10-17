package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Department;
import com.yf.jpalearn.entity.Employee;
import jakarta.persistence.EntityManager;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一对多 / 多对一关联映射测试（23-24 讲）。
 * <p>
 * 本质是一回事：Department(一) &lt;-&gt; Employee(多)。最佳实践是<b>双向</b>——
 * "一"方 {@code @OneToMany(mappedBy)} 反向，"多"方 {@code @ManyToOne + @JoinColumn} 拥有并维护外键。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("一对多 / 多对一（23-24 讲）")
class OneToManyMappingTest {

    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;

    /**
     * 双向一对多（推荐写法）：外键落在"多"方 Employee.dept_id，由 @ManyToOne 维护。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>"一"方用 {@code mappedBy = "department"} 标记为<b>反向</b>，不再重复建设外键列。</li>
     *   <li>addEmployee 里"两边都设"，内存一致性与数据库一致。</li>
     *   <li>集合默认 LAZY；这里 cascade=ALL + orphanRemoval，从集合移除即删除子行。</li>
     * </ul>
     */
    @Test
    @DisplayName("双向：用 A 方 addEmployee 同步两边，外键落在 B 方")
    void bidirectional_addEmployee_setsBothSides() {
        Department dept = new Department("研发部");
        dept.addEmployee(new Employee("张三"));
        dept.addEmployee(new Employee("李四"));

        Department saved = departmentRepository.save(dept); // cascade=ALL 保存两名员工
        em.flush();
        em.clear();

        assertThat(employeeRepository.findByDepartmentId(saved.getId())).hasSize(2);
    }

    /**
     * 坑：只往"一"方集合里 add、忘了设"多"方的 department。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>反向集合（{@code @OneToMany(mappedBy)}）<b>不维护外键</b>，Hibernate 会忽略它的变化。</li>
     *   <li>结果：员工被存下来了，但 {@code dept_id} 为 null，按部门查不到——"内存看着对，库里没关联"。</li>
     *   <li>口诀：谁是维护方，就改谁（这里改 Employee.department）。</li>
     * </ul>
     */
    @Test
    @DisplayName("坑：只设反向集合，外键不更新（dept_id 为 null）")
    void pitfall_onlySettingInverseSide() {
        Department dept = new Department("市场部");
        Employee emp = new Employee("忘记设部门");
        dept.getEmployees().add(emp);     // 只动反向集合，没有 emp.setDepartment(dept)

        Department saved = departmentRepository.save(dept); // 员工被 cascade 保存
        em.flush();
        em.clear();

        assertThat(employeeRepository.findById(emp.getId())).isPresent();     // 员工在
        assertThat(employeeRepository.findByDepartmentId(saved.getId())).isEmpty(); // 但没归属
    }

    /**
     * 坑：事务外访问懒加载集合 -&gt; LazyInitializationException（no Session）。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>{@code @OneToMany} / {@code @ManyToOne} 默认 LAZY，脱离事务/Session 后再访问代理会抛异常。</li>
     *   <li>解法：事务内访问、@EntityGraph 或 join fetch 预加载、或只取标量（见 LazyInitializationTest）。</li>
     *   <li>本方法关闭了测试自带事务（NOT_SUPPORTED），用 TransactionTemplate 手工控制边界，才能真正复现。</li>
     * </ul>
     */
    @Test
    @DisplayName("坑：事务外访问懒集合 -> LazyInitializationException")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void pitfall_lazyOutsideTransaction() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long deptId = tx.execute(status -> {
            Department dept = new Department("懒加载部");
            dept.addEmployee(new Employee("王五"));
            return departmentRepository.save(dept).getId();
        });

        Department detached = tx.execute(status -> departmentRepository.findById(deptId).orElseThrow());
        // 事务已提交，集合仍是未初始化的代理
        assertThatThrownBy(() -> detached.getEmployees().size())
                .isInstanceOf(LazyInitializationException.class);

        // 关掉了测试事务，数据不会自动回滚，需手工清理
        tx.execute(status -> {
            em.createNativeQuery("DELETE FROM employee WHERE dept_id = :id")
                    .setParameter("id", deptId).executeUpdate();
            em.createNativeQuery("DELETE FROM department WHERE id = :id")
                    .setParameter("id", deptId).executeUpdate();
            return null;
        });
    }
}
