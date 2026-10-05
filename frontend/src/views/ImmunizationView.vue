<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const STAGES = [
  { value: 'GESTATION', label: '配怀' },
  { value: 'FARROWING', label: '分娩' },
  { value: 'NURSERY', label: '保育' },
  { value: 'FINISHING', label: '育肥' }
]
const MODES = [
  { value: 'SNAPSHOT', label: '生成时名单快照' },
  { value: 'RECALCULATE', label: '确认时按现状重算' }
]

const plans = ref([])
const tasks = ref([])
const loading = ref(false)
const selectedPlanId = ref(null)
const horizonDays = ref(30)
const planDialog = ref(false)
const editingPlan = ref(null)
const planForm = ref(emptyPlan())
const resultDialog = ref(false)
const generateResult = ref(null)
const confirmDialog = ref(false)
const confirmForm = ref({ items: [], operator: '周防疫员' })
const confirmSaving = ref(false)
const confirmResult = ref(null)

const groups = [
  { key: 'PENDING', title: '待接种', color: '#29b6f6' },
  { key: 'NEAR_DUE', title: '临期（7 天内）', color: '#ff8f00' },
  { key: 'MISSED', title: '漏种', color: '#ef5350' },
  { key: 'DONE', title: '已补种 / 已完成', color: '#66bb6a' }
]

const openSelectable = computed(() => visibleTasks.value.filter((t) => ['PENDING', 'NEAR_DUE', 'MISSED'].includes(t.status)))
const selectedTasks = computed(() => {
  const visibleById = new Map(visibleTasks.value.map((t) => [t.id, t]))
  return confirmForm.value.items
    .map((item) => visibleById.get(item.taskId))
    .filter((task) => task && (['PENDING', 'NEAR_DUE', 'MISSED'].includes(task.status) || !!confirmResult.value))
})
const visibleTasks = computed(() => selectedPlanId.value
  ? tasks.value.filter((t) => t.planId === selectedPlanId.value)
  : tasks.value)
const groupCounts = computed(() => Object.fromEntries(groups.map((g) => [g.key, visibleTasks.value.filter((t) => t.group === g.key).length])))
const terminatedTasks = computed(() => visibleTasks.value.filter((t) => t.group === 'TERMINATED'))

watch(selectedPlanId, () => {
  const visibleIds = new Set(visibleTasks.value.map((t) => t.id))
  confirmForm.value.items = confirmForm.value.items.filter((item) => visibleIds.has(item.taskId))
})

function emptyPlan () {
  return {
    planName: '', vaccineName: '', growthStage: 'NURSERY', firstDoseAgeDays: 14,
    doseIntervalDays: 21, totalDoses: 2, graceDays: 7, defaultDoseMl: 2,
    executionMode: 'SNAPSHOT', status: 'ACTIVE', note: ''
  }
}

async function load () {
  loading.value = true
  try {
    const [planRows, taskRows] = await Promise.all([api.immunization.plans(), api.immunization.tasks()])
    plans.value = planRows
    tasks.value = taskRows
  } finally {
    loading.value = false
  }
}

function openPlan (plan) {
  editingPlan.value = plan || null
  planForm.value = plan ? {
    planCode: plan.planCode,
    planName: plan.planName,
    vaccineName: plan.vaccineName,
    growthStage: plan.growthStage,
    firstDoseAgeDays: plan.firstDoseAgeDays,
    doseIntervalDays: plan.doseIntervalDays,
    totalDoses: plan.totalDoses,
    graceDays: plan.graceDays,
    defaultDoseMl: plan.defaultDoseMl,
    executionMode: plan.executionMode,
    status: plan.status,
    note: plan.note || ''
  } : emptyPlan()
  planDialog.value = true
}

