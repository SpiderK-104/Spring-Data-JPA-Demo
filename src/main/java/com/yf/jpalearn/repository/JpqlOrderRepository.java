package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 订单相关的 JPQL 专项仓储（@Query 版）：关联查询、join fetch、集合函数、EXISTS 子查询。
 */
public interface JpqlOrderRepository extends JpaRepository<Order, Long> {

    /**
     * 关联查询（INNER JOIN）：用属性路径连接，不写外键列名
     */
    @Query("select o from Order o join o.user u where u.username = :name")
    List<Order> findByUserUsername(@Param("name") String name);

    /**
     * JOIN FETCH 提前加载集合；集合抓取会产生重复行，需 distinct 去重
     */
    @Query("select distinct o from Order o join fetch o.items where o.id = :id")
    Optional<Order> findWithItemsByIdJoinFetch(@Param("id") Long id);

    /**
     * 集合属性函数 size()
     */
    @Query("select o from Order o where size(o.items) >= :n")
    List<Order> findByItemCountAtLeast(@Param("n") int n);

    /**
     * EXISTS 相关子查询
     */
    @Query("select count(o) from Order o where exists (select i from OrderItem i where i.order = o)")
    long countWithItems();
}
