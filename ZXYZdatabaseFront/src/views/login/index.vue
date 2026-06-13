<template>
  <div class="common-layout">
    <el-container>
      <el-header v-once class="title">欢迎来到指绣云章</el-header>
      <el-main class="form">
        <br />
        <el-form
          ref="loginFormRef"
          style="max-width: 600px"
          :model="loginForm"
          :rules="loginRules"
          label-width="auto"
          @submit.prevent
        >
          <el-form-item prop="username">
            <el-input
              v-model="loginForm.username"
              :disabled="loggingIn"
              placeholder="请输入用户名"
              autocomplete="username"
              class="input"
            >
              <template #prefix class="form_tip">用户名：</template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="loginForm.password"
              :disabled="loggingIn"
              placeholder="请输入密码"
              autocomplete="current-password"
              class="input"
              show-password
            >
              <template #prefix class="form_tip">密 码：</template>
            </el-input>
          </el-form-item>
        </el-form>
        <el-text class="tip">有问题找管理员哦</el-text>
      </el-main>
    </el-container>
    <TeamSelectDialog
      v-model:visible="teamDialogVisible"
      v-model:selected-team-id="pendingTeamId"
      v-model:set-as-default="setAsDefault"
      :teams="teams"
      :submitting="teamGuideSubmitting"
      @confirm="confirmTeamSelect"
      @skip="skipTeamSelect"
    />
  </div>
</template>

<script setup>
import { useEventListener } from '@vueuse/core'

import TeamSelectDialog from '@/components/TeamSelectDialog.vue'
import { useLoginForm } from '@/composables/useLoginForm'
import { usePostLoginGuide } from '@/composables/usePostLoginGuide'

const { loginForm, loginFormRef, loginRules, loggingIn, handleKeydown } = useLoginForm()
const {
  teams,
  teamDialogVisible,
  pendingTeamId,
  setAsDefault,
  submitting: teamGuideSubmitting,
  handlePostLoginSuccess,
  confirmTeamSelect,
  skipTeamSelect,
} = usePostLoginGuide()

async function handleLoginByEnter(event) {
  try {
    const result = await handleKeydown(event)
    if (!result) {
      return
    }

    await handlePostLoginSuccess()
  } catch (error) {
    // 登录或后续引导流程的异常已由各层内部处理，此处仅防止未捕获 Promise 向上冒泡。
    console.warn('Login enter handler error:', error)
  }
}

useEventListener(document, 'keydown', handleLoginByEnter)
</script>

<style scoped>
@font-face {
  font-family: 'BoutiqueBitmap';
  src: url('@/assets/fonts/BoutiqueBitmap9x9_1.9.ttf') format('woff2');
}

@font-face {
  font-family: 'BoutiqueBitmap9x9_3D';
  src: url('@/assets/fonts/BoutiqueBitmap9x9_3D.ttf') format('woff2');
}

.common-layout {
  height: 100vh;
  width: 100vw;
  background: url(@/assets/images/background.jpg) no-repeat center/cover;
  display: flex;
  flex-direction: column;
}

.title {
  text-align: center;
  margin-top: 48px;
  font-size: 72px;
  font-weight: 400;
  letter-spacing: 6px;
  line-height: 80px;
  color: rgba(0, 96, 128, 1);
  font-family: 'BoutiqueBitmap9x9_3D', 'Source Han Sans CN', 'Microsoft YaHei', sans-serif;
}

.input :deep(.el-input__prefix) {
  font-size: 24px;
  color: rgba(69, 159, 201, 1);
  font-family: 'BoutiqueBitmap', 'Source Han Sans CN', 'Microsoft YaHei', sans-serif;
  margin-right: 8px;
  white-space: nowrap;
}

.form {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
}

.input {
  width: 700px;
  height: 60px;
}

.input :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.75);

  height: 100%;
  line-height: inherit;
  display: flex;
  align-items: center;
  border-radius: var(--el-border-radius-round);
  box-shadow: none;
}

.input :deep(input) {
  font-size: 22px;
  height: 100%;
  line-height: inherit;
  padding-right: 40px;
}

.input :deep(input::placeholder) {
  opacity: 1;
  font-size: 24px;
  font-weight: 400;
  color: rgba(129, 129, 129, 1);
  font-family: 'BoutiqueBitmap', 'Source Han Sans CN', 'Microsoft YaHei', sans-serif;
}

.input :deep(.el-input__suffix-inner) {
  display: flex;
  align-items: center;
}

.tip {
  width: 100%;
  height: 60px;
  font-size: 48px;
  line-height: 60px;
  color: white;
  text-align: center;
}

.register-link {
  margin-top: 16px;
  font-size: 16px;
}
</style>
