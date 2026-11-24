<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAccount, getBalance, getFundEntries,
  closeAccount, setLimits, getOperations
} from '../api/account'
import { formatAmount } from '../utils/money'
import { accountStatusLabel, accountStatusTagType } from '../utils/status'
import { newRequestId } from '../utils/requestId'

const route = useRoute()
const router = useRouter()
const props = defineProps({ accountId: String })

const loading = ref(false)
const account = ref(null)
const balance = ref(null)

// 资金流水分页（BR-19：只含实际资金事件）
const entries = reactive({ items: [], total: 0, page: 1, pageSize: 20 })
const entriesLoading = ref(false)

// UC-AC-08 设置个人限额
const limitForm = reactive({
  personalSingleLimit: '',
  personalDailyLimit: '',
  requestId: newRequestId()
})
const savingLimits = ref(false)

// UC-AC-09 账户操作记录
const operations = reactive({ items: [], total: 0, page: 1, pageSize: 20 })
const opsLoading = ref(false)

// UC-AC-07 注销账户
const closing = ref(false)

async function loadAccount() {
  loading.value = true
  try {
    const [accRes, balRes] = await Promise.all([
      getAccount(props.accountId),
      getBalance(props.accountId)
    ])
    if (accRes.code === 'SUCCEEDED') {
      account.value = accRes.data
      limitForm.personalSingleLimit = accRes.data.personalSingleLimit ?? ''
      limitForm.personalDailyLimit = accRes.data.personalDailyLimit ?? ''
    } else ElMessage.error(accRes.message || '账户查询失败')

    if (balRes.code === 'SUCCEEDED') balance.value = balRes.data
    else ElMessage.error(balRes.message || '余额查询失败')
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function loadEntries() {
  entriesLoading.value = true
  try {
    const res = await getFundEntries(props.accountId, {
      page: entries.page,
      pageSize: entries.pageSize
    })
    if (res.code === 'SUCCEEDED') {
      entries.items = res.data.items || []
      entries.total = res.data.total || 0
    } else {
      ElMessage.error(res.message || '流水查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    entriesLoading.value = false
  }
}

async function loadOperations() {
  opsLoading.value = true
  try {
    const res = await getOperations(props.accountId, {
      page: operations.page,
      pageSize: operations.pageSize
    })
    if (res.code === 'SUCCEEDED') {
      operations.items = res.data.items || []
      operations.total = res.data.total || 0
    } else {
      ElMessage.error(res.message || '操作记录查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    opsLoading.value = false
  }
}

async function saveLimits() {
  savingLimits.value = true
  try {
    const payload = {
      requestId: limitForm.requestId
    }
    if (limitForm.personalSingleLimit !== '') payload.personalSingleLimit = limitForm.personalSingleLimit
    if (limitForm.personalDailyLimit !== '') payload.personalDailyLimit = limitForm.personalDailyLimit
    const res = await setLimits(props.accountId, payload)
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('限额设置成功')
      limitForm.requestId = newRequestId()
      loadAccount()
    } else {
      ElMessage.error(res.message || '限额设置失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    savingLimits.value = false
  }
}

async function handleClose() {
  try {
    await ElMessageBox.confirm(
      `确认注销账户 ${props.accountId}？该操作不可撤销，且要求余额与占用均为零、无未决业务。`,
      '注销账户',
      { confirmButtonText: '确认注销', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  closing.value = true
  try {
    const res = await closeAccount(props.accountId, { requestId: newRequestId() })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('账户已注销')
      router.push({ name: 'accounts' })
    } else {
      ElMessage.error(res.message || '注销失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    closing.value = false
  }
}

function refresh() {
  loadAccount()
  loadEntries()
  loadOperations()
}

watch(() => props.accountId, refresh)
onMounted(refresh)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">账户详情</h2>
      <el-button :loading="loading" @click="refresh">刷新</el-button>
    </div>

    <div v-loading="loading">
      <!-- 余额三值：同一快照读取，A = B − R（BR-04） -->
      <el-card v-if="balance" class="card-block" shadow="never">
        <template #header>
          <div class="balance-header">
            <span class="account-no">{{ props.accountId }}</span>
            <el-tag :type="accountStatusTagType(balance.status)">
              {{ accountStatusLabel(balance.status) }}
            </el-tag>
            <span class="currency">{{ balance.currency }}</span>
          </div>
        </template>
        <el-row :gutter="16">
          <el-col :span="8">
            <div class="bal-item">
              <div class="bal-label">可用余额 A</div>
              <div class="bal-value main">{{ formatAmount(balance.availableBalance) }}</div>
              <div class="bal-formula">A = B − R</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="bal-item">
              <div class="bal-label">账面余额 B</div>
              <div class="bal-value">{{ formatAmount(balance.bookBalance) }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="bal-item">
              <div class="bal-label">占用金额 R</div>
              <div class="bal-value reserved">{{ formatAmount(balance.reservedAmount) }}</div>
              <div class="bal-formula">处理中交易占用</div>
            </div>
          </el-col>
        </el-row>
        <div v-if="account" class="open-time">开户时间：{{ account.createdAt || '—' }}</div>
      </el-card>

      <!-- UC-AC-08 设置个人限额 -->
      <el-card class="card-block" shadow="never">
        <template #header>个人交易限额</template>
        <el-form :model="limitForm" label-width="140px" style="max-width: 520px">
          <el-form-item label="单笔限额">
            <el-input v-model="limitForm.personalSingleLimit" placeholder="例如 5000.00，留空表示不修改" />
          </el-form-item>
          <el-form-item label="日累计限额">
            <el-input v-model="limitForm.personalDailyLimit" placeholder="例如 20000.00，留空表示不修改" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingLimits" @click="saveLimits">保存限额</el-button>
          </el-form-item>
        </el-form>
        <div class="field-hint">限额只能在本行上限内设置。留空的字段保持原值不变。</div>
      </el-card>

      <!-- UC-AC-09 账户操作记录 -->
      <el-card class="card-block" shadow="never">
        <template #header>账户操作记录</template>
        <el-table :data="operations.items" v-loading="opsLoading" stripe>
          <el-table-column prop="occurredAt" label="时间" min-width="170" />
          <el-table-column prop="action" label="操作" width="120" />
          <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
          <el-table-column prop="operator" label="操作人" width="140" />
        </el-table>
        <el-pagination
          v-model:current-page="operations.page"
          :page-size="operations.pageSize"
          :total="operations.total"
          layout="total, prev, pager, next"
          style="margin-top: 12px; justify-content: flex-end"
          @current-change="loadOperations"
        />
      </el-card>

      <!-- UC-AC-07 注销账户 -->
      <el-card class="card-block" shadow="never">
        <template #header>注销账户</template>
        <div class="danger-zone">
          <span>注销要求余额与占用均为零,且无未决业务。注销后不可恢复。</span>
          <el-button type="danger" plain :loading="closing" @click="handleClose">注销此账户</el-button>
        </div>
      </el-card>

      <!-- 资金流水 -->
      <el-card class="card-block" shadow="never">
        <template #header>资金流水（只含实际资金事件）</template>
        <el-table :data="entries.items" v-loading="entriesLoading" stripe>
          <el-table-column prop="occurredAt" label="时间" min-width="170" />
          <el-table-column prop="tradeNo" label="交易号" min-width="170" show-overflow-tooltip />
          <el-table-column prop="entryRole" label="角色" width="100" />
          <el-table-column label="方向" width="90">
            <template #default="{ row }">
              <span :class="row.direction === 'CREDIT' ? 'in' : 'out'">
                {{ row.direction === 'CREDIT' ? '收入' : '支出' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="130" align="right">
            <template #default="{ row }">
              <span :class="row.direction === 'CREDIT' ? 'in' : 'out'">
                {{ row.direction === 'CREDIT' ? '+' : '-' }}{{ formatAmount(row.amount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="交易后余额" width="130" align="right">
            <template #default="{ row }">{{ formatAmount(row.bookBalanceAfter) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="entries.page"
          :page-size="entries.pageSize"
          :total="entries.total"
          layout="total, prev, pager, next"
          style="margin-top: 12px; justify-content: flex-end"
          @current-change="loadEntries"
        />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-title {
  margin: 8px 0;
}
.balance-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.account-no {
  font-family: Consolas, Menlo, monospace;
}
.currency {
  color: #909399;
  font-size: 13px;
}
.bal-item {
  text-align: center;
  padding: 12px 8px;
  border-radius: 10px;
  background: var(--bg-page);
}
.bal-label {
  font-size: 13px;
  color: var(--text-muted);
}
.bal-value {
  font-size: 22px;
  font-weight: 700;
  margin-top: 6px;
  color: var(--text-main);
  font-variant-numeric: tabular-nums;
}
.bal-value.main {
  font-size: 28px;
  color: var(--brand-accent);
}
.bal-value.reserved {
  color: #b45309;
}
.bal-formula {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
}
.open-time {
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}
.in {
  color: var(--brand-danger);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.out {
  color: var(--brand-success);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.field-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: -8px;
}
.danger-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px dashed #f3c4c4;
  border-radius: 8px;
  background: #fff8f8;
  color: var(--text-muted);
  font-size: 13px;
}
</style>
