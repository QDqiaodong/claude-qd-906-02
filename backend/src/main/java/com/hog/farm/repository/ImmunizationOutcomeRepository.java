package com.hog.farm.repository;

import com.hog.farm.domain.ImmunizationOutcome;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmunizationOutcomeRepository extends JpaRepository<ImmunizationOutcome, Long> {

    Optional<ImmunizationOutcome> findByRequestNo(String requestNo);

    Optional<ImmunizationOutcome> findByTaskId(Long taskId);

    Optional<ImmunizationOutcome> findByPlanIdAndPigIdAndDoseNo(Long planId, Long pigId, Integer doseNo);

    List<ImmunizationOutcome> findByPigIdOrderByVaccineDateDescIdDesc(Long pigId);

    boolean existsByPlanIdAndPigIdAndDoseNo(Long planId, Long pigId, Integer doseNo);
}
