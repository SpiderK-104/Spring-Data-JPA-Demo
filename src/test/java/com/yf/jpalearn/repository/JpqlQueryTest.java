package com.yf.jpalearn.repository;

import com.yf.jpalearn.dto.UserSummary;
import com.yf.jpalearn.entity.Order;
import com.yf.jpalearn.entity.OrderItem;
import com.yf.jpalearn.entity.Product;
import com.yf.jpalearn.entity.User;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单独的 JPQL 测试用例（@Query 版）：每个方法一个场景，并写明"注意事项"。
 *
 * 查询全部通过 JpqlUserRepository / JpqlOrderRepository 上的 @Query 发起，
 * 与日常 Spring Data JPA 开发一致；不再在测试里手写 EntityManager.createQuery。
 *
 * 数据基线来自 init.sql：tb_user 共 10 条，status=1 有 8 条、status=0 有 2 条（charlie、frank）。
 *
 * 两个通用前提：
 * - JPQL 面向实体名 / 属性名，不是表名 / 列名。
 * - 本次用 @DataJpaTest（事务内），所有写操作在方法结束时回滚。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("JPQL 查询语言测试（@Query 版）")
@Slf4j
class JpqlQueryTest {

    @Autowired
    private JpqlUserRepository userRepo;
    @Autowired
    private JpqlOrderRepository orderRepo;
    @Autowired
    private ProductRepository productRepo;
    @Autowired
    private UserRepository userCrudRepo; // 复用派生查询做测试夹具/结果校验
    /**
     * 仅用于清一级缓存（配合 join fetch 演示），查询本身都走 @Query。
     */
    @Autowired
    private EntityManager em;

    /**
     * 场景：最基础的查询 + 位置参数。
     *
     * 注意事项：
     * - @Query 是编译期静态的：查询串固定写在注解里，无法运行时拼接（动态查询要用 Specification / Criteria）。
     * - 位置参数写作 ?1，编号从 1 开始，参数一多就不直观。
     */
    @Test
    @DisplayName("基础查询 + 位置参数")
    void basicSelect_positionalParam() {
        List<User> disabled = userRepo.findByStatusPositional(0);

        List<User> userFilter = disabled.stream()
                .filter(user -> user.getAge() > 10)
                // 打印每个过滤后的元素
                .peek(System.out::println)
                .toList();
    }

    /**
     * 场景：命名参数 :name + @Param。
     *
     * 注意事项：
     * - 命名参数比位置参数清晰，同一参数可复用，推荐优先使用。
     * - 方法名需要给参数加 @Param("x")（除非开启 -parameters 编译参数）。
     * - 聚合/标量查询返回单值，可直接用基本类型或包装类型接收。
     */
    @Test
    @DisplayName("命名参数（推荐）")
    void namedParameters() {
        long count = userRepo.countByMinAgeAndStatus(25, 1);
        log.info("\ncount: {}", count);
    }

    /**
     * 场景：where 中的比较/范围/集合运算符（between、in）。
     *
     * 注意事项：
     * - between :lo and :hi 是闭区间（含两端）。
     * - in :list 参数传集合；空集合行为各库不同，别依赖。
     */
    @Test
    @DisplayName("比较 / 范围 / 集合运算符")
    void whereOperators() {
        List<User> list = userRepo.searchByAgeRangeAndStatus(19, 24, List.of(1));

        // [19,24] 且 status=1：grace19、alice20、dave22、heidi24
        assertThat(list).extracting(User::getUsername)
                .containsExactly("grace", "alice", "dave", "heidi");
    }

    /**
     * 场景：LIKE 模糊匹配（含中文）。
     *
     * 注意事项：
     * - JPQL 是标准语法，没有 ILIKE（那是 PostgreSQL 方言）；忽略大小写请用 lower() 包一层。
     * - 通配符 % 放在参数值里，不要拼进 @Query 字符串。
     */
    @Test
    @DisplayName("LIKE 模糊匹配")
    void likeOperator() {
        List<User> list = userRepo.searchByNickname("%丝%");

        assertThat(list).extracting(User::getUsername)
                .containsExactly("alice", "grace"); // 爱丽丝、格蕾丝
    }

