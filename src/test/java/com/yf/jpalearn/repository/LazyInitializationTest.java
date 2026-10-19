package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Order;
import com.yf.jpalearn.entity.OrderItem;
import com.yf.jpalearn.entity.Product;
import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA 懒加载 + 事务边界机制测试。
 *
 * 参照 doc/spring-jpa-lazy-notes.md：复现 LazyInitializationException (no Session)，
 * 并验证其三种解法——事务内访问 / @EntityGraph 预加载 / join fetch 预加载 / 只取标量。
 *
 * 这里用 @Transactional(propagation = NOT_SUPPORTED) 关掉测试自带事务，
 * 由 TransactionTemplate 手工控制事务边界，才能真正模拟"事务外访问懒集合"。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("JPA 懒加载与事务边界机制（参照 spring-jpa-lazy-notes.md）")
class LazyInitializationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    private Long userId;
    private Long productId;
    private Long orderId;

    @BeforeEach
    void initData() {
        tx = new TransactionTemplate(txManager);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        tx.execute(status -> {
            User user = User.of("lazy_" + suffix, "懒加载用户", "lazy_" + suffix + "@example.com", 30, 1);
            em.persist(user);

            Product product = new Product();
            product.setName("产品-" + suffix);
            product.setPrice(new BigDecimal("9.90"));
            em.persist(product);

            Order order = new Order();
            order.setUser(user);

            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(2);
            order.addItem(item);

            em.persist(order); // Order.items 配了 cascade = ALL，明细一并保存

            userId = user.getId();
            productId = product.getId();
            orderId = order.getId();
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        // 因为关掉了测试事务，数据不会自动回滚，需手工清理
        tx.execute(status -> {
            em.createNativeQuery("DELETE FROM order_items WHERE order_id = :id")
                    .setParameter("id", orderId).executeUpdate();
            em.createNativeQuery("DELETE FROM orders WHERE id = :id")
                    .setParameter("id", orderId).executeUpdate();
            em.createNativeQuery("DELETE FROM products WHERE id = :id")
                    .setParameter("id", productId).executeUpdate();
            em.createNativeQuery("DELETE FROM tb_user WHERE id = :id")
                    .setParameter("id", userId).executeUpdate();
            return null;
        });
    }

    @Test
    @DisplayName("事务内访问懒集合：正常（Session 还活着，可随时补查）")
    void lazyInsideTransaction_ok() {
        tx.execute(status -> {
            Order order = em.find(Order.class, orderId);
            assertThat(order.getItems()).hasSize(1); // 懒加载在事务内被触发，成功
            return null;
        });
    }

    @Test
    @DisplayName("事务外访问懒集合：抛 LazyInitializationException（no Session）")
    void lazyOutsideTransaction_throws() {
        // 事务结束时 Order 变为 Detached，items 仍是未初始化的代理
        Order detached = tx.execute(status -> em.find(Order.class, orderId));

        int size = detached.getItems().size();
//        assertThatThrownBy(() ->)
//                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    @DisplayName("解法B-1：@EntityGraph 在事务内预加载集合，事务外可安全访问")
    void entityGraphPreload_ok() {
        Order loaded = orderRepository.findWithItemsById(orderId).orElseThrow();

        assertThat(loaded.getItems()).hasSize(1); // 已初始化，脱离事务也不报错
    }

    @Test
    @DisplayName("解法B-2：JPQL join fetch 预加载集合")
    void joinFetchPreload_ok() {
        Order loaded = orderRepository.findWithItemsByIdJoinFetch(orderId).orElseThrow();

        assertThat(loaded.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("解法A：事务外只取标量（含懒代理的 id），不碰集合，安全")
    void scalarOnlyOutsideTransaction_ok() {
        Order detached = tx.execute(status -> em.find(Order.class, orderId));

        assertThat(detached.getId()).isEqualTo(orderId);
        // 取懒代理的 id 不会触发初始化（代理自身就持有标识），因此也安全
        assertThat(detached.getUser().getId()).isEqualTo(userId);
    }
}
