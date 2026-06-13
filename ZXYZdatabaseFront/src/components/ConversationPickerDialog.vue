<template>
  <el-dialog
    :model-value="visible"
    title="选择会话"
    width="520"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="conversation-picker">
      <button
        v-for="conversation in conversations"
        :key="conversation.id"
        type="button"
        class="conversation-option"
        @click="handleSelect(conversation)"
      >
        <el-avatar
          :size="40"
          :src="
            conversation.type === CONVERSATION_TYPE_DIRECT
              ? conversation.peerAvatar
              : conversation.avatar
          "
        >
          {{ conversationTitle(conversation).slice(0, 1) }}
        </el-avatar>
        <div class="conversation-option__content">
          <strong>{{ conversationTitle(conversation) }}</strong>
          <small>{{ conversation.type === CONVERSATION_TYPE_DIRECT ? '私聊' : '群聊' }}</small>
        </div>
      </button>
      <el-empty v-if="!conversations.length" description="暂无可用会话" />
    </div>
  </el-dialog>
</template>

<script setup>
import { DIRECT } from '@/constants/conversationTypes'

defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  conversations: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['update:visible', 'select'])

const CONVERSATION_TYPE_DIRECT = DIRECT

function conversationTitle(conversation) {
  if (conversation.type === DIRECT) {
    return conversation.peerName || conversation.peerUsername || `用户 ${conversation.peerUserId}`
  }
  return conversation.name || `团队 ${conversation.teamId}`
}

function handleSelect(conversation) {
  emit('select', conversation)
}
</script>

<style scoped>
.conversation-picker {
  display: grid;
  gap: 12px;
}

.conversation-option {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  text-align: left;
}

.conversation-option:hover {
  border-color: #c6e2ff;
  background: #f9fbff;
}

.conversation-option__content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.conversation-option__content strong,
.conversation-option__content small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-option__content small {
  color: #667085;
}
</style>
