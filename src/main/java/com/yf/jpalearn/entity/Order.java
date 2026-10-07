package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单表: User -> Order -> OrderItem
 * 订单表与用户表是 1:N关系, 订单表与订单明细表是1:N关系.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 订单所属的用户
     * ManyToOne 关联关系, 一个订单只能属于一个用户.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 订单明细列表
     * OneToMany 关联关系, 一个订单可以有多个订单明细.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @CreatedDate
    private LocalDateTime createdAt;

    /**
     * 添加订单明细 到订单明细列表
     *
     * @param item 订单明细
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}