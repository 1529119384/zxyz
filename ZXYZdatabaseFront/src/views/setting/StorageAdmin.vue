<template>
  <section class="settings-panel storage-admin-panel">
    <div class="panel-title">
      <div class="admin-toolbar-title">
        <div>
          <h2>存储管理</h2>
          <span>管理存储提供者配置和健康状态</span>
        </div>
        <div class="admin-toolbar-actions">
          <el-button @click="loadProviders">刷新</el-button>
        </div>
      </div>
    </div>

    <el-table v-loading="loading" :data="providers" height="480">
      <el-table-column prop="providerId" label="提供者 ID" min-width="120" />
      <el-table-column prop="displayName" label="显示名称" min-width="150" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
            {{ row.enabled ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="默认" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isDefault ? 'warning' : 'info'" size="small">
            {{ row.isDefault ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="预签名上传" width="120" align="center">
        <template #default="{ row }">
          {{ row.supportsPresignedUpload ? '支持' : '不支持' }}
        </template>
      </el-table-column>
      <el-table-column label="预签名下载" width="120" align="center">
        <template #default="{ row }">
          {{ row.supportsPresignedDownload ? '支持' : '不支持' }}
        </template>
      </el-table-column>
      <el-table-column label="健康状态" width="150" align="center">
        <template #default="{ row }">
          <el-button size="small" :loading="row.healthChecking" @click="checkHealth(row)">
            {{ row.healthChecked ? (row.healthy ? '正常' : '异常') : '检查' }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="健康信息" min-width="200">
        <template #default="{ row }">
          <span v-if="row.healthMessage" :class="row.healthy ? 'health-ok' : 'health-err'">
            {{ row.healthMessage }}
          </span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

import { listStorageProviders, checkStorageProviderHealth } from '@/api/storage'

const loading = ref(false)
const providers = ref([])

async function loadProviders() {
  loading.value = true
  try {
    const res = await listStorageProviders()
    providers.value = (res?.data || []).map((p) => ({
      ...p,
      healthChecked: false,
      healthy: false,
      healthMessage: '',
      healthChecking: false,
    }))
  } catch {
    ElMessage.error('加载存储提供者失败')
  } finally {
    loading.value = false
  }
}

async function checkHealth(row) {
  row.healthChecking = true
  try {
    const res = await checkStorageProviderHealth(row.providerId)
    const data = res?.data || {}
    row.healthy = data.healthy
    row.healthMessage = data.message || (data.healthy ? '正常' : '异常')
    row.healthChecked = true
  } catch {
    row.healthy = false
    row.healthMessage = '检查失败'
    row.healthChecked = true
  } finally {
    row.healthChecking = false
  }
}

onMounted(() => {
  loadProviders()
})
</script>

<style scoped>
.storage-admin-panel {
  padding: 20px;
}
.health-ok {
  color: #67c23a;
}
.health-err {
  color: #f56c6c;
}
</style>