async function submitPlan () {
  try {
    if (editingPlan.value) {
      await api.immunization.updatePlan(editingPlan.value.id, planForm.value)
      ElMessage.success('免疫计划已更新')
    } else {
      await api.immunization.createPlan(planForm.value)
      ElMessage.success('免疫计划已创建')
    }
    planDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function generateTasks () {
  try {
    generateResult.value = await api.immunization.generate({
      planId: selectedPlanId.value,
      horizonDays: horizonDays.value,
      operator: '周防疫员'
    })
    resultDialog.value = true
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function toggleTask (task) {
  const idx = confirmForm.value.items.findIndex((i) => i.taskId === task.id)
  if (idx >= 0) {
    confirmForm.value.items.splice(idx, 1)
  } else {
    confirmForm.value.items.push({
      taskId: task.id,
      doseMl: task.doseMl || 2,
      vaccineDate: new Date().toISOString().slice(0, 10),
      supplementReason: task.status === 'MISSED' ? '' : undefined,
      remark: ''
    })
  }
}

function openConfirm () {
  const openSelected = selectedTasks.value.filter((t) => ['PENDING', 'NEAR_DUE', 'MISSED'].includes(t.status))
  if (!openSelected.length) {
    ElMessage.warning('请先勾选待接种、临期或漏种任务')
    return
  }
  confirmResult.value = null
  confirmForm.value.items = confirmForm.value.items.filter((item) => openSelected.some((t) => t.id === item.taskId))
  confirmDialog.value = true
}

function itemOf (task) {
  return confirmForm.value.items.find((i) => i.taskId === task.id)
}

async function submitConfirm () {
  confirmSaving.value = true
  try {
    const missedWithoutReason = selectedTasks.value.some((task) => {
      const item = itemOf(task)
      return task.status === 'MISSED' && !(item?.supplementReason || '').trim()
    })
    if (missedWithoutReason) {
      ElMessage.warning('漏种任务必须填写补种原因')
      return
    }
    confirmResult.value = await api.immunization.confirm({
      ...confirmForm.value,
      requestNo: confirmResult.value?.requestNo
    })
    const successIds = new Set(confirmResult.value.results.filter((r) => r.resultStatus === 'SUCCESS').map((r) => r.taskId))
    confirmForm.value.items = confirmForm.value.items.filter((item) => !successIds.has(item.taskId))
    if (confirmResult.value.failedCount === 0) {
      confirmForm.value.items = []
    }
    ElMessage.success(confirmResult.value.message)
    if (confirmResult.value.failedCount === 0) {
      confirmDialog.value = false
      confirmForm.value = { items: [], operator: '周防疫员' }
    }
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    confirmSaving.value = false
  }
}

function taskList (key) {
  return visibleTasks.value.filter((t) => t.group === key)
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="view-head">
      <span class="view-title">免疫计划看板</span>
      <span class="view-tip">按计划生成任务；转栏不断线，漏种需补种原因，同一猪/计划/剂次结果唯一</span>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      <template #title>
        两种执行口径会在每张任务卡上保留：<b>生成时名单快照</b>冻结生成时猪只与栏位；
        <b>确认时按现状重算</b>保留原计划日期，但确认时复核当前生长阶段。隔离暂缓、阶段不符终止、死亡/出栏/已有等效接种均给出原因。
      </template>
    </el-alert>

    <div class="chip-bar">
      <el-select v-model="selectedPlanId" clearable placeholder="全部启用计划" size="small" style="width: 240px">
        <el-option v-for="p in plans" :key="p.id" :label="`${p.planCode} · ${p.planName}`" :value="p.id" />
      </el-select>
      <span class="chip">生成范围</span>
      <el-input-number v-model="horizonDays" :min="1" :max="180" size="small" />
      <span class="chip">天</span>
      <el-button size="small" type="primary" @click="generateTasks">生成 / 刷新到期任务</el-button>
      <el-button size="small" type="success" style="margin-left: auto" @click="openPlan">新建免疫计划</el-button>
    </div>

    <div class="panel" style="margin-bottom: 12px">
      <div class="section-title">计划规则（{{ plans.length }}）</div>
      <el-table :data="plans" size="small">
        <el-table-column prop="planCode" label="编号" width="115" />
        <el-table-column prop="planName" label="计划" min-width="150" />
        <el-table-column prop="vaccineName" label="疫苗" min-width="140" />
        <el-table-column label="阶段" width="70"><template #default="{ row }">{{ row.growthStageName }}</template></el-table-column>
        <el-table-column label="首剂/间隔/总剂" width="120">
          <template #default="{ row }">{{ row.firstDoseAgeDays }} / {{ row.doseIntervalDays }} / {{ row.totalDoses }}</template>
        </el-table-column>
        <el-table-column prop="graceDays" label="宽限" width="65" />
        <el-table-column label="执行口径" width="150"><template #default="{ row }">{{ row.executionModeName }}</template></el-table-column>
        <el-table-column label="任务" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="warning">{{ row.nearDueCount }} 临期</el-tag>
            <el-tag size="small" type="danger" style="margin-left:4px">{{ row.missedCount }} 漏种</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" fixed="right">
          <template #default="{ row }"><el-button size="small" link @click="openPlan(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div style="display:grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px;">
      <div v-for="g in groups" :key="g.key" class="panel imm-column">
        <div class="section-title">
          <i :style="`background:${g.color}`"></i>{{ g.title }}
          <b>{{ groupCounts[g.key] || 0 }}</b>
        </div>
        <div v-for="task in taskList(g.key)" :key="task.id" class="task-card" :class="{ missed: task.status === 'MISSED', selected: itemOf(task) }">
            <el-checkbox
              v-if="['PENDING','NEAR_DUE','MISSED'].includes(task.status)"
              :model-value="!!itemOf(task)"
              @change="toggleTask(task)"
            >选择</el-checkbox>
            <div class="task-title">{{ task.earTag }} · 第 {{ task.doseNo }}/{{ task.totalDoses }} 剂</div>
            <div class="task-line">{{ task.vaccineName }} · {{ task.planCode }}</div>
            <div class="task-line">计划：<b>{{ task.plannedDate }}</b> ｜ 宽限至：{{ task.graceEndDate }}</div>
            <div class="task-line">当前：{{ task.currentHouseCode || '—' }} / {{ task.currentPenCode || '无栏位' }}
              <span v-if="task.snapshotPenCode !== task.currentPenCode" class="moved">已转栏</span>
            </div>
            <div class="task-line">快照：{{ task.snapshotHouseCode || '—' }} / {{ task.snapshotPenCode || '无栏位' }}</div>
            <div class="task-basis">{{ task.basisText }}</div>
            <div v-if="task.status === 'MISSED'" class="task-warn">超过宽限期，需登记补种日期与原因</div>
            <div v-if="task.group === 'DONE'" class="task-done">
              实际：{{ task.actualVaccineDate }}
              <span v-if="task.overdueDays">；逾期 {{ task.overdueDays }} 天</span>
              <div v-if="task.supplementReason">补种原因：{{ task.supplementReason }}</div>
            </div>
          </div>
        <div v-if="!taskList(g.key).length" class="empty-tip">暂无任务</div>
      </div>
    </div>

    <div class="panel" style="margin-top: 12px">
      <div class="section-title">可追溯终止（{{ terminatedTasks.length }}）</div>
      <el-table :data="terminatedTasks" size="small" max-height="220">
        <el-table-column prop="earTag" label="耳标" width="130" />
        <el-table-column prop="vaccineName" label="疫苗" min-width="130" />
        <el-table-column label="剂次" width="70"><template #default="{ row }">{{ row.doseNo }}/{{ row.totalDoses }}</template></el-table-column>
        <el-table-column prop="plannedDate" label="原计划日期" width="110" />
        <el-table-column prop="currentPenCode" label="最后栏位" width="110" />
        <el-table-column prop="terminatedReason" label="终止原因" min-width="260" />
        <el-table-column prop="terminatedAt" label="终止时间" width="170" />
      </el-table>
    </div>

    <el-button
      v-if="selectedTasks.length"
      class="imm-confirm-fab"
      type="primary"
      size="large"
      @click="openConfirm"
    >确认接种（{{ selectedTasks.length }} 头）</el-button>

    <!-- 计划编辑 -->
    <el-dialog v-model="planDialog" :title="editingPlan ? '编辑免疫计划' : '新建免疫计划'" width="620px">
      <el-form :model="planForm" label-width="125px">
        <el-form-item label="计划名称" required><el-input v-model="planForm.planName" /></el-form-item>
        <el-form-item label="疫苗" required>
          <el-select v-model="planForm.vaccineName" filterable allow-create style="width:100%">
            <el-option v-for="name in ['猪瘟活疫苗','口蹄疫 O 型灭活疫苗','伪狂犬病活疫苗','圆环病毒灭活疫苗','高致病性蓝耳病活疫苗','细小病毒灭活疫苗','猪传染性胃肠炎二联苗','支原体灭活疫苗']" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用生长阶段" required>
          <el-radio-group v-model="planForm.growthStage">
            <el-radio-button v-for="s in STAGES" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="首剂日龄" required><el-input-number v-model="planForm.firstDoseAgeDays" :min="0" :max="500" /></el-form-item>
        <el-form-item label="剂次间隔" required><el-input-number v-model="planForm.doseIntervalDays" :min="1" :max="365" /></el-form-item>
        <el-form-item label="总剂次" required><el-input-number v-model="planForm.totalDoses" :min="1" :max="10" /></el-form-item>
        <el-form-item label="宽限天数" required><el-input-number v-model="planForm.graceDays" :min="0" :max="180" /></el-form-item>
        <el-form-item label="默认剂量 ml"><el-input-number v-model="planForm.defaultDoseMl" :min="0.1" :max="20" :precision="2" /></el-form-item>
        <el-form-item label="执行口径" required>
          <el-radio-group v-model="planForm.executionMode">
            <el-radio v-for="m in MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert :title="planForm.executionMode === 'SNAPSHOT' ? '名单和归属栏位在生成时快照；转栏只更新当前栏位，不改变任务适用性。' : '确认时读取猪只当前栏位/阶段；隔离暂缓、阶段不符任务终止，原计划日期不变。'" type="info" :closable="false" />
        <el-form-item label="备注" style="margin-top:10px"><el-input v-model="planForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 生成解释 -->
    <el-dialog v-model="resultDialog" title="任务生成依据与名单解释" width="760px">
      <div v-if="generateResult">
        <el-alert :title="generateResult.message" type="success" :closable="false" style="margin-bottom:10px" />
        <el-tabs>
          <el-tab-pane :label="`纳入（${generateResult.includedCount}）`">
            <gen-table :rows="generateResult.includedItems" />
          </el-tab-pane>
          <el-tab-pane :label="`暂缓（${generateResult.deferredCount}）`">
            <gen-table :rows="generateResult.deferredItems" />
          </el-tab-pane>
          <el-tab-pane :label="`不再适用（${generateResult.notApplicableCount}）`">
            <gen-table :rows="generateResult.notApplicableItems" />
          </el-tab-pane>
          <el-tab-pane :label="`已有任务（${generateResult.existingCount}）`">
            <gen-table :rows="generateResult.existingItems" />
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>

    <!-- 批量确认 -->
    <el-dialog v-model="confirmDialog" :title="`确认接种（${selectedTasks.length} 头）`" width="820px">
      <el-alert v-if="confirmResult" :title="confirmResult.message" :type="confirmResult.failedCount ? 'warning' : 'success'" :closable="false" style="margin-bottom:10px" />
      <el-table :data="confirmResult ? selectedTasks.filter((t) => confirmResult.results.some((r) => r.taskId === t.id && r.resultStatus === 'FAILED')) : selectedTasks" size="small" max-height="420">
        <el-table-column prop="earTag" label="耳标" width="130" />
        <el-table-column prop="vaccineName" label="疫苗" min-width="130" />
        <el-table-column label="剂次" width="65"><template #default="{ row }">{{ row.doseNo }}/{{ row.totalDoses }}</template></el-table-column>
        <el-table-column label="计划/宽限" width="190">
          <template #default="{ row }">{{ row.plannedDate }} 至 {{ row.graceEndDate }}</template>
        </el-table-column>
        <el-table-column label="剂量" width="110">
          <template #default="{ row }"><el-input-number v-model="itemOf(row).doseMl" :min="0.1" :max="20" :precision="2" size="small" /></template>
        </el-table-column>
        <el-table-column label="接种日期" width="145">
          <template #default="{ row }"><el-date-picker v-model="itemOf(row).vaccineDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:130px" /></template>
        </el-table-column>
        <el-table-column label="补种原因" min-width="180">
          <template #default="{ row }">
            <el-input v-if="row.status === 'MISSED'" v-model="itemOf(row).supplementReason" placeholder="漏种必填" size="small" />
            <span v-else class="task-line">按期接种</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="confirmResult" style="margin-top:10px">
        <div v-for="r in confirmResult.results" :key="r.taskId" :style="`font-size:12px;margin:4px 0;color:${r.resultStatus === 'SUCCESS' ? 'var(--hog-green)' : 'var(--hog-red)'}`">
          {{ r.earTag || ('猪#' + r.pigId) }}：{{ r.resultStatusName }} - {{ r.reason }}
        </div>
      </div>
      <template #footer>
        <el-button @click="confirmDialog = false">关闭</el-button>
        <el-button type="primary" :loading="confirmSaving" @click="submitConfirm">
          {{ confirmResult?.failedCount ? '重试失败项' : '保存确认结果' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { defineComponent, h } from 'vue'
import { ElTable, ElTableColumn } from 'element-plus'
const GenTable = defineComponent({
  props: { rows: { type: Array, default: () => [] } },
  setup (props) {
    return () => h(ElTable, { data: props.rows, size: 'small', maxHeight: 360 }, () => [
      h(ElTableColumn, { prop: 'earTag', label: '耳标', width: '130' }),
      h(ElTableColumn, { prop: 'penCode', label: '栏位', width: '120' }),
      h(ElTableColumn, { label: '剂次', width: '70' }, { default: ({ row }) => row.doseNo || '—' }),
      h(ElTableColumn, { prop: 'plannedDate', label: '计划日期', width: '110' }),
      h(ElTableColumn, { prop: 'reason', label: '形成依据 / 原因', minWidth: '300' })
    ])
  }
})
export default { components: { GenTable } }
</script>

<style scoped>
.imm-column { min-height: 360px; }
.imm-column .section-title i { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:6px; }
.imm-column .section-title b { margin-left:auto; }
.task-card { border:1px solid var(--line); border-radius:10px; padding:10px; margin:8px 0; background:#fff; }
.task-card.selected { border-color:var(--hog-green); box-shadow:0 0 0 2px rgba(102,187,106,.15); }
.task-card.missed { border-color:var(--hog-red); background:#fff7f7; }
.task-title { font-weight:700; margin:4px 0; }
.task-line { font-size:12px; color:var(--ink-dim); line-height:1.7; }
.task-basis { font-size:12px; color:#607d8b; background:#f5f7fa; border-radius:6px; padding:6px; margin-top:6px; }
.task-warn { color:var(--hog-red); font-size:12px; margin-top:6px; font-weight:700; }
.task-done { color:var(--hog-green); font-size:12px; margin-top:6px; }
.moved { color:#fff; background:var(--hog-primary); border-radius:4px; padding:0 4px; margin-left:4px; }
.imm-confirm-fab { position:fixed; right:28px; bottom:96px; z-index:50; box-shadow:0 8px 24px rgba(0,0,0,.18); }
</style>
