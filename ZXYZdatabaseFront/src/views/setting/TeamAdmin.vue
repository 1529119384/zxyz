<template>
  <section class="settings-panel create-team-panel">
    <div class="panel-title">
      <h2>创建团队</h2>
      <span>系统管理员创建团队、配额和团队管理员账号</span>
    </div>
    <el-form label-position="top" @submit.prevent>
      <div class="form-grid">
        <el-form-item label="团队名称">
          <el-input v-model="createTeamForm.name" maxlength="50" placeholder="输入团队名称" />
        </el-form-item>
        <el-form-item label="团队人数上限">
          <el-input-number v-model="createTeamForm.memberLimit" :min="1" :max="100000" />
        </el-form-item>
        <el-form-item label="团队空间上限（GB）">
          <el-input-number v-model="createTeamForm.storageLimitGb" :min="1" :max="1048576" />
        </el-form-item>
        <el-form-item label="团队描述">
          <el-input v-model="createTeamForm.description" maxlength="500" placeholder="可选" />
        </el-form-item>
        <el-form-item label="团队管理员用户名">
          <el-input v-model="createTeamForm.ownerUsername" maxlength="64" />
        </el-form-item>
        <el-form-item label="团队管理员密码">
          <el-input v-model="createTeamForm.ownerPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="团队管理员昵称">
          <el-input v-model="createTeamForm.ownerName" maxlength="64" placeholder="可选" />
        </el-form-item>
        <el-form-item label="团队管理员邮箱">
          <el-input v-model="createTeamForm.ownerEmail" placeholder="可选，需登录后验证" />
        </el-form-item>
        <el-form-item label="团队管理员手机号">
          <el-input v-model="createTeamForm.ownerPhone" placeholder="可选，需登录后验证" />
        </el-form-item>
      </div>
      <el-button type="primary" :loading="creatingTeam" @click="submitCreateTeam"
        >确认创建</el-button
      >
    </el-form>
  </section>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

import { createAdminTeam } from '@/api/adminTeam'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import { GB } from '@/utils/format'

const teamStore = useTeamStore()
const creatingTeam = ref(false)
const createTeamForm = reactive({
  name: '',
  description: '',
  memberLimit: 100,
  storageLimitGb: 100,
  ownerUsername: '',
  ownerPassword: '',
  ownerName: '',
  ownerEmail: '',
  ownerPhone: '',
})

async function submitCreateTeam() {
  if (!createTeamForm.name.trim()) return ElMessage.warning('请输入团队名称')
  if (!createTeamForm.ownerUsername.trim() || !createTeamForm.ownerPassword.trim()) {
    return ElMessage.warning('请输入团队管理员用户名和密码')
  }
  if (createTeamForm.ownerPassword.trim().length < 6)
    return ElMessage.warning('团队管理员密码不能少于 6 位')
  creatingTeam.value = true
  try {
    await createAdminTeam({
      name: createTeamForm.name.trim(),
      description: createTeamForm.description.trim() || null,
      memberLimit: createTeamForm.memberLimit,
      storageLimit: Math.round(createTeamForm.storageLimitGb * GB),
      ownerUsername: createTeamForm.ownerUsername.trim(),
      ownerPassword: createTeamForm.ownerPassword.trim(),
      ownerName: createTeamForm.ownerName.trim() || null,
      ownerEmail: createTeamForm.ownerEmail.trim() || null,
      ownerPhone: createTeamForm.ownerPhone.trim() || null,
    })
    Object.assign(createTeamForm, {
      name: '',
      description: '',
      memberLimit: 100,
      storageLimitGb: 100,
      ownerUsername: '',
      ownerPassword: '',
      ownerName: '',
      ownerEmail: '',
      ownerPhone: '',
    })
    await teamStore.loadTeams()
    ElMessage.success('团队和团队管理员已创建')
  } catch (error) {
    handleBusinessError(error, '创建团队失败')
  } finally {
    creatingTeam.value = false
  }
}
</script>
