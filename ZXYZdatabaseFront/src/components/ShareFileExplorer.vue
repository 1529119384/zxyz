<template>
  <div class="share-file-explorer">
    <div class="share-file-explorer__toolbar">
      <el-breadcrumb :separator-icon="ArrowRight" class="breadcrumb">
        <el-breadcrumb-item @click="emit('navigate', '')"> 根目录 </el-breadcrumb-item>
        <el-breadcrumb-item
          v-for="(name, index) in crumbs"
          :key="`${name}-${index}`"
          @click="emit('navigate', buildCrumbPath(index))"
        >
          {{ name }}
        </el-breadcrumb-item>
      </el-breadcrumb>

      <el-button type="primary" :disabled="archiveDisabled" @click="emit('archive-download')">
        打包下载当前目录
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="list"
      row-key="id"
      class="share-table"
      :empty-text="emptyText"
      @row-dblclick="handleRowDblClick"
    >
      <el-table-column min-width="260" label="文件名">
        <template #default="{ row }">
          <div class="name-cell">
            <svg class="icon">
              <use :xlink:href="getFileIcon(row)" />
            </svg>
            <span v-if="row.type === 0" class="folder-name">
              {{ row.fileName }}
            </span>
            <span v-else class="file-name">
              {{ row.fileName }}
            </span>
            <el-tag v-if="row.invalid" size="small" type="danger">
              {{ row.invalidText || '已失效' }}
            </el-tag>
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

      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <div class="actions">
            <el-button
              v-if="row.type === 0"
              link
              type="primary"
              :disabled="row.invalid"
              @click.stop="emit('archive-download-folder', row)"
            >
              打包下载
            </el-button>
            <el-button
              v-else
              link
              type="primary"
              :disabled="row.invalid"
              @click.stop="emit('download', row)"
            >
              下载
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'

import { getFileIcon } from '@/models/file'
import { splitSharePath } from '@/models/share'
import { fmtTime, formatSize } from '@/utils/format'

const props = defineProps({
  list: {
    type: Array,
    default: () => [],
  },
  currentPath: {
    type: String,
    default: '',
  },
  loading: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits([
  'download',
  'open-folder',
  'archive-download',
  'archive-download-folder',
  'navigate',
])

const crumbs = computed(() => splitSharePath(props.currentPath))
const archiveDisabled = computed(() => !props.list.some((item) => !item.invalid))
const emptyText = computed(() => (props.currentPath ? '当前目录暂无内容' : '暂无分享文件'))

function buildCrumbPath(index) {
  return crumbs.value.slice(0, index + 1).join('/')
}

function handleRowDblClick(row) {
  if (row?.type !== 0 || row.invalid) {
    return
  }

  emit('open-folder', row)
}
</script>

<style scoped>
.share-file-explorer {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.share-file-explorer__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.breadcrumb :deep(.el-breadcrumb__inner) {
  cursor: pointer;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.folder-name {
  color: #303133;
}

.file-name {
  color: #303133;
}

.actions {
  display: flex;
  align-items: center;
}
</style>
