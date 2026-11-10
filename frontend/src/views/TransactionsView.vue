<script setup>
// 交易记录页（UC-TR-05 列表形态）
// 非终态（PROCESSING/PENDING/RECOVERING）交易可点击"查询状态"触发渠道核对收敛。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listTransactions, getTransaction } from '../api/transaction'
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

const statusOptions = [
  'RECEIVED', 'REJECTED', 'PROCESSING', 'PENDING', 'SUCCEEDED', 'RECOVERING', 'FAILED'
]

async function load() {
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

// 查询最新状态：客户触发的查询还会发起一次渠道核对（帮助 PENDING/RECOVERING 收敛）
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
      <div class="filters">
        <el-select v-model="filters.businessType" placeholder="业务类型" clearable style="width: 160px">
          <el-option value="DEPOSIT" label="存款" />
          <el-option value="WITHDRAWAL" label="取款" />
          <el-option value="INTERNAL_TRANSFER" label="本行转账" />
          <el-option value="EXTERNAL_TRANSFER" label="跨行转账" />
        </el-select>
        <el-select v-model="filters.status" placeholder="交易状态" clearable style="width: 160px">
          <el-option v-for="s in statusOptions" :key="s" :value="s" :label="statusLabel(s)" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table :data="rows.items" v-loading="loading" stripe>
        <el-table-column prop="tradeNo" label="交易号" min-width="170" show-overflow-tooltip />
        <el-table-column label="业务类型" width="110">
          <template #default="{ row }">{{ businessTypeLabel(row.businessType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="受理时间" min-width="170" />
        <el-table-column label="操作" width="130" fixed="right">
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
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
</style>
