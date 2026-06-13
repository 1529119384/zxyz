<template>
  <section class="settings-panel database-maintenance-panel">
    <div class="panel-title admin-toolbar-title">
      <div>
        <h2>数据库维护</h2>
        <span>导出当前数据库备份，或导入本系统导出的备份包</span>
      </div>
      <div class="admin-toolbar-actions">
        <el-button
          :icon="Refresh"
          :loading="loadingDatabaseMaintenanceStatus"
          @click="loadDatabaseMaintenanceStatus"
        >
          刷新
        </el-button>
        <el-button
          type="primary"
          :icon="Download"
          :disabled="!databaseMaintenanceStatus.enabled"
          :loading="exportingDatabase"
          @click="downloadDatabaseExport"
        >
          导出数据库
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!databaseMaintenanceStatus.enabled"
      class="database-maintenance-alert"
      type="warning"
      title="数据库维护功能未启用"
      description="请在后端配置 DATABASE_MAINTENANCE_ENABLED=true，并确认服务器已安装 mysql 与 mysqldump 命令。"
      show-icon
      :closable="false"
    />
    <el-alert
      v-else
      class="database-maintenance-alert"
      type="error"
      title="导入会覆盖当前数据库数据"
      description="请先导出当前数据库备份，再执行导入。导入仅接受本功能导出的 zip 备份包。"
      show-icon
      :closable="false"
    />

    <div class="database-maintenance-summary">
      <div class="database-maintenance-summary-item">
        <span>维护状态</span>
        <el-tag :type="databaseMaintenanceStatus.enabled ? 'success' : 'info'">
          {{ databaseMaintenanceStatus.enabled ? '已启用' : '未启用' }}
        </el-tag>
      </div>
      <div class="database-maintenance-summary-item">
        <span>备份目标</span>
        <strong>{{ formatDatabaseTargets() }}</strong>
      </div>
    </div>

    <el-form class="database-import-form" label-position="top" @submit.prevent>
      <el-form-item label="导入备份文件">
        <div class="database-file-row">
          <el-upload
            accept=".zip"
            :auto-upload="false"
            :show-file-list="false"
            :disabled="!databaseMaintenanceStatus.enabled || importingDatabase"
            :on-change="selectDatabaseImportFile"
          >
            <el-button
              :icon="Upload"
              :disabled="!databaseMaintenanceStatus.enabled || importingDatabase"
            >
              选择 zip 备份
            </el-button>
          </el-upload>
          <span class="database-file-name">{{ databaseImportFile?.name || '未选择文件' }}</span>
        </div>
      </el-form-item>
      <el-form-item
        :label="`确认文字：${databaseMaintenanceStatus.confirmationText || '确认导入数据库'}`"
      >
        <el-input
          v-model.trim="databaseImportConfirmation"
          :disabled="!databaseMaintenanceStatus.enabled || importingDatabase"
          placeholder="请输入确认文字后才能导入"
        />
      </el-form-item>
      <el-button
        type="danger"
        :icon="Upload"
        :disabled="!canImportDatabase"
        :loading="importingDatabase"
        @click="submitDatabaseImport"
      >
        导入数据库
      </el-button>
    </el-form>
  </section>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { Download, Refresh, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'

import {
  exportDatabaseArchive,
  fetchDatabaseMaintenanceStatus,
  importDatabaseArchive,
} from '@/api/databaseAdmin'
import { triggerDownloadByBlob } from '@/utils/download'
import { handleBusinessError } from '@/utils/error'

const databaseMaintenanceStatus = reactive({
  enabled: false,
  confirmationText: '确认导入数据库',
  targets: [],
})
const databaseImportFile = ref(null)
const databaseImportConfirmation = ref('')
const loadingDatabaseMaintenanceStatus = ref(false)
const exportingDatabase = ref(false)
const importingDatabase = ref(false)

const canImportDatabase = computed(() => {
  return Boolean(
    databaseMaintenanceStatus.enabled &&
    databaseImportFile.value &&
    databaseImportConfirmation.value === databaseMaintenanceStatus.confirmationText &&
    !importingDatabase.value,
  )
})

onMounted(loadDatabaseMaintenanceStatus)

async function loadDatabaseMaintenanceStatus() {
  loadingDatabaseMaintenanceStatus.value = true
  try {
    const response = await fetchDatabaseMaintenanceStatus()
    const data = response?.data || {}
    databaseMaintenanceStatus.enabled = Boolean(data.enabled)
    databaseMaintenanceStatus.confirmationText = data.confirmationText || '确认导入数据库'
    databaseMaintenanceStatus.targets = Array.isArray(data.targets) ? data.targets : []
  } catch (error) {
    handleBusinessError(error, '加载数据库维护状态失败')
  } finally {
    loadingDatabaseMaintenanceStatus.value = false
  }
}

async function downloadDatabaseExport() {
  exportingDatabase.value = true
  try {
    const response = await exportDatabaseArchive()
    const blob = response?.data
    const fileName =
      parseDownloadFileName(response?.headers?.['content-disposition']) ||
      'zxyz-database-export.zip'
    triggerDownloadByBlob(blob, fileName)
    ElMessage.success('数据库备份已导出')
  } catch (error) {
    handleBusinessError(error, '导出数据库失败')
  } finally {
    exportingDatabase.value = false
  }
}

function selectDatabaseImportFile(uploadFile) {
  databaseImportFile.value = uploadFile?.raw || null
}

async function submitDatabaseImport() {
  if (!canImportDatabase.value) {
    return ElMessage.warning('请选择备份文件，并输入正确的确认文字')
  }
  importingDatabase.value = true
  try {
    const response = await importDatabaseArchive(
      databaseImportFile.value,
      databaseImportConfirmation.value,
    )
    const importedTargets = response?.data?.importedTargets || []
    databaseImportFile.value = null
    databaseImportConfirmation.value = ''
    ElMessage.success(`数据库导入完成：${importedTargets.join('、') || '已完成'}`)
  } catch (error) {
    handleBusinessError(error, '导入数据库失败')
  } finally {
    importingDatabase.value = false
  }
}

function formatDatabaseTargets() {
  const targets = databaseMaintenanceStatus.targets || []
  if (!targets.length) return '未配置'
  return targets.map((target) => target.displayName || target.name).join('、')
}

function parseDownloadFileName(contentDisposition = '') {
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1])
  }
  const match = contentDisposition.match(/filename="?([^";]+)"?/i)
  return match?.[1] || ''
}
</script>
