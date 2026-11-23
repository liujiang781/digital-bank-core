<script setup>
// UC-ID-03 维护个人资料：只允许改手机号和邮箱（后端 ProfileRequest 限制）
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getProfile, updateMe } from '../api/auth'
import { newRequestId } from '../utils/requestId'

const loading = ref(false)
const saving = ref(false)
const profile = ref(null)
const form = reactive({ phone: '', email: '', requestId: newRequestId() })

async function load() {
  loading.value = true
  try {
    const res = await getProfile()
    if (res.code === 'SUCCEEDED') {
      profile.value = res.data
      form.phone = res.data.phone || ''
      form.email = res.data.email || ''
    } else {
      ElMessage.error(res.message || '资料查询失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const res = await updateMe({
      phone: form.phone || null,
      email: form.email || null,
      requestId: form.requestId
    })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('资料已更新')
      form.requestId = newRequestId()
      load()
    } else {
      ElMessage.error(res.message || '更新失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h2 class="page-title">个人资料</h2>

    <el-card v-loading="loading" class="card-block" shadow="never">
      <template #header>基本信息</template>
      <el-descriptions v-if="profile" :column="2" border>
        <el-descriptions-item label="客户编号">{{ profile.customerId }}</el-descriptions-item>
        <el-descriptions-item label="身份标识">{{ profile.identityId }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ profile.realName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="实名状态">{{ profile.realNameStatus || '—' }}</el-descriptions-item>
        <el-descriptions-item label="账户状态">{{ profile.status || '—' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="card-block" shadow="never">
      <template #header>修改联系方式</template>
      <el-form :model="form" label-width="100px" style="max-width: 480px">
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="11 位手机号" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="电子邮箱" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
        </el-form-item>
      </el-form>
      <div class="field-hint">实名信息由开户流程确定,此处只能修改手机号和邮箱。</div>
    </el-card>
  </div>
</template>

<style scoped>
.field-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: -8px;
}
</style>
