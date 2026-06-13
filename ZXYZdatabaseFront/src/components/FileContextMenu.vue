<!-- 文件上下文菜单 -->
<template>
  <Teleport to="body">
    <div v-if="visible" ref="menuRef" class="context-menu" :style="menuStyle" @click.stop>
      <template v-for="(item, index) in menuItems" :key="`${item.action || 'divider'}-${index}`">
        <el-divider v-if="item.type === 'divider'" class="context-menu__divider" />

        <button
          v-else
          type="button"
          class="context-menu__item"
          :disabled="item.disabled"
          @mousedown.prevent.stop="handleAction(item)"
        >
          {{ item.label }}
        </button>
      </template>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useEventListener } from '@vueuse/core'

import { FILE_CONTEXT_ACTIONS } from '@/models/fileActions'
import { logger } from '@/utils/logger'
import { isProjectVirtualEntry } from '@/utils/projectVirtualFolder'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  position: {
    type: Object,
    default: () => ({ x: 0, y: 0 }),
  },
  contextType: {
    type: String,
    default: 'blank',
  },
  mode: {
    type: String,
    default: 'space',
  },
  selectedItems: {
    type: Array,
    default: () => [],
  },
  targetItem: {
    type: Object,
    default: null,
  },
  canWrite: {
    type: Boolean,
    default: false,
  },
  virtualDirectory: {
    type: String,
    default: '',
  },
  canManageProjects: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['action', 'close'])

const menuRef = ref(null)
const DIVIDER_ITEM = { type: 'divider' }
const ACTIONS = FILE_CONTEXT_ACTIONS

function buildMenuSection(items = []) {
  return items.filter(Boolean)
}

function appendSection(menu, items = []) {
  const section = buildMenuSection(items)
  if (!section.length) {
    return
  }

  if (menu.length) {
    menu.push(DIVIDER_ITEM)
  }
  menu.push(...section)
}

function getSelectionItems() {
  if (props.selectedItems.length) {
    return props.selectedItems
  }

  return props.targetItem ? [props.targetItem] : []
}

const selectionState = computed(() => {
  const items = getSelectionItems()
  const count = items.length

  if (!count) {
    return {
      key: 'blank',
      count: 0,
    }
  }

  const folderCount = items.filter((item) => item?.type === 0).length
  const fileCount = count - folderCount

  if (folderCount === count) {
    return {
      key: count === 1 ? 'singleFolder' : 'multiFolder',
      count,
    }
  }

  if (fileCount === count) {
    return {
      key: count === 1 ? 'singleFile' : 'multiFile',
      count,
    }
  }

  return {
    key: 'mixed',
    count,
  }
})

const isRecycleMode = computed(() => props.mode === 'recycle')
const hasVirtualSelection = computed(() =>
  getSelectionItems().some((item) => isProjectVirtualEntry(item)),
)

function createRecycleMenu(count) {
  const menu = []

  if (props.contextType === 'blank') {
    appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])
    return menu
  }

  if (!props.canWrite) {
    appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])
    return menu
  }

  const restoreAction = {
    action: count > 1 ? ACTIONS.RESTORE_SELECTED : ACTIONS.RESTORE,
    label: count > 1 ? `取消删除（${count}）` : '取消删除',
  }
  const deleteForeverAction = {
    action: count > 1 ? ACTIONS.DELETE_FOREVER_SELECTED : ACTIONS.DELETE_FOREVER,
    label: count > 1 ? `彻底删除（${count}）` : '彻底删除',
  }

  appendSection(menu, [restoreAction, deleteForeverAction])
  appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])

  return menu
}

