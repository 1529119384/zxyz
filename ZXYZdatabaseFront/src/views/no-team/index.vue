<template>
  <main class="no-team-page">
    <section class="no-team-panel">
      <template v-if="currentUserStore.isAdmin">
        <h1 v-once>欢迎，系统管理员</h1>
        <p v-once>当前还没有团队，请先创建一个团队以开始使用。</p>
        <el-button type="primary" @click="goToCreateTeam">创建团队</el-button>
      </template>
      <template v-else>
        <h1 v-once>账号未开通团队</h1>
        <p v-once>当前账号还没有团队归属，企业云盘暂不可用。请联系团队管理员为你开通成员账号。</p>
        <el-button type="primary" @click="logout">返回登录</el-button>
      </template>
    </section>
  </main>
</template>

<script setup>
import { useRouter } from 'vue-router'

import { logout as logoutApi } from '@/api/auth'
import { useCurrentUserStore } from '@/store/currentUser'

const router = useRouter()
const currentUserStore = useCurrentUserStore()

function goToCreateTeam() {
  router.replace({ name: 'teamAdminSettings' })
}

async function logout() {
  try {
    await logoutApi()
  } catch (_) {
    // 即使后端清理失败，也要清除本地状态
  }
  currentUserStore.clearAll()
  router.replace({ name: 'login' })
}
</script>

<style scoped>
.no-team-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: #f6f8fb;
}

.no-team-panel {
  width: min(420px, 100%);
  padding: 28px;
  border: 1px solid #e6eaf0;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 12px 32px rgb(15 23 42 / 8%);
}

.no-team-panel h1 {
  margin: 0 0 12px;
  font-size: 22px;
  color: #1f2937;
}

.no-team-panel p {
  margin: 0 0 20px;
  line-height: 1.7;
  color: #5f6b7a;
}
</style>
