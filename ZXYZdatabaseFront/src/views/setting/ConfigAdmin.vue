<template>
  <section class="settings-panel config-admin-panel">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="配置列表" name="config">
        <div class="panel-title">
          <div class="admin-toolbar-title">
            <div>
              <h2>配置管理</h2>
              <span>查看和管理系统配置项</span>
            </div>
            <div class="admin-toolbar-actions">
              <el-input
                v-model="searchKey"
                placeholder="搜索配置键"
                clearable
                style="width: 220px"
              />
              <el-button @click="loadConfigs">刷新</el-button>
              <el-button type="primary" @click="openCreateDialog">新增配置</el-button>
            </div>
          </div>
        </div>

        <el-table v-loading="loading" :data="filteredConfigs" height="480">
          <el-table-column prop="configKey" label="配置键" min-width="200" />
          <el-table-column label="配置值" min-width="260">
            <template #default="{ row }">
              <span v-if="row.isEncrypted">******</span>
              <span v-else class="config-value-cell">{{ row.configValue }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column label="加密" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isEncrypted ? 'danger' : 'info'" size="small">
                {{ row.isEncrypted ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="180">
            <template #default="{ row }">
              {{ row.updatedAt || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="变更历史" name="audit">
        <div class="panel-title">
          <div class="admin-toolbar-title">
            <div>
              <h2>变更历史</h2>
              <span>查看配置项的变更记录</span>
            </div>
          </div>
        </div>

        <el-table v-loading="auditLoading" :data="auditLogs" height="480">
          <el-table-column prop="configKey" label="配置键" min-width="200" />
          <el-table-column prop="oldValue" label="原值" min-width="200" show-overflow-tooltip />
          <el-table-column prop="newValue" label="新值" min-width="200" show-overflow-tooltip />
          <el-table-column prop="changedBy" label="操作人" width="120" />
          <el-table-column prop="changedAt" label="变更时间" width="180" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>

  <el-dialog
    v-model="dialogVisible"
    :title="isCreateMode ? '新增配置' : '编辑配置'"
    width="500px"
    @closed="resetForm"
  >
    <el-form label-position="top" @submit.prevent>
      <el-form-item v-if="isCreateMode" label="配置键">
        <el-input v-model="form.configKey" placeholder="例如: app.feature.toggle" />
      </el-form-item>
      <el-form-item v-else label="配置键">
        <el-input :model-value="form.configKey" disabled />
      </el-form-item>
      <el-form-item label="配置值">
        <el-input
          v-model="form.configValue"
          type="textarea"
          :rows="3"
          placeholder="输入配置值"
        />
      </el-form-item>
      <el-form-item v-if="isCreateMode" label="描述">
        <el-input v-model="form.description" placeholder="可选" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitForm">
        {{ isCreateMode ? '创建' : '保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'

import { createConfig, fetchAllConfigs, fetchAuditLogs, updateConfig } from '@/api/configAdmin'
import { handleBusinessError } from '@/utils/error'

const configs = ref([])
const loading = ref(false)
const saving = ref(false)
const searchKey = ref('')
const dialogVisible = ref(false)
const isCreateMode = ref(false)
const activeTab = ref('config')
const auditLogs = ref([])
const auditLoading = ref(false)

const form = reactive({
  configKey: '',
  configValue: '',
  description: '',
})

const filteredConfigs = computed(() => {
  const keyword = searchKey.value.trim().toLowerCase()
  if (!keyword) return configs.value
  return configs.value.filter((item) => item.configKey?.toLowerCase().includes(keyword))
})

onMounted(loadConfigs)

watch(activeTab, (tab) => {
  if (tab === 'audit' && auditLogs.value.length === 0) {
    loadAuditLogs()
  }
})

async function loadAuditLogs() {
  auditLoading.value = true
  try {
    const response = await fetchAuditLogs()
    auditLogs.value = Array.isArray(response?.data) ? response.data : []
  } catch (error) {
    handleBusinessError(error, '加载变更历史失败')
  } finally {
    auditLoading.value = false
  }
}

async function loadConfigs() {
  loading.value = true
  try {
    const response = await fetchAllConfigs()
    configs.value = Array.isArray(response?.data) ? response.data : []
  } catch (error) {
    handleBusinessError(error, '加载配置列表失败')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  isCreateMode.value = true
  form.configKey = ''
  form.configValue = ''
  form.description = ''
  dialogVisible.value = true
}

function openEditDialog(row) {
  isCreateMode.value = false
  form.configKey = row.configKey || ''
  form.configValue = row.configValue || ''
  form.description = row.description || ''
  dialogVisible.value = true
}

function resetForm() {
  form.configKey = ''
  form.configValue = ''
  form.description = ''
}

async function submitForm() {
  if (!form.configKey.trim()) {
    return ElMessage.warning('请输入配置键')
  }
  saving.value = true
  try {
    if (isCreateMode.value) {
      await createConfig({
        configKey: form.configKey.trim(),
        configValue: form.configValue,
        description: form.description.trim() || null,
      })
      ElMessage.success('配置已创建')
    } else {
      await updateConfig(form.configKey, form.configValue)
      ElMessage.success('配置已更新')
    }
    dialogVisible.value = false
    await loadConfigs()
  } catch (error) {
    handleBusinessError(error, isCreateMode.value ? '创建配置失败' : '更新配置失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.config-admin-panel {
  grid-column: 1 / -1;
}

.config-value-cell {
  display: inline-block;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
