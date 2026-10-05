package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 单头猪、单个计划、单个剂次的免疫任务。任务跟随猪只，不跟随栏位；
 * 转栏时更新当前位置并保留原始快照位置，未完成任务不得丢失。
 */
@Entity
@Table(name = "immunization_task")
@Getter
@Setter
public class ImmunizationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    @Column(name = "ear_tag", length = 32)
    private String earTag;

    @Column(name = "dose_no", nullable = false)
    private Integer doseNo;

    /** 生成名单时猪只所在栏位 / 栋舍：快照依据 */
    @Column(name = "snapshot_pen_id")
    private Long snapshotPenId;

    @Column(name = "snapshot_house_id")
    private Long snapshotHouseId;

    /** 当前栏位 / 栋舍：转栏后更新，任务主键不变 */
    @Column(name = "current_pen_id")
    private Long currentPenId;

    @Column(name = "current_house_id")
    private Long currentHouseId;

    /** 形成依据，如出生日期、首剂日龄、上一剂日期等 */
    @Column(name = "basis_text", length = 500)
    private String basisText;

    /** 原计划日期：补种、重算、转栏后都不能覆盖 */
    @Column(name = "planned_date", nullable = false)
    private LocalDate plannedDate;

    @Column(name = "grace_end_date", nullable = false)
    private LocalDate graceEndDate;

    /** PENDING / NEAR_DUE / MISSED / COMPLETED / SUPPLEMENTED / TERMINATED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "status_updated_at")
    private LocalDateTime statusUpdatedAt;

    @Column(name = "actual_vaccine_date")
    private LocalDate actualVaccineDate;

    @Column(name = "overdue_days")
    private Integer overdueDays;

    @Column(name = "supplement_reason", length = 255)
    private String supplementReason;

    @Column(name = "terminated_reason_code", length = 32)
    private String terminatedReasonCode;

    @Column(name = "terminated_reason", length = 255)
    private String terminatedReason;

    @Column(name = "terminated_at")
    private LocalDateTime terminatedAt;
}
