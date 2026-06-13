<template>
  <el-drawer
    :model-value="visible"
    title="更多"
    size="420px"
    @update:model-value="handleVisibleChange"
  >
    <div class="more-drawer">
      <section class="more-section">
        <h3>搜索聊天记录</h3>
        <div class="search-row">
          <el-input
            v-model="searchKeyword"
            clearable
            placeholder="搜索当前会话消息"
            @keyup.enter="submitSearch"
          />
          <el-button type="primary" @click="submitSearch">搜索</el-button>
        </div>
        <article
          v-for="message in searchResults"
          :key="message.messageId"
          class="search-result-item"
        >
          <strong>{{ displayName(message) }}</strong>
          <p>
            {{
              message.messageType === 'FILE_CARD'
                ? fileCardTitle(message.fileCard || {})
                : displaySearchContent(message)
            }}
          </p>
          <small>{{ formatTime(message.createTime) }}</small>
        </article>
        <el-empty v-if="!searchResults.length" description="暂无搜索结果" />
      </section>

      <section v-if="!isReadonlyConversation" class="more-section">
        <h3>会话操作</h3>
        <el-button type="primary" plain @click="emit('share-file')">分享文件</el-button>
      </section>

      <section v-if="isTeamConversation" class="more-section">
        <h3>群聊管理</h3>
        <el-button v-if="canOpenTeamSettings" plain @click="emit('open-team-settings')"
          >成员与团队设置</el-button
        >
        <el-button v-if="canOpenPermissionSettings" plain @click="emit('open-permission-settings')"
          >权限设置</el-button
        >
      </section>

      <section v-if="isMemberListConversation" class="more-section">
        <div class="section-title-row">
          <h3>群成员</h3>
          <span>{{ teamMembers.length }} 人</span>
        </div>
        <div v-if="visibleGroupMembers.length" class="member-grid">
          <button
            v-for="member in visibleGroupMembers"
            :key="member.userId"
            type="button"
            class="member-card-trigger"
            @click="emit('open-member-card', member, $event)"
          >
            <el-avatar :size="36" :src="member.avatar">{{
              displayMemberName(member).slice(0, 1)
            }}</el-avatar>
            <span>{{ displayMemberName(member) }}</span>
          </button>
        </div>
        <el-empty v-else description="暂无可显示成员" />
        <el-button v-if="canExpandGroupMembers" text @click="emit('expand-members')"
          >展开更多群成员</el-button
        >
      </section>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  searchResults: {
    type: Array,
    default: () => [],
  },
  teamMembers: {
    type: Array,
    default: () => [],
  },
  visibleGroupMembers: {
    type: Array,
    default: () => [],
  },
  isReadonlyConversation: {
    type: Boolean,
    default: false,
  },
  isTeamConversation: {
    type: Boolean,
    default: false,
  },
  isMemberListConversation: {
    type: Boolean,
    default: false,
  },
  canOpenTeamSettings: {
    type: Boolean,
    default: false,
  },
  canOpenPermissionSettings: {
    type: Boolean,
    default: false,
  },
  canExpandGroupMembers: {
    type: Boolean,
    default: false,
  },
  displayName: {
    type: Function,
    required: true,
  },
  displaySearchContent: {
    type: Function,
    required: true,
  },
  displayMemberName: {
    type: Function,
    required: true,
  },
  formatTime: {
    type: Function,
    required: true,
  },
  fileCardTitle: {
    type: Function,
    required: true,
  },
})

const emit = defineEmits([
  'update:visible',
  'search',
  'share-file',
  'open-team-settings',
  'open-permission-settings',
  'open-member-card',
  'expand-members',
])

const searchKeyword = ref('')

function handleVisibleChange(value) {
  emit('update:visible', value)
}

function submitSearch() {
  emit('search', searchKeyword.value.trim())
}
</script>

<style scoped>
.more-drawer,
.more-section {
  display: grid;
  gap: 10px;
}

.more-section {
  padding-bottom: 16px;
  border-bottom: 1px solid #e4e7ed;
}

.more-section h3 {
  margin: 0;
  font-size: 15px;
}

.section-title-row,
.search-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.section-title-row span {
  color: #909399;
  font-size: 13px;
}

.search-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
}

.member-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.member-card-trigger {
  min-width: 0;
  padding: 8px 4px;
  border: 0;
  background: transparent;
  display: grid;
  justify-items: center;
  gap: 6px;
  cursor: pointer;
}

.member-card-trigger:hover {
  background: #f5f7fa;
}

.member-card-trigger span {
  width: 100%;
  overflow: hidden;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.search-result-item {
  display: grid;
  gap: 4px;
  padding: 12px 0;
  border-bottom: 1px solid #e4e7ed;
}

.search-result-item p {
  margin: 0;
  word-break: break-word;
}
</style>
