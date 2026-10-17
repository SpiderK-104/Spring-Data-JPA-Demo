package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 员工详情（22 讲 · 双向一对一的<b>被维护方</b>）。
 * <p>
 * {@code mappedBy = "detail"} 指向 Employee.detail；本类不维护外键，只做逆向导航。
 */
@Entity
@Table(name = "employee_detail")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String address;

    @Column(length = 200)
    private String remark;

    @OneToOne(mappedBy = "detail", fetch = FetchType.LAZY)
    private Employee employee;

    public EmployeeDetail(String address, String remark) {
        this.address = address;
        this.remark = remark;
    }
}
