<template>
  <section class="settings-panel admin-team-panel">
    <div class="panel-title">
      <h2>团队上限管理</h2>
      <span>查看成员人数、空间配额，并修改成员上限和空间上限</span>
    </div>
    <el-table v-loading="loadingAdminTeams" :data="adminTeams" height="360">
      <el-table-column prop="name" label="团队" min-width="160" />
      <el-table-column label="成员人数" min-width="120">
        <template #default="{ row }">{{ row.memberCount }} / {{ row.memberLimit }}</template>
      </el-table-column>
      <el-table-column label="空间用量" min-width="180">
        <template #default="{ row }"
          >{{ formatStorageText(row.usedStorage) }} /
          {{ formatStorageText(row.storageLimit) }}</template
        >
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button size="small" @click="openTeamQuotaDialog(row)">修改</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      class="admin-team-pagination"
      layout="total, sizes, prev, pager, next"
      :total="adminTeamPage.total"
      :current-page="adminTeamPage.page"
      :page-size="adminTeamPage.pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="handleAdminTeamPageChange"
      @size-change="handleAdminTeamPageSizeChange"
    />
  </section>

  <el-dialog v-model="teamQuotaDialogVisible" title="修改团队上限" width="460px">
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="团队名称">
        <el-input :model-value="teamQuotaForm.name" disabled />
      </el-form-item>
      <el-form-item label="成员上限">
        <el-input-number v-model="teamQuotaForm.memberLimit" :min="1" :max="100000" />
        <small class="quota-helper">当前成员数：{{ teamQuotaForm.memberCount }}</small>
      </el-form-item>
      <el-form-item label="空间上限（GB）">
        <el-input-number v-model="teamQuotaForm.storageLimitGb" :min="1" :max="1048576" />
        <small class="quota-helper"
          >当前已使用：{{ formatStorageText(teamQuotaForm.usedStorage) }}</small
        >
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="teamQuotaDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingTeamQuota" @click="submitTeamQuota">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { fetchAdminTeams, updateAdminTeamQuota } from '@/api/adminTeam'
import { handleBusinessError } from '@/utils/error'
import { formatStorageText, GB } from '@/utils/format'

const adminTeams = ref([])
const loadingAdminTeams = ref(false)
const savingTeamQuota = ref(false)
const teamQuotaDialogVisible = ref(false)

// 分页状态。后端已从「一次返回全部团队」改为 PageResult 信封（默认 20 / 上限 200），
// 管理端必须自己维护页码，否则永远只能看到第一页。
const adminTeamPage = reactive({
  page: 1,
  pageSize: 20,
  total: 0,
})

const teamQuotaForm = reactive({
  id: null,
  name: '',
  memberCount: 0,
  memberLimit: 1,
  usedStorage: 0,
  storageLimitGb: 1,
})

onMounted(loadAdminTeams)

async function loadAdminTeams() {
  loadingAdminTeams.value = true
  try {
    const response = await fetchAdminTeams({
      page: adminTeamPage.page,
      pageSize: adminTeamPage.pageSize,
    })
    const data = response?.data
    // 兼容两种形状：新版是分页信封 {page,pageSize,total,list}，旧版直接是数组。
    // 保留数组分支，是为了「前端已发、后端未发」的中间态下管理页不至于整页空白。
    if (Array.isArray(data)) {
      adminTeams.value = data
      adminTeamPage.total = data.length
    } else {
      adminTeams.value = Array.isArray(data?.list) ? data.list : []
      adminTeamPage.total = Number(data?.total || 0)
    }
  } catch (error) {
    handleBusinessError(error, '加载团队运营数据失败')
  } finally {
    loadingAdminTeams.value = false
  }
}

function handleAdminTeamPageChange(page) {
  adminTeamPage.page = page
  loadAdminTeams()
}

function handleAdminTeamPageSizeChange(size) {
  adminTeamPage.pageSize = size
  // 改每页条数后必须回到第 1 页：否则当前页码可能已越界，用户会看到一张空表
  adminTeamPage.page = 1
  loadAdminTeams()
}

function openTeamQuotaDialog(team) {
  teamQuotaForm.id = team?.id ?? null
  teamQuotaForm.name = team?.name || ''
  teamQuotaForm.memberCount = Number(team?.memberCount || 0)
  teamQuotaForm.memberLimit = Number(team?.memberLimit || 1)
  teamQuotaForm.usedStorage = Number(team?.usedStorage || 0)
  teamQuotaForm.storageLimitGb = Math.max(1, Math.ceil(Number(team?.storageLimit || GB) / GB))
  teamQuotaDialogVisible.value = true
}

async function submitTeamQuota() {
  const teamId = Number(teamQuotaForm.id)
  if (!Number.isSafeInteger(teamId) || teamId <= 0) {
    return ElMessage.warning('团队数据异常')
  }
  savingTeamQuota.value = true
  try {
    await updateAdminTeamQuota(teamId, {
      memberLimit: Number(teamQuotaForm.memberLimit),
      storageLimit: Math.round(Number(teamQuotaForm.storageLimitGb) * GB),
    })
    teamQuotaDialogVisible.value = false
    await loadAdminTeams()
    ElMessage.success('团队上限已更新')
  } catch (error) {
    handleBusinessError(error, '更新团队上限失败')
  } finally {
    savingTeamQuota.value = false
  }
}
</script>
