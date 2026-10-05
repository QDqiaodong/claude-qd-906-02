package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一次确认尝试中某头猪的逐头结果。成功和局部失败都保留，便于复核；
 * 失败不会创建免疫记录，也不会占用剂次唯一结果。
 */
@Entity
@Table(name = "immunization_attempt")
@Getter
@Setter
public class ImmunizationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_no", nullable = false, length = 64)
    private String requestNo;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "pig_id")
    private Long pigId;

    @Column(name = "dose_no")
    private Integer doseNo;

    /** SUCCESS / FAILED / SKIPPED */
    @Column(name = "result_status", nullable = false, length = 16)
    private String resultStatus;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "outcome_id")
    private Long outcomeId;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
