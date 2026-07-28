<template>
  <div class="my-share-page">
    <div class="page-header">
      <div>
        <h2 v-once class="page-title">我的分享</h2>
        <p v-once class="page-subtitle">进入页面时会重新拉取当前账号的分享记录</p>
      </div>
      <el-button :loading="loading" @click="loadList"> 刷新 </el-button>
    </div>

    <el-table v-loading="loading" :data="list" row-key="shareId" empty-text="暂无分享记录">
      <el-table-column label="分享链接" min-width="340">
        <template #default="{ row }">
          <div class="share-link-cell">
            <span class="share-link">{{ row.shareUrl }}</span>
            <div class="share-link-meta">
              <span>访问人数：{{ row.currentAccessCount }}</span>
              <span v-if="row.maxAccessCount > 0">/ {{ row.maxAccessCount }}</span>
              <span v-else>/ 不限制</span>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="提取码" width="100">
        <template #default="{ row }">
          {{ row.hasPassword ? '已设置' : '-' }}
        </template>
      </el-table-column>

      <el-table-column label="有效期" width="180">
        <template #default="{ row }">
          {{ formatShareExpireText(row) }}
        </template>
      </el-table-column>

      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="resolveStatusType(row.status)">
            {{ row.statusText }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">
          {{ fmtTime(row.createTime) }}
        </template>
      </el-table-column>

      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="copyShareRecord(row)"> 复制链接 </el-button>
          <el-button
            link
            type="danger"
            :disabled="row.status !== 0"
            @click="cancelShareRecord(row)"
          >
            取消分享
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        @current-change="handleCurrentChange"
        @size-change="handleSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { defineOptions } from 'vue'

import { formatShareExpireText } from '@/models/share'
import { useMyShareList } from '@/composables/useMyShareList'
import { fmtTime } from '@/utils/format'

defineOptions({ name: 'MyShare' })

const {
  loading,
  list,
  total,
  page,
  pageSize,
  loadList,
  handleCurrentChange,
  handleSizeChange,
  copyShareRecord,
  cancelShareRecord,
} = useMyShareList()

function resolveStatusType(status) {
  if (status === 0) {
    return 'success'
  }

  if (status === 1) {
    return 'info'
  }

  return 'warning'
}
</script>

<style scoped>
.my-share-page {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.page-title {
  font-size: 24px;
  color: #303133;
}

.page-subtitle {
  margin-top: 4px;
  color: #909399;
  font-size: 13px;
}

.share-link-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.share-link {
  color: #303133;
  word-break: break-all;
}

.share-link-meta {
  color: #909399;
  font-size: 12px;
}

.pagination-wrapper {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
</style>
