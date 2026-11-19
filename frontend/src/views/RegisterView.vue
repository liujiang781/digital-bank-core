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
