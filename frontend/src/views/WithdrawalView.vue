<script setup>
// 取款表单（UC-TR-02，P0）
// 编排：风控 → 预留 → 提交（后端 transaction-service 负责），前端只做输入与状态展示。
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAccounts } from '../api/account'
import { withdraw, getTransaction } from '../api/transaction'
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
  requestId: newRequestId()
})

const rules = {
  accountId: [{ required: true, message: '请选择取款账户', trigger: 'change' }],
  amount: [
    { required: true, message: '请输入取款金额', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value && !isValidAmount(value)) callback(new Error('金额最多两位小数，不能为负'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

watch(
  () => [form.accountId, form.amount],
  () => {
    if (!submitting.value) form.requestId = newRequestId()
  }
)

const { submitting, networkFailed, result, polling, submit, retry, refreshStatus } = useTradeSubmit(
  () => withdraw({
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
      `从账户 ${form.accountId} 取出 ${formatAmount(form.amount)}？可用余额需充足（A = B − R）。`,
      '取款确认',
      { confirmButtonText: '确认取出', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  const res = await submit()
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
    <h2 class="page-title">取款</h2>

    <el-card class="card-block" shadow="never">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="取款账户" prop="accountId">
          <el-select v-model="form.accountId" placeholder="选择本人账户" style="width: 100%">
            <el-option
              v-for="acc in accounts"
              :key="acc.accountId"
              :value="acc.accountId"
              :label="`${acc.accountId}（${accountStatusLabel(acc.status)}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="取款金额" prop="amount">
          <el-input v-model="form.amount" placeholder="例如 100.00（两位小数）" style="width: 220px">
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">提交取款</el-button>
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
