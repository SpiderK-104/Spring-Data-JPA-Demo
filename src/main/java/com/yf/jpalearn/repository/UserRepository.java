package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    List<User> findByAgeBetween(int min, int max);

    List<User> findByStatusOrderByCreatedAtDesc(int status);

    // 分页版：按状态分页（返回 Page，会额外 COUNT）
    Page<User> findByStatus(int status, Pageable pageable);

    // 分页版：按状态分页（返回 Slice，不统计总数，适合加载更多）
    Slice<User> findByStatusOrderByIdAsc(int status, Pageable pageable);

    // 自定义 JPQL（支持分页）
    @Query("select u from User u where u.nickname like %:keyword%")
    Page<User> searchByNickname(@Param("keyword") String keyword, Pageable pageable);

    // 自定义修改（需配合 @Modifying + @Transactional 使用）
    @Modifying
    @Query("update User u set u.status = :status where u.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") int status);
}