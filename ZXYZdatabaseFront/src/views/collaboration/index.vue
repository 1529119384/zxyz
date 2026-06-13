<template>
  <section class="collaboration-redirect">
    <el-empty description="团队协作已迁移到聊天会话">
      <el-button type="primary" @click="goChat">进入聊天会话</el-button>
    </el-empty>
  </section>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

function goChat() {
  router.replace({ name: 'chatHome' })
}

onMounted(goChat)
</script>

<style scoped>
.collaboration-redirect {
  height: 100%;
  display: grid;
  place-items: center;
}
</style>
