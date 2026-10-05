package com.hog.farm.repository;

import com.hog.farm.domain.VaccineRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VaccineRecordRepository extends JpaRepository<VaccineRecord, Long> {

    List<VaccineRecord> findAllByOrderByVaccineDateDescIdDesc();

    List<VaccineRecord> findByPigIdOrderByVaccineDateDescIdDesc(Long pigId);

    List<VaccineRecord> findByPigIdAndVaccineNameOrderByVaccineDateAscIdAsc(Long pigId, String vaccineName);
}
