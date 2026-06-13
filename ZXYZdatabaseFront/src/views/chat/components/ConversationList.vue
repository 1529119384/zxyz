<template>
  <aside class="conversation-panel">
    <header class="panel-header">
      <div>
        <h1>消息</h1>
        <p>{{ wsStatusText }}</p>
      </div>
    </header>

    <div class="conversation-list">
      <button
        v-for="conversation in orderedConversations"
        :key="conversation.id"
        type="button"
        class="conversation-item"
        :class="{
          active: conversation.id === activeConversationId,
          pinned: pinnedConversationIds.includes(conversation.id),
        }"
        @click="emit('select', conversation)"
        @contextmenu.prevent="emit('contextmenu', $event, conversation)"
      >
        <el-avatar :size="38" :src="conversationAvatar(conversation)">
          {{ conversationTitle(conversation).slice(0, 1) }}
        </el-avatar>
        <div class="conversation-content">
          <strong>{{ conversationTitle(conversation) }}</strong>
          <small>{{ conversationTypeText(conversation) }}</small>
        </div>
        <el-badge :value="conversation.unreadCount" :hidden="!conversation.unreadCount" />
      </button>
      <el-empty v-if="!orderedConversations.length" description="暂无会话" />
    </div>
  </aside>
</template>

<script setup>
defineProps({
  wsStatusText: {
    type: String,
    default: '',
  },
  orderedConversations: {
    type: Array,
    default: () => [],
  },
  activeConversationId: {
    type: [String, Number, null],
    default: null,
  },
  pinnedConversationIds: {
    type: Array,
    default: () => [],
  },
  conversationAvatar: {
    type: Function,
    required: true,
  },
  conversationTitle: {
    type: Function,
    required: true,
  },
  conversationTypeText: {
    type: Function,
    required: true,
  },
})

const emit = defineEmits(['select', 'contextmenu'])
</script>

<style scoped>
.conversation-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid #e4e7ed;
}

.panel-header h1,
.panel-header p {
  margin: 0;
}

.panel-header p {
  color: #909399;
  font-size: 12px;
}

.conversation-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.conversation-item {
  width: 100%;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid #f0f2f5;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.conversation-item.active,
.conversation-item:hover {
  background: #eef5ff;
}

.conversation-item.pinned {
  background: #f7fbff;
}

.conversation-content {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.conversation-content strong,
.conversation-content small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-content small {
  color: #909399;
  font-size: 12px;
}
</style>
