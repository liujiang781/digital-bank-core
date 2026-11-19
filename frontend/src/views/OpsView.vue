<script setup>
// 运营管理台（UC-OP-01 / UC-AU-02 / UC-AC-05 / UC-AC-06）
// 角色与数据范围由服务端会话校验。前端只展示接口返回的内容，权限不足会收到 403。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listCustomers, getAuthorization, updateAuthorization,
  freezeAccount, unfreezeAccount
} from '../api/ops'
import { newRequestId } from '../utils/requestId'

const loading = ref(false)
const customers = ref([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 20, reason: '' })

const authDialog = ref(false)
const authLoading = ref(false)
const authTarget = ref('')
const authData = ref(null)

async function load() {
  loading.value = true
  try {
    // 后台查询必须带 reason（服务端对 OPERATIONS/RISK 强制校验）
    const res = await listCustomers({
      page: query.page,
      pageSize: query.pageSize,
      reason: query.reason || '运营管理查询'
    })
    if (res.code === 'SUCCEEDED') {
      const d = res.data || {}
      customers.value = d.items || []
      total.value = d.total || 0
    } else {
      ElMessage.error(res.message || '客户列表查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function openAuth(identityId) {
  authTarget.value = identityId
  authLoading.value = true
  authDialog.value = true
  try {
    const res = await getAuthorization(identityId)
    if (res.code === 'SUCCEEDED') authData.value = res.data
    else ElMessage.error(res.message || '授权查询失败')
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    authLoading.value = false
  }
}

async function saveAuth() {
  if (!authData.value?.roleId) {
    ElMessage.error('没有可更新的角色记录')
    return
  }
  let reason = ''
  try {
    const answer = await ElMessageBox.prompt('请输入授权变更原因（写入审计）', '变更授权', {
      inputValidator: (v) => !!v?.trim() || '原因不能为空'
    })
    reason = answer.value
  } catch {
    return
  }
  try {
    const res = await updateAuthorization(authData.value.roleId, {
      identityId: authTarget.value,
      enabled: authData.value.enabled,
      accountScope: authData.value.accountScope || [],
      reason,
      requestId: newRequestId()
    })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('授权已更新')
      authDialog.value = false
    } else {
      ElMessage.error(res.message || '授权更新失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  }
}

async function freeze(row, unfreeze = false) {
  const accountId = row.accountId
  let reason = ''
  try {
    const answer = await ElMessageBox.prompt(
      `${unfreeze ? '解冻' : '冻结'}账户 ${accountId}，请输入原因`,
      unfreeze ? '解冻账户' : '冻结账户',
      { inputValidator: (v) => !!v?.trim() || '原因不能为空' }
    )
    reason = answer.value
  } catch {
    return
  }
  try {
    const res = unfreeze
      ? await unfreezeAccount(accountId, {
          reason,
          releaseEvidence: '运营解除冻结',
          resolvedRestrictionIds: [],
          requestId: newRequestId()
        })
      : await freezeAccount(accountId, { reason, requestId: newRequestId() })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success(unfreeze ? '账户已解冻' : '账户已冻结')
      load()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h2 class="page-title">运营管理</h2>

    <el-card class="card-block" shadow="never">
      <div class="filters">
        <el-input v-model="query.reason" placeholder="查询原因（后台必填）" style="width: 260px" />
        <el-button type="primary" @click="load">查询客户</el-button>
      </div>

      <el-table :data="customers" v-loading="loading" stripe>
        <el-table-column prop="customerId" label="客户编号" min-width="140" />
        <el-table-column prop="identityId" label="身份标识" min-width="140" />
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="账户" min-width="180">
          <template #default="{ row }">
            <template v-if="row.accounts?.length">
              <div v-for="a in row.accounts" :key="a.accountId" class="acct-line">
                {{ a.accountId }} · {{ a.status }}
                <el-button link size="small" @click="freeze(a, a.status === 'FROZEN')">
                  {{ a.status === 'FROZEN' ? '解冻' : '冻结' }}
                </el-button>
              </div>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAuth(row.identityId)">角色授权</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load"
      />
    </el-card>

    <el-dialog v-model="authDialog" title="角色与数据范围" width="520px">
      <div v-loading="authLoading">
        <template v-if="authData">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="身份标识">{{ authTarget }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ (authData.roles || []).join('、') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="账户范围">
              {{ (authData.accountScope || []).join('、') || '无限制' }}
            </el-descriptions-item>
            <el-descriptions-item label="角色编号">{{ authData.roleId || '—' }}</el-descriptions-item>
          </el-descriptions>
          <div class="auth-edit">
            <el-checkbox v-model="authData.enabled">启用该授权</el-checkbox>
          </div>
        </template>
        <el-empty v-else-if="!authLoading" description="无授权记录" />
      </div>
      <template #footer>
        <el-button @click="authDialog = false">取消</el-button>
        <el-button v-if="authData" type="primary" @click="saveAuth">保存</el-button>
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
.acct-line {
  font-size: 12px;
  color: var(--text-muted);
}
.auth-edit {
  margin-top: 16px;
}
</style>
