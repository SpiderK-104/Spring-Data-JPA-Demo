package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 纯 CRUD 仓储：只使用 Spring Data 派生查询 + 分页/排序，不掺杂任何原生 SQL、
 * 缓存策略或 JPA 状态相关的演示代码（那些内容见 {@link UserNativeRepository} 与各测试类）。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByStatus(Integer status);

    List<User> findByStatus(Integer status);

    List<User> findByAgeBetween(int min, int max);

    List<User> findByStatusOrderByCreatedAtDesc(Integer status);

    // 分页版：返回 Page（会额外发一条 COUNT）
    Page<User> findByStatus(Integer status, Pageable pageable);

    // 分页版：返回 Slice（不统计总数，适合"加载更多"）
    Slice<User> findByStatusOrderByIdAsc(Integer status, Pageable pageable);

    // 派生查询 + 分页
    Page<User> findByNicknameContaining(String keyword, Pageable pageable);
}
