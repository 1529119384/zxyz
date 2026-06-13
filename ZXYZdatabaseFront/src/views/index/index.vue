<template>
  <div class="index-page">
    <Uploader
      ref="uploaderRef"
      :get-sibling-entries="() => fileShowRef?.getCurrentList?.() || []"
      @success="handleUploaderSuccess"
    />
    <section v-if="showStorageUsage" class="storage-usage">
      <div class="storage-usage__meta">
        <strong>{{ spaceUsageTitle }}</strong>
        <span
          >{{ formatStorageSize(storageUsage.usedStorage) }} /
          {{ storageUsage.unlimited ? '无限' : formatStorageSize(storageUsage.storageLimit) }}</span
        >
      </div>
      <el-progress
        :percentage="storageUsagePercentage"
        :status="storageUsagePercentage >= 90 ? 'exception' : undefined"
      />
      <small>
        剩余：{{
          storageUsage.unlimited ? '无限' : formatStorageSize(storageUsage.remainingStorage)
        }}
        <template v-if="spaceType === SPACE_TYPE.PROJECT && storageUsage.unlimited"
          >，仍受团队空间总配额限制</template
        >
        <template v-else-if="spaceType === SPACE_TYPE.PERSONAL && spaceTeamId"
          >，受团队配额限制</template
        >
      </small>
    </section>
    <FileExplorer
      ref="fileShowRef"
      :search-text="searchText"
      @context-action="handleContextAction"
      @row-action="handleRowAction"
    />
    <ArchiveNameDialog
      v-model:visible="archiveDialogVisible"
      :default-value="archiveDefaultName"
      :submitting="archiveSubmitting"
      @submit="handleArchiveDownloadSubmit"
    />
    <CreateShareDialog
      :visible="createShareDialogVisible"
      :items="shareTargets"
      :submitting="createShareSubmitting"
      @update:visible="handleCreateShareDialogVisibleChange"
      @submit="handleCreateShareSubmit"
    />
    <ConversationPickerDialog
      :visible="conversationPickerVisible"
      :conversations="imChat.conversations"
      @update:visible="handleConversationPickerVisibleChange"
      @select="handleConversationSelect"
    />
    <ShareSuccessDialog
      :visible="shareSuccessDialogVisible"
      :message="shareSuccessMessage"
      @update:visible="handleShareSuccessDialogVisibleChange"
      @copy="copyLatestShareMessage"
    />
    <CreateFolder
      v-model:visible="createFolderDialogVisible"
      :default-value="createFolderDefaultName"
      :submitting="createFolderSubmitting"
      @submit="handleCreateFolder"
    />
    <RenameFileDialog
      :visible="renameDialogVisible"
      :default-value="renameDefaultName"
      :submitting="renameSubmitting"
      :target-type="renameTargetType"
      @update:visible="handleRenameDialogVisibleChange"
      @submit="handleRenameSubmit"
    />
    <DeleteConfirmDialog
      :visible="deleteDialogVisible"
      :file-name="deleteDialogOptions.fileName"
      :type="deleteDialogOptions.type"
      :message="deleteDialogOptions.message"
      :tip="deleteDialogOptions.tip"
      :confirm-text="deleteDialogOptions.confirmText"
      :submitting="deleteSubmitting"
      @update:visible="handleDeleteDialogVisibleChange"
      @submit="handleDeleteSubmit"
    />
    <MoveCopyDialog
      :visible="moveCopyDialogVisible"
      :mode="moveCopyDialogMode"
      :items="moveCopyItems"
      :source-path="moveCopySourcePath"
      :path-to-id-map="currentIdStore.pathToIdMap"
      @update:visible="handleMoveCopyDialogVisibleChange"
      @submit="handleMoveCopySubmit"
    />
    <CreateProjectDialog
      v-model:form="projectForm"
      :visible="createProjectDialogVisible"
      :team-members="teamManagement.teamMembers"
      :submitting="creatingProject"
      :can-manage-projects="canManageProjectsInCurrentTeam"
      @update:visible="handleCreateProjectVisibleChange"
      @submit="submitCreateProject"
    />
    <ProjectSettingsDialog
      v-model:form="projectSettingsForm"
      :visible="projectSettingsDialogVisible"
      :submitting="savingProjectSettings"
      @update:visible="handleProjectSettingsVisibleChange"
      @submit="submitProjectSettings"
    />
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { logicalDeleteFiles } from '@/api/files'
import ArchiveNameDialog from '@/components/ArchiveNameDialog.vue'
import ConversationPickerDialog from '@/components/ConversationPickerDialog.vue'
import CreateFolder from '@/components/CreateFolder.vue'
import CreateProjectDialog from '@/components/CreateProjectDialog.vue'
import CreateShareDialog from '@/components/CreateShareDialog.vue'
import DeleteConfirmDialog from '@/components/DeleteConfirmDialog.vue'
import FileExplorer from '@/components/FileExplorer.vue'
import MoveCopyDialog from '@/components/MoveCopyDialog.vue'
import ProjectSettingsDialog from '@/components/ProjectSettingsDialog.vue'
import RenameFileDialog from '@/components/RenameFileDialog.vue'
import ShareSuccessDialog from '@/components/ShareSuccessDialog.vue'
import Uploader from '@/components/Uploader.vue'
import { useArchiveDownload } from '@/composables/useArchiveDownload'
import { useCreateFolderAction } from '@/composables/useCreateFolderAction'
import { provideCurrentSpaceContext } from '@/composables/useCurrentSpaceContext'
import { useDeleteDialog } from '@/composables/useDeleteDialog'
import { useFileSpaceActions } from '@/composables/useFileSpaceActions'
import { useMoveCopyAction } from '@/composables/useMoveCopyAction'
import { useProjectManagement } from '@/composables/project/useProjectManagement'
import { useRenameAction } from '@/composables/useRenameAction'
import { useSendToConversation } from '@/composables/useSendToConversation'
import { useShareCreateAction } from '@/composables/useShareCreateAction'
import { useStorageUsage } from '@/composables/useStorageUsage'
import { SPACE_TYPE } from '@/models/space'
import { useChatStore } from '@/store/chat'
import { useCurrentIdStore } from '@/store/currentId'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import {
  collectArchiveEntries,
  getArchiveTargets,
  getDefaultArchiveName,
  normalizeArchiveName,
} from '@/utils/archive/backendArchive'
import { formatSize as formatStorageSize } from '@/utils/format'
import { getRouteQueryText } from '@/utils/routeQuery'

