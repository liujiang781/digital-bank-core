<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Wallet, Plus, Minus, Switch, List, User, OfficeBuilding, Lock } from '@element-plus/icons-vue'
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
        <span class="brand-mark">银</span>
        <span class="brand-name">数字银行</span>
        <span class="brand-sub">核心业务模拟系统 · 教学演示</span>
      </div>
      <div class="header-right">
        <span class="user-badge">{{ session.displayName }}</span>
        <el-button text class="logout-btn" @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>
    <el-container>
      <el-aside width="210px" class="layout-aside">
        <div class="aside-label">{{ session.isBackOffice ? '查询与资料' : '业务办理' }}</div>
        <el-menu :default-active="$route.path" router>
          <!-- 员工账号没有个人银行业务，不显示我的账户/存款/取款/转账 -->
          <template v-if="!session.isBackOffice">
            <el-menu-item index="/accounts">
              <el-icon><Wallet /></el-icon><span>我的账户</span>
            </el-menu-item>
            <el-menu-item index="/deposit">
              <el-icon><Plus /></el-icon><span>存款</span>
            </el-menu-item>
            <el-menu-item index="/withdraw">
              <el-icon><Minus /></el-icon><span>取款</span>
            </el-menu-item>
            <el-menu-item index="/transfer">
              <el-icon><Switch /></el-icon><span>转账</span>
            </el-menu-item>
          </template>
          <el-menu-item index="/transactions">
            <el-icon><List /></el-icon><span>交易记录</span>
          </el-menu-item>
          <el-menu-item index="/profile">
            <el-icon><User /></el-icon><span>个人资料</span>
          </el-menu-item>

          <!-- 后台入口只给有对应角色的账号显示；服务端仍会独立校验权限 -->
          <template v-if="session.isOpsAdmin || session.isOpsRisk">
            <div class="aside-label aside-label-second">后台管理</div>
            <el-menu-item v-if="session.isOpsAdmin" index="/ops">
              <el-icon><OfficeBuilding /></el-icon><span>运营管理</span>
            </el-menu-item>
            <el-menu-item v-if="session.isOpsRisk" index="/risk">
              <el-icon><Lock /></el-icon><span>风险控制</span>
            </el-menu-item>
          </template>
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
  height: 64px;
  padding: 0 26px;
  background: linear-gradient(100deg, #0f172a 0%, #0c3455 60%, #075985 100%);
  color: #fff;
  box-shadow: 0 2px 14px rgba(15, 23, 42, 0.22);
  z-index: 10;
}
.brand {
  display: flex;
  align-items: center;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  margin-right: 12px;
  background: rgba(255, 255, 255, 0.15);
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 9px;
  font-size: 18px;
  font-weight: 700;
}
.brand-name {
  font-size: 19px;
  font-weight: 600;
  letter-spacing: 1px;
}
.brand-sub {
  margin-left: 14px;
  padding-left: 14px;
  border-left: 1px solid rgba(255, 255, 255, 0.25);
  font-size: 12px;
  opacity: 0.8;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-badge {
  padding: 6px 14px;
  background: rgba(255, 255, 255, 0.14);
  border-radius: 20px;
  font-size: 13px;
}
.header-right :deep(.logout-btn) {
  color: #fff;
  opacity: 0.9;
}
.layout-aside {
  background: #fff;
  border-right: 1px solid var(--border-soft);
}
.aside-label {
  padding: 22px 20px 8px;
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 2px;
}
.aside-label-second {
  padding-top: 16px;
  border-top: 1px solid var(--border-soft);
  margin-top: 12px;
}
.layout-aside :deep(.el-menu) {
  border-right: none;
}
.layout-aside :deep(.el-menu-item) {
  margin: 4px 10px;
  border-radius: 8px;
  height: 44px;
}
.layout-aside :deep(.el-menu-item.is-active) {
  background: #e8f0f8;
  color: var(--brand-primary);
  font-weight: 600;
}
</style>
