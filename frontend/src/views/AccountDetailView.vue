<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getAccount, getBalance, getFundEntries } from '../api/account'
import { formatAmount } from '../utils/money'
import { accountStatusLabel, accountStatusTagType } from '../utils/status'

const route = useRoute()
const props = defineProps({ accountId: String })

const loading = ref(false)
const account = ref(null)
const balance = ref(null)

// 资金流水分页（BR-19：只含实际资金事件）
const entries = reactive({ items: [], total: 0, page: 1, pageSize: 20 })
const entriesLoading = ref(false)

async function loadAccount() {
  loading.value = true
  try {
    const [accRes, balRes] = await Promise.all([
      getAccount(props.accountId),
      getBalance(props.accountId)
    ])
    if (accRes.code === 'SUCCEEDED') account.value = accRes.data
    else ElMessage.error(accRes.message || '账户查询失败')

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

function refresh() {
  loadAccount()
  loadEntries()
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
        <div v-if="account" class="open-time">开户时间：{{ account.openedAt || '—' }}</div>
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
}
.bal-label {
  font-size: 13px;
  color: #909399;
}
.bal-value {
  font-size: 22px;
  font-weight: 700;
  margin-top: 6px;
}
.bal-value.main {
  font-size: 28px;
  color: #c0392b;
}
.bal-value.reserved {
  color: #e6a23c;
}
.bal-formula {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 4px;
}
.open-time {
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}
.in {
  color: #c0392b;
}
.out {
  color: #27ae60;
}
</style>
