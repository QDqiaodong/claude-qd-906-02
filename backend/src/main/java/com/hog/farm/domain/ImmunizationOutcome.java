package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 免疫任务的唯一成功完成结果。uk_task_result 保证同一猪、同一计划、同一剂次只有一个结果。
 */
@Entity
@Table(name = "immunization_outcome")
@Getter
@Setter
public class ImmunizationOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_no", nullable = false, unique = true, length = 64)
    private String requestNo;

    @Column(name = "task_id", nullable = false, unique = true)
    private Long taskId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    @Column(name = "dose_no", nullable = false)
    private Integer doseNo;

    @Column(name = "vaccine_record_id")
    private Long vaccineRecordId;

    @Column(name = "vaccine_name", length = 64)
    private String vaccineName;

    @Column(name = "dose_ml", precision = 8, scale = 2)
    private BigDecimal doseMl;

    @Column(name = "vaccine_date", nullable = false)
    private LocalDate vaccineDate;

    /** ON_TIME 按期 / SUPPLEMENTED 补种 */
    @Column(name = "result_type", nullable = false, length = 16)
    private String resultType;

    @Column(name = "supplement_reason", length = 255)
    private String supplementReason;

    @Column(name = "overdue_days")
    private Integer overdueDays;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
