package com.hog.farm.repository;

import com.hog.farm.domain.ImmunizationPlan;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmunizationPlanRepository extends JpaRepository<ImmunizationPlan, Long> {

    boolean existsByPlanCode(String planCode);

    List<ImmunizationPlan> findAllByOrderByIdAsc();

    List<ImmunizationPlan> findByStatusOrderByIdAsc(String status);
}
