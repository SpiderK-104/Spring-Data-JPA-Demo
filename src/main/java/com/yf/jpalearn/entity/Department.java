package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门（23 讲 · 一对多）。
 * <p>
 * 这里是反向（被维护方）：mappedBy = "department" 指向 Employee.department，
 * 真正维护外键 dept_id 的是“多”方 Employee 的 @ManyToOne。
 */
@Entity
@Table(name = "department")
@Getter
@Setter
@NoArgsConstructor
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /**
     * 一对多集合：一个部门对应多个职员。默认 LAZY；反向映射因此不会额外维护外键。
     * cascade = ALL + orphanRemoval 让“从集合移除 = 删除明细”更符合直觉。
     */
    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Employee> employees = new ArrayList<>();

    public Department(String name) {
        this.name = name;
    }

    /**
     * 双向关系必须“两边都设”，否则内存看起来对、数据库却没更新。
     */
    public void addEmployee(Employee employee) {
        employees.add(employee);
        employee.setDepartment(this);
    }

    public void removeEmployee(Employee employee) {
        employees.remove(employee);
        employee.setDepartment(null);
    }
}