const menuItems = computed(() => {
  const menu = []
  const { key, count } = selectionState.value

  if (isRecycleMode.value) {
    return createRecycleMenu(count)
  }

  if (props.virtualDirectory === 'projectRoot' && key === 'blank') {
    appendSection(menu, [
      {
        action: ACTIONS.CREATE_PROJECT_GROUP,
        label: props.canManageProjects ? '新建项目组' : '申请项目组',
      },
    ])
    appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])
    return menu
  }

  if (hasVirtualSelection.value) {
    appendSection(menu, [{ action: ACTIONS.OPEN, label: '打开' }])
    const projectItem = getSelectionItems().find((item) => item?.virtualType === 'projectFolder')
    if (projectItem?.manageable) {
      appendSection(menu, [{ action: ACTIONS.PROJECT_SETTINGS, label: '项目配置' }])
    }
    if (props.virtualDirectory === 'projectRoot') {
      appendSection(menu, [
        {
          action: ACTIONS.CREATE_PROJECT_GROUP,
          label: props.canManageProjects ? '新建项目组' : '申请项目组',
        },
      ])
    }
    appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])
    return menu
  }

  const writeActions = props.canWrite
    ? [
        { action: ACTIONS.CREATE_FOLDER, label: '新建文件夹' },
        { action: ACTIONS.UPLOAD_FILE, label: '上传文件' },
        { action: ACTIONS.UPLOAD_FOLDER, label: '上传文件夹' },
      ]
    : []

  if (key === 'blank') {
    appendSection(menu, writeActions)
    appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])
    return menu
  }

  if (key === 'singleFolder') {
    appendSection(menu, [
      { action: ACTIONS.OPEN, label: '打开' },
      { action: ACTIONS.OPEN_IN_NEW_TAB, label: '新标签页打开' },
      { action: ACTIONS.COPY_FILE_NAME, label: '复制文件名称' },
      { action: ACTIONS.ARCHIVE_DOWNLOAD, label: '打包下载' },
      { action: ACTIONS.SHARE_FILE, label: '分享文件' },
      { action: ACTIONS.SEND_TO_CONVERSATION, label: '发送到会话' },
    ])
    appendSection(
      menu,
      props.canWrite
        ? [
            { action: ACTIONS.RENAME, label: '重命名' },
            { action: ACTIONS.MOVE, label: '移动' },
            { action: ACTIONS.COPY, label: '复制' },
            { action: ACTIONS.DELETE, label: `删除（${count}）` },
          ]
        : [],
    )
  }

  if (key === 'multiFolder') {
    appendSection(menu, [
      { action: ACTIONS.COPY_FILE_NAME, label: '复制文件名称' },
      { action: ACTIONS.ARCHIVE_DOWNLOAD, label: '打包下载' },
      { action: ACTIONS.SHARE_FILE, label: '分享文件' },
      { action: ACTIONS.SEND_TO_CONVERSATION, label: '发送到会话' },
    ])
    appendSection(
      menu,
      props.canWrite
        ? [
            { action: ACTIONS.MOVE, label: '移动' },
            { action: ACTIONS.COPY, label: '复制' },
            { action: ACTIONS.DELETE, label: `删除（${count}）` },
          ]
        : [],
    )
  }

  if (key === 'singleFile') {
    appendSection(menu, [
      { action: ACTIONS.PREVIEW, label: '预览' },
      { action: ACTIONS.DOWNLOAD, label: '下载' },
      { action: ACTIONS.COPY_DOWNLOAD_LINK, label: '复制下载链接' },
      { action: ACTIONS.COPY_FILE_NAME, label: '复制文件名称' },
      { action: ACTIONS.ARCHIVE_DOWNLOAD, label: '打包下载' },
    ])
    appendSection(menu, [
      { action: ACTIONS.GET_DIRECT_LINK, label: '获取直链' },
      { action: ACTIONS.SHARE_FILE, label: '分享文件' },
      { action: ACTIONS.SEND_TO_CONVERSATION, label: '发送到会话' },
      // { action: 'generateShortLink', label: '生成短链' },
      { action: ACTIONS.GET_DIRECT_AND_SHORT_LINK, label: '同时获取' },
    ])
    appendSection(
      menu,
      props.canWrite
        ? [
            { action: ACTIONS.RENAME, label: '重命名' },
            { action: ACTIONS.MOVE, label: '移动' },
            { action: ACTIONS.COPY, label: '复制' },
            { action: ACTIONS.DELETE, label: `删除（${count}）` },
          ]
        : [],
    )
  }

  if (key === 'multiFile') {
    appendSection(menu, [
      { action: ACTIONS.BATCH_DOWNLOAD, label: '批量下载' },
      { action: ACTIONS.COPY_DOWNLOAD_LINK, label: '复制下载链接' },
      { action: ACTIONS.COPY_FILE_NAME, label: '复制文件名称' },
      { action: ACTIONS.ARCHIVE_DOWNLOAD, label: '打包下载' },
    ])
    appendSection(menu, [
      { action: ACTIONS.GET_DIRECT_LINK, label: '获取直链' },
      { action: ACTIONS.SHARE_FILE, label: '分享文件' },
      { action: ACTIONS.SEND_TO_CONVERSATION, label: '发送到会话' },
      // { action: 'generateShortLink', label: '生成短链' },
      { action: ACTIONS.GET_DIRECT_AND_SHORT_LINK, label: '同时获取' },
    ])
    appendSection(
      menu,
      props.canWrite
        ? [
            { action: ACTIONS.MOVE, label: '移动' },
            { action: ACTIONS.COPY, label: '复制' },
            { action: ACTIONS.DELETE, label: `删除（${count}）` },
          ]
        : [],
    )
  }

  if (key === 'mixed') {
    appendSection(menu, [
      { action: ACTIONS.COPY_FILE_NAME, label: '复制文件名称' },
      { action: ACTIONS.ARCHIVE_DOWNLOAD, label: '打包下载' },
      { action: ACTIONS.SHARE_FILE, label: '分享文件' },
      { action: ACTIONS.SEND_TO_CONVERSATION, label: '发送到会话' },
    ])
    appendSection(
      menu,
      props.canWrite
        ? [
            { action: ACTIONS.MOVE, label: '移动' },
            { action: ACTIONS.COPY, label: '复制' },
            { action: ACTIONS.DELETE, label: `删除（${count}）` },
          ]
        : [],
    )
  }

  appendSection(menu, writeActions)
  appendSection(menu, [{ action: ACTIONS.REFRESH, label: '刷新' }])

  return menu
})

