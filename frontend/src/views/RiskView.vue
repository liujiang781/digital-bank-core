<script setup>
// 风控管理台（UC-RK-01—04）
// 规则/名单变更走「提交→审批→应用」两阶段，审批人≠提交人，角色由服务端校验。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRules, submitRuleChange, listChanges, approveChange,
  listRiskEntries, submitListChange, disposeAlert, listRiskEvents
} from '../api/ops'
import { newRequestId } from '../utils/requestId'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const tab = ref('rules')

// 规则
const rules = ref([])
const rulesLoading = ref(false)
const editRule = reactive({
  ruleId: '', depositSingleLimit: '', outgoingSingleLimit: '',
  outgoingDailyLimit: '', enabled: true, expectedVersion: 0, reason: ''
})
const ruleDialog = ref(false)

// 审批单
const changes = ref([])
const changesLoading = ref(false)

// 名单
const entries = ref([])
const entriesLoading = ref(false)
const listForm = reactive({
  listType: 'BLACKLIST', subjectType: 'ACCOUNT', subjectId: '',
  reasonCode: '', enabled: true, op: 'UPSERT', reason: ''
})
const listDialog = ref(false)

// 风险记录
const events = ref([])
const eventsLoading = ref(false)
const eventQuery = reactive({ result: '', reasonCode: '', reason: '' })