const currentIdStore = useCurrentIdStore()
const currentUserStore = useCurrentUserStore()
const imChat = useChatStore()
const teamManagement = useTeamStore()
const route = useRoute()
const router = useRouter()
const fileShowRef = ref(null)
const uploaderRef = ref(null)
const searchText = computed(() => getRouteQueryText(route.query.search))
const spaceContext = provideCurrentSpaceContext()
const spaceType = spaceContext.spaceType
const spaceTeamId = spaceContext.teamId
const spaceProjectId = spaceContext.projectId
const isTeamSpace = spaceContext.isTeamSpace
const isProjectSpace = spaceContext.isProjectSpace
const spaceCanWrite = spaceContext.canWrite
const canManageProjectsInCurrentTeam = spaceContext.canManageProjects

const {
  storageUsage,
  spaceUsageTitle,
  showStorageUsage,
  storageUsagePercentage,
  refreshStorageUsage,
} = useStorageUsage({
  spaceContext,
  getCurrentFolderId: () => currentIdStore.currentId,
})

const {
  conversationPickerVisible,
  handleConversationPickerVisibleChange,
  openSendToConversation,
  handleConversationSelect,
} = useSendToConversation({ chatStore: imChat })

function refreshFileList() {
  return fileShowRef.value?.refresh?.()
}

function getCurrentEntries() {
  return fileShowRef.value?.getCurrentList?.() || []
}

const projectManagement = useProjectManagement({
  teamStore: teamManagement,
  currentUserStore,
  spaceContext,
  canManageProjects: canManageProjectsInCurrentTeam,
  // 项目配置会影响虚拟项目目录和顶部配额，两处刷新策略仍由文件页统一编排。
  onCreated: refreshFileList,
  onSettingsSaved: () => Promise.all([refreshFileList(), refreshStorageUsage()]),
})
const {
  createProjectDialogVisible,
  creatingProject,
  projectForm,
  projectSettingsDialogVisible,
  savingProjectSettings,
  projectSettingsForm,
  handleCreateProjectVisibleChange,
  handleProjectSettingsVisibleChange,
  openCreateProjectDialog,
  submitCreateProject,
  openProjectSettings,
  submitProjectSettings,
} = projectManagement

const createFolderAction = useCreateFolderAction({
  spaceContext,
  getParentId: () => currentIdStore.currentId,
  getSiblingEntries: getCurrentEntries,
  onSuccess: refreshFileList,
})
const {
  createFolderDialogVisible,
  createFolderSubmitting,
  createFolderDefaultName,
  openCreateFolderDialog,
  handleCreateFolder,
} = createFolderAction

