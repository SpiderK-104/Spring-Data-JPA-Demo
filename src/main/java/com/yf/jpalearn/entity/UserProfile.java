package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户详情表（20-22 讲 · 一对一）。
 * <p>
 * 这里用作<b>单向一对一</b>的非维护方：它<b>不</b>持有 User 的引用；
 * 外键 profile_id 放在 tb_user 上（由 {@link User#getProfile()} 的 @JoinColumn 维护）。
 */
@Entity
@Table(name = "user_profile")
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String bio;

    @Column(length = 30)
    private String phone;

    public UserProfile(String bio, String phone) {
        this.bio = bio;
        this.phone = phone;
    }
}
