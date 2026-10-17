package com.yf.jpalearn.repository;

import com.yf.jpalearn.dto.UserSummary;
import com.yf.jpalearn.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * JPQL 专项仓储（@Query 版）：所有查询都用注解声明，贴近日常 Spring Data JPA 开发。
 * <p>
 * 与 {@link UserRepository}（纯派生查询）、{@link UserNativeRepository}（原生 SQL）区分开，
 * 便于对照三种写法的差异。
 */
public interface JpqlUserRepository extends JpaRepository<User, Long> {

    /**
     * 1. 位置参数 {@code ?1}
     */
    @Query("select u from User u where u.status = ?1")
    List<User> findByStatusPositional(Integer status);

    /**
     * 2. 命名参数 {@code :name}（配合 @Param）
     * 占位符名称要和 @Param("xxx") 里的字符串 完全一致
     * 查询语言不声明,就默认是 JPQL（不用写 nativeQuery = true）
     */
    @Query("select count(u) from User u where u.age >= :min and u.status = :status")
    long countByMinAgeAndStatus(@Param("min") int min, @Param("status") int status);

    /**
     * 3. 范围 / 集合运算符
     */
    @Query("select u from User u where u.age between :lo and :hi and u.status in :statuses order by u.age")
    List<User> searchByAgeRangeAndStatus(@Param("lo") int lo, @Param("hi") int hi, @Param("statuses") List<Integer> statuses);

    /**
     * 4. LIKE 模糊匹配
     */
    @Query("select u from User u where u.nickname like :kw order by u.id")
    List<User> searchByNickname(@Param("kw") String kw);

    /**
     * 5. 排序 + 分页：Pageable 注入 limit/offset（JPQL 不能写 LIMIT/OFFSET）
     */
    @Query("select u from User u ")
    List<User> findTopByAgeDesc(Pageable pageable);

    /**
     * 6. 聚合函数
     */
    @Query("select count(u) from User u")
    long countAll();

    @Query("select max(u.age) from User u")
    Integer maxAge();

    @Query("select min(u.age) from User u")
    Integer minAge();

    @Query("select avg(u.age) from User u")
    Double avgAge();

    @Query("select sum(u.age) from User u")
    Long sumAge();

    /**
     * 7. GROUP BY + HAVING
     */
    @Query("select u.status, count(u) from User u group by u.status having count(u) > 1 order by u.status")
    List<Object[]> groupByStatusHaving();

    /**
     * 8. DISTINCT
     */
    @Query("select distinct u.status from User u order by u.status")
    List<Integer> distinctStatuses();

    /**
     * 9. 投影：多列 -> Object[]
     */
    @Query("select u.username, u.age from User u order by u.id")
    List<Object[]> findUsernameAndAge();

    /**
     * 10. 投影：构造器表达式（全限定类名 + 匹配的构造器）
     */
    @Query("select new com.yf.jpalearn.dto.UserSummary(u.username, u.age) from User u where u.status = 1 order by u.age")
    List<UserSummary> findSummaries();

    /**
     * 11. 标量子查询
     */
    @Query("select u from User u where u.age > (select avg(u2.age) from User u2) order by u.age")
    List<User> findOlderThanAverage();

    /**
     * 12. 批量 UPDATE：{@code @Modifying} 必须运行在事务中；返回受影响行数。
     * clearAutomatically=true 会在执行后清理一级缓存，flushAutomatically=true 先刷未落库的改动。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.status = :newStatus where u.status = :oldStatus")
    int bulkUpdateStatus(@Param("oldStatus") int oldStatus, @Param("newStatus") int newStatus);

    /**
     * 13. 批量 DELETE
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from User u where u.status = :status")
    int bulkDeleteByStatus(@Param("status") int status);

    /**
     * 14. NULL 处理：is null + coalesce
     */
    @Query("select u from User u where u.email is null")
    List<User> findWithoutEmail();

    @Query("select coalesce(u.email, 'N/A') from User u where u.username = :n")
    String findEmailOrFallback(@Param("n") String n);
}
