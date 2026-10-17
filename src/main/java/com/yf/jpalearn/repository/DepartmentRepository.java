package com.yf.jpalearn.repository;

import com.yf.jpalearn.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
}
