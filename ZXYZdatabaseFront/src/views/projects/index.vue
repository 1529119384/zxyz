<template>
  <div class="projects-page">
    <div class="toolbar">
      <div>
        <h2>项目组</h2>
        <p>项目组空间仅项目成员可访问。</p>
      </div>
      <el-button type="primary" :disabled="!selectedTeamId" @click="openCreateProjectDialog">{{
        createProjectButtonText
      }}</el-button>
    </div>

    <el-empty v-if="!selectedTeamId" description="请先选择团队" />

    <el-table v-else v-loading="loading" :data="projects" row-key="id">
      <el-table-column prop="name" label="项目名称" min-width="180" />
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column prop="leaderUserId" label="负责人" width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'">{{
            row.status === 0 ? '进行中' : '已归档'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openSpace(row)">空间</el-button>
          <el-button size="small" :disabled="!row.conversationId" @click="openChat(row)"
            >群聊</el-button
          >
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="!canCreateProject || row.status !== 0"
            @click="archive(row)"
            >归档</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <CreateProjectDialog
      v-model:form="projectForm"
      :visible="createProjectDialogVisible"
      :team-members="teamManagement.teamMembers"
      :submitting="creatingProject"
      :can-manage-projects="canCreateProject"
      @update:visible="handleCreateProjectVisibleChange"
      @submit="submitCreateProject"
    />
  </div>
</template>

<script setup>
import { defineOptions } from 'vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

defineOptions({ name: 'Projects' })

import { archiveProject, fetchTeamProjects } from '@/api/project'
import CreateProjectDialog from '@/components/CreateProjectDialog.vue'
import { useProjectManagement } from '@/composables/project/useProjectManagement'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'

const router = useRouter()
const currentUserStore = useCurrentUserStore()
const teamManagement = useTeamStore()
const projects = ref([])
const loading = ref(false)

const selectedTeamId = computed(
  () => teamManagement.selectedTeamId || teamManagement.teams[0]?.id || null,
)
const canCreateProject = computed(() =>
  teamManagement.hasTeamPermission({ teamId: selectedTeamId.value, code: 'team:project:manage' }),
)
const createProjectButtonText = computed(() => (canCreateProject.value ? '新建项目' : '申请项目组'))

async function loadProjects() {
  if (!selectedTeamId.value) {
    projects.value = []
    return
  }
  loading.value = true
  try {
    const response = await fetchTeamProjects(selectedTeamId.value)
    projects.value = Array.isArray(response?.data) ? response.data : []
  } catch (error) {
    handleBusinessError(error, '加载项目组失败')
  } finally {
    loading.value = false
  }
}

const projectManagement = useProjectManagement({
  teamStore: teamManagement,
  currentUserStore,
  getTeamId: () => selectedTeamId.value,
  canManageProjects: canCreateProject,
  onCreated: loadProjects,
})
const {
  createProjectDialogVisible,
  creatingProject,
  projectForm,
  handleCreateProjectVisibleChange,
  openCreateProjectDialog,
  submitCreateProject,
} = projectManagement

function openSpace(row) {
  router.push({ name: 'projectSpace', params: { projectId: String(row.id) } })
}

async function openChat(row) {
  if (!row.conversationId) return
  await router.push({ name: 'chatHome', query: { conversationId: String(row.conversationId) } })
}

async function archive(row) {
  try {
    await ElMessageBox.confirm(`确认归档项目组「${row.name}」？归档后群聊只读。`, '归档项目组', {
      type: 'warning',
      confirmButtonText: '归档',
      cancelButtonText: '取消',
    })
    await archiveProject(row.id)
    ElMessage.success('项目组已归档')
    await loadProjects()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    handleBusinessError(error, '归档项目组失败')
  }
}

onMounted(async () => {
  await loadProjects()
})

watch(selectedTeamId, loadProjects)
</script>

<style scoped>
.projects-page {
  height: 100%;
  padding: 20px;
  box-sizing: border-box;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.toolbar h2 {
  margin: 0;
  font-size: 22px;
}

.toolbar p {
  margin: 6px 0 0;
  color: #606266;
  font-size: 13px;
}
</style>
