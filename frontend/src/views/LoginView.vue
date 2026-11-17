<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({
  identityId: '',
  password: ''
})

const rules = {
  identityId: [{ required: true, message: '请输入证件号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login({ ...form })
    if (res.code === 'SUCCEEDED') {
      session.loginSuccess(res.data)
      ElMessage.success('登录成功')
      router.push(route.query.redirect || '/')
    } else {
      // 错误提示用后端 message（不硬编码）
      ElMessage.error(res.message || '登录失败')
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
      <h2 class="login-title">数字银行 · 登录</h2>
      <p class="login-sub">核心业务模拟系统（教学演示）</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
        <el-form-item label="证件号" prop="identityId">
          <el-input v-model="form.identityId" placeholder="实名证件号" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="登录密码"
            autocomplete="current-password"
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">
          登 录
        </el-button>
        <div class="login-links">
          <router-link to="/register">还没有账户？注册新客户</router-link>
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