const archiveDownloadAction = useArchiveDownload({
  resolveOpenState: (items = []) => {
    const targets = getArchiveTargets(items, spaceContext.resolveRequestParams())
    if (!targets.length) {
      ElMessage.warning('请选择要打包的文件或文件夹')
      return { opened: false }
    }

    return {
      opened: true,
      defaultName: getDefaultArchiveName(targets),
      context: {
        targets,
      },
    }
  },
  buildRunnerOptions: (archiveName, context) => {
    const targets = context?.targets || []
    if (!targets.length) {
      ElMessage.warning('请选择要打包的文件或文件夹')
      return null
    }

    return {
      collectEntries: () => collectArchiveEntries(targets),
      archiveName: normalizeArchiveName(archiveName),
      onEmpty: () => {
        ElMessage.warning('没有可打包的文件')
      },
      onSuccess: () => {
        ElMessage.success('打包下载已开始')
      },
    }
  },
})
const {
  archiveDialogVisible,
  archiveSubmitting,
  archiveDefaultName,
  openArchiveNameDialog,
  handleArchiveDownloadSubmit,
} = archiveDownloadAction
const shareCreateAction = useShareCreateAction()
const {
  createShareDialogVisible,
  createShareSubmitting,
  shareTargets,
  shareSuccessDialogVisible,
  shareSuccessMessage,
  openCreateShareDialog,
  handleCreateShareDialogVisibleChange,
  handleCreateShareSubmit,
  handleShareSuccessDialogVisibleChange,
  copyLatestShareMessage,
} = shareCreateAction
const renameAction = useRenameAction({
  onSuccess: refreshFileList,
})
const {
  renameDialogVisible,
  renameSubmitting,
  renameDefaultName,
  renameTargetType,
  openRenameDialog,
  handleRenameDialogVisibleChange,
  handleRenameSubmit,
} = renameAction
const deleteAction = useDeleteDialog({
  tipText: '删除后文件会进入回收站，可在回收站中恢复或彻底删除。',
  confirmText: '确认删除',
  getBatchMessage: () => '选中的文件将移入回收站，是否继续？',
  deleteRequest: (items) => {
    const fileIds = items.map((item) => item.id)
    return logicalDeleteFiles(fileIds)
  },
  getSuccessMessage: (items) => (items.length > 1 ? '批量删除成功' : '删除成功'),
  getFallbackMessage: () => '删除失败，请稍后重试',
  onSuccess: refreshFileList,
})
const {
  deleteDialogVisible,
  deleteSubmitting,
  deleteDialogOptions,
  openDeleteDialog,
  handleDeleteDialogVisibleChange,
  handleDeleteSubmit,
} = deleteAction
const moveCopyAction = useMoveCopyAction({
  spaceContext,
  onSuccess: async (payload) => {
    await router.push({
      name: isProjectSpace.value ? 'projectSpace' : isTeamSpace.value ? 'teamSpace' : 'index',
      params: isProjectSpace.value ? { projectId: String(spaceProjectId.value) } : {},
      query: buildFileSpaceQuery(payload?.targetPath || ''),
    })
    await refreshFileList()
  },
})
const {
  moveCopyDialogVisible,
  moveCopyDialogMode,
  moveCopyItems,
  moveCopySourcePath,
  resetMoveCopyDialog,
  openMoveCopyDialog,
  handleMoveCopyDialogVisibleChange,
  handleMoveCopySubmit,
} = moveCopyAction

const { handleContextAction, handleRowAction } = useFileSpaceActions({
  router,
  fileShowRef,
  uploaderRef,
  canWrite: spaceCanWrite,
  refreshFileList,
  openCreateFolderDialog,
  openCreateProjectDialog,
  openProjectSettings,
  openArchiveNameDialog,
  openRenameDialog,
  openDeleteDialog,
  openMoveCopyDialog,
  openCreateShareDialog,
  openSendToConversation,
  openProjectSpace,
})

function openProjectSpace(row) {
  const projectId = Number(row?.projectId)
  if (!Number.isSafeInteger(projectId) || projectId <= 0) {
    ElMessage.warning('项目组入口数据异常')
    return
  }
  if (row.accessible === false) {
    ElMessage.warning('你不是该项目组成员，只能查看项目名称')
    return
  }
  router.push({
    name: 'projectSpace',
    params: { projectId: String(projectId) },
    query: buildFileSpaceQuery(''),
  })
}

function buildFileSpaceQuery(path) {
  const query = { ...route.query }
  if (path) {
    query.path = path
  } else {
    delete query.path
  }

  return query
}

function handleUploaderSuccess() {
  refreshFileList()
  refreshStorageUsage()
}

onMounted(() => {
  resetMoveCopyDialog()
  refreshStorageUsage()
})

onBeforeUnmount(() => {
  resetMoveCopyDialog()
})

watch([spaceTeamId, spaceType, spaceProjectId], () => {
  currentIdStore.setCurrentId(-1)
  refreshFileList()
  refreshStorageUsage()
})
</script>

<style scoped>
.index-page {
  height: 100%;
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.storage-usage {
  margin: 16px 20px 0;
  padding: 12px 14px;
  border: 1px solid #ebeef5;
  background: #fff;
  display: grid;
  gap: 8px;
}

.storage-usage__meta {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: #303133;
}
</style>
