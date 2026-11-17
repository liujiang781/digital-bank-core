<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '../api/auth'
import { newRequestId } from '../utils/requestId'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = reactive({
  realName: '',
  identityId: '',
  password: '',
  password2: '',
  // 注册操作级的幂等键：提交/重试复用同一个 requestId
  requestId: newRequestId()
})

const rules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  identityId: [{ required: true, message: '请输入证件号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  password2: [
    {
      validator: (rule, value, callback) => {
        if (value !== form.password) callback(new Error('两次密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

async function submit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await register({
      realName: form.realName,
      identityId: form.identityId,
      password: form.password,
      // 重试复用同一 requestId，后端幂等认领
      requestId: form.requestId
    })
    if (res.code === 'SUCCEEDED') {
      ElMessage.success('注册成功，请登录')
      router.push({ name: 'login' })
    } else {
      ElMessage.error(res.message || '注册失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '网络异常')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="login-title">注册新客户</h2>
      <p class="login-sub">注册后需实名信息校验（教学模拟）</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="证件号" prop="identityId">
          <el-input v-model="form.identityId" placeholder="实名证件号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="password2">
          <el-input v-model="form.password2" type="password" show-password />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">
          注 册
        </el-button>
        <div class="login-links">
          <router-link to="/login">已有账户？返回登录</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #1f3a5f 0%, #3d5a80 100%);
}
.login-card {
  width: 380px;
}
.login-title {
  margin: 0;
  text-align: center;
}
.login-sub {
  text-align: center;
  color: #909399;
  font-size: 13px;
  margin-top: 4px;
}
.login-btn {
  width: 100%;
  margin-top: 8px;
}
.login-links {
  margin-top: 12px;
  text-align: center;
  font-size: 13px;
}
</style>
