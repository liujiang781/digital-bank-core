<script setup>
// 交易结果面板：展示 tradeNo、当前状态、后端 message 与 traceId。
// 非终态（RECEIVED/PROCESSING/PENDING/RECOVERING）支持"查询最新状态"；
// 成功交易可跳转账户流水；网络失败未送达时提示重试（复用原 requestId）。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  statusLabel,
  statusTagType,
  isFinalStatus
} from '../utils/status'

const props = defineProps({
  // 统一响应体 { code, message, data: { tradeNo, status, failureReason }, traceId }
  result: { type: Object, default: null },
  networkFailed: { type: Boolean, default: false },
  polling: { type: Boolean, default: false }
})
const emit = defineEmits(['retry', 'refresh'])

const router = useRouter()
const tradeNo = computed(() => props.result?.data?.tradeNo)
const status = computed(() => props.result?.data?.status || props.result?.code)
const open = computed(() => status.value && !isFinalStatus(status.value))
const succeeded = computed(() => status.value === 'SUCCEEDED')

function goFundEntries() {
  router.push({ name: 'transactions' })
}
</script>

<template>
  <el-card v-if="networkFailed" class="result-panel failed" shadow="never">
    <div class="result-row">
      <span>请求未确认送达（网络异常）。重试将复用原 requestId，不会重复入账。</span>
      <el-button type="primary" @click="emit('retry')">重试提交</el-button>
    </div>
  </el-card>

  <el-card v-else-if="result" class="result-panel" shadow="never">
    <div class="result-row head">
      <el-tag :type="statusTagType(status)" size="large">{{ statusLabel(status) }}</el-tag>
      <span class="msg">{{ result.message }}</span>
    </div>
    <el-descriptions :column="1" border size="small" class="result-desc">
      <el-descriptions-item label="交易号">{{ tradeNo || '—' }}</el-descriptions-item>
      <el-descriptions-item label="当前状态">{{ statusLabel(status) }}</el-descriptions-item>
      <el-descriptions-item v-if="result.data?.failureReason" label="失败原因">
        {{ result.data.failureReason }}
      </el-descriptions-item>
      <el-descriptions-item label="traceId">{{ result.traceId || '—' }}</el-descriptions-item>
    </el-descriptions>
    <div class="result-actions">
      <el-button v-if="open" type="primary" :loading="polling" @click="emit('refresh')">
        查询最新状态
      </el-button>
      <span v-if="open" class="hint">非终态交易将自动轮询查询，请勿重复提交</span>
      <el-button v-if="succeeded" @click="goFundEntries">查看交易记录</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.result-panel {
  margin-top: 16px;
}
.result-panel.failed {
  background: #fdf6ec;
}
.result-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.result-row.head .msg {
  font-size: 15px;
  font-weight: 600;
}
.result-desc {
  margin-top: 12px;
}
.result-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.hint {
  font-size: 13px;
  color: #e6a23c;
}
</style>
