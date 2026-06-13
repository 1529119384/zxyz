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
    const response = await fetchAdminTeams()
    adminTeams.value = Array.isArray(response?.data) ? response.data : []
  } catch (error) {
    handleBusinessError(error, '加载团队运营数据失败')
  } finally {
    loadingAdminTeams.value = false
  }
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