const menuStyle = computed(() => ({
  left: `${props.position.x}px`,
  top: `${props.position.y}px`,
}))

function closeMenu() {
  emit('close')
}

function handleAction(item) {
  if (item.disabled) {
    return
  }

  logger.debug('[FileContextMenu] handleAction', {
    action: item.action,
    contextType: props.contextType,
    canWrite: props.canWrite,
    selectedCount: props.selectedItems.length,
    hasTargetItem: Boolean(props.targetItem),
  })

  emit('action', {
    action: item.action,
    contextType: props.contextType,
    selectedItems: props.selectedItems,
    targetItem: props.targetItem,
  })
  closeMenu()
}

function handlePointerDown(event) {
  if (menuRef.value?.contains(event.target)) {
    return
  }

  closeMenu()
}

useEventListener(document, 'mousedown', (event) => {
  if (props.visible) handlePointerDown(event)
})
useEventListener(document, 'contextmenu', (event) => {
  if (props.visible) handlePointerDown(event)
})
</script>

<style scoped>
.context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 180px;
  padding: 4px 0;
  border: 1px solid #e4e7ed;
  border-radius: 0;
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.15);
}

.context-menu__item {
  width: 100%;
  display: block;
  padding: 8px 12px;
  border: none;
  border-radius: 0;
  text-align: left;
  background: transparent;
  color: #303133;
  cursor: pointer;
}

.context-menu__item + .context-menu__item {
  margin-top: 0;
}

:deep(.context-menu__divider.el-divider--horizontal) {
  margin: 2px 0;
}

.context-menu__item:hover:not(:disabled) {
  background: #f5f7fa;
}

.context-menu__item:disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}
</style>
