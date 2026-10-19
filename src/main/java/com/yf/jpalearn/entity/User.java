package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tb_user")
@EntityListeners(AuditingEntityListener.class)
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "nickname", length = 50)
    private String nickname;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "age")
    private Integer age;

    @Column(name = "status")
    private Integer status; // 1: 正常 0: 禁用

    @Version
    @Column(name = "version")
    private Long version;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 单向一对一（21 讲）：User 持有 UserProfile，外键 profile_id 在 tb_user 上；
     * UserProfile 不反向引用，所以只能 User -> Profile。
     */
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "profile_id", unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private UserProfile profile;

    /**
     * 多对多（25-27 讲）的维护方：@JoinTable 定义中间表 user_role，改动会写中间表。
     * - 用 Set 而非 List：避免重复元素导致中间表重复/主键冲突。
     * - cascade 只给 PERSIST/MERGE，绝不给 REMOVE：否则删用户会连带删角色。
     * - 默认 LAZY；这里排除出 toString/equals，避免与 Role.users 互相递归。
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Role> roles = new HashSet<>();

    // 便于测试中构造“瞬时态”（Transient）对象
    public static User of(String username, String nickname, String email, Integer age, Integer status) {
        return User.builder()
                .username(username)
                .nickname(nickname)
                .email(email)
                .age(age)
                .status(status)
                .build();
    }
}