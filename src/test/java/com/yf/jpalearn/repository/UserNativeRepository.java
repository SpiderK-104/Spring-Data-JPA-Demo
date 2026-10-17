package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 实战场景仓储（简化自 doc/pgsql-usage.md 中的真实用例）。
 * <p>
 * 之所以单独放一个类、且换个名字：这些方法清一色是原生 SQL（ON CONFLICT / RETURNING /
 * FOR UPDATE SKIP LOCKED / 分批 DELETE），并直接操作会话与一级缓存，语义上不属于"纯 CRUD"。
 */
@Repository
public class UserNativeRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * 场景 1：幂等 Upsert —— 对应 pgsql-usage 4.2 的 ON CONFLICT ... DO UPDATE。
     * 以 username 唯一键冲突为触发点，重复写入只更新、不新增第二行。
     */
    @Transactional
    public void upsert(String username, String nickname, Integer age, Integer status) {
        em.createNativeQuery(
                        "INSERT INTO tb_user(username, nickname, age, status, created_at, updated_at) "
                                + "VALUES (:username, :nickname, :age, :status, now(), now()) "
                                + "ON CONFLICT (username) DO UPDATE SET "
                                + "  nickname = EXCLUDED.nickname, age = EXCLUDED.age, "
                                + "  status = EXCLUDED.status, updated_at = now()")
                .setParameter("username", username)
                .setParameter("nickname", nickname)
                .setParameter("age", age)
                .setParameter("status", status)
                .executeUpdate();
    }

    /**
     * 场景 2：写入即取自增主键 —— 对应 pgsql-usage 4.2 的 INSERT ... RETURNING id。
     */
    @Transactional
    public Long insertReturningId(String username, String nickname, Integer age, Integer status) {
        Number id = (Number) em.createNativeQuery(
                        "INSERT INTO tb_user(username, nickname, age, status, created_at, updated_at) "
                                + "VALUES (:username, :nickname, :age, :status, now(), now()) RETURNING id")
                .setParameter("username", username)
                .setParameter("nickname", nickname)
                .setParameter("age", age)
                .setParameter("status", status)
                .getSingleResult();
        return id.longValue();
    }

    /**
     * 场景 3：多消费者抢任务 —— 对应 pgsql-usage 4.1 的 FOR UPDATE SKIP LOCKED。
     * 这里把 status=1 的用户当作待处理任务：先加行锁捞出 N 条（其他事务会跳过已锁行），
     * 再在事务内改状态，靠脏检查落库。
     */
    @Transactional
    public List<User> claimPending(int limit) {
        List<User> picked = em.createNativeQuery(
                        "SELECT * FROM tb_user WHERE status = 1 ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
                        User.class)
                .setParameter("limit", limit)
                .getResultList();
        for (User user : picked) {
            user.setStatus(2);
        }
        em.flush();
        return picked;
    }

    /**
     * 场景 4：分批清理 —— 对应 pgsql-usage 4.6 的 DELETE ... WHERE id IN (SELECT ... LIMIT)。
     * 一次只删一批，避免长事务锁表。
     */
    @Transactional
    public int deleteDisabledBatch(int limit) {
        return em.createNativeQuery(
                        "DELETE FROM tb_user WHERE id IN ("
                                + "  SELECT id FROM tb_user WHERE status = 0 ORDER BY id LIMIT :limit)")
                .setParameter("limit", limit)
                .executeUpdate();
    }

    /**
     * 场景 5：悲观锁读 —— 对应 pgsql-usage 3.1 的 SELECT ... FOR UPDATE。
     */
    @Transactional
    public Optional<User> findByUsernameForUpdate(String username) {
        List<User> list = em.createNativeQuery(
                        "SELECT * FROM tb_user WHERE username = :username FOR UPDATE", User.class)
                .setParameter("username", username)
                .getResultList();
        return list.stream().findFirst();
    }

    /**
     * 场景 6：乐观锁 CAS —— 对应 pgsql-usage 3.1 的 WHERE version = :version。
     * 返回受影响行数：0 表示版本已被他人改动。
     */
    @Transactional
    public int casRename(String username, String newNickname, long expectedVersion) {
        return em.createNativeQuery(
                        "UPDATE tb_user SET nickname = :nickname, version = version + 1, updated_at = now() "
                                + "WHERE username = :username AND version = :version")
                .setParameter("nickname", newNickname)
                .setParameter("username", username)
                .setParameter("version", expectedVersion)
                .executeUpdate();
    }
}
