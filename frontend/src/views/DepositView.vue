<script setup>
// 存款表单（UC-TR-01，P0）
// S5 验收要点：loading、后端 message 提示、网络重试复用原 requestId、
// 待确认状态展示与查询收敛、成功后可跳转查看余额与流水。
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAccounts } from '../api/account'
import { deposit } from '../api/transaction'
import { getTransaction } from '../api/transaction'
import { useTradeSubmit } from '../composables/useTradeSubmit'
import TradeResultPanel from '../components/TradeResultPanel.vue'
import { isValidAmount, formatAmount } from '../utils/money'
import { accountStatusLabel } from '../utils/status'
import { newRequestId } from '../utils/requestId'

const formRef = ref()
const accounts = ref([])

const form = reactive({
  accountId: '',
  amount: '',
  // 幂等键：一次用户操作一个 requestId；网络重试复用；送达成功后换新
  requestId: newRequestId()
})

const rules = {
  accountId: [{ required: true, message: '请选择存款账户', trigger: 'change' }],
  amount: [
    { required: true, message: '请输入存款金额', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value && !isValidAmount(value)) callback(new Error('金额最多两位小数，不能为负'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

// 表单内容变更 = 新的操作意图，换新 requestId
watch(
  () => [form.accountId, form.amount],
  () => {
    if (!submitting.value) form.requestId = newRequestId()
  }
)

const { submitting, networkFailed, result, polling, submit, retry, refreshStatus } = useTradeSubmit(
  // 提交闭包：引用 form（reactive），重试时读取的还是同一个 requestId
  () => deposit({
    accountId: form.accountId,
    amount: form.amount,
    requestId: form.requestId
  }),
  getTransaction
)

async function handleSubmit() {
  await formRef.value.validate()
  try {
    await ElMessageBox.confirm(
      `向账户 ${form.accountId} 存入 ${formatAmount(form.amount)}？`,
      '存款确认',
      { confirmButtonText: '确认存入', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  const res = await submit()
  // 后端已确认送达（任何业务结果）：这笔操作结束，下一笔用新 requestId
  if (res) form.requestId = newRequestId()
}

onMounted(async () => {
  const res = await listAccounts({ page: 1, pageSize: 50 })
  if (res.code === 'SUCCEEDED') accounts.value = res.data.items || []
  else ElMessage.error(res.message || '账户查询失败')
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">存款</h2>

    <el-card class="card-block" shadow="never">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="存款账户" prop="accountId">
          <el-select v-model="form.accountId" placeholder="选择本人账户" style="width: 100%">
            <el-option
              v-for="acc in accounts"
              :key="acc.accountId"
              :value="acc.accountId"
              :label="`${acc.accountId}（${accountStatusLabel(acc.status)}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="存款金额" prop="amount">
          <el-input v-model="form.amount" placeholder="例如 100.00（两位小数）" style="width: 220px">
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="币种">
          <el-input value="CNY 人民币" disabled style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">提交存款</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <TradeResultPanel
      :result="result"
      :network-failed="networkFailed"
      :polling="polling"
      @retry="retry"
      @refresh="refreshStatus"
    />
  </div>
</template>
