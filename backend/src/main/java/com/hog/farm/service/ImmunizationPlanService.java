package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.House;
import com.hog.farm.domain.ImmunizationAttempt;
import com.hog.farm.domain.ImmunizationOutcome;
import com.hog.farm.domain.ImmunizationPlan;
import com.hog.farm.domain.ImmunizationTask;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.VaccineRecord;
import com.hog.farm.dto.ImmunizationRequest.ConfirmRequest;
import com.hog.farm.dto.ImmunizationRequest.GenerateRequest;
import com.hog.farm.dto.ImmunizationRequest.Item;
import com.hog.farm.dto.ImmunizationRequest.PlanRequest;
import com.hog.farm.dto.Views.GenerateItemVO;
import com.hog.farm.dto.Views.ImmunizationBatchResultVO;
import com.hog.farm.dto.Views.ImmunizationPlanVO;
import com.hog.farm.dto.Views.ImmunizationResultVO;
import com.hog.farm.dto.Views.ImmunizationTaskVO;
import com.hog.farm.dto.Views.PigImmunizationTraceVO;
import com.hog.farm.dto.Views.VaccineVO;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.ImmunizationAttemptRepository;
import com.hog.farm.repository.ImmunizationOutcomeRepository;
import com.hog.farm.repository.ImmunizationPlanRepository;
import com.hog.farm.repository.ImmunizationTaskRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.VaccineRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 免疫计划、到期任务、漏种补种和剂次唯一性的业务闭环。
 * 任务以猪只为锚点，转栏只更新当前位置；死亡/出栏/等效接种进入可追溯终止态。
 */
@Service
public class ImmunizationPlanService {

    public static final String SNAPSHOT = "SNAPSHOT";
    public static final String RECALCULATE = "RECALCULATE";

    private static final List<String> OPEN_STATUSES = List.of("PENDING", "NEAR_DUE", "MISSED");

    private final ImmunizationPlanRepository planRepository;
    private final ImmunizationTaskRepository taskRepository;
    private final ImmunizationOutcomeRepository outcomeRepository;
    private final ImmunizationAttemptRepository attemptRepository;
    private final VaccineRecordRepository vaccineRepository;
    private final PigRepository pigRepository;
    private final PenRepository penRepository;
    private final HouseRepository houseRepository;

