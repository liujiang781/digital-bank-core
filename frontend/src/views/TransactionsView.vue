<script setup>
// 交易记录页（UC-TR-05 列表形态）
// 非终态（PROCESSING/UNKNOWN/RECOVERING）交易可点击"查询状态"触发渠道核对收敛。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTransactions, getTransaction, getReceipt, reconcileTrade, recoverTrade
} from '../api/transaction'
import { listAccounts, getFundEntries } from '../api/account'
import {
  statusLabel,
  statusTagType,
  isFinalStatus,
  businessTypeLabel
} from '../utils/status'
import { formatAmount } from '../utils/money'

const loading = ref(false)
const rows = reactive({ items: [], total: 0, page: 1, pageSize: 20 })
const filters = reactive({ businessType: '', status: '' })
const checking = reactive({})

// UC-TR-07 交易回单
const receiptVisible = ref(false)
const receipt = ref(null)
const receiptLoading = ref(false)

// UC-TR-08 核对 / UC-TR-09 恢复（运营授权，后端校验角色）
const actionLoading = reactive({})

async function showReceipt(tradeNo) {
  receiptLoading.value = true
  try {
    const res = await getReceipt(tradeNo)
    if (res.code === 'SUCCEEDED') {
      receipt.value = res.data
      receiptVisible.value = true
    } else {
      ElMessage.error(res.message || '回单生成失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    receiptLoading.value = false
  }
}

async function doAction(tradeNo, kind) {
  const label = kind === 'reconcile' ? '核对待确认交易' : '重试资金恢复'
  let reason = ''
  try {
    const answer = await ElMessageBox.prompt(
      `${label}：${tradeNo}。请输入操作原因（必填，将写入审计）。`,
      label,
      { confirmButtonText: '确认', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '原因不能为空' }
    )
    reason = answer.value
  } catch {
    return
  }
  actionLoading[tradeNo + kind] = true
  try {
    const api = kind === 'reconcile' ? reconcileTrade : recoverTrade
    const res = await api(tradeNo, { reason, requestId: crypto.randomUUID() })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success(`${label}成功：${res.data?.status || '已提交'}`)
      await load()
    } else {
      ElMessage.error(res.message || `${label}失败`)
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    actionLoading[tradeNo + kind] = false
  }
}

// 后端 transaction 表只记发起方的原单，收款方查不到交易记录。
// 收款方的入账在资金流水里，用「我收到的款项」标签单独展示。
const tab = ref('initiated')

const statusOptions = [
  'ACCEPTED', 'REJECTED', 'PROCESSING', 'UNKNOWN', 'SUCCESS', 'RECOVERING', 'FAILED'
]

// 收款记录：从本人所有账户的资金流水里取入账（CREDIT）记录
async function loadReceived() {
  loading.value = true
  try {
    const accRes = await listAccounts({ page: 1, pageSize: 100 })
    if (accRes.code !== 'SUCCEEDED') {
      ElMessage.error(accRes.message || '账户查询失败')
      return
    }
    const accounts = accRes.data.items || []
    const all = []
    for (const acc of accounts) {
      const res = await getFundEntries(acc.accountId, { page: 1, pageSize: 100 })
      if (res.code === 'SUCCEEDED') {
        for (const e of res.data.items || []) {
          if (e.direction !== 'CREDIT') continue
          all.push({
            tradeNo: e.tradeNo,
            accountId: acc.accountId,
            businessType: e.entryRole === 'TRANSFER_IN' ? 'INTERNAL_TRANSFER' : 'DEPOSIT',
            status: 'SUCCESS',
            amount: e.amount,
            createdAt: e.occurredAt,
            roleLabel: e.entryRole === 'TRANSFER_IN' ? '转入' : '存入'
          })
        }
      }
    }
    let filtered = all
    if (filters.businessType) filtered = filtered.filter((x) => x.businessType === filters.businessType)
    filtered.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
    rows.total = filtered.length
    rows.items = filtered.slice((rows.page - 1) * rows.pageSize, rows.page * rows.pageSize)
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function load() {
  if (tab.value === 'received') return loadReceived()
  loading.value = true
  try {
    const params = {
      page: rows.page,
      pageSize: rows.pageSize
    }
    if (filters.businessType) params.businessType = filters.businessType
    if (filters.status) params.status = filters.status
    const res = await listTransactions(params)
    if (res.code === 'SUCCEEDED') {
      rows.items = res.data.items || []
      rows.total = res.data.total || 0
    } else {
      ElMessage.error(res.message || '查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

// 查询最新状态：客户触发的查询还会发起一次渠道核对（帮助 UNKNOWN/RECOVERING 收敛）
async function checkStatus(tradeNo) {
  checking[tradeNo] = true
  try {
    const res = await getTransaction(tradeNo)
    if (res.code === 'SUCCEEDED' || res.data) {
      ElMessage.success(`${tradeNo}：${statusLabel(res.data?.status)}（${res.message || '已查询'}）`)
      await load()
    } else {
      ElMessage.error(res.message || '查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    checking[tradeNo] = false
  }
}

function search() {
  rows.page = 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h2 class="page-title">交易记录</h2>

    <el-card class="card-block" shadow="never">
      <el-tabs v-model="tab" style="margin-bottom: 12px" @tab-change="search">
        <el-tab-pane label="我发起的交易" name="initiated" />
        <el-tab-pane label="我收到的款项" name="received" />
      </el-tabs>

      <div class="filters">
        <el-select v-model="filters.businessType" placeholder="业务类型" clearable style="width: 160px">
          <el-option value="DEPOSIT" label="存款" />
          <el-option value="WITHDRAWAL" label="取款" />
          <el-option value="INTERNAL_TRANSFER" label="本行转账" />
          <el-option value="EXTERNAL_TRANSFER" label="跨行转账" />
        </el-select>
        <el-select
          v-if="tab === 'initiated'"
          v-model="filters.status"
          placeholder="交易状态"
          clearable
          style="width: 160px"
        >
          <el-option v-for="s in statusOptions" :key="s" :value="s" :label="statusLabel(s)" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table :data="rows.items" v-loading="loading" stripe>
        <el-table-column prop="tradeNo" label="交易号" min-width="170" show-overflow-tooltip />
        <el-table-column label="业务类型" width="110">
          <template #default="{ row }">{{ businessTypeLabel(row.businessType) }}</template>
        </el-table-column>
        <el-table-column label="入账账户" width="130">
          <template #default="{ row }">{{ row.accountId || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态/类型" width="110">
          <template #default="{ row }">
            <el-tag v-if="tab === 'initiated'" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
            <el-tag v-else type="success">{{ row.roleLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">+{{ formatAmount(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <el-table-column v-if="tab === 'initiated'" label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!isFinalStatus(row.status)"
              size="small"
              type="primary"
              :loading="checking[row.tradeNo]"
              @click="checkStatus(row.tradeNo)"
            >
              查询状态
            </el-button>
            <el-button
              v-if="row.status === 'SUCCESS'"
              size="small"
              :loading="receiptLoading && receipt?.tradeNo === row.tradeNo"
              @click="showReceipt(row.tradeNo)"
            >
              回单
            </el-button>
            <el-button
              v-if="row.status === 'UNKNOWN'"
              size="small"
              :loading="actionLoading[row.tradeNo + 'reconcile']"
              @click="doAction(row.tradeNo, 'reconcile')"
            >
              核对
            </el-button>
            <el-button
              v-if="row.status === 'RECOVERING'"
              size="small"
              :loading="actionLoading[row.tradeNo + 'recover']"
              @click="doAction(row.tradeNo, 'recover')"
            >
              恢复
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="rows.page"
        :page-size="rows.pageSize"
        :total="rows.total"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load"
      />
    </el-card>

    <!-- UC-TR-07 交易回单（模拟凭证） -->
    <el-dialog v-model="receiptVisible" title="交易回单（教学模拟）" width="480px">
      <div v-if="receipt" class="receipt-box">
        <div class="receipt-no">{{ receipt.receiptNo }}</div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="交易号">{{ receipt.tradeNo }}</el-descriptions-item>
          <el-descriptions-item label="业务类型">{{ businessTypeLabel(receipt.businessType) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(receipt.status) }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ formatAmount(receipt.amount) }} {{ receipt.currency }}</el-descriptions-item>
          <el-descriptions-item label="账户">{{ receipt.accountId }}</el-descriptions-item>
          <el-descriptions-item label="渠道凭证">{{ receipt.channelEvidenceId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ receipt.completedAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签发时间">{{ receipt.issuedAt || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="receipt.notice" class="receipt-notice">{{ receipt.notice }}</div>
      </div>
      <template #footer>
        <el-button @click="receiptVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.receipt-box {
  padding: 4px 0;
}
.receipt-no {
  text-align: center;
  font-family: Consolas, Menlo, monospace;
  font-size: 15px;
  font-weight: 600;
  color: var(--brand-primary);
  margin-bottom: 14px;
  letter-spacing: 1px;
}
.receipt-notice {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--bg-page);
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
