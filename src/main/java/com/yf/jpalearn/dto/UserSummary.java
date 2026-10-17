package com.yf.jpalearn.dto;

/**
 * JPQL 构造器表达式（constructor projection）的目标类型。
 * <p>
 * JPQL 的 {@code select new ...} 需要一个"全限定类名 + 参数顺序/类型完全匹配"的构造器；
 * record 的规范构造器天然满足，因此无需额外写 getter / setter。
 */
public record UserSummary(String username, Integer age) {
}
