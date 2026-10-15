package com.yf.jpalearn.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement  // 启用声明式事务（@Transactional）
@EnableJpaRepositories(basePackages = "com.yf.jpalearn.repository")  // 扫描 Repository 接口
@EnableJpaAuditing           // 启用 JPA 审计（@CreatedDate、@LastModifiedDate）
public class JpaConfig {
}