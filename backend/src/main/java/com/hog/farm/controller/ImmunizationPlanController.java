package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.dto.ImmunizationRequest.ConfirmRequest;
import com.hog.farm.dto.ImmunizationRequest.GenerateRequest;
import com.hog.farm.dto.ImmunizationRequest.PlanRequest;
import com.hog.farm.dto.Views.ImmunizationBatchResultVO;
import com.hog.farm.dto.Views.ImmunizationPlanVO;
import com.hog.farm.dto.Views.ImmunizationTaskVO;
import com.hog.farm.dto.Views.PigImmunizationTraceVO;
import com.hog.farm.service.ImmunizationPlanService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 免疫计划、到期任务、漏种补种看板 */
@RestController
@RequestMapping("/api/immunization")
public class ImmunizationPlanController {

    private final ImmunizationPlanService service;

    public ImmunizationPlanController(ImmunizationPlanService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public ApiResponse<List<ImmunizationPlanVO>> plans() {
        return ApiResponse.success(service.listPlans());
    }

    @PostMapping("/plans")
    public ApiResponse<ImmunizationPlanVO> createPlan(@RequestBody PlanRequest request) {
        return ApiResponse.success("免疫计划已创建", service.savePlan(null, request));
    }

    @PutMapping("/plans/{id}")
    public ApiResponse<ImmunizationPlanVO> updatePlan(@PathVariable Long id, @RequestBody PlanRequest request) {
        return ApiResponse.success("免疫计划已更新", service.savePlan(id, request));
    }

    @PostMapping("/tasks/generate")
    public ApiResponse<ImmunizationBatchResultVO> generate(@RequestBody(required = false) GenerateRequest request) {
        return ApiResponse.success("免疫任务已生成", service.generate(request));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<ImmunizationTaskVO>> tasks() {
        return ApiResponse.success(service.listTasks());
    }

    @PostMapping("/confirm")
    public ApiResponse<ImmunizationBatchResultVO> confirm(@RequestBody ConfirmRequest request) {
        return ApiResponse.success("免疫确认已处理", service.confirm(request));
    }

    @GetMapping("/pig/{pigId}")
    public ApiResponse<PigImmunizationTraceVO> pigTrace(@PathVariable Long pigId) {
        return ApiResponse.success(service.traceForPig(pigId));
    }
}
