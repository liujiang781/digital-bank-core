<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '../stores/session'
import { logout } from '../api/auth'

const router = useRouter()
const session = useSessionStore()

async function handleLogout() {
  try {
    // 后端登出（会话失效）；本地失败也照样清理
    await logout()
  } catch {
    // 网络异常不影响本地登出
  }
  session.clear()
  ElMessage.success('已退出登录')
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header class="layout-header">
      <div class="brand">
        <span class="brand-name">数字银行</span>
        <span class="brand-sub">核心业务模拟系统（教学演示）</span>
      </div>
      <div class="header-right">
        <span class="user-name">{{ session.displayName }}</span>
        <el-button text @click="handleLogout">退出</el-button>
      </div>
    </el-header>
    <el-container>
      <el-aside width="200px" class="layout-aside">
        <el-menu :default-active="$route.path" router>
          <el-menu-item index="/accounts">我的账户</el-menu-item>
          <el-menu-item index="/deposit">存款</el-menu-item>
          <el-menu-item index="/withdraw">取款</el-menu-item>
          <el-menu-item index="/transfer">转账</el-menu-item>
          <el-menu-item index="/transactions">交易记录</el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #1f3a5f;
  color: #fff;
}
.brand-name {
  font-size: 18px;
  font-weight: 600;
}
.brand-sub {
  margin-left: 10px;
  font-size: 12px;
  opacity: 0.75;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-right :deep(.el-button) {
  color: #fff;
}
.user-name {
  font-size: 14px;
}
.layout-aside {
  background: #fff;
}
</style>
