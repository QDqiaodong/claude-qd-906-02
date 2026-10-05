package com.hog.farm.repository;

import com.hog.farm.domain.ImmunizationAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmunizationAttemptRepository extends JpaRepository<ImmunizationAttempt, Long> {

    List<ImmunizationAttempt> findByRequestNoOrderByIdAsc(String requestNo);
}
