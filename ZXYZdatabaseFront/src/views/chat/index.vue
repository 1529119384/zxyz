<template>
  <section class="chat-page">
    <el-empty
      v-if="!teamStore.teams.length && !imChat.conversations.length"
      description="暂无可用会话"
    />
    <el-empty
      v-else-if="teamStore.teams.length && !teamStore.selectedTeamId"
      description="请先在左下角选择团队"
    />

    <el-splitter v-else class="chat-splitter">
      <el-splitter-panel size="32%" min="260px" max="460px">
        <ConversationList
          :ws-status-text="wsStatusText"
          :ordered-conversations="orderedConversations"
          :active-conversation-id="imChat.activeConversationId"
          :pinned-conversation-ids="pinnedConversationIds"
          :conversation-avatar="conversationAvatar"
          :conversation-title="conversationTitle"
          :conversation-type-text="conversationTypeText"
          @select="actions.openConversation"
          @contextmenu="openConversationMenu"
        />
      </el-splitter-panel>

      <el-splitter-panel :min="360">
        <main class="message-panel">
          <template v-if="activeConversation">
            <header class="message-header">
              <div>
                <h2>{{ headerTitle }}</h2>
                <p>{{ conversationTypeText(activeConversation) }}</p>
              </div>
              <div class="header-actions">
                <el-button @click="moreDrawerVisible = true">更多</el-button>
              </div>
            </header>

            <div class="message-list">
              <VirtualMessageList
                v-if="messages.length"
                ref="virtualListRef"
                :messages="messages"
                :current-user-id="currentUserId"
                :mention-name="actions.mentionName"
                :can-review-project-create-requests="canReviewProjectCreateRequests"
                :reviewing-application-id="reviewingApplicationId"
                @load-older="loadOlderMessages"
                @contextmenu="openMessageMenu"
                @recall="actions.recallMessageItem"
                @file-card-action="actions.handleFileCardAction"
                @review-project-create-request="actions.reviewProjectCreateRequest"
              />
              <el-empty v-else description="暂无消息" />
            </div>

            <MessageEditor
              v-model="draft"
              :sending-disabled="sendingDisabled"
              :is-readonly="isReadonlyConversation"
              :readonly-tip="readonlyConversationTip"
              @submit="handleSend"
            />
          </template>
          <el-empty v-else description="请选择一个会话" />
        </main>
      </el-splitter-panel>
    </el-splitter>

    <TeamSettingDrawer v-model:visible="settingVisible" />
    <FileCardPickerDialog
      :visible="filePickerVisible"
      :team-id="activeConversation?.teamId || teamStore.selectedTeamId"
      @update:visible="filePickerVisible = $event"
      @confirm="actions.handleFilePickerConfirm"
    />

    <ChatMoreDrawer
      v-model:visible="moreDrawerVisible"
      :search-results="imChat.searchResults"
      :team-members="teamStore.teamMembers"
      :visible-group-members="visibleGroupMembers"
      :is-readonly-conversation="isReadonlyConversation"
      :is-team-conversation="isTeamConversation"
      :is-member-list-conversation="isMemberListConversation"
      :can-open-team-settings="canOpenTeamSettings"
      :can-open-permission-settings="canOpenPermissionSettings"
      :can-expand-group-members="canExpandGroupMembers"
      :display-name="actions.displayName"
      :display-search-content="actions.displaySearchContent"
      :display-member-name="actions.displayMemberName"
      :format-time="actions.formatTime"
      :file-card-title="actions.fileCardTitle"
      @search="actions.searchMessages"
      @share-file="handleOpenFilePicker"
      @open-team-settings="openTeamSettingsFromMore"
      @open-permission-settings="openPermissionSettings"
      @open-member-card="actions.openMemberCard"
      @expand-members="actions.expandMembers"
    />

    <el-popover
      v-model:visible="actions.memberCardVisible.value"
      virtual-triggering
      :virtual-ref="actions.memberCardVirtualRef.value"
      placement="left-start"
      width="260"
    >
      <div v-if="actions.selectedMember.value" class="member-profile-card">
        <el-avatar :size="54" :src="actions.selectedMember.value.avatar">{{
          actions.displayMemberName(actions.selectedMember.value).slice(0, 1)
        }}</el-avatar>
        <div class="member-profile-card__main">
          <strong>{{ actions.displayMemberName(actions.selectedMember.value) }}</strong>
          <span>{{
            actions.selectedMember.value.username || `用户 ${actions.selectedMember.value.userId}`
          }}</span>
        </div>
        <el-button
          type="primary"
          plain
          :disabled="actions.selectedMember.value.userId === currentUserId"
          @click="actions.startDirectChatFromMember(actions.selectedMember.value)"
          >发私信</el-button
        >
      </div>
    </el-popover>

    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="contextMenuStyle"
      role="menu"
      @keydown.escape="contextMenu.visible = false"
    >
      <button
        type="button"
        role="menuitem"
        @click="togglePinConversation"
        @keydown.enter="togglePinConversation"
      >
        {{ contextMenuActionText }}
      </button>
    </div>
  </section>
