<template>
  <section class="settings-panel email-record-panel">
    <div class="panel-title admin-toolbar-title">
      <div>
        <h2>历史邮件记录</h2>
        <span>按状态、收件人和业务类型筛选邮件发送记录</span>
      </div>
      <div class="admin-toolbar-actions">
        <el-button :icon="Refresh" :loading="loadingEmailRecords" @click="loadEmailRecords()"
          >刷新</el-button
        >
      </div>
    </div>

    <el-form class="email-record-filters" inline @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="emailRecordFilters.status" clearable placeholder="全部状态">
          <el-option
            v-for="option in emailRecordStatusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="收件人">
        <el-input
          v-model.trim="emailRecordFilters.recipient"
          clearable
          placeholder="输入邮箱"
          @keyup.enter="searchEmailRecords"
        />
      </el-form-item>
      <el-form-item label="业务类型">
        <el-input
          v-model.trim="emailRecordFilters.businessType"
          clearable
          placeholder="例如 SYSTEM_MESSAGE"
          @keyup.enter="searchEmailRecords"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="searchEmailRecords">查询</el-button>
        <el-button @click="resetEmailRecordFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loadingEmailRecords" :data="emailRecords" height="420">
      <el-table-column prop="recipient" label="收件人" min-width="220" show-overflow-tooltip />
      <el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" min-width="110">
        <template #default="{ row }">
          <el-tag :type="getEmailRecordStatusType(row.status)">{{
            formatEmailRecordStatus(row.status)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="业务类型" min-width="150">
        <template #default="{ row }">{{ row.businessType || '-' }}</template>
      </el-table-column>
      <el-table-column label="尝试次数" min-width="100">
        <template #default="{ row }"
          >{{ row.attemptCount || 0 }} / {{ row.maxAttempts || 0 }}</template
        >
      </el-table-column>
      <el-table-column label="发送账号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{
          row.serverConfigName || row.senderUsername || '-'
        }}</template>
      </el-table-column>
      <el-table-column label="计划时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.scheduledTime) }}</template>
      </el-table-column>
      <el-table-column label="发送时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.sentTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEmailRecordDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="email-record-pagination"
      background
      layout="total, sizes, prev, pager, next"
      :total="emailRecordPagination.total"
      :current-page="emailRecordPagination.page"
      :page-size="emailRecordPagination.pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="handleEmailRecordPageChange"
      @size-change="handleEmailRecordPageSizeChange"
    />
  </section>

  <el-dialog v-model="emailRecordDetailVisible" title="邮件详情" width="860px">
    <div v-loading="loadingEmailRecordDetail">
      <el-descriptions v-if="emailRecordDetail" :column="2" border>
        <el-descriptions-item label="收件人">{{
          emailRecordDetail.recipient
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getEmailRecordStatusType(emailRecordDetail.status)">
            {{ formatEmailRecordStatus(emailRecordDetail.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="主题">{{ emailRecordDetail.subject }}</el-descriptions-item>
        <el-descriptions-item label="业务类型">{{
          emailRecordDetail.businessType || '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="发送账号">
          {{ emailRecordDetail.serverConfigName || emailRecordDetail.senderUsername || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="发送账号邮箱">{{
          emailRecordDetail.senderUsername || '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">
          {{ emailRecordDetail.attemptCount || 0 }} / {{ emailRecordDetail.maxAttempts || 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="下次重试">{{
          formatDateTime(emailRecordDetail.nextRetryTime)
        }}</el-descriptions-item>
        <el-descriptions-item label="计划发送">{{
          formatDateTime(emailRecordDetail.scheduledTime)
        }}</el-descriptions-item>
        <el-descriptions-item label="实际发送">{{
          formatDateTime(emailRecordDetail.sentTime)
        }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{
          formatDateTime(emailRecordDetail.createTime)
        }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{
          formatDateTime(emailRecordDetail.updateTime)
        }}</el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">
          {{ emailRecordDetail.failureReason || '-' }}
        </el-descriptions-item>
      </el-descriptions>
      <div class="email-record-preview-block">
        <div class="email-record-preview-title">邮件内容预览</div>
        <iframe class="email-record-preview" :srcdoc="emailRecordPreviewHtml" sandbox />
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import DOMPurify from 'dompurify'
import { Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'

import { fetchEmailRecordDetail, fetchEmailRecords } from '@/api/emailAdmin'
import { handleBusinessError } from '@/utils/error'

const emailRecordStatusOptions = [
  { label: '待发送', value: 'PENDING' },
  { label: '发送中', value: 'SENDING' },
  { label: '已发送', value: 'SENT' },
  { label: '发送失败', value: 'FAILED' },
]

const emailRecords = ref([])
const emailRecordDetail = ref(null)
const loadingEmailRecords = ref(false)
const loadingEmailRecordDetail = ref(false)
const emailRecordDetailVisible = ref(false)

const emailRecordFilters = reactive({ status: '', recipient: '', businessType: '' })
const emailRecordPagination = reactive({ page: 1, pageSize: 10, total: 0 })

const emailRecordPreviewHtml = computed(() => {
  const rawContent = emailRecordDetail.value?.contentHtml || '<p style="color:#909399;">无内容</p>'
  const cleanContent = DOMPurify.sanitize(rawContent)
  return `<!doctype html><html><head><meta charset="UTF-8"><style>body{margin:0;padding:16px;font-family:Arial,'Microsoft YaHei',sans-serif;color:#111827;line-height:1.6;word-break:break-word;}img{max-width:100%;height:auto;}table{max-width:100%;border-collapse:collapse;}</style></head><body>${cleanContent}</body></html>`
})

onMounted(loadEmailRecords)

async function loadEmailRecords(page = emailRecordPagination.page) {
  loadingEmailRecords.value = true
  try {
    const response = await fetchEmailRecords({
      status: emailRecordFilters.status || undefined,
      recipient: emailRecordFilters.recipient || undefined,
      businessType: emailRecordFilters.businessType || undefined,
      page,
      pageSize: emailRecordPagination.pageSize,
    })
    const data = response?.data || {}
    emailRecords.value = Array.isArray(data.records) ? data.records : []
    emailRecordPagination.page = Number(data.page || page || 1)
    emailRecordPagination.pageSize = Number(data.pageSize || emailRecordPagination.pageSize)
    emailRecordPagination.total = Number(data.total || 0)
  } catch (error) {
    handleBusinessError(error, '加载历史邮件记录失败')
  } finally {
    loadingEmailRecords.value = false
  }
}

function searchEmailRecords() {
  emailRecordPagination.page = 1
  loadEmailRecords(1)
}

function resetEmailRecordFilters() {
  emailRecordFilters.status = ''
  emailRecordFilters.recipient = ''
  emailRecordFilters.businessType = ''
  searchEmailRecords()
}

function handleEmailRecordPageChange(page) {
  emailRecordPagination.page = page
  loadEmailRecords(page)
}

function handleEmailRecordPageSizeChange(pageSize) {
  emailRecordPagination.pageSize = pageSize
  emailRecordPagination.page = 1
  loadEmailRecords(1)
}

async function openEmailRecordDetail(record) {
  if (!record?.id) return
  emailRecordDetailVisible.value = true
  loadingEmailRecordDetail.value = true
  emailRecordDetail.value = record
  try {
    const response = await fetchEmailRecordDetail(record.id)
    emailRecordDetail.value = response?.data || record
  } catch (error) {
    handleBusinessError(error, '加载邮件详情失败')
  } finally {
    loadingEmailRecordDetail.value = false
  }
}

function formatEmailRecordStatus(status) {
  const option = emailRecordStatusOptions.find((item) => item.value === status)
  return option?.label || status || '-'
}

function getEmailRecordStatusType(status) {
  if (status === 'SENT') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'SENDING') return 'warning'
  return 'info'
}

function formatDateTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>
