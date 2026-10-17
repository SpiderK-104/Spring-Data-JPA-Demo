package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 订单仓储：提供两种"提前加载懒集合"的写法，用于验证 LazyInitializationException 的解法
 * （见 doc/spring-jpa-lazy-notes.md 的方案 B）。
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * @EntityGraph 注解可以把 items 一起查出来，事务外访问不再报错。
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    /**
     * JPQL join fetch：等价的提前加载写法。
     */
    @Query("select distinct o from Order o join fetch o.items where o.id = :id")
    Optional<Order> findWithItemsByIdJoinFetch(@Param("id") Long id);
}
