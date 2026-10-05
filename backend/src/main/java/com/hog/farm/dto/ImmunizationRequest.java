package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 免疫计划、任务生成和批量确认请求 */
public final class ImmunizationRequest {

    private ImmunizationRequest() {
    }

    @Data
    public static class PlanRequest {
        private String planCode;
        private String planName;
        private String vaccineName;
        private String vaccineKey;
        private String growthStage;
        private Integer firstDoseAgeDays;
        private Integer doseIntervalDays;
        private Integer totalDoses;
        private Integer graceDays;
        private BigDecimal defaultDoseMl;
        private String executionMode;
        private String status;
        private String note;
    }

    @Data
    public static class GenerateRequest {
        private Long planId;
        /** 只生成计划日期在未来 N 天内的下一剂任务；漏种会在状态刷新时标出 */
        private Integer horizonDays = 30;
        private String operator;
    }

    @Data
    public static class ConfirmRequest {
        private String requestNo;
        private List<Item> items = new ArrayList<>();
        private String operator;
    }

    @Data
    public static class Item {
        private Long taskId;
        private BigDecimal doseMl;
        private LocalDate vaccineDate;
        /** 漏种补种必填：记录补种原因 */
        private String supplementReason;
        private String remark;
    }
}