    public ImmunizationPlanService(ImmunizationPlanRepository planRepository,
                                   ImmunizationTaskRepository taskRepository,
                                   ImmunizationOutcomeRepository outcomeRepository,
                                   ImmunizationAttemptRepository attemptRepository,
                                   VaccineRecordRepository vaccineRepository,
                                   PigRepository pigRepository,
                                   PenRepository penRepository,
                                   HouseRepository houseRepository) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.outcomeRepository = outcomeRepository;
        this.attemptRepository = attemptRepository;
        this.vaccineRepository = vaccineRepository;
        this.pigRepository = pigRepository;
        this.penRepository = penRepository;
        this.houseRepository = houseRepository;
    }

    // ---------------- 计划规则 ----------------

    @Transactional(readOnly = true)
    public List<ImmunizationPlanVO> listPlans() {
        LocalDate today = LocalDate.now();
        List<ImmunizationTask> tasks = taskRepository.findAllByOrderByPlannedDateAscIdAsc().stream()
                .filter(task -> OPEN_STATUSES.contains(task.getStatus()))
                .toList();
        Map<Long, long[]> counts = new LinkedHashMap<>();
        for (ImmunizationTask task : tasks) {
            long[] item = counts.computeIfAbsent(task.getPlanId(), key -> new long[3]);
            item[0]++;
            if ("NEAR_DUE".equals(refreshStatus(task, today))) {
                item[1]++;
            }
            if ("MISSED".equals(refreshStatus(task, today))) {
                item[2]++;
            }
        }
        List<ImmunizationPlanVO> result = new ArrayList<>();
        for (ImmunizationPlan plan : planRepository.findAllByOrderByIdAsc()) {
            long[] item = counts.getOrDefault(plan.getId(), new long[3]);
            result.add(toPlanVO(plan, (int) item[0], (int) item[1], (int) item[2]));
        }
        return result;
    }

    @Transactional
    public ImmunizationPlanVO savePlan(Long id, PlanRequest request) {
        validatePlan(request);
        ImmunizationPlan plan = id == null ? new ImmunizationPlan()
                : planRepository.findById(id).orElseThrow(() -> new BizException("免疫计划不存在：" + id));
        if (id == null) {
            String code = StringUtils.hasText(request.getPlanCode()) ? request.getPlanCode().trim() : nextPlanCode();
            if (planRepository.existsByPlanCode(code)) {
                throw new BizException("计划编号已存在：" + code);
            }
            plan.setPlanCode(code);
            plan.setCreatedAt(LocalDateTime.now());
            plan.setStatus("ACTIVE");
        }
        plan.setPlanName(request.getPlanName().trim());
        plan.setVaccineName(request.getVaccineName().trim());
        plan.setVaccineKey(StringUtils.hasText(request.getVaccineKey()) ? request.getVaccineKey().trim()
                : request.getVaccineName().trim());
        plan.setGrowthStage(request.getGrowthStage());
        plan.setFirstDoseAgeDays(request.getFirstDoseAgeDays());
        plan.setDoseIntervalDays(request.getDoseIntervalDays());
        plan.setTotalDoses(request.getTotalDoses());
        plan.setGraceDays(request.getGraceDays());
        plan.setDefaultDoseMl(request.getDefaultDoseMl() == null ? BigDecimal.valueOf(2) : request.getDefaultDoseMl());
        plan.setExecutionMode(request.getExecutionMode());
        if (StringUtils.hasText(request.getStatus())) {
            plan.setStatus("INACTIVE".equals(request.getStatus()) ? "INACTIVE" : "ACTIVE");
        }
        plan.setNote(request.getNote());
        ImmunizationPlan saved = planRepository.save(plan);
        return toPlanVO(saved, 0, 0, 0);
    }

    // ---------------- 任务生成 ----------------

    @Transactional
    public ImmunizationBatchResultVO generate(GenerateRequest request) {
        reconcileOpenTasks();
        GenerateRequest normalized = request == null ? new GenerateRequest() : request;
        int horizon = normalized.getHorizonDays() == null ? 30 : normalized.getHorizonDays();
        if (horizon < 1 || horizon > 180) {
            throw new BizException("生成范围只能是未来 1~180 天");
        }

        List<ImmunizationPlan> plans = normalized.getPlanId() == null
                ? planRepository.findByStatusOrderByIdAsc("ACTIVE")
                : List.of(loadActivePlan(normalized.getPlanId()));
        List<Pig> pigs = pigRepository.findAllByOrderByEarTagAsc();
        Map<Long, Pen> pens = pensById();
        Map<Long, House> houses = housesById();
        LocalDate today = LocalDate.now();
        LocalDate horizonDate = today.plusDays(horizon);

        ImmunizationBatchResultVO result = new ImmunizationBatchResultVO();
        result.setRequestNo("GEN-" + newRequestId());
        for (ImmunizationPlan plan : plans) {
            for (Pig pig : pigs) {
                GenerateItemVO item = evaluateForGeneration(plan, pig, today, horizonDate, pens, houses);
                if (item == null) {
                    continue;
                }
                addGenerateItem(result, item);
            }
        }
        result.setTotalCount(result.getIncludedCount() + result.getDeferredCount()
                + result.getNotApplicableCount() + result.getExistingCount());
        result.setMessage("已生成 " + result.getIncludedCount() + " 个任务；暂缓 "
                + result.getDeferredCount() + " 头；不适用 " + result.getNotApplicableCount()
                + " 头；已有任务 " + result.getExistingCount() + " 个");
        return result;
    }

    private GenerateItemVO evaluateForGeneration(ImmunizationPlan plan, Pig pig, LocalDate today,
                                                  LocalDate horizonDate, Map<Long, Pen> pens,
                                                  Map<Long, House> houses) {
        Pen pen = pig.getPenId() == null ? null : pens.get(pig.getPenId());
        House house = pig.getHouseId() == null ? null : houses.get(pig.getHouseId());
        String currentStage = house == null ? "" : house.getHouseType();
        GenerateItemVO item = baseItem(pig, pen);

        if (Dict.isTerminalPigStatus(pig.getStatus())) {
            item.setCategory("NOT_APPLICABLE");
            item.setCategoryName("不再适用");
            item.setReason("猪只已" + Dict.pigStatusName(pig.getStatus()) + "，未完成任务已转入终止状态");
            return item;
        }

        int completed = equivalentVaccineCount(pig.getId(), plan.getVaccineName());
        if (completed >= plan.getTotalDoses()) {
            item.setCategory("NOT_APPLICABLE");
            item.setCategoryName("不再适用");
            item.setReason("已有 " + completed + " 次「" + plan.getVaccineName() + "」等效接种，达到计划总剂次");
            return item;
        }

        int doseNo = completed + 1;
        LocalDate plannedDate = plannedDate(plan, pig.getBirthDate(), doseNo);
        item.setDoseNo(doseNo);
        item.setPlannedDate(plannedDate.toString());
        if (outcomeRepository.existsByPlanIdAndPigIdAndDoseNo(plan.getId(), pig.getId(), doseNo)) {
            item.setCategory("NOT_APPLICABLE");
            item.setCategoryName("不再适用");
            item.setReason("第 " + doseNo + " 剂已有唯一完成结果");
            return item;
        }
        List<ImmunizationTask> sameDoseTasks = taskRepository
                .findByPlanIdAndPigIdAndDoseNoOrderByIdAsc(plan.getId(), pig.getId(), doseNo);
        ImmunizationTask openExisting = sameDoseTasks.stream()
                .filter(existingTask -> !"TERMINATED".equals(existingTask.getStatus()))
                .findFirst().orElse(null);
        if (openExisting != null) {
            item.setCategory("EXISTING");
            item.setCategoryName("已存在");
            item.setTaskId(openExisting.getId());
            item.setReason("第 " + doseNo + " 剂任务已存在，状态为「"
                    + Dict.immunizationTaskStatusName(openExisting.getStatus()) + "」，不重复生成");
            return item;
        }
        if (sameDoseTasks.stream().anyMatch(existingTask -> "TERMINATED".equals(existingTask.getStatus()))) {
            ImmunizationTask terminated = sameDoseTasks.get(sameDoseTasks.size() - 1);
            item.setCategory("NOT_APPLICABLE");
            item.setCategoryName("不再适用");
            item.setTaskId(terminated.getId());
            item.setReason("第 " + doseNo + " 剂任务已终止：" + terminated.getTerminatedReason());
            return item;
        }

        if ("ISOLATION".equals(currentStage)) {
            item.setCategory("DEFERRED");
            item.setCategoryName("暂缓");
            item.setReason("当前在隔离观察舍，暂缓生成接种任务；移出隔离并重新生成后再纳入");
            return item;
        }
        if (!plan.getGrowthStage().equals(currentStage)) {
            item.setCategory("NOT_APPLICABLE");
            item.setCategoryName("不再适用");
            item.setReason("计划适用「" + Dict.immunizationStageName(plan.getGrowthStage())
                    + "」阶段，当前位于「" + Dict.houseTypeName(currentStage) + "」");
            return item;
        }
        if (plannedDate.isAfter(horizonDate)) {
            // 尚未进入本次生成窗口，不进任何结果分组；页面规则显示生成范围，避免把远期任务解释成暂缓。
            return null;
        }

        ImmunizationTask task = new ImmunizationTask();
        task.setPlanId(plan.getId());
        task.setPigId(pig.getId());
        task.setEarTag(pig.getEarTag());
        task.setDoseNo(doseNo);
        task.setSnapshotPenId(pig.getPenId());
        task.setSnapshotHouseId(pig.getHouseId());
        task.setCurrentPenId(pig.getPenId());
        task.setCurrentHouseId(pig.getHouseId());
        task.setPlannedDate(plannedDate);
        task.setGraceEndDate(plannedDate.plusDays(plan.getGraceDays()));
        task.setGeneratedAt(LocalDateTime.now());
        task.setStatusUpdatedAt(LocalDateTime.now());
        task.setBasisText(buildBasis(plan, pig, completed, pens, houses));
        task.setStatus(refreshStatus(task, today));
        ImmunizationTask saved = taskRepository.save(task);

        item.setCategory("INCLUDED");
        item.setCategoryName("纳入");
        item.setTaskId(saved.getId());
        item.setReason("当前在" + Dict.immunizationStageName(plan.getGrowthStage())
                + "阶段，出生日期 " + pig.getBirthDate() + "，第 " + doseNo + " 剂计划日为 " + plannedDate);
        return item;
    }

    // ---------------- 看板与猪只追溯 ----------------

    @Transactional
    public List<ImmunizationTaskVO> listTasks() {
        reconcileOpenTasks();
        Map<Long, ImmunizationPlan> plans = plansById();
        Map<Long, Pen> pens = pensById();
        Map<Long, House> houses = housesById();
        Map<Long, ImmunizationOutcome> outcomes = new LinkedHashMap<>();
        for (ImmunizationOutcome outcome : outcomeRepository.findAll()) {
            outcomes.put(outcome.getTaskId(), outcome);
        }
        LocalDate today = LocalDate.now();
        List<ImmunizationTaskVO> result = new ArrayList<>();
        for (ImmunizationTask task : taskRepository.findAllByOrderByPlannedDateAscIdAsc()) {
            ImmunizationTask copy = copyTask(task);
            copy.setStatus(refreshStatus(copy, today));
            result.add(toTaskVO(copy, plans.get(task.getPlanId()), pens, houses, outcomes.get(task.getId())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PigImmunizationTraceVO traceForPig(Long pigId) {
        Pig pig = pigRepository.findById(pigId).orElseThrow(() -> new BizException("猪只不存在：" + pigId));
        Map<Long, ImmunizationPlan> plans = plansById();
        Map<Long, Pen> pens = pensById();
        Map<Long, House> houses = housesById();
        LocalDate today = LocalDate.now();
        PigImmunizationTraceVO vo = new PigImmunizationTraceVO();
        for (ImmunizationTask task : taskRepository.findByPigIdOrderByPlannedDateAscIdAsc(pigId)) {
            ImmunizationTask copy = copyTask(task);
            copy.setStatus(refreshStatus(copy, today));
            vo.getTasks().add(toTaskVO(copy, plans.get(task.getPlanId()), pens, houses,
                    outcomeRepository.findByTaskId(task.getId()).orElse(null)));
        }
        for (VaccineRecord record : vaccineRepository.findByPigIdOrderByVaccineDateDescIdDesc(pig.getId())) {
            vo.getVaccines().add(toVaccineVO(record));
        }
        vo.setOpenCount((int) vo.getTasks().stream().filter(item -> OPEN_STATUSES.contains(item.getStatus())).count());
        vo.setMissedCount((int) vo.getTasks().stream().filter(item -> "MISSED".equals(item.getStatus())).count());
        return vo;
    }

    // ---------------- 批量确认 / 补种 ----------------

    @Transactional
    public ImmunizationBatchResultVO confirm(ConfirmRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BizException("请至少选择一个免疫任务");
        }
        String requestNo = StringUtils.hasText(request.getRequestNo()) ? request.getRequestNo().trim()
                : "VAC-" + newRequestId();
        List<ImmunizationAttempt> replay = attemptRepository.findByRequestNoOrderByIdAsc(requestNo);
        if (!replay.isEmpty()) {
            return buildResultFromAttempts(requestNo, "该确认请求已提交，本次为幂等重试，未重复生成免疫记录");
        }

        List<Long> taskIds = request.getItems().stream().map(Item::getTaskId)
                .filter(id -> id != null).distinct().sorted().toList();
        if (taskIds.size() != request.getItems().size()) {
            throw new BizException("任务不能为空且不能重复提交");
        }
        List<ImmunizationTask> tasks = taskRepository.lockByIds(taskIds);
        Map<Long, ImmunizationTask> taskMap = new LinkedHashMap<>();
        for (ImmunizationTask task : tasks) {
            taskMap.put(task.getId(), task);
        }

        reconcileOpenTasks();
        Map<Long, ImmunizationPlan> plans = plansById();
        Map<Long, Pen> pens = pensById();
        Map<Long, House> houses = housesById();
        LocalDate today = LocalDate.now();
        ImmunizationBatchResultVO result = new ImmunizationBatchResultVO();
        result.setRequestNo(requestNo);

        for (Item item : request.getItems()) {
          ImmunizationTask task = taskMap.get(item.getTaskId());
          try {
              if (task == null) {
                  throw new BizException("任务不存在或已被其他操作处理，请刷新名单");
              }
              ImmunizationResultVO row = confirmOne(requestNo, task, plans.get(task.getPlanId()),
                      item, today, pens, houses);
              result.getResults().add(row);
              result.setSuccessCount(result.getSuccessCount() + 1);
              saveAttempt(requestNo, task, "SUCCESS", row.getReason(), row.getOutcomeId(), request.getOperator());
          } catch (BizException ex) {
              ImmunizationResultVO row = failedRow(task, ex.getMessage());
              result.getResults().add(row);
              result.setFailedCount(result.getFailedCount() + 1);
              saveAttempt(requestNo, task, "FAILED", ex.getMessage(), null, request.getOperator());
          }
        }
        result.setTotalCount(result.getResults().size());
        result.setMessage("本次确认成功 " + result.getSuccessCount() + " 头，失败 "
                + result.getFailedCount() + " 头；失败任务保持原状态，可核对后单独重试");
        return result;
    }

    private ImmunizationResultVO confirmOne(String requestNo, ImmunizationTask task, ImmunizationPlan plan,
                                            Item item, LocalDate today, Map<Long, Pen> pens,
                                            Map<Long, House> houses) {
        if (plan == null) {
            throw new BizException("免疫计划不存在，任务 #" + task.getId() + " 不能确认");
        }
        if ("TERMINATED".equals(task.getStatus())) {
            String terminatedReason = StringUtils.hasText(task.getTerminatedReason())
                    ? task.getTerminatedReason() : "任务已终止";
            throw new BizException(terminatedReason);
        }
        if ("COMPLETED".equals(task.getStatus()) || "SUPPLEMENTED".equals(task.getStatus())
                || outcomeRepository.findByTaskId(task.getId()).isPresent()) {
            ImmunizationOutcome existing = outcomeRepository.findByTaskId(task.getId()).orElse(null);
            throw new BizException("第 " + task.getDoseNo() + " 剂已有完成结果"
                    + (existing == null ? "" : "（" + existing.getRequestNo() + "）"));
        }
        if (outcomeRepository.findByPlanIdAndPigIdAndDoseNo(plan.getId(), task.getPigId(), task.getDoseNo()).isPresent()) {
            throw new BizException("同一猪只、计划和剂次只能形成一个完成结果");
        }

        Pig pig = pigRepository.lockById(task.getPigId())
                .orElseThrow(() -> {
                    terminateTask(task, "PIG_MISSING", "猪只台账已不存在，任务终止", today);
                    taskRepository.save(task);
                    return new BizException("猪只不存在：" + task.getPigId());
                });
        if (Dict.isTerminalPigStatus(pig.getStatus())) {
            terminateTask(task, Dict.pigStatusName(pig.getStatus()),
                    "猪只已" + Dict.pigStatusName(pig.getStatus()) + "，任务终止", today);
            taskRepository.save(task);
            throw new BizException("猪只已" + Dict.pigStatusName(pig.getStatus()) + "，任务已终止");
        }

        if (RECALCULATE.equals(plan.getExecutionMode())) {
            House house = pig.getHouseId() == null ? null : houses.get(pig.getHouseId());
            String stage = house == null ? "" : house.getHouseType();
            if ("ISOLATION".equals(stage)) {
                throw new BizException("确认时按现状重算：猪只正在隔离观察，暂缓接种");
            }
            if (!plan.getGrowthStage().equals(stage)) {
                terminateTask(task, "STAGE_MISMATCH",
                        "确认时按现状重算：当前为「" + Dict.houseTypeName(stage)
                                + "」，不再符合计划适用的「" + Dict.immunizationStageName(plan.getGrowthStage()) + "」",
                        today);
                taskRepository.save(task);
                throw new BizException(task.getTerminatedReason());
            }
        }

        LocalDate vaccineDate = item.getVaccineDate() == null ? today : item.getVaccineDate();
        if (vaccineDate.isAfter(today)) {
            throw new BizException("接种日期不能晚于今天");
        }
        BigDecimal doseMl = item.getDoseMl() == null ? plan.getDefaultDoseMl() : item.getDoseMl();
        if (doseMl == null || doseMl.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("免疫剂量必须大于 0");
        }
        if (doseMl.compareTo(Dict.MAX_DOSE_ML) > 0) {
            throw new BizException("单次免疫剂量不得超过 " + Dict.MAX_DOSE_ML + "ml");
        }
        if (vaccineDate.isBefore(task.getPlannedDate())) {
            throw new BizException("接种日期 " + vaccineDate + " 早于原计划日期 " + task.getPlannedDate());
        }

        boolean missed = vaccineDate.isAfter(task.getGraceEndDate());
        Integer overdueDays = (int) ChronoUnit.DAYS.between(task.getPlannedDate(), vaccineDate);
        if (missed && !StringUtils.hasText(item.getSupplementReason())) {
            throw new BizException("已超过宽限期，请记录补种日期与补种原因后才能完成");
        }
        if (!missed && StringUtils.hasText(item.getSupplementReason())) {
            // 非漏种不允许占用补种字段，保证漏种统计语义不被污染。
            throw new BizException("当前仍在宽限期内，无需填写补种原因");
        }

        VaccineRecord vaccine = new VaccineRecord();
        vaccine.setPigId(pig.getId());
        vaccine.setEarTag(pig.getEarTag());
        vaccine.setVaccineName(plan.getVaccineName());
        vaccine.setDoseMl(doseMl);
        vaccine.setVaccineDate(vaccineDate);
        vaccine.setNextDate(task.getDoseNo() < plan.getTotalDoses()
                ? task.getPlannedDate().plusDays(plan.getDoseIntervalDays()) : null);
        vaccine.setOperator(StringUtils.hasText(request.getOperator()) ? request.getOperator() : "周防疫员");
        vaccine.setRemark(item.getRemark());
        vaccine.setCreatedAt(LocalDateTime.now());
        vaccine.setPlanId(plan.getId());
        vaccine.setDoseNo(task.getDoseNo());
        vaccine = vaccineRepository.save(vaccine);

        ImmunizationOutcome outcome = new ImmunizationOutcome();
        outcome.setRequestNo(requestNo);
        outcome.setTaskId(task.getId());
        outcome.setPlanId(plan.getId());
        outcome.setPigId(pig.getId());
        outcome.setDoseNo(task.getDoseNo());
        outcome.setVaccineRecordId(vaccine.getId());
        outcome.setVaccineName(plan.getVaccineName());
        outcome.setDoseMl(doseMl);
        outcome.setVaccineDate(vaccineDate);
        outcome.setResultType(missed ? "SUPPLEMENTED" : "ON_TIME");
        outcome.setSupplementReason(missed ? item.getSupplementReason().trim() : null);
        outcome.setOverdueDays(overdueDays);
        outcome.setOperator(vaccine.getOperator());
        outcome.setRemark(item.getRemark());
        outcome.setCreatedAt(LocalDateTime.now());
        outcome = outcomeRepository.save(outcome);

        vaccine.setOutcomeId(outcome.getId());
        vaccineRepository.save(vaccine);

        task.setStatus(missed ? "SUPPLEMENTED" : "COMPLETED");
        task.setStatusUpdatedAt(LocalDateTime.now());
        task.setActualVaccineDate(vaccineDate);
        task.setOverdueDays(overdueDays);
        task.setSupplementReason(outcome.getSupplementReason());
        taskRepository.save(task);

        ImmunizationResultVO row = new ImmunizationResultVO();
        row.setRequestNo(requestNo);
        row.setTaskId(task.getId());
        row.setPlanId(plan.getId());
        row.setPigId(pig.getId());
        row.setEarTag(pig.getEarTag());
        row.setDoseNo(task.getDoseNo());
        row.setResultStatus("SUCCESS");
        row.setResultStatusName("成功");
        row.setReason(missed ? "漏种已补种，原计划日期和逾期天数保留" : "接种成功");
        row.setOutcomeId(outcome.getId());
        row.setVaccineRecordId(vaccine.getId());
        row.setVaccineDate(vaccineDate.toString());
        row.setPlannedDate(task.getPlannedDate().toString());
        row.setGraceEndDate(task.getGraceEndDate().toString());
        row.setOverdueDays(overdueDays);
        row.setTaskStatus(task.getStatus());
        row.setTaskStatusName(Dict.immunizationTaskStatusName(task.getStatus()));
        row.setSupplementReason(outcome.getSupplementReason());
        return row;
    }

    // ---------------- 转栏 / 死亡 / 出栏 / 手工免疫联动 ----------------

    @Transactional
    public void syncTransfer(Pig pig, Long penId, Long houseId) {
        if (pig == null) {
            return;
        }
        for (ImmunizationTask task : taskRepository.findByPigIdOrderByPlannedDateAscIdAsc(pig.getId())) {
            if (OPEN_STATUSES.contains(task.getStatus())) {
                task.setCurrentPenId(penId);
                task.setCurrentHouseId(houseId);
                task.setStatusUpdatedAt(LocalDateTime.now());
                taskRepository.save(task);
            }
        }
    }

    @Transactional
    public void terminatePigTasks(Pig pig) {
        if (pig == null || !Dict.isTerminalPigStatus(pig.getStatus())) {
            return;
        }
        String code = pig.getStatus();
        String reason = "猪只已" + Dict.pigStatusName(pig.getStatus()) + "，未完成免疫任务终止并保留审计";
        for (ImmunizationTask task : taskRepository.findByPigIdOrderByPlannedDateAscIdAsc(pig.getId())) {
            if (OPEN_STATUSES.contains(task.getStatus())) {
                terminateTask(task, code, reason, LocalDate.now());
                taskRepository.save(task);
            }
        }
    }

    /** 手工登记的等效免疫记录也会关闭对应未完成任务，但不会伪造计划完成结果。 */
    @Transactional
    public void handleExternalVaccine(VaccineRecord record) {
        if (record == null) {
            return;
        }
        int count = equivalentVaccineCount(record.getPigId(), record.getVaccineName());
        for (ImmunizationTask task : taskRepository.findByPigIdOrderByPlannedDateAscIdAsc(record.getPigId())) {
            if (OPEN_STATUSES.contains(task.getStatus()) && task.getDoseNo() <= count) {
                ImmunizationPlan plan = planRepository.findById(task.getPlanId()).orElse(null);
                if (plan != null && plan.getVaccineName().equals(record.getVaccineName())) {
                    terminateTask(task, "EQUIVALENT_VACCINE",
                            "已有等效接种记录：" + record.getVaccineDate() + "「" + record.getVaccineName() + "」",
                            LocalDate.now());
                    taskRepository.save(task);
                }
            }
        }
    }

    @Transactional
    public void reconcileOpenTasks() {
        LocalDate today = LocalDate.now();
        for (ImmunizationTask task : taskRepository.findAllByOrderByPlannedDateAscIdAsc()) {
            if (!OPEN_STATUSES.contains(task.getStatus())) {
                continue;
            }
            Pig pig = pigRepository.findById(task.getPigId()).orElse(null);
            if (pig == null) {
                terminateTask(task, "PIG_MISSING", "猪只台账已不存在，任务终止", today);
                taskRepository.save(task);
                continue;
            }
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                terminateTask(task, pig.getStatus(),
                        "猪只已" + Dict.pigStatusName(pig.getStatus()) + "，未完成免疫任务终止", today);
                taskRepository.save(task);
                continue;
            }
            task.setCurrentPenId(pig.getPenId());
            task.setCurrentHouseId(pig.getHouseId());
            ImmunizationPlan plan = planRepository.findById(task.getPlanId()).orElse(null);
            String currentStage = pig.getHouseId() == null ? ""
                    : houseRepository.findById(pig.getHouseId()).map(House::getHouseType).orElse("");
            if (plan != null && RECALCULATE.equals(plan.getExecutionMode())
                    && !"ISOLATION".equals(currentStage) && !plan.getGrowthStage().equals(currentStage)) {
                terminateTask(task, "STAGE_MISMATCH",
                        "确认时按现状重算：当前已不在「" + Dict.immunizationStageName(plan.getGrowthStage())
                                + "」阶段，任务终止", today);
                taskRepository.save(task);
                continue;
            }
            if (plan != null && equivalentVaccineCount(pig.getId(), plan.getVaccineName()) >= task.getDoseNo()) {
                terminateTask(task, "EQUIVALENT_VACCINE", "已有等效接种记录，无需重复接种", today);
                taskRepository.save(task);
                continue;
            }
            String refreshed = refreshStatus(task, today);
            if (!refreshed.equals(task.getStatus())) {
                task.setStatus(refreshed);
                task.setStatusUpdatedAt(LocalDateTime.now());
                taskRepository.save(task);
            }
        }
    }

    // ---------------- 私有辅助 ----------------

    private void validatePlan(PlanRequest request) {
        if (request == null) {
            throw new BizException("免疫计划内容不能为空");
        }
        if (!StringUtils.hasText(request.getPlanName())) {
            throw new BizException("计划名称不能为空");
        }
        if (!StringUtils.hasText(request.getVaccineName())) {
            throw new BizException("疫苗不能为空");
        }
        if (!Dict.isValidImmunizationStage(request.getGrowthStage())) {
            throw new BizException("适用生长阶段只能是 配怀 / 分娩 / 保育 / 育肥");
        }
        if (!Dict.isValidImmunizationExecutionMode(request.getExecutionMode())) {
            throw new BizException("请明确执行方式：生成时名单快照或确认时按现状重算");
        }
        if (request.getFirstDoseAgeDays() == null || request.getFirstDoseAgeDays() < 0) {
            throw new BizException("首剂日龄必须是 0 或以上");
        }
        if (request.getDoseIntervalDays() == null || request.getDoseIntervalDays() < 1) {
            throw new BizException("剂次间隔必须大于 0");
        }
        if (request.getTotalDoses() == null || request.getTotalDoses() < 1 || request.getTotalDoses() > 10) {
            throw new BizException("总剂次必须在 1~10 之间");
        }
        if (request.getGraceDays() == null || request.getGraceDays() < 0 || request.getGraceDays() > 180) {
            throw new BizException("宽限天数必须在 0~180 之间");
        }
        if (request.getDefaultDoseMl() != null) {
            if (request.getDefaultDoseMl().compareTo(BigDecimal.ZERO) <= 0
                    || request.getDefaultDoseMl().compareTo(Dict.MAX_DOSE_ML) > 0) {
                throw new BizException("默认剂量必须在 0~" + Dict.MAX_DOSE_ML + "ml 之间");
            }
        }
    }

    private ImmunizationPlan loadActivePlan(Long id) {
        ImmunizationPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new BizException("免疫计划不存在：" + id));
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BizException("计划 " + plan.getPlanCode() + " 已停用");
        }
        return plan;
    }

    private LocalDate plannedDate(ImmunizationPlan plan, LocalDate birthDate, int doseNo) {
        return birthDate.plusDays(plan.getFirstDoseAgeDays())
                .plusDays((long) plan.getDoseIntervalDays() * (doseNo - 1));
    }

    private String refreshStatus(ImmunizationTask task, LocalDate today) {
        String current = task.getStatus();
        if ("COMPLETED".equals(current) || "SUPPLEMENTED".equals(current) || "TERMINATED".equals(current)) {
            return current;
        }
        if (today.isAfter(task.getGraceEndDate())) {
            return "MISSED";
        }
        if (!today.isBefore(task.getPlannedDate().minusDays(Dict.IMMUNIZATION_NEAR_DUE_DAYS))) {
            return "NEAR_DUE";
        }
        return "PENDING";
    }

    private int equivalentVaccineCount(Long pigId, String vaccineName) {
        return vaccineRepository.findByPigIdAndVaccineNameOrderByVaccineDateAscIdAsc(pigId, vaccineName).size();
    }

    private void terminateTask(ImmunizationTask task, String code, String reason, LocalDate today) {
        task.setStatus("TERMINATED");
        task.setTerminatedReasonCode(code);
        task.setTerminatedReason(reason);
        task.setTerminatedAt(LocalDateTime.now());
        task.setStatusUpdatedAt(LocalDateTime.now());
    }

    private String buildBasis(ImmunizationPlan plan, Pig pig, int completed,
                              Map<Long, Pen> pens, Map<Long, House> houses) {
        Pen pen = pig.getPenId() == null ? null : pens.get(pig.getPenId());
        House house = pig.getHouseId() == null ? null : houses.get(pig.getHouseId());
        String location = (house == null ? "无栋舍" : house.getHouseCode())
                + " / " + (pen == null ? "无栏位" : pen.getPenCode());
        if (completed == 0) {
            return "出生日期 " + pig.getBirthDate() + " + 首剂日龄 " + plan.getFirstDoseAgeDays()
                    + " 天；当前归属 " + location + "；执行方式：" + Dict.immunizationExecutionModeName(plan.getExecutionMode());
        }
        return "已有 " + completed + " 次等效接种，第 " + (completed + 1) + " 剂按间隔 "
                + plan.getDoseIntervalDays() + " 天推算；当前归属 " + location;
    }

    private void saveAttempt(String requestNo, ImmunizationTask task, String status, String reason,
                             Long outcomeId, String operator) {
        ImmunizationAttempt attempt = new ImmunizationAttempt();
        attempt.setRequestNo(requestNo);
        attempt.setTaskId(task == null ? null : task.getId());
        attempt.setPlanId(task == null ? null : task.getPlanId());
        attempt.setPigId(task == null ? null : task.getPigId());
        attempt.setDoseNo(task == null ? null : task.getDoseNo());
        attempt.setResultStatus(status);
        attempt.setReason(reason);
        attempt.setOutcomeId(outcomeId);
        attempt.setOperator(operator);
        attempt.setCreatedAt(LocalDateTime.now());
        attemptRepository.save(attempt);
    }

    private ImmunizationBatchResultVO buildResultFromAttempts(String requestNo, String message) {
        ImmunizationBatchResultVO result = new ImmunizationBatchResultVO();
        result.setRequestNo(requestNo);
        result.setMessage(message);
        for (ImmunizationAttempt attempt : attemptRepository.findByRequestNoOrderByIdAsc(requestNo)) {
            ImmunizationTask task = attempt.getTaskId() == null ? null
                    : taskRepository.findById(attempt.getTaskId()).orElse(null);
            ImmunizationOutcome outcome = attempt.getOutcomeId() == null ? null
                    : outcomeRepository.findById(attempt.getOutcomeId()).orElse(null);
            ImmunizationResultVO row = new ImmunizationResultVO();
            row.setRequestNo(requestNo);
            row.setTaskId(attempt.getTaskId());
            row.setPlanId(attempt.getPlanId());
            row.setPigId(attempt.getPigId());
            row.setDoseNo(attempt.getDoseNo());
            row.setResultStatus(attempt.getResultStatus());
            row.setResultStatusName("SUCCESS".equals(attempt.getResultStatus()) ? "成功" : "失败");
            row.setReason(attempt.getReason());
            row.setOutcomeId(attempt.getOutcomeId());
            if (task != null) {
                row.setEarTag(task.getEarTag());
                row.setTaskStatus(task.getStatus());
                row.setTaskStatusName(Dict.immunizationTaskStatusName(task.getStatus()));
                row.setPlannedDate(task.getPlannedDate().toString());
                row.setGraceEndDate(task.getGraceEndDate().toString());
                row.setOverdueDays(task.getOverdueDays());
            }
            if (outcome != null) {
                row.setVaccineRecordId(outcome.getVaccineRecordId());
                row.setVaccineDate(outcome.getVaccineDate().toString());
            }
            result.getResults().add(row);
            if ("SUCCESS".equals(attempt.getResultStatus())) {
                result.setSuccessCount(result.getSuccessCount() + 1);
            } else {
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }
        result.setTotalCount(result.getResults().size());
        return result;
    }

    private ImmunizationResultVO failedRow(ImmunizationTask task, String reason) {
        ImmunizationResultVO row = new ImmunizationResultVO();
        row.setResultStatus("FAILED");
        row.setResultStatusName("失败");
        row.setReason(reason);
        if (task != null) {
            row.setTaskId(task.getId());
            row.setPlanId(task.getPlanId());
            row.setPigId(task.getPigId());
            row.setDoseNo(task.getDoseNo());
            row.setEarTag(task.getEarTag());
            row.setTaskStatus(task.getStatus());
            row.setTaskStatusName(Dict.immunizationTaskStatusName(task.getStatus()));
            row.setPlannedDate(task.getPlannedDate().toString());
            row.setGraceEndDate(task.getGraceEndDate().toString());
        }
        return row;
    }

    private GenerateItemVO baseItem(Pig pig, Pen pen) {
        GenerateItemVO item = new GenerateItemVO();
        item.setPigId(pig.getId());
        item.setEarTag(pig.getEarTag());
        item.setPenCode(pen == null ? "" : pen.getPenCode());
        return item;
    }

    private void addGenerateItem(ImmunizationBatchResultVO result, GenerateItemVO item) {
        switch (item.getCategory()) {
            case "INCLUDED" -> {
                result.getIncludedItems().add(item);
                result.setIncludedCount(result.getIncludedCount() + 1);
            }
            case "DEFERRED" -> {
                result.getDeferredItems().add(item);
                result.setDeferredCount(result.getDeferredCount() + 1);
            }
            case "EXISTING" -> {
                result.getExistingItems().add(item);
                result.setExistingCount(result.getExistingCount() + 1);
            }
            default -> {
                result.getNotApplicableItems().add(item);
                result.setNotApplicableCount(result.getNotApplicableCount() + 1);
            }
        }
    }

    private ImmunizationPlanVO toPlanVO(ImmunizationPlan plan, int open, int near, int missed) {
        ImmunizationPlanVO vo = new ImmunizationPlanVO();
        vo.setId(plan.getId());
        vo.setPlanCode(plan.getPlanCode());
        vo.setPlanName(plan.getPlanName());
        vo.setVaccineName(plan.getVaccineName());
        vo.setVaccineKey(plan.getVaccineKey());
        vo.setGrowthStage(plan.getGrowthStage());
        vo.setGrowthStageName(Dict.immunizationStageName(plan.getGrowthStage()));
        vo.setFirstDoseAgeDays(plan.getFirstDoseAgeDays());
        vo.setDoseIntervalDays(plan.getDoseIntervalDays());
        vo.setTotalDoses(plan.getTotalDoses());
        vo.setGraceDays(plan.getGraceDays());
        vo.setDefaultDoseMl(plan.getDefaultDoseMl());
        vo.setExecutionMode(plan.getExecutionMode());
        vo.setExecutionModeName(Dict.immunizationExecutionModeName(plan.getExecutionMode()));
        vo.setExecutionModeText(Dict.immunizationExecutionModeText(plan.getExecutionMode()));
        vo.setStatus(plan.getStatus());
        vo.setStatusName("ACTIVE".equals(plan.getStatus()) ? "启用" : "停用");
        vo.setNote(plan.getNote());
        vo.setCreatedAt(plan.getCreatedAt() == null ? null : plan.getCreatedAt().toString());
        vo.setOpenTaskCount(open);
        vo.setNearDueCount(near);
        vo.setMissedCount(missed);
        return vo;
    }

    private ImmunizationTaskVO toTaskVO(ImmunizationTask task, ImmunizationPlan plan,
                                        Map<Long, Pen> pens, Map<Long, House> houses,
                                        ImmunizationOutcome outcome) {
        ImmunizationTaskVO vo = new ImmunizationTaskVO();
        vo.setId(task.getId());
        vo.setPlanId(task.getPlanId());
        if (plan != null) {
            vo.setPlanCode(plan.getPlanCode());
            vo.setPlanName(plan.getPlanName());
            vo.setVaccineName(plan.getVaccineName());
            vo.setGrowthStage(plan.getGrowthStage());
            vo.setGrowthStageName(Dict.immunizationStageName(plan.getGrowthStage()));
            vo.setExecutionMode(plan.getExecutionMode());
            vo.setExecutionModeName(Dict.immunizationExecutionModeName(plan.getExecutionMode()));
            vo.setTotalDoses(plan.getTotalDoses());
            vo.setDoseMl(plan.getDefaultDoseMl());
        }
        vo.setPigId(task.getPigId());
        vo.setEarTag(task.getEarTag());
        vo.setDoseNo(task.getDoseNo());
        vo.setCurrentPenId(task.getCurrentPenId());
        vo.setSnapshotPenId(task.getSnapshotPenId());
        vo.setCurrentPenCode(codeOfPen(task.getCurrentPenId(), pens));
        vo.setSnapshotPenCode(codeOfPen(task.getSnapshotPenId(), pens));
        vo.setCurrentHouseCode(codeOfHouse(task.getCurrentHouseId(), houses));
        vo.setSnapshotHouseCode(codeOfHouse(task.getSnapshotHouseId(), houses));
        vo.setBasisText(task.getBasisText());
        vo.setPlannedDate(task.getPlannedDate().toString());
        vo.setGraceEndDate(task.getGraceEndDate().toString());
        vo.setStatus(task.getStatus());
        vo.setStatusName(Dict.immunizationTaskStatusName(task.getStatus()));
        vo.setGroup(groupOf(task.getStatus()));
        vo.setGeneratedAt(task.getGeneratedAt() == null ? null : task.getGeneratedAt().toString());
        vo.setActualVaccineDate(task.getActualVaccineDate() == null ? null : task.getActualVaccineDate().toString());
        vo.setOverdueDays(task.getOverdueDays());
        vo.setSupplementReason(task.getSupplementReason());
        vo.setTerminatedReasonCode(task.getTerminatedReasonCode());
        vo.setTerminatedReason(task.getTerminatedReason());
        vo.setTerminatedAt(task.getTerminatedAt() == null ? null : task.getTerminatedAt().toString());
        vo.setTerminal("TERMINATED".equals(task.getStatus()));
        if (outcome != null) {
            vo.setOperator(outcome.getOperator());
            vo.setDoseMl(outcome.getDoseMl());
            vo.setSupplementReason(outcome.getSupplementReason());
            vo.setOverdueDays(outcome.getOverdueDays());
        }
        return vo;
    }

    private String groupOf(String status) {
        return switch (status) {
            case "NEAR_DUE" -> "NEAR_DUE";
            case "MISSED" -> "MISSED";
            case "SUPPLEMENTED", "COMPLETED" -> "DONE";
            case "TERMINATED" -> "TERMINATED";
            default -> "PENDING";
        };
    }

    private VaccineVO toVaccineVO(VaccineRecord record) {
        VaccineVO vo = new VaccineVO();
        vo.setId(record.getId());
        vo.setPigId(record.getPigId());
        vo.setEarTag(record.getEarTag());
        vo.setVaccineName(record.getVaccineName());
        vo.setDoseMl(record.getDoseMl());
        vo.setVaccineDate(record.getVaccineDate() == null ? null : record.getVaccineDate().toString());
        vo.setNextDate(record.getNextDate() == null ? null : record.getNextDate().toString());
        vo.setPlanId(record.getPlanId());
        vo.setDoseNo(record.getDoseNo());
        vo.setOutcomeId(record.getOutcomeId());
        vo.setOperator(record.getOperator());
        vo.setRemark(record.getRemark());
        vo.setOverdue(Boolean.valueOf(record.isOverdue()));
        return vo;
    }

    private ImmunizationTask copyTask(ImmunizationTask source) {
        ImmunizationTask copy = new ImmunizationTask();
        copy.setId(source.getId());
        copy.setPlanId(source.getPlanId());
        copy.setPigId(source.getPigId());
        copy.setEarTag(source.getEarTag());
        copy.setDoseNo(source.getDoseNo());
        copy.setSnapshotPenId(source.getSnapshotPenId());
        copy.setSnapshotHouseId(source.getSnapshotHouseId());
        copy.setCurrentPenId(source.getCurrentPenId());
        copy.setCurrentHouseId(source.getCurrentHouseId());
        copy.setBasisText(source.getBasisText());
        copy.setPlannedDate(source.getPlannedDate());
        copy.setGraceEndDate(source.getGraceEndDate());
        copy.setStatus(source.getStatus());
        copy.setGeneratedAt(source.getGeneratedAt());
        copy.setStatusUpdatedAt(source.getStatusUpdatedAt());
        copy.setActualVaccineDate(source.getActualVaccineDate());
        copy.setOverdueDays(source.getOverdueDays());
        copy.setSupplementReason(source.getSupplementReason());
        copy.setTerminatedReasonCode(source.getTerminatedReasonCode());
        copy.setTerminatedReason(source.getTerminatedReason());
        copy.setTerminatedAt(source.getTerminatedAt());
        return copy;
    }

    private Map<Long, ImmunizationPlan> plansById() {
        Map<Long, ImmunizationPlan> map = new LinkedHashMap<>();
        for (ImmunizationPlan plan : planRepository.findAllByOrderByIdAsc()) {
            map.put(plan.getId(), plan);
        }
        return map;
    }

    private Map<Long, Pen> pensById() {
        Map<Long, Pen> map = new LinkedHashMap<>();
        for (Pen pen : penRepository.findAllByOrderByPenCodeAsc()) {
            map.put(pen.getId(), pen);
        }
        return map;
    }

    private Map<Long, House> housesById() {
        Map<Long, House> map = new LinkedHashMap<>();
        for (House house : houseRepository.findAllByOrderByHouseCodeAsc()) {
            map.put(house.getId(), house);
        }
        return map;
    }

    private String codeOfPen(Long id, Map<Long, Pen> pens) {
        Pen pen = id == null ? null : pens.get(id);
        return pen == null ? "" : pen.getPenCode();
    }

    private String codeOfHouse(Long id, Map<Long, House> houses) {
        House house = id == null ? null : houses.get(id);
        return house == null ? "" : house.getHouseCode();
    }

    private String nextPlanCode() {
        String prefix = "IP-";
        for (int i = 1; i < 1000; i++) {
            String code = prefix + String.format("%03d", i);
            if (!planRepository.existsByPlanCode(code)) {
                return code;
            }
        }
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }
}
