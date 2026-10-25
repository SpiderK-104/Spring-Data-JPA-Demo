package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("JPA 分页、排序、Page 与 Slice 测试")
class UserPagingTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("基础分页：按 createdAt 降序取第一页，每页 3 条")
    void testPagingByCreatedAtDesc() {
        Pageable pageable = PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> page = userRepository.findAll(pageable);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(3);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(10);
        assertThat(page.getTotalPages()).isGreaterThanOrEqualTo(4);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.isFirst()).isTrue();
    }

    @Test
    @DisplayName("按状态分页：findByStatus(status=1)")
    void testFindByStatusPaging() {
        Pageable pageable = PageRequest.of(0, 5, Sort.by("age").ascending());
        Page<User> page = userRepository.findByStatus(1, pageable);




        assertThat(page.getContent()).isNotEmpty();
        page.getContent().forEach(u -> assertThat(u.getStatus()).isEqualTo(1));

        List<User> list = page.getContent();
        for (int i = 1; i < list.size(); i++) {
            assertThat(list.get(i).getAge()).isGreaterThanOrEqualTo(list.get(i - 1).getAge());
        }
    }

    @Test
    @DisplayName("派生查询 + 分页：findByNicknameContaining like %keyword%")
    void testSearchByNicknamePaging() {
        Pageable pageable = PageRequest.of(0, 2);
        Page<User> page = userRepository.findByNicknameContaining("丽", pageable);

        assertThat(page.getContent()).extracting(User::getUsername).contains("alice");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("Slice 用法：只关心是否有下一页，不统计总数（更轻）")
    void testSlice() {
        Pageable pageable = PageRequest.of(0, 4);
        Slice<User> slice = userRepository.findByStatusOrderByIdAsc(1, pageable);
        System.out.println(slice.getSize());

        assertThat(slice.getContent()).hasSize(4);
        assertThat(slice.hasNext()).isTrue();
        assertThat(slice.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("多字段排序：先按 status 升序，再按 age 降序")
    void testMultiSort() {
        Sort sort = Sort.by(Sort.Order.asc("status"), Sort.Order.desc("age"));
        Pageable pageable = PageRequest.of(0, 10, sort);
        List<User> users = userRepository.findAll(pageable).getContent();

        assertThat(users).isNotEmpty();
        assertThat(users.get(0).getStatus()).isLessThanOrEqualTo(users.get(users.size() - 1).getStatus());
    }
}