</template>

<script setup>
import { defineOptions } from 'vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

defineOptions({ name: 'ChatHome' })

import FileCardPickerDialog from '@/components/FileCardPickerDialog.vue'
import TeamSettingDrawer from '@/components/TeamSettingDrawer.vue'
import { useTeamManagement } from '@/composables/team/useTeamManagement'
import { useImWorkspace } from '@/composables/useImWorkspace'
import { SYSTEM, TEAM, TEAM_NOTIFICATION } from '@/constants/conversationTypes'
import {
  TEAM_MANAGEMENT_PERMISSION_CODES,
  TEAM_PERMISSION_CENTER_CODES,
} from '@/constants/teamPermissions'
import { useChatStore } from '@/store/chat'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'

import ChatMoreDrawer from './components/ChatMoreDrawer.vue'
import ConversationList from './components/ConversationList.vue'
import MessageEditor from './components/MessageEditor.vue'
import VirtualMessageList from './components/VirtualMessageList.vue'
import { useChatContextMenu } from './composables/useChatContextMenu'
import { useChatConversationView } from './composables/useChatConversationView'
import { useChatPageActions } from './composables/useChatPageActions'
import { useChatVisibilitySync } from './composables/useChatVisibilitySync'
import { usePinnedConversations } from './composables/usePinnedConversations'
import { useVirtualScroll } from './composables/useVirtualScroll'

const imChat = useChatStore()
const teamStore = useTeamStore()
const currentUserStore = useCurrentUserStore()
const router = useRouter()
const imWorkspace = useImWorkspace({ teamStore, chatStore: imChat })
const { hasTeamPermission, hasAnyTeamPermission } = useTeamManagement({
  teamStore,
  chatStore: imChat,
  currentUserStore,
  router,
})

const virtualListRef = ref(null)
const settingVisible = ref(false)
const filePickerVisible = ref(false)
const moreDrawerVisible = ref(false)
const draft = ref('')
const scrollRafId = ref(null)

const { pinnedConversationIds, togglePin } = usePinnedConversations()

const currentUserId = computed(() => currentUserStore.profile?.id ?? null)
const activeConversation = computed(() => imChat.activeConversation)
const messages = computed(() => imChat.activeMessages)

const { isNearBottom, scrollToBottom, loadOlderMessages, handleNewMessage, resetForConversation } =
  useVirtualScroll({ messages, chatStore: imChat, listRef: virtualListRef })

const isSystemConversation = computed(() => activeConversation.value?.type === SYSTEM)
const isTeamNotificationConversation = computed(
  () => activeConversation.value?.type === TEAM_NOTIFICATION,
)
const isReadonlyConversation = computed(
  () => isSystemConversation.value || isTeamNotificationConversation.value,
)
const readonlyConversationTip = computed(() =>
  isTeamNotificationConversation.value ? '团队消息为只读会话' : '系统消息为只读会话',
)
const isTeamConversation = computed(() => activeConversation.value?.type === TEAM)
const activeTeamId = computed(() => {
  const teamId = Number(activeConversation.value?.teamId || teamStore.selectedTeamId)
  return Number.isSafeInteger(teamId) && teamId > 0 ? teamId : null
})
const canReviewProjectCreateRequests = computed(() =>
  hasTeamPermission({
    teamId: activeConversation.value?.teamId || teamStore.selectedTeamId,
    code: 'team:project:manage',
  }),
)
const sendingDisabled = computed(
  () => isReadonlyConversation.value || !draft.value.trim() || !imChat.activeConversationId,
)
const canOpenTeamSettings = computed(() => {
  if (!isTeamConversation.value || !activeTeamId.value) return false
  return hasAnyTeamPermission(TEAM_MANAGEMENT_PERMISSION_CODES, activeTeamId.value)
})
const canOpenPermissionSettings = computed(() => {
  if (!isTeamConversation.value || !activeTeamId.value) return false
  return hasAnyTeamPermission(TEAM_PERMISSION_CENTER_CODES, activeTeamId.value)
})