    /**
     * 场景：排序 + 分页。
     *
     * 注意事项：
     * - JPQL 不支持 LIMIT / OFFSET：把 Pageable 作为方法参数，Spring 自动追加 limit/offset。
     * - @Query 里已写死 order by 时，传入的 Pageable 不要再带 Sort，否则会冲突（这里用 unsorted 的 PageRequest）。
     * - 返回 Page 会额外发一条 count，返回 Slice 则不统计总数。
     */
    @Test
    @DisplayName("排序 + 分页")
    void orderByAndPaging() {
        List<User> page = userRepo.findTopByAgeDesc(PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "age")));
        log.info("page: {}", page);
    }

    /**
     * 场景：聚合函数 count / min / max / avg / sum。
     *
     * 注意事项：
     * - count 与整型 sum 返回 Long；avg 返回 Double。
     * - 无匹配行时 count 返回 0，其他聚合可能返回 null，接收类型最好用包装类型。
     */
    @Test
    @DisplayName("聚合函数")
    void aggregateFunctions() {
        assertThat(userRepo.countAll()).isEqualTo(10L);
        assertThat(userRepo.maxAge()).isEqualTo(35);
        assertThat(userRepo.minAge()).isEqualTo(19);
        assertThat(userRepo.avgAge()).isEqualTo(26.1);
        assertThat(userRepo.sumAge()).isEqualTo(261L);
    }

    /**
     * 场景：GROUP BY + HAVING。
     *
     * 注意事项：
     * - select 里的非聚合列必须出现在 group by 中，否则报错。
     * - 过滤分组结果用 having，不能用 where（where 作用在分组之前）。
     * - 多列结果返回 List<Object[]>。
     */
    @Test
    @DisplayName("GROUP BY + HAVING")
    void groupByHaving() {
        List<Object[]> rows = userRepo.groupByStatusHaving();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(0, 2L); // status=0 共 2
        assertThat(rows.get(1)).containsExactly(1, 8L); // status=1 共 8
    }

    /**
     * 场景：DISTINCT 去重。
     *
     * 注意事项：
     * - 这里用来取"出现过的 status 取值集合"。
     * - 它和 join fetch ... distinct（消除集合抓取的重复行）用途不同。
     */
    @Test
    @DisplayName("DISTINCT 去重")
    void distinctValues() {
        assertThat(userRepo.distinctStatuses()).containsExactly(0, 1);
    }

    /**
     * 场景：多列投影 -> Object[]。
     *
     * 注意事项：
     * - 多列 select 返回 Object[]，靠下标取值，顺序敏感、类型不安全。
     * - 需要类型安全时改用构造器表达式（见下一个用例）。
     */
    @Test
    @DisplayName("投影：Object[]")
    void projectionArray() {
        List<Object[]> rows = userRepo.findUsernameAndAge();

        assertThat(rows).hasSize(10);
        assertThat(rows.get(0)[0]).isEqualTo("alice");
        assertThat(rows.get(0)[1]).isEqualTo(20);
    }

    /**
     * 场景：构造器投影 select new ...（DTO）。
     *
     * 注意事项：
     * - 必须写全限定类名，且实参个数、顺序、类型要与构造器完全一致，否则启动/运行时报错。
     * - 只查需要的列，减少数据传输，也避免把实体长时间放进一级缓存。
     */
    @Test
    @DisplayName("投影：构造器表达式（DTO）")
    void projectionConstructor() {
        List<UserSummary> list = userRepo.findSummaries();

        assertThat(list).hasSize(8);
        assertThat(list.get(0)).isEqualTo(new UserSummary("grace", 19));
    }

    /**
     * 场景：关联查询（INNER JOIN）。
     *
     * 注意事项：
     * - JPQL 用属性路径（o.user u）连接，不写外键列名；join 默认就是内连接。
     * - 这一步会产生订单数据（测试事务内，结束回滚）。
     */
    @Test
    @DisplayName("关联查询：INNER JOIN")
    void innerJoin() {
        createOrder("bob", 2, 3);

        List<Order> orders = orderRepo.findByUserUsername("bob");

        assertThat(orders).hasSize(1);
    }

    /**
     * 场景：JOIN FETCH 提前加载集合（避免 N+1 与事务外懒加载异常）。
     *
     * 注意事项：
     * - 抓取集合会产生笛卡尔积重复行，务必加 distinct 去重。
     * - 仅用于"确实要用到关联集合"的查询；滥用会导致结果集膨胀。
     * - 先 em.clear() 清缓存，确保是查询本身把 items 抓回来，而不是命中一级缓存。
     */
    @Test
    @DisplayName("JOIN FETCH 提前加载集合")
    void joinFetch() {
        Order created = createOrder("eve", 1, 5);
        em.clear();

        Order loaded = orderRepo.findWithItemsByIdJoinFetch(created.getId()).orElseThrow();

        assertThat(loaded.getItems()).hasSize(2);
    }

    /**
     * 场景：集合属性函数 size()。
     *
     * 注意事项：
     * - size(o.items) 统计集合元素个数，底层生成聚合/子查询。
     * - 仅适用于 mappedBy 的集合属性，不能用于普通标量。
     */
    @Test
    @DisplayName("集合函数 size()")
    void collectionSize() {
        createOrder("judy", 1, 1, 1); // 3 条明细

        assertThat(orderRepo.findByItemCountAtLeast(3)).hasSize(1);
    }

    /**
     * 场景：标量子查询。
     *
     * 注意事项：
     * - 子查询用独立别名（u2），不要与外层别名混淆。
     * - 标量子查询需保证只返回单值（avg/min/max），否则报错。
     */
    @Test
    @DisplayName("标量子查询")
    void scalarSubquery() {
        List<User> olderThanAvg = userRepo.findOlderThanAverage();

        // 平均 26.1，年龄大于它的：judy27、eve28、charlie30、ivan31、frank35
        assertThat(olderThanAvg).extracting(User::getUsername)
                .containsExactly("judy", "eve", "charlie", "ivan", "frank");
    }

    /**
     * 场景：EXISTS 子查询。
     *
     * 注意事项：
     * - exists 只判断"是否存在"，通常比 in + 去重更高效。
     * - 子查询可引用外层别名（i.order = o）实现相关子查询。
     */
    @Test
    @DisplayName("EXISTS 子查询")
    void existsSubquery() {
        createOrder("heidi", 1);

        assertThat(orderRepo.countWithItems()).isEqualTo(1L);
    }

    /**
     * 场景：批量 UPDATE。
     *
     * 注意事项（重点）：
     * - 必须加 @Modifying 且运行在事务中；方法返回值是受影响行数，不再需要手动 executeUpdate()。
     * - 批量操作直接发一条 SQL，绕过持久化上下文：加 clearAutomatically = true 自动清缓存，flushAutomatically = true 先刷未落库改动。
     * - 不触发 @PreUpdate/@PostUpdate 回调，也不会自动递增 @Version（乐观锁可能失效）。
     */
    @Test
    @DisplayName("批量 UPDATE")
    void bulkUpdate() {
        int updated = userRepo.bulkUpdateStatus(0, 2);

        assertThat(updated).isEqualTo(2);
        // clearAutomatically 已清缓存，这里能读到最新状态
        assertThat(userCrudRepo.countByStatus(2)).isEqualTo(2);
    }

    /**
     * 场景：批量 DELETE。
     *
     * 注意事项：
     * - 同样需要 @Modifying + 事务，返回受影响行数。
     * - delete from User u 中的 u 只是别名，写法是 delete from User u，不是 delete u from User u。
     * - 不触发生命周期回调，级联删除也不生效（由数据库外键约束负责）。
     */
    @Test
    @DisplayName("批量 DELETE")
    void bulkDelete() {
        int deleted = userRepo.bulkDeleteByStatus(0);

        assertThat(deleted).isEqualTo(2); // charlie、frank
        assertThat(userCrudRepo.count()).isEqualTo(8L);
    }

    /**
     * 场景：NULL 处理（is null / coalesce）。
     *
     * 注意事项：
     * - 判空必须用 is null / is not null，写成 = null 永远匹配不到。
     * - coalesce(u.email, 'N/A') 提供空值兜底；nullif(a, b) 在 a=b 时返回 null。
     */
    @Test
    @DisplayName("NULL 处理")
    void nullHandling() {
        userCrudRepo.save(User.of("noemail", "无邮箱", null, 40, 1));

        assertThat(userRepo.findWithoutEmail()).extracting(User::getUsername)
                .containsExactly("noemail");
        assertThat(userRepo.findEmailOrFallback("noemail")).isEqualTo("N/A");
    }

    /**
     * 造一个订单（含若干明细）用于关联类查询，仅供测试事务内使用。
     */
    private Order createOrder(String username, int... quantities) {
        User user = userCrudRepo.findByUsername(username).orElseThrow();

        Product product = new Product();
        product.setName("P-" + username);
        product.setPrice(new BigDecimal("9.90"));
        productRepo.save(product);

        Order order = new Order();
        order.setUser(user);
        for (int qty : quantities) {
            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(qty);
            order.addItem(item);
        }
        return orderRepo.save(order); // Order.items 配了 cascade = ALL，明细一并保存
    }
}
