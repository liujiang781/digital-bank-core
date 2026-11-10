<script setup>
// 转账表单（UC-TR-03 本行 P0 / UC-TR-04 跨行 P0）
// 本行：双方余额与流水在 account-service 同一本地事务提交（BR-15）。
// 跨行：受理后进入 PENDING（外部结果待确认，BR-16/17），页面支持查询收敛。
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
  kind: 'INTERNAL', // INTERNAL 本行 / EXTERNAL 跨行
  payerAccountId: '',
  payeeAccountId: '',
  externalBankCode: '',
  externalPayeeRef: '',
  amount: '',
  purpose: '',
  requestId: newRequestId()
})

const rules = computed(() => ({
  payerAccountId: [{ required: true, message: '请选择付款账户', trigger: 'change' }],
  payeeAccountId:
    form.kind === 'INTERNAL'
      ? [{ required: true, message: '请选择收款账户', trigger: 'change' }]
      : [],
  externalBankCode:
    form.kind === 'EXTERNAL'
      ? [{ required: true, message: '请输入收款银行代码', trigger: 'blur' }]
      : [],
  externalPayeeRef:
    form.kind === 'EXTERNAL'
      ? [{ required: true, message: '请输入收款方账号/ reference', trigger: 'blur' }]
      : [],
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
}))

watch(
  () => [
    form.kind,
    form.payerAccountId,
    form.payeeAccountId,
    form.externalBankCode,
    form.externalPayeeRef,
    form.amount
  ],
  () => {
    if (!submitting.value) form.requestId = newRequestId()
  }
)

const { submitting, networkFailed, result, polling, submit, retry, refreshStatus } = useTradeSubmit(
  () => {
    const payload = {
      payerAccountId: form.payerAccountId,
      amount: form.amount,
      purpose: form.purpose || null,
      requestId: form.requestId
    }
    if (form.kind === 'INTERNAL') {
      payload.payeeAccountId = form.payeeAccountId
    } else {
      payload.externalBankCode = form.externalBankCode
      payload.externalPayeeRef = form.externalPayeeRef
    }
    return transfer(payload)
  },
  getTransaction
)

async function handleSubmit() {
  await formRef.value.validate()
  const target =
    form.kind === 'INTERNAL'
      ? `本行账户 ${form.payeeAccountId}`
      : `跨行账户 ${form.externalBankCode} / ${form.externalPayeeRef}`
  try {
    // 转账强制二次确认（frontend/README.md 开发约定）
    await ElMessageBox.confirm(
      `从 ${form.payerAccountId} 转出 ${formatAmount(form.amount)} 至 ${target}？`,
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
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="转账类型">
          <el-radio-group v-model="form.kind">
            <el-radio value="INTERNAL">本行转账</el-radio>
            <el-radio value="EXTERNAL">跨行转账</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="付款账户" prop="payerAccountId">
          <el-select v-model="form.payerAccountId" placeholder="选择本人账户" style="width: 100%">
            <el-option
              v-for="acc in accounts"
              :key="acc.accountId"
              :value="acc.accountId"
              :label="`${acc.accountId}（${accountStatusLabel(acc.status)}）`"
            />
          </el-select>
        </el-form-item>

        <template v-if="form.kind === 'INTERNAL'">
          <el-form-item label="收款账户" prop="payeeAccountId">
            <el-select
              v-model="form.payeeAccountId"
              placeholder="选择本行收款账户"
              style="width: 100%"
            >
              <el-option
                v-for="acc in accounts.filter((a) => a.accountId !== form.payerAccountId)"
                :key="acc.accountId"
                :value="acc.accountId"
                :label="acc.accountId"
              />
            </el-select>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="收款银行" prop="externalBankCode">
            <el-input
              v-model="form.externalBankCode"
              placeholder="外部银行代码（教学模拟）"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item label="收款账号" prop="externalPayeeRef">
            <el-input
              v-model="form.externalPayeeRef"
              placeholder="外部收款方账号 reference"
              style="width: 260px"
            />
          </el-form-item>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="跨行转账受理后为「待确认」状态，需等待外部渠道结果，可用「查询最新状态」收敛"
          />
        </template>

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
