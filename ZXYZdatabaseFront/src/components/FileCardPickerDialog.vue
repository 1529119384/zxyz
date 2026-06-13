<template>
  <el-dialog
    :model-value="visible"
    title="选择云盘资源"
    width="760"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <div class="picker-toolbar">
      <div class="picker-breadcrumb">
        <el-button text :disabled="pathStack.length <= 1" @click="goParent">返回上一级</el-button>
        <span>{{ currentPathLabel }}</span>
      </div>
      <div class="picker-summary">已选 {{ selectedIds.length }} 项</div>
    </div>

    <div v-loading="loading" class="picker-table">
      <el-table
        :data="items"
        row-key="id"
        @selection-change="handleSelectionChange"
        @row-dblclick="handleRowDblClick"
      >
        <el-table-column type="selection" width="55" />
        <el-table-column label="名称" min-width="260">
          <template #default="{ row }">
            <div class="name-cell">
              <svg class="icon">
                <use :xlink:href="getFileIcon(row)" />
              </svg>
              <span>{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="120">
          <template #default="{ row }">
            {{ formatSize(row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column label="修改时间" width="180">
          <template #default="{ row }">
            {{ row.modifyTime || '-' }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="emit('update:visible', false)">取消</el-button>
        <el-button type="primary" :disabled="!selectedItems.length" @click="handleConfirm"
          >发送</el-button
        >
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { fetchFileList } from '@/api/files'
import { getFileIcon } from '@/models/file'
import { formatSize } from '@/utils/format'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  teamId: {
    type: [Number, String],
    default: null,
  },
})

const emit = defineEmits(['update:visible', 'confirm'])

const ROOT_PARENT_ID = -1

const loading = ref(false)
const items = ref([])
const selectedItems = ref([])
const selectedIds = ref([])
const pathStack = ref([{ id: ROOT_PARENT_ID, name: '全部文件' }])

const currentParentId = computed(
  () => pathStack.value[pathStack.value.length - 1]?.id ?? ROOT_PARENT_ID,
)
const currentPathLabel = computed(() => pathStack.value.map((item) => item.name).join(' / '))

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) {
      return
    }
    pathStack.value = [{ id: ROOT_PARENT_ID, name: '全部文件' }]
    selectedItems.value = []
    selectedIds.value = []
    await loadItems()
  },
  { immediate: true },
)

async function loadItems() {
  loading.value = true
  try {
    const response = await fetchFileList(currentParentId.value, { teamId: props.teamId || null })
    items.value = Array.isArray(response?.data) ? response.data : []
  } finally {
    loading.value = false
  }
}

function handleVisibleChange(value) {
  emit('update:visible', value)
}

function handleSelectionChange(selection) {
  selectedItems.value = selection
  selectedIds.value = selection.map((item) => item.id)
}

async function handleRowDblClick(row) {
  if (row.type !== 0) {
    return
  }
  pathStack.value.push({
    id: row.id,
    name: row.fileName,
  })
  selectedItems.value = []
  selectedIds.value = []
  await loadItems()
}

async function goParent() {
  if (pathStack.value.length <= 1) {
    return
  }
  pathStack.value.pop()
  selectedItems.value = []
  selectedIds.value = []
  await loadItems()
}

function handleConfirm() {
  if (!selectedItems.value.length) {
    ElMessage.warning('请选择要分享的文件或文件夹')
    return
  }
  emit('confirm', selectedItems.value)
}
</script>

<style scoped>
.picker-toolbar,
.picker-breadcrumb,
.dialog-footer,
.name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.picker-toolbar {
  justify-content: space-between;
  margin-bottom: 12px;
}

.picker-summary {
  color: #667085;
  font-size: 12px;
}

.picker-table {
  min-height: 360px;
}

.name-cell {
  min-width: 0;
}

.icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
</style>
