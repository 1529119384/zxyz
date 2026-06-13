<template>
  <article class="message-item" :class="{ mine: isMine }">
    <el-avatar :size="34" :src="message.senderAvatar">{{
      displayName(message).slice(0, 1)
    }}</el-avatar>
    <div class="message-bubble">
      <div class="message-meta">
        <strong>{{ displayName(message) }}</strong>
        <span>{{ formatTime(message.createTime) }}</span>
        <span v-if="messageStatusText(message.status)" class="message-status">{{
          messageStatusText(message.status)
        }}</span>
        <el-button
          v-if="message.status !== MESSAGE_STATUS_RECALLED && message.messageId"
          link
          size="small"
          @click="emit('recall', message)"
          >撤回</el-button
        >
      </div>

      <p v-if="message.status === MESSAGE_STATUS_RECALLED" class="recalled-message">
        {{ recallText(message) }}
      </p>

      <div v-else-if="message.messageType === 'SYSTEM_NOTIFICATION'" class="announcement-card">
        <strong>系统消息</strong>
        <h4 v-if="systemNotification.title">{{ systemNotification.title }}</h4>
        <p>{{ systemNotification.content || message.content }}</p>
      </div>

      <div v-else-if="message.messageType === 'ANNOUNCEMENT'" class="announcement-card">
        <strong>团队公告</strong>
        <h4 v-if="announcement.title">{{ announcement.title }}</h4>
        <p>{{ announcement.content || message.content }}</p>
      </div>

      <div v-else-if="message.messageType === 'FILE_CARD' && message.fileCard" class="file-card">
        <strong>{{ fileCardTitle(message.fileCard) }}</strong>
        <small>{{ fileCardSummary(message.fileCard) }}</small>
        <ul>
          <li v-for="entry in previewEntries(message.fileCard)" :key="entry.fileId">
            {{ entry.originalName }}
          </li>
        </ul>
        <div class="file-card__actions">
          <el-button
            v-if="canDownloadFileCard(message.fileCard)"
            link
            type="primary"
            @click="emit('file-card-action', message, 'download')"
            >下载</el-button
          >
          <el-button
            v-if="canOpenFileCardFolder(message.fileCard)"
            link
            type="primary"
            @click="emit('file-card-action', message, 'openFolder')"
            >打开位置</el-button
          >
          <el-button
            v-if="canArchiveFileCard(message.fileCard)"
            link
            type="primary"
            @click="emit('file-card-action', message, 'archiveDownload')"
            >打包下载</el-button
          >
        </div>
      </div>

      <div v-else-if="message.messageType === 'FILE_CARD'" class="file-card file-card--broken">
        <strong>文件卡片加载失败</strong>
        <small>该消息已保存，但文件卡片内容未随历史消息返回。</small>
      </div>

      <div
        v-else-if="message.messageType === 'PROJECT_CREATION_APPLICATION'"
        class="project-create-request-card"
      >
        <strong>项目组申请</strong>
        <p>{{ projectApplication.projectName }}</p>
        <small>
          申请人：{{ projectApplication.requesterName || projectApplication.requesterUserId }} ·
          负责人：{{ projectApplication.leaderName || projectApplication.leaderUserId }} · 配额：{{
            formatQuota(projectApplication.storageLimit)
          }}
        </small>
        <p v-if="projectApplication.description">{{ projectApplication.description }}</p>
        <p
          v-if="projectApplication.status && projectApplication.status !== 'PENDING'"
          class="project-create-request-card__status"
        >
          {{ projectCreateRequestStatusText(projectApplication) }}
        </p>
        <div
          v-if="canReviewProjectCreateRequests && projectApplication.status === 'PENDING'"
          class="project-create-request-card__actions"
        >
          <el-button
            size="small"
            type="primary"
            :loading="isReviewingApplication"
            @click="emit('review-project-create-request', message, true)"
            >同意</el-button
          >
          <el-button
            size="small"
            :loading="isReviewingApplication"
            @click="emit('review-project-create-request', message, false)"
            >拒绝</el-button
          >
        </div>
      </div>

      <template v-else>
        <p>{{ message.content }}</p>
        <div v-if="message.mentions?.length" class="mention-tags">
          <el-tag v-for="userId in message.mentions" :key="userId" size="small"
            >@{{ mentionName(userId) }}</el-tag
          >
        </div>
      </template>
    </div>
  </article>
