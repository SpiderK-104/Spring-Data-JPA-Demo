package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 商品仓储（JPQL 测试夹具用）。
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
