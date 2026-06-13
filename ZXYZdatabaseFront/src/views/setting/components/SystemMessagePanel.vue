<template>
  <section class="settings-panel">
    <div class="panel-title">
      <h2>全局系统消息</h2>
      <span>系统管理员可统一向全部用户发送系统消息</span>
    </div>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="消息标题">
        <el-input
          v-model="systemMessageForm.title"
          maxlength="120"
          show-word-limit
          placeholder="输入系统消息标题"
        />
      </el-form-item>
      <el-form-item label="消息内容">
        <el-input
          v-model="systemMessageForm.content"
          type="textarea"
          :rows="4"
          maxlength="5000"
          show-word-limit
          placeholder="输入发送给全部用户的系统消息内容"
        />
      </el-form-item>
      <el-button type="primary" :loading="broadcastingSystemMessage" @click="submitSystemMessage"
        >立即发送</el-button
      >
    </el-form>
  </section>

  <section class="settings-panel">
    <div class="panel-title">
      <h2>预约邮件群发</h2>
      <span>向所有已验证邮箱用户发送邮件</span>
    </div>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="邮件主题">
        <el-input
          v-model="scheduledEmailForm.subject"
          maxlength="120"
          show-word-limit
          placeholder="输入邮件主题"
        />
      </el-form-item>
      <el-form-item label="邮件内容">
        <el-input
          v-model="scheduledEmailForm.content"
          type="textarea"
          :rows="4"
          maxlength="5000"
          show-word-limit
          placeholder="输入邮件正文"
        />
      </el-form-item>
      <el-form-item label="发送时间">
        <el-date-picker
          v-model="scheduledEmailForm.scheduledTime"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="选择发送时间"
        />
      </el-form-item>
      <el-button type="primary" :loading="schedulingEmailBatch" @click="submitScheduledEmail"
        >预约发送</el-button
      >
    </el-form>
  </section>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

import { broadcastSystemMessage, scheduleSystemEmailBatch } from '@/api/adminTeam'
import { handleBusinessError } from '@/utils/error'

const broadcastingSystemMessage = ref(false)
const schedulingEmailBatch = ref(false)

const systemMessageForm = reactive({ title: '', content: '' })
const scheduledEmailForm = reactive({ subject: '', content: '', scheduledTime: '' })

async function submitSystemMessage() {
  const title = systemMessageForm.title.trim()
  const content = systemMessageForm.content.trim()
  if (!title || !content) {
    return ElMessage.warning('请填写系统消息标题和内容')
  }
  broadcastingSystemMessage.value = true
  try {
    await broadcastSystemMessage({ title, content })
    systemMessageForm.title = ''
    systemMessageForm.content = ''
    ElMessage.success('系统消息已发送')
  } catch (error) {
    handleBusinessError(error, '发送系统消息失败')
  } finally {
    broadcastingSystemMessage.value = false
  }
}

async function submitScheduledEmail() {
  const subject = scheduledEmailForm.subject.trim()
  const content = scheduledEmailForm.content.trim()
  const scheduledTime = scheduledEmailForm.scheduledTime
  if (!subject || !content || !scheduledTime) {
    return ElMessage.warning('请填写邮件主题、内容和发送时间')
  }
  schedulingEmailBatch.value = true
  try {
    await scheduleSystemEmailBatch({
      subject,
      contentHtml: toEmailHtml(content),
      scheduledTime,
    })
    scheduledEmailForm.subject = ''
    scheduledEmailForm.content = ''
    scheduledEmailForm.scheduledTime = ''
    ElMessage.success('邮件群发已预约')
  } catch (error) {
    handleBusinessError(error, '预约邮件群发失败')
  } finally {
    schedulingEmailBatch.value = false
  }
}

function toEmailHtml(content) {
  return content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
    .replace(/\r?\n/g, '<br>')
}
</script>
