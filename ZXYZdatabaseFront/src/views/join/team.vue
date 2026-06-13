<template>
  <section class="join-team-page">
    <h1>加入团队</h1>
    <p>该链接需要提交加入申请，管理员审核通过后你会进入团队。</p>
    <el-button type="primary" :loading="submitting" @click="submit">提交加入申请</el-button>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'

const route = useRoute()
const router = useRouter()
const teamManagement = useTeamStore()
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    await teamManagement.submitJoinRequest(route.params.token)
    ElMessage.success('加入申请已提交')
    router.push({ name: 'chatHome' })
  } catch (error) {
    handleBusinessError(error, '提交加入申请失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.join-team-page {
  height: 100%;
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 14px;
  text-align: center;
}

.join-team-page h1,
.join-team-page p {
  margin: 0;
}
</style>