const {
  wsStatusText,
  orderedConversations,
  headerTitle,
  conversationAvatar,
  conversationTitle,
  conversationTypeText,
} = useChatConversationView({ chatStore: imChat, activeConversation, pinnedConversationIds })

const actions = useChatPageActions({
  chatStore: imChat,
  teamStore,
  router,
  currentUserStore,
  currentUserId,
  activeConversation,
  moreDrawerVisible,
  scrollToBottom,
  resetForConversation,
  filePickerVisible,
})

// 解构 computed/ref，使模板中自动解包为原始值
const {
  visibleGroupMembers,
  isMemberListConversation,
  canExpandGroupMembers,
  reviewingApplicationId,
} = actions

const {
  contextMenu,
  contextMenuStyle,
  contextMenuActionText,
  openConversationMenu,
  openMessageMenu,
  togglePinConversation,
} = useChatContextMenu({ activeConversation, pinnedConversationIds, togglePin })

useChatVisibilitySync({ imChat })

onMounted(() => {
  refreshCurrentTeam()
})

onUnmounted(() => {
  if (scrollRafId.value) {
    cancelAnimationFrame(scrollRafId.value)
    scrollRafId.value = null
  }
})

watch(
  () => teamStore.selectedTeamId,
  async () => {
    imChat.clearActiveConversation()
    actions.resetMemberPanel()
    await refreshCurrentTeam()
  },
)

watch(
  () => activeConversation.value?.id,
  () => {
    actions.resetConversationState()
  },
)

watch(
  () => messages.value.length,
  (newLen, oldLen) => {
    if (newLen > oldLen) {
      if (scrollRafId.value) cancelAnimationFrame(scrollRafId.value)
      scrollRafId.value = requestAnimationFrame(() => {
        handleNewMessage()
        scrollRafId.value = null
      })
    }
  },
)

async function refreshCurrentTeam() {
  if (!teamStore.selectedTeamId) return
  await imWorkspace
    .loadSelectedTeamChat()
    .catch((error) => handleBusinessError(error, '加载聊天数据失败'))
}

async function handleSend() {
  if (sendingDisabled.value) return
  const content = draft.value.trim()
  draft.value = ''
  const ok = await actions.submitMessage(content)
  if (!ok) {
    draft.value = content
  }
}

function handleOpenFilePicker() {
  actions.openFilePickerFromMore(isReadonlyConversation.value)
}

function syncActiveTeamContext() {
  const teamId = activeTeamId.value
  if (!teamId) return null
  if (Number(teamStore.selectedTeamId) !== teamId) {
    teamStore.setSelectedTeam(teamId)
  }
  return teamId
}

async function openTeamSettingsFromMore() {
  if (!canOpenTeamSettings.value) return
  const teamId = syncActiveTeamContext()
  if (!teamId) return
  moreDrawerVisible.value = false
  await nextTick()
  settingVisible.value = true
}

async function openPermissionSettings() {
  if (!canOpenPermissionSettings.value) return
  const teamId = syncActiveTeamContext()
  if (!teamId) return
  moreDrawerVisible.value = false
  await nextTick()
  router.push({
    name: 'permissionCenter',
    query: { scope: 'team', teamId: String(teamId) },
  })
}
</script>

<style scoped>
.chat-page,
.chat-splitter {
  height: 100%;
}

.message-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.message-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid #e4e7ed;
}

.message-header h2,
.message-header p {
  margin: 0;
}

.message-header p {
  color: #909399;
  font-size: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.message-list {
  flex: 1;
  min-height: 0;
}

.member-profile-card {
  display: grid;
  justify-items: center;
  gap: 10px;
  text-align: center;
}

.member-profile-card__main {
  display: grid;
  gap: 4px;
}

.member-profile-card__main span {
  color: #909399;
  font-size: 13px;
}

.context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 120px;
  padding: 6px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.context-menu button {
  width: 100%;
  padding: 8px 10px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.context-menu button:hover {
  background: #f5f7fa;
}
</style>