async function loadRules() {
  rulesLoading.value = true
  try {
    const res = await listRules()
    if (res.code === 'SUCCEEDED') {
      const d = res.data || {}
      rules.value = Array.isArray(d) ? d : (d.items || [])
    } else ElMessage.error(res.message || '规则查询失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
  finally { rulesLoading.value = false }
}

async function loadChanges() {
  changesLoading.value = true
  try {
    const res = await listChanges({ page: 1, pageSize: 50 })
    if (res.code === 'SUCCEEDED') changes.value = res.data?.items || []
    else ElMessage.error(res.message || '审批单查询失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
  finally { changesLoading.value = false }
}

async function loadEntries() {
  entriesLoading.value = true
  try {
    const res = await listRiskEntries({ page: 1, pageSize: 50 })
    if (res.code === 'SUCCEEDED') entries.value = res.data?.items || []
    else ElMessage.error(res.message || '名单查询失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
  finally { entriesLoading.value = false }
}

async function loadEvents() {
  eventsLoading.value = true
  try {
    const params = { page: 1, pageSize: 50, reason: eventQuery.reason || '风控审计查询' }
    if (eventQuery.result) params.result = eventQuery.result
    if (eventQuery.reasonCode) params.reasonCode = eventQuery.reasonCode
    const res = await listRiskEvents(params)
    if (res.code === 'SUCCEEDED') events.value = res.data?.items || []
    else ElMessage.error(res.message || '风险记录查询失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
  finally { eventsLoading.value = false }
}

function openRule(rule) {
  Object.assign(editRule, {
    ruleId: rule.ruleId,
    depositSingleLimit: rule.depositSingleLimit || '',
    outgoingSingleLimit: rule.outgoingSingleLimit || '',
    outgoingDailyLimit: rule.outgoingDailyLimit || '',
    enabled: rule.enabled,
    expectedVersion: rule.ruleVersion || 0,
    reason: ''
  })
  ruleDialog.value = true
}

async function saveRule() {
  try {
    const res = await submitRuleChange(editRule.ruleId, {
      depositSingleLimit: editRule.depositSingleLimit || null,
      outgoingSingleLimit: editRule.outgoingSingleLimit || null,
      outgoingDailyLimit: editRule.outgoingDailyLimit || null,
      enabled: editRule.enabled,
      expectedVersion: editRule.expectedVersion,
      reason: editRule.reason || '规则变更',
      requestId: newRequestId()
    })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('规则变更已提交，等待审批')
      ruleDialog.value = false
      loadRules(); loadChanges()
    } else ElMessage.error(res.message || '提交失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
}

async function approve(row, decision) {
  let note = ''
  try {
    const answer = await ElMessageBox.prompt(
      `${decision === 'APPROVE' ? '通过' : '拒绝'}审批单 ${row.changeId}，请输入意见`,
      '审批', { inputValidator: (v) => !!v?.trim() || '意见不能为空' }
    )
    note = answer.value
  } catch { return }
  try {
    const res = await approveChange(row.changeId, {
      decision, note, requestId: newRequestId()
    })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('审批已提交')
      loadChanges(); loadRules(); loadEntries()
    } else ElMessage.error(res.message || '审批失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
}

async function saveList() {
  try {
    const res = await submitListChange({
      listType: listForm.listType,
      subjectType: listForm.subjectType,
      subjectId: listForm.subjectId,
      reasonCode: listForm.reasonCode,
      enabled: listForm.enabled,
      op: listForm.op,
      reason: listForm.reason || '名单变更',
      requestId: newRequestId()
    })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('名单变更已提交，等待审批')
      listDialog.value = false
      loadEntries(); loadChanges()
    } else ElMessage.error(res.message || '提交失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
}

async function dispose(row) {
  let payload
  try {
    const answer = await ElMessageBox.prompt(
      `处置风险事件 ${row.eventId || row.tradeNo}，请输入处置依据`,
      '处置告警', { inputValidator: (v) => !!v?.trim() || '依据不能为空' }
    )
    payload = answer.value
  } catch { return }
  try {
    const res = await disposeAlert(row.eventId, {
      decision: 'HANDLED', basis: payload, requestId: newRequestId()
    })
    if (res.code === 'SUCCEEDED') { ElMessage.success('告警已处置'); loadEvents() }
    else ElMessage.error(res.message || '处置失败')
  } catch (e) { ElMessage.error(e.message || '网络异常') }
}

function refresh() {
  if (tab.value === 'rules') loadRules()
  else if (tab.value === 'changes') loadChanges()
  else if (tab.value === 'lists') loadEntries()
  else if (tab.value === 'events') { loadEvents(); loadChanges() }
}

onMounted(() => { loadRules(); loadChanges() })
</script>

<template>
  <div class="page">
    <h2 class="page-title">风控管理</h2>

    <el-card class="card-block" shadow="never">
      <el-tabs v-model="tab" @tab-change="refresh">
        <!-- 规则配置 -->
        <el-tab-pane label="风控规则" name="rules">
          <el-table :data="rules" v-loading="rulesLoading" stripe>
            <el-table-column prop="ruleId" label="规则编号" min-width="140" />
            <el-table-column prop="ruleType" label="类型" width="140" />
            <el-table-column prop="depositSingleLimit" label="存款单笔" width="120" />
            <el-table-column prop="outgoingSingleLimit" label="出账单笔" width="120" />
            <el-table-column prop="outgoingDailyLimit" label="出账日累计" width="130" />
            <el-table-column label="启用" width="90">
              <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column prop="ruleVersion" label="版本" width="80" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button v-if="session.canWriteRisk" link type="primary" @click="openRule(row)">变更</el-button>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 审批单 -->
        <el-tab-pane label="变更审批" name="changes">
          <el-table :data="changes" v-loading="changesLoading" stripe>
            <el-table-column prop="changeId" label="审批单号" min-width="150" show-overflow-tooltip />
            <el-table-column prop="changeType" label="类型" width="110" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="submittedBy" label="提交人" width="140" />
            <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="提交时间" min-width="170" />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <template v-if="session.canWriteRisk && (row.status === 'PENDING' || row.status === 'SUBMITTED')">
                  <el-button link type="success" @click="approve(row, 'APPROVE')">通过</el-button>
                  <el-button link type="danger" @click="approve(row, 'REJECT')">拒绝</el-button>
                </template>
                <span v-else>{{ row.approvedBy || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 风险名单 -->
        <el-tab-pane label="风险名单" name="lists">
          <div class="filters">
            <el-button v-if="session.canWriteRisk" type="primary" @click="listDialog = true">新增名单变更</el-button>
            <span v-else class="readonly-hint">当前账号为只读权限，可查看名单但不能变更</span>
          </div>
          <el-table :data="entries" v-loading="entriesLoading" stripe>
            <el-table-column prop="entryId" label="条目编号" min-width="140" />
            <el-table-column prop="listType" label="名单" width="120" />
            <el-table-column prop="subjectType" label="对象类型" width="120" />
            <el-table-column prop="subjectId" label="对象标识" min-width="150" />
            <el-table-column prop="reasonCode" label="原因码" width="140" />
            <el-table-column label="启用" width="90">
              <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column prop="approvedBy" label="审批人" width="140" />
          </el-table>
        </el-tab-pane>

        <!-- 风险记录与告警 -->
        <el-tab-pane label="风险记录" name="events">
          <div class="filters">
            <el-select v-model="eventQuery.result" placeholder="结果" clearable style="width: 140px">
              <el-option value="PASS" label="通过" />
              <el-option value="REJECT" label="拒绝" />
            </el-select>
            <el-input v-model="eventQuery.reasonCode" placeholder="原因码" style="width: 160px" />
            <el-input v-model="eventQuery.reason" placeholder="查询原因（后台必填）" style="width: 220px" />
            <el-button type="primary" @click="loadEvents">查询</el-button>
          </div>
          <el-table :data="events" v-loading="eventsLoading" stripe>
            <el-table-column prop="eventId" label="事件编号" min-width="150" show-overflow-tooltip />
            <el-table-column prop="tradeNo" label="交易号" min-width="150" show-overflow-tooltip />
            <el-table-column prop="ruleId" label="规则" width="120" />
            <el-table-column prop="reasonCode" label="原因码" width="140" />
            <el-table-column prop="result" label="结果" width="100" />
            <el-table-column prop="occurredAt" label="时间" min-width="170" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button v-if="session.canWriteRisk && row.result === 'REJECT'" link type="primary" @click="dispose(row)">处置</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 规则变更对话框 -->
    <el-dialog v-model="ruleDialog" title="变更风控规则" width="520px">
      <el-form :model="editRule" label-width="130px">
        <el-form-item label="存款单笔限额">
          <el-input v-model="editRule.depositSingleLimit" placeholder="两位小数，留空不改" />
        </el-form-item>
        <el-form-item label="出账单笔限额">
          <el-input v-model="editRule.outgoingSingleLimit" placeholder="两位小数，留空不改" />
        </el-form-item>
        <el-form-item label="出账日累计限额">
          <el-input v-model="editRule.outgoingDailyLimit" placeholder="两位小数，留空不改" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editRule.enabled" />
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="editRule.reason" placeholder="必填，写入审计" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRule">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 名单变更对话框 -->
    <el-dialog v-model="listDialog" title="新增名单变更" width="520px">
      <el-form :model="listForm" label-width="110px">
        <el-form-item label="名单类型">
          <el-select v-model="listForm.listType" style="width: 100%">
            <el-option value="BLACKLIST" label="黑名单" />
            <el-option value="WHITELIST" label="白名单" />
          </el-select>
        </el-form-item>
        <el-form-item label="对象类型">
          <el-select v-model="listForm.subjectType" style="width: 100%">
            <el-option value="ACCOUNT" label="账户" />
            <el-option value="CUSTOMER" label="客户" />
          </el-select>
        </el-form-item>
        <el-form-item label="对象标识">
          <el-input v-model="listForm.subjectId" placeholder="账号或客户编号" />
        </el-form-item>
        <el-form-item label="原因码">
          <el-input v-model="listForm.reasonCode" placeholder="例如 RISK_MANUAL" />
        </el-form-item>
        <el-form-item label="操作">
          <el-select v-model="listForm.op" style="width: 100%">
            <el-option value="UPSERT" label="新增或更新" />
            <el-option value="REMOVE" label="移除" />
          </el-select>
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="listForm.reason" placeholder="必填，写入审计" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="listDialog = false">取消</el-button>
        <el-button type="primary" @click="saveList">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  align-items: center;
}
.readonly-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
