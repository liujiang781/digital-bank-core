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
          <el-input v-model="form.identityId" placeholder="身份标识（演示：UID-000001）" autocomplete="username" />
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
  background: linear-gradient(135deg, #0f172a 0%, #0c3455 50%, #075985 100%);
  position: relative;
  overflow: hidden;
}
.login-wrap::before {
  content: '';
  position: absolute;
  width: 620px;
  height: 620px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.05);
  top: -240px;
  right: -160px;
}
.login-wrap::after {
  content: '';
  position: absolute;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.04);
  bottom: -180px;
  left: -120px;
}
.login-card {
  width: 400px;
  position: relative;
  z-index: 1;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.25);
}
.login-title {
  margin: 0;
  text-align: center;
  font-size: 24px;
  color: var(--brand-primary);
  letter-spacing: 1px;
}
.login-sub {
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
  margin: 6px 0 20px;
}
.login-btn {
  width: 100%;
  margin-top: 12px;
  height: 42px;
  font-size: 15px;
  letter-spacing: 4px;
}
.login-links {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
}
</style>
