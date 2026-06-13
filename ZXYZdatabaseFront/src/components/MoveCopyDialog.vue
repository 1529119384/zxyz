<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="820px"
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @update:model-value="handleVisibleChange"
  >
    <div class="move-copy-dialog">
      <div class="move-copy-dialog__summary">
        已选择 {{ items.length }} 项，当前目标目录：<span class="move-copy-dialog__path">{{
          currentPath || '/'
        }}</span>
      </div>

      <el-breadcrumb :separator-icon="ArrowRight" class="move-copy-dialog__breadcrumb">
        <el-breadcrumb-item @click="handleGoRoot"> 首页 </el-breadcrumb-item>
        <el-breadcrumb-item
          v-for="(name, index) in crumbArr"
          :key="`${name}-${index}`"
          @click="handleGoToCrumb(index)"
        >
          {{ name }}
        </el-breadcrumb-item>
      </el-breadcrumb>

      <el-table
        v-loading="loading"
        :data="list"
        row-key="id"
        height="360"
        empty-text="当前目录暂无文件"
        @row-dblclick="handleRowDblClick"
      >
        <el-table-column label="文件名" min-width="260">
          <template #default="{ row }">
            <div class="name-cell">
              <svg class="icon">
                <use :xlink:href="getFileIcon(row)" />
              </svg>
              <span class="file-name">{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="修改时间" width="180">
          <template #default="{ row }">
            {{ fmtTime(row.modifyTime) }}
          </template>
        </el-table-column>

        <el-table-column label="大小" width="120">
          <template #default="{ row }">
            {{ formatSize(row.fileSize) }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <CreateFolder
      v-model:visible="createFolderDialogVisible"
      :default-value="createFolderDefaultName"
      :submitting="createFolderSubmitting"
      @submit="handleCreateFolder"
    />

    <template #footer>
      <div class="move-copy-dialog__footer">
        <el-button type="primary" plain :disabled="submitting" @click="openCreateFolderDialog">
          新建文件夹
        </el-button>
        <div class="move-copy-dialog__footer-actions">
          <el-button :disabled="submitting" @click="closeDialog"> 取消 </el-button>
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="submitting"
            @click="handleConfirm"
          >
            确定
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ArrowRight } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import CreateFolder from '@/components/CreateFolder.vue'
import { useCreateFolderAction } from '@/composables/useCreateFolderAction'
import { useProvidedSpaceContext } from '@/composables/useCurrentSpaceContext'
import { useFolderPickerNavigation } from '@/composables/useFolderPickerNavigation'
import { getFileIcon } from '@/models/file'
import { fmtTime, formatSize } from '@/utils/format'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  mode: {
    type: String,
    default: 'move',
  },
  items: {
    type: Array,
    default: () => [],
  },
  sourcePath: {
    type: String,
    default: '',
  },
  pathToIdMap: {
    type: Object,
    default: () => ({}),
  },
})

const emit = defineEmits(['update:visible', 'submit'])
const spaceContext = useProvidedSpaceContext()
const {
  currentPath,
  currentParentId,
  list,
  loading,
  crumbArr,
  crumbPath,
  reset,
  enterFolder,
  goToPath,
  loadFolder,
} = useFolderPickerNavigation({
  spaceContext,
})
const submitting = ref(false)

const createFolderAction = useCreateFolderAction({
  spaceContext,
  getParentId: () => currentParentId.value,
  getSiblingEntries: () => list.value,
  onSuccess: async () => {
    await loadFolder(currentParentId.value)
  },
})

const dialogTitle = computed(() => (props.mode === 'copy' ? '复制' : '移动'))

function buildItemPath(item) {
  if (!item || item.type !== 0) {
    return ''
  }

  if (!props.sourcePath) {
    return `/${item.fileName}`
  }

  return `${props.sourcePath}/${item.fileName}`.replace(/\/{2,}/g, '/')
}

function isTargetInsideSelectedFolder() {
  return props.items.some((item) => {
    if (item?.type !== 0) {
      return false
    }

    const folderPath = buildItemPath(item)
    if (!folderPath) {
      return false
    }

    return currentPath.value === folderPath || currentPath.value.startsWith(`${folderPath}/`)
  })
}

async function initializeDialog() {
  if (props.sourcePath) {
    await goToPath(props.sourcePath, props.pathToIdMap)
  } else {
    await reset()
  }
}

async function handleConfirm() {
  if (submitting.value) {
    return
  }

  if (!props.items.length) {
    ElMessage.warning(`请选择要${dialogTitle.value}的文件或文件夹`)
    return
  }

  if (isTargetInsideSelectedFolder()) {
    ElMessage.warning('不能将文件夹移动或复制到其自身或子目录中')
    return
  }

  try {
    submitting.value = true
    // Dialog 仅负责收集目标目录，真正的移动/复制副作用交给上层 action 处理。
    emit('submit', {
      fileIds: props.items.map((item) => item.id),
      targetParentId: currentParentId.value,
      targetPath: currentPath.value,
      mode: props.mode,
      ...spaceContext.resolveRequestParams(),
    })
    emit('update:visible', false)
  } finally {
    submitting.value = false
  }
}

function handleVisibleChange(value) {
  if (!value && submitting.value) {
    return
  }

  emit('update:visible', value)
}

function closeDialog() {
  if (submitting.value) {
    return
  }

  createFolderDialogVisible.value = false
  emit('update:visible', false)
}

function handleRowDblClick(row) {
  if (row.type !== 0) {
    return
  }

  enterFolder(row)
}

function handleGoRoot() {
  reset()
}

function handleGoToCrumb(index) {
  goToPath(crumbPath(index), props.pathToIdMap)
}

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) {
      return
    }

    await initializeDialog()
  },
)

const {
  createFolderDialogVisible,
  createFolderSubmitting,
  createFolderDefaultName,
  openCreateFolderDialog,
  handleCreateFolder,
} = createFolderAction
</script>

<style scoped>
.move-copy-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.move-copy-dialog__summary {
  color: #606266;
  font-size: 14px;
}

.move-copy-dialog__path {
  color: #303133;
  font-weight: 500;
}

.move-copy-dialog__breadcrumb {
  user-select: none;
}

.move-copy-dialog__breadcrumb :deep(.el-breadcrumb__inner) {
  cursor: pointer;
}

.move-copy-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.move-copy-dialog__footer-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name-cell {
  display: flex;
  align-items: center;
}

.file-name {
  margin-left: 8px;
}

.icon {
  width: 20px;
  height: 20px;
  fill: currentColor;
}
</style>
