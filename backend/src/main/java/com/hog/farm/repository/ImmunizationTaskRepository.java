package com.hog.farm.repository;

import com.hog.farm.domain.ImmunizationTask;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImmunizationTaskRepository extends JpaRepository<ImmunizationTask, Long> {

    List<ImmunizationTask> findAllByOrderByPlannedDateAscIdAsc();

    List<ImmunizationTask> findByPigIdOrderByPlannedDateAscIdAsc(Long pigId);

    List<ImmunizationTask> findByPlanIdAndPigIdAndDoseNoOrderByIdAsc(Long planId, Long pigId, Integer doseNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ImmunizationTask t where t.id in :ids order by t.id asc")
    List<ImmunizationTask> lockByIds(@Param("ids") List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ImmunizationTask> lockById(Long id);
}
