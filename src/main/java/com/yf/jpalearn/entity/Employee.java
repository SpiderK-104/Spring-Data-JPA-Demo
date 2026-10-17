package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 员工（24 讲 · 多对一；22 讲 · 双向一对一）。
 * <p>
 * 它是外键的<b>维护方</b>：{@code @ManyToOne} 维护 dept_id，{@code @OneToOne} 维护 detail_id。
 * 默认 FetchType（@ManyToOne/@OneToOne）是 EAGER，这里显式改成 LAZY，顺带演示懒加载坑。
 */
@Entity
@Table(name = "employee")
@Getter
@Setter
@NoArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** 多对一：外键 dept_id 的真正拥有方 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id")
    private Department department;

    /** 一对一（维护方）：外键 detail_id 在 employee 表 */
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "detail_id", unique = true)
    private EmployeeDetail detail;

    public Employee(String name) {
        this.name = name;
    }

    /** 双向一对一：两边都要设，否则数据库里的外键不会更新 */
    public void bindDetail(EmployeeDetail employeeDetail) {
        this.detail = employeeDetail;
        employeeDetail.setEmployee(this);
    }
}
