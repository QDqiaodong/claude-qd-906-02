package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 免疫计划规则：疫苗 + 适用生长阶段 + 首剂日龄 + 剂次间隔 + 宽限天数。
 * executionMode 明确任务执行时采用生成时名单快照，还是确认接种时按猪只现状重算。
 */
@Entity
@Table(name = "immunization_plan")
@Getter
@Setter
public class ImmunizationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_code", nullable = false, unique = true, length = 32)
    private String planCode;

    @Column(name = "plan_name", nullable = false, length = 64)
    private String planName;

    @Column(name = "vaccine_name", nullable = false, length = 64)
    private String vaccineName;

    /** 等效免疫标识；当前同疫苗同名即视为等效，保留字段用于后续疫苗抗原归并 */
    @Column(name = "vaccine_key", nullable = false, length = 64)
    private String vaccineKey;

    /** GESTATION / FARROWING / NURSERY / FINISHING */
    @Column(name = "growth_stage", nullable = false, length = 24)
    private String growthStage;

    @Column(name = "first_dose_age_days", nullable = false)
    private Integer firstDoseAgeDays;

    @Column(name = "dose_interval_days", nullable = false)
    private Integer doseIntervalDays;

    @Column(name = "total_doses", nullable = false)
    private Integer totalDoses;

    @Column(name = "grace_days", nullable = false)
    private Integer graceDays;

    @Column(name = "default_dose_ml", precision = 8, scale = 2)
    private BigDecimal defaultDoseMl;

    /** SNAPSHOT 生成时名单快照 / RECALCULATE 确认时按现状重算 */
    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    /** ACTIVE / INACTIVE */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "note", length = 255)
    private String note;
}