</template>

<script setup>
import { computed, toRef } from 'vue'

import { RECALLED } from '@/constants/messageStatus'
import {
  formatProjectCreateRequestStatusText,
  formatProjectQuotaText,
  getFileCardPreviewEntries,
  getFileCardSummary,
  getFileCardTitle,
  parseProjectCreateRequestPayload,
} from '@/models/imPresentation'

import { useChatMessageModel } from '../composables/useChatMessageModel'

const props = defineProps({
  message: {
    type: Object,
    required: true,
  },
  currentUserId: {
    type: [Number, String],
    default: null,
  },
  canReviewProjectCreateRequests: {
    type: Boolean,
    default: false,
  },
  reviewingApplicationId: {
    type: [Number, String],
    default: null,
  },
  mentionName: {
    type: Function,
    required: true,
  },
})

const emit = defineEmits(['recall', 'file-card-action', 'review-project-create-request'])

const MESSAGE_STATUS_RECALLED = RECALLED

const currentUserId = toRef(props, 'currentUserId')
const {
  messageStatusText,
  displayName,
  recallText,
  announcementPayload,
  systemNotificationPayload,
  formatTime,
} = useChatMessageModel({ currentUserId })

const isMine = computed(() => props.message.senderUserId === props.currentUserId)
const systemNotification = computed(() => systemNotificationPayload(props.message))
const announcement = computed(() => announcementPayload(props.message))
const projectApplication = computed(() => parseProjectCreateRequestPayload(props.message))
const isReviewingApplication = computed(
  () => Number(props.reviewingApplicationId) === Number(projectApplication.value.applicationId),
)

function fileCardTitle(fileCard = {}) {
  return getFileCardTitle(fileCard)
}

function fileCardSummary(fileCard = {}) {
  return getFileCardSummary(fileCard)
}

function previewEntries(fileCard = {}) {
  return getFileCardPreviewEntries(fileCard)
}

function canDownloadFileCard(fileCard = {}) {
  return fileCard?.shareType === 'SINGLE_FILE'
}

function canOpenFileCardFolder(fileCard = {}) {
  return Array.isArray(fileCard?.entries) && fileCard.entries.length > 0
}

function canArchiveFileCard(fileCard = {}) {
  return fileCard?.shareType === 'SINGLE_FOLDER' || fileCard?.shareType === 'MULTI_FILE'
}

function projectCreateRequestStatusText(payload = {}) {
  return formatProjectCreateRequestStatusText(payload, formatTime)
}

function formatQuota(value) {
  return formatProjectQuotaText(value)
}
</script>

<style scoped>
.message-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.message-item.mine {
  justify-content: flex-end;
}

.message-item.mine :deep(.el-avatar) {
  order: 2;
}

.message-bubble {
  max-width: min(720px, 78%);
  padding: 10px 12px;
  border-radius: 8px;
  background: #f5f7fa;
}

.message-item.mine .message-bubble {
  background: #e9f3ff;
}

.message-meta,
.file-card__actions,
.mention-tags {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.message-meta {
  color: #909399;
  font-size: 12px;
}

.message-bubble p,
.announcement-card h4 {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.announcement-card,
.file-card,
.project-create-request-card {
  display: grid;
  gap: 10px;
}

.project-create-request-card {
  padding: 12px;
  border: 1px solid #dcdfe6;
  background: #fff;
}

.project-create-request-card small,
.project-create-request-card__status {
  color: #606266;
}

.project-create-request-card__actions {
  display: flex;
  gap: 8px;
}

.file-card ul {
  margin: 0;
  padding-left: 18px;
}

.file-card--broken,
.recalled-message {
  color: #909399;
}

.recalled-message {
  font-style: italic;
}
</style>
