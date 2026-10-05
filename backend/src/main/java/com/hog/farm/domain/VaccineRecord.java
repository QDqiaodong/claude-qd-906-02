package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 免疫记录：一头猪一次疫苗注射。疫苗名称必填、剂量 > 0、免疫日期不能晚于今天。
 */
@Entity
@Table(name = "vaccine_record")
@Getter
@Setter
public class VaccineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 猪只 id */
    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    /** 耳标号（冗余，便于列表直接展示） */
    @Column(name = "ear_tag", length = 32)
    private String earTag;

    /** 疫苗名称：必填 */
    @Column(name = "vaccine_name", nullable = false, length = 64)
    private String vaccineName;

    /** 免疫剂量（ml/头份）：必须 > 0 */
    @Column(name = "dose_ml", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMl;

    /** 免疫日期：不能晚于今天 */
    @Column(name = "vaccine_date", nullable = false)
    private LocalDate vaccineDate;

    /** 下次免疫日期 */
    @Column(name = "next_date")
    private LocalDate nextDate;

    /** 免疫员 */
    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "remark", length = 128)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 完成免疫计划后回写，保证计划结果与实际免疫记录可互查 */
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "dose_no")
    private Integer doseNo;

    @Column(name = "outcome_id")
    private Long outcomeId;

    /** 是否逾期未做下次免疫 */
    @Transient
    public boolean isOverdue() {
        return nextDate != null && nextDate.isBefore(LocalDate.now());
    }
}
