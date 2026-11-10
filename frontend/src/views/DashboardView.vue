<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAccounts, openAccount } from '../api/account'
import { formatAmount } from '../utils/money'
import { accountStatusLabel, accountStatusTagType } from '../utils/status'
import { newRequestId } from '../utils/requestId'

const router = useRouter()
const loading = ref(false)
const accounts = ref([])
const opening = ref(false)

// 开户操作级幂等键（BR-02：未注销账户最多 3 个）
const openForm = reactive({ requestId: newRequestId() })

async function load() {
  loading.value = true
  try {
    const res = await listAccounts({ page: 1, pageSize: 50 })
    if (res.code === 'SUCCEEDED') {
      accounts.value = res.data.items || []
    } else {
      ElMessage.error(res.message || '查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function handleOpen() {
  try {
    await ElMessageBox.confirm(
      '将开立一个人民币活期账户（初始余额 0.00），每人最多 3 个未注销账户。',
      '开户确认',
      { confirmButtonText: '确认开户', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  opening.value = true
  try {
    const res = await openAccount({ requestId: openForm.requestId })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success(`开户成功：${res.data.accountId}`)
      // 新一次开户操作换新的 requestId（终态不可重开，BR-11）
      openForm.requestId = newRequestId()
      await load()
    } else {
      // 例：ACCOUNT_LIMIT_REACHED（已满 3 户）
      ElMessage.error(res.message || '开户失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    opening.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">我的账户</h2>
      <el-button type="primary" :loading="opening" @click="handleOpen">开立新账户</el-button>
    </div>

    <el-empty v-if="!loading && accounts.length === 0" description="暂无账户，点击右上角开户" />

    <div v-loading="loading" class="cards">
      <el-card
        v-for="acc in accounts"
        :key="acc.accountId"
        class="account-card"
        shadow="hover"
        @click="router.push({ name: 'account-detail', params: { accountId: acc.accountId } })"
      >
        <div class="card-row-1">
          <span class="account-no">{{ acc.accountId }}</span>
          <el-tag :type="accountStatusTagType(acc.status)" size="small">
            {{ accountStatusLabel(acc.status) }}
          </el-tag>
        </div>
        <div class="card-row-2">
          <span class="balance-label">可用余额</span>
          <span class="balance-value">{{ formatAmount(acc.availableBalance) }}</span>
        </div>
        <div class="card-row-3">
          <span>{{ acc.currency === 'CNY' ? '人民币活期' : acc.currency }}</span>
          <span class="link">查看详情 →</span>
        </div>
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
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.account-card {
  cursor: pointer;
}
.card-row-1 {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.account-no {
  font-family: Consolas, Menlo, monospace;
  color: #606266;
}
.card-row-2 {
  margin: 14px 0 6px;
}
.balance-label {
  font-size: 13px;
  color: #909399;
  margin-right: 8px;
}
.balance-value {
  font-size: 26px;
  font-weight: 700;
  color: #c0392b;
}
.card-row-3 {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #909399;
}
.link {
  color: #409eff;
}
</style>
