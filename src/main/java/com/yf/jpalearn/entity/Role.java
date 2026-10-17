package com.yf.jpalearn.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * 角色（25-27 讲 · 多对多）。
 * <p>
 * 本类是<b>被维护方</b>：{@code mappedBy = "roles"} 指向 {@link User#getRoles()}（真正的维护方）。
 * 因此往 {@code users} 里 add 用户<b>不会</b>写中间表，只有维护方（User.roles）的改动才会落库。
 */
@Entity
@Table(name = "role")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // 只按 name 判等，便于 Set 去重演示
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    @EqualsAndHashCode.Include
    private String name;

    /**
     * 被维护方集合：仅供逆向导航/演示，改动它不会同步中间表。
     * 注意：这里不加 cascade，尤其是不要加 REMOVE，避免删除用户时误删角色。
     */
    @ManyToMany(mappedBy = "roles")
    private Set<User> users = new HashSet<>();

    public Role(String name) {
        this.name = name;
    }
}
