<template>
  <section class="settings-panel email-server-panel">
    <div class="panel-title admin-toolbar-title">
      <div>
        <h2>邮件服务器</h2>
        <span>查看当前 SMTP 账号、连通性测试结果，并切换发送账号</span>
      </div>
      <div class="admin-toolbar-actions">
        <el-button
          :icon="Refresh"
          :loading="loadingEmailServerConfigs"
          @click="loadEmailServerConfigs"
          >刷新</el-button
        >
        <el-button type="primary" :icon="Plus" @click="openEmailConfigDialog()">新增账号</el-button>
      </div>
    </div>

    <div class="email-server-summary">
      <div class="email-server-summary-item">
        <span>当前账号</span>
        <strong>{{ currentEmailServerConfig?.configName || '未启用' }}</strong>
      </div>
      <div class="email-server-summary-item">
        <span>SMTP 地址</span>
        <strong>{{ formatServerAddress(currentEmailServerConfig) }}</strong>
      </div>
      <div class="email-server-summary-item">
        <span>真实发送</span>
        <el-tag :type="getEmailRuntimeStatusType()">{{ formatEmailRuntimeStatus() }}</el-tag>
      </div>
      <div class="email-server-summary-item">
        <span>最近测试</span>
        <el-tag :type="getTestStatusType(currentEmailServerConfig?.lastTestStatus)">
          {{ formatTestStatus(currentEmailServerConfig?.lastTestStatus) }}
        </el-tag>
      </div>
      <div class="email-server-summary-item email-server-summary-message">
        <span>测试结果</span>
        <strong>{{ currentEmailServerConfig?.lastTestMessage || '暂无测试结果' }}</strong>
      </div>
    </div>

    <el-alert
      v-if="emailRuntimeStatus && !emailRuntimeStatus.sendingEnabled"
      class="email-runtime-alert"
      type="warning"
      :title="emailRuntimeStatus.message || '邮件发送功能已关闭，请联系管理员'"
      description="SMTP 连通性测试只验证账号可用性；真实发送关闭时，验证码和邮件任务不会实际投递。"
      show-icon
      :closable="false"
    />

    <el-table v-loading="loadingEmailServerConfigs" :data="emailServerConfigs" height="360">
      <el-table-column label="账号名称" min-width="150">
        <template #default="{ row }">
          <div class="email-server-name">
            <strong>{{ row.configName }}</strong>
            <el-tag v-if="isEmailServerActive(row)" size="small" type="success">当前</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="SMTP" min-width="180">
        <template #default="{ row }">{{ formatServerAddress(row) }}</template>
      </el-table-column>
      <el-table-column prop="username" label="账号" min-width="200" show-overflow-tooltip />
      <el-table-column label="发件人" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.fromAddress || row.username }}</template>
      </el-table-column>
      <el-table-column label="传输策略" min-width="110">
        <template #default="{ row }">{{ formatTransportStrategy(row.transportStrategy) }}</template>
      </el-table-column>
      <el-table-column label="测试状态" min-width="130">
        <template #default="{ row }">
          <el-tag :type="getTestStatusType(row.lastTestStatus)">
            {{ formatTestStatus(row.lastTestStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近测试时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.lastTestTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :icon="Edit" @click="openEmailConfigDialog(row)">编辑</el-button>
          <el-button
            size="small"
            :loading="testingEmailServerId === row.id"
            @click="testEmailConfig(row)"
            >测试</el-button
          >
          <el-button
            size="small"
            type="primary"
            :disabled="isEmailServerActive(row)"
            :loading="activatingEmailServerId === row.id"
            @click="activateEmailConfig(row)"
          >
            启用
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-dialog
    v-model="emailConfigDialogVisible"
    :title="emailConfigDialogMode === 'create' ? '新增邮件服务器账号' : '编辑邮件服务器账号'"
    width="520px"
  >
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="配置名称">
        <el-input
          v-model.trim="emailConfigForm.configName"
          maxlength="64"
          placeholder="例如：QQ 邮箱主账号"
        />
      </el-form-item>
      <el-form-item label="SMTP 主机">
        <el-input v-model.trim="emailConfigForm.host" maxlength="255" placeholder="smtp.qq.com" />
      </el-form-item>
      <div class="form-grid">
        <el-form-item label="SMTP 端口">
          <el-input-number v-model="emailConfigForm.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="传输策略">
          <el-select v-model="emailConfigForm.transportStrategy">
            <el-option
              v-for="option in transportStrategyOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="SMTP 账号">
        <el-input
          v-model.trim="emailConfigForm.username"
          maxlength="255"
          placeholder="your@example.com"
        />
      </el-form-item>
      <el-form-item
        :label="emailConfigDialogMode === 'create' ? 'SMTP 授权码' : 'SMTP 授权码（留空则不修改）'"
      >
        <el-input
          v-model="emailConfigForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="请输入 SMTP 授权码或密码"
        />
      </el-form-item>
      <el-form-item label="发件人地址">
        <el-input
          v-model.trim="emailConfigForm.fromAddress"
          maxlength="255"
          placeholder="默认使用 SMTP 账号"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emailConfigDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingEmailServerConfig" @click="submitEmailConfig"
        >保存</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'

import {
  activateEmailServerConfig,
  createEmailServerConfig,
  fetchCurrentEmailServerConfig,
  fetchEmailRuntimeStatus,
  fetchEmailServerConfigs,
  testEmailServerConfig,
  updateEmailServerConfig,
} from '@/api/emailAdmin'
import { handleBusinessError } from '@/utils/error'

const transportStrategyOptions = [
  { label: 'STARTTLS', value: 'SMTP_TLS' },
  { label: 'SSL/TLS', value: 'SMTPS' },
  { label: '普通 SMTP', value: 'SMTP' },
]

const emailServerConfigs = ref([])
const currentEmailServerConfig = ref(null)
const emailRuntimeStatus = ref(null)
const loadingEmailServerConfigs = ref(false)
const savingEmailServerConfig = ref(false)
const testingEmailServerId = ref(null)
const activatingEmailServerId = ref(null)
const emailConfigDialogVisible = ref(false)
const emailConfigDialogMode = ref('create')

const emailConfigForm = reactive({
  id: null,
  configName: '',
  host: '',
  port: 587,
  username: '',
  password: '',
  fromAddress: '',
  transportStrategy: 'SMTP_TLS',
})

onMounted(loadEmailServerConfigs)

async function loadEmailServerConfigs() {
  loadingEmailServerConfigs.value = true
  try {
    const [listResponse, currentResponse, runtimeResponse] = await Promise.all([
      fetchEmailServerConfigs(),
      fetchCurrentEmailServerConfig(),
      fetchEmailRuntimeStatus(),
    ])
    emailServerConfigs.value = Array.isArray(listResponse?.data) ? listResponse.data : []
    currentEmailServerConfig.value = currentResponse?.data || null
    emailRuntimeStatus.value = runtimeResponse?.data || null
  } catch (error) {
    handleBusinessError(error, '加载邮件服务器配置失败')
  } finally {
    loadingEmailServerConfigs.value = false
  }
}

function openEmailConfigDialog(config = null) {
  emailConfigDialogMode.value = config ? 'edit' : 'create'
  Object.assign(emailConfigForm, {
    id: config?.id ?? null,
    configName: config?.configName || '',
    host: config?.host || '',
    port: Number(config?.port || 587),
    username: config?.username || '',
    password: '',
    fromAddress: config?.fromAddress || '',
    transportStrategy: config?.transportStrategy || 'SMTP_TLS',
  })
  emailConfigDialogVisible.value = true
}

async function submitEmailConfig() {
  const payload = buildEmailConfigPayload()
  if (!payload) return

  savingEmailServerConfig.value = true
  try {
    if (emailConfigDialogMode.value === 'create') {
      await createEmailServerConfig(payload)
      ElMessage.success('邮件服务器账号已新增')
    } else {
      await updateEmailServerConfig(emailConfigForm.id, payload)
      ElMessage.success('邮件服务器账号已更新')
    }
    emailConfigDialogVisible.value = false
    await loadEmailServerConfigs()
  } catch (error) {
    handleBusinessError(error, '保存邮件服务器账号失败')
  } finally {
    savingEmailServerConfig.value = false
  }
}

function buildEmailConfigPayload() {
  const configName = emailConfigForm.configName.trim()
  const host = emailConfigForm.host.trim()
  const username = emailConfigForm.username.trim()
  const password = emailConfigForm.password.trim()
  const fromAddress = emailConfigForm.fromAddress.trim()
  const port = Number(emailConfigForm.port)

  if (!configName || !host || !username) {
    ElMessage.warning('请填写配置名称、SMTP 主机和 SMTP 账号')
    return null
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    ElMessage.warning('SMTP 端口必须在 1 到 65535 之间')
    return null
  }
  if (emailConfigDialogMode.value === 'create' && !password) {
    ElMessage.warning('请填写 SMTP 授权码')
    return null
  }

  const payload = {
    configName,
    host,
    port,
    username,
    fromAddress: fromAddress || null,
    transportStrategy: emailConfigForm.transportStrategy || 'SMTP_TLS',
  }
  if (password) {
    payload.password = password
  }
  return payload
}

async function testEmailConfig(config) {
  if (!config?.id) return
  testingEmailServerId.value = config.id
  try {
    const response = await testEmailServerConfig(config.id)
    const result = response?.data || {}
    await loadEmailServerConfigs()
    if (result.status === 'SUCCESS') {
      ElMessage.success('SMTP 连接测试成功')
    } else {
      ElMessage.warning(result.message || 'SMTP 连接测试失败')
    }
  } catch (error) {
    handleBusinessError(error, 'SMTP 连接测试失败')
  } finally {
    testingEmailServerId.value = null
  }
}

async function activateEmailConfig(config) {
  if (!config?.id || isEmailServerActive(config)) return
  activatingEmailServerId.value = config.id
  try {
    await activateEmailServerConfig(config.id)
    await loadEmailServerConfigs()
    ElMessage.success('当前邮件服务器账号已切换')
  } catch (error) {
    await loadEmailServerConfigs()
    handleBusinessError(error, '切换邮件服务器账号失败')
  } finally {
    activatingEmailServerId.value = null
  }
}

function isEmailServerActive(config) {
  return Boolean(config?.active || (config?.id && config.id === currentEmailServerConfig.value?.id))
}

function formatServerAddress(config) {
  if (!config?.host) return '未配置'
  return `${config.host}:${config.port || '-'}`
}

function formatTransportStrategy(strategy) {
  const option = transportStrategyOptions.find((item) => item.value === strategy)
  return option?.label || strategy || '-'
}

function formatTestStatus(status) {
  const statusMap = {
    SUCCESS: '连接成功',
    FAILED: '连接失败',
    NOT_TESTED: '未测试',
  }
  return statusMap[status] || '未测试'
}

function getTestStatusType(status) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function formatEmailRuntimeStatus() {
  if (!emailRuntimeStatus.value) return '未知'
  return emailRuntimeStatus.value.sendingEnabled ? '已开启' : '已关闭'
}

function getEmailRuntimeStatusType() {
  if (!emailRuntimeStatus.value) return 'info'
  return emailRuntimeStatus.value.sendingEnabled ? 'success' : 'danger'
}

function formatDateTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>
