package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用户表的Repository接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
