<script setup>
// 本行转账表单（UC-TR-03，P0，BR-15）
// 双方余额与流水在 account-service 同一本地事务提交。
// 跨行转账（UC-TR-04）本轮 S3 未实现，见交接文档第 7.1 节，页面保留说明但不提交。
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAccounts } from '../api/account'
import { transfer, getTransaction } from '../api/transaction'
import { useTradeSubmit } from '../composables/useTradeSubmit'
import TradeResultPanel from '../components/TradeResultPanel.vue'
import { isValidAmount, formatAmount } from '../utils/money'
import { accountStatusLabel } from '../utils/status'
import { newRequestId } from '../utils/requestId'

const formRef = ref()
const accounts = ref([])

const form = reactive({
  accountId: '', // 付款账户（后端 TransferRequest 字段名）
  payeeAccountId: '',
  amount: '',
  purpose: '',
  requestId: newRequestId()
})

const rules = {
  accountId: [{ required: true, message: '请选择付款账户', trigger: 'change' }],
  payeeAccountId: [{ required: true, message: '请选择收款账户', trigger: 'change' }],
  amount: [
    { required: true, message: '请输入转账金额', trigger: 'blur' },
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
  () => [form.accountId, form.payeeAccountId, form.amount],
  () => {
    if (!submitting.value) form.requestId = newRequestId()
  }
)

const { submitting, networkFailed, result, polling, submit, retry, refreshStatus } = useTradeSubmit(
  () =>
    transfer({
      accountId: form.accountId,
      payeeAccountId: form.payeeAccountId,
      amount: form.amount,
      purpose: form.purpose || null,
      requestId: form.requestId
    }),
  getTransaction
)

async function handleSubmit() {
  await formRef.value.validate()
  const target = `本行账户 ${form.payeeAccountId}`
  try {
    // 转账强制二次确认（frontend/README.md 开发约定）
    await ElMessageBox.confirm(
      `从 ${form.accountId} 转出 ${formatAmount(form.amount)} 至 ${target}？`,
      '转账确认',
      { confirmButtonText: '确认转账', cancelButtonText: '取消', type: 'warning' }
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
    <h2 class="page-title">转账</h2>

    <el-card class="card-block" shadow="never">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
        title="本行转账：双方余额与流水在账户服务同一事务内提交。跨行转账本轮后端未实现。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="付款账户" prop="accountId">
          <el-select v-model="form.accountId" placeholder="选择本人账户" style="width: 100%">
            <el-option
              v-for="acc in accounts"
              :key="acc.accountId"
              :value="acc.accountId"
              :label="`${acc.accountId}（${accountStatusLabel(acc.status)}）`"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="收款账户" prop="payeeAccountId">
          <el-select
            v-model="form.payeeAccountId"
            placeholder="选择本行收款账户"
            style="width: 100%"
          >
            <el-option
              v-for="acc in accounts.filter((a) => a.accountId !== form.accountId)"
              :key="acc.accountId"
              :value="acc.accountId"
              :label="acc.accountId"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="转账金额" prop="amount">
          <el-input v-model="form.amount" placeholder="例如 100.00（两位小数）" style="width: 220px">
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="用途">
          <el-input v-model="form.purpose" placeholder="选填" style="width: 320px" maxlength="50" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            提交转账
          </el-button>
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
