<template>
  <el-drawer
    :model-value="visible"
    title="团队设置"
    size="520px"
    @update:model-value="emit('update:visible', $event)"
  >
    <input
      ref="teamAvatarInputRef"
      class="avatar-file-input"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      @change="handleTeamAvatarFileChange"
    />
    <TeamBasicInfoPanel
      :selected-team="teamManagement.selectedTeam"
      :profile-form="profileForm"
      :can-update-team="canUpdateTeam"
      :can-access-permission-center="canAccessPermissionCenter"
      :saving-profile="savingProfile"
      :uploading-team-avatar="uploadingTeamAvatar"
      :team-avatar-upload-progress="teamAvatarUploadProgress"
      @save-profile="saveProfile"
      @open-permission-center="openPermissionCenter"
      @open-team-avatar-picker="openTeamAvatarPicker"
      @update:profile-form="Object.assign(profileForm, $event)"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="成员" name="members">
        <TeamMemberPanel
          :team-members="safeTeamMembers"
          :member-form="memberForm"
          :creating-member="creatingMember"
          :current-user-id="currentUserId"
          :can-create-member="canCreateMember"
          :can-invite-member="canInviteMember"
          :can-assign-role="canAssignRole"
          :can-remove-member="canRemoveMember"
          :role-text="roleText"
          :display-name="displayName"
          @show-invite-dialog="inviteDialogVisible = true"
          @open-permission-center="openPermissionCenter"
          @leave-team="leaveTeam"
          @create-member="createMemberAccount"
          @start-direct-chat="startDirectChat"
          @remove-member="removeMember"
          @update:member-form="Object.assign(memberForm, $event)"
        />
      </el-tab-pane>

      <el-tab-pane label="管理" name="management">
        <TeamManagementPanel
          :team-members="safeTeamMembers"
          :team-mutes="safeTeamMutes"
          :announcement-form="announcementForm"
          :mute-form="muteForm"
          :current-user-id="currentUserId"
          :can-publish-announcement="canPublishAnnouncement"
          :can-manage-mute="canManageMute"
          :display-name="displayName"
          @publish-announcement="publishAnnouncement"
          @mute-member="muteMember"
          @unmute-member="unmuteMember"
          @update:announcement-form="Object.assign(announcementForm, $event)"
          @update:mute-form="Object.assign(muteForm, $event)"
        />
      </el-tab-pane>

      <el-tab-pane label="申请" name="requests">
        <TeamRequestsPanel
          :join-requests="safeJoinRequests"
          :invite-link-form="inviteLinkForm"
          :invite-link="teamManagement.inviteLink"
          :join-link-text="joinLinkText"
          :can-manage-invite-link="canManageInviteLink"
          :can-review-join-requests="canReviewJoinRequests"
          @create-invite-link="createInviteLink"
          @approve-join-request="approveJoinRequest"
          @reject-join-request="rejectJoinRequest"
          @update:invite-link-form="Object.assign(inviteLinkForm, $event)"
        />
      </el-tab-pane>

      <el-tab-pane label="存储分配" name="storage">
        <TeamStoragePanel
          :member-storage-list="safeMemberStorageList"
          :storage-limit-forms="storageLimitForms"
          :loading="storageMembersLoading"
          :saving="storageLimitSaving"
          :can-allocate-storage="canAllocateStorage"
          :role-text="roleText"
          :display-name="displayName"
          @save-member-limit="saveMemberLimit"
          @update:storage-limit-forms="Object.assign(storageLimitForms, $event)"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="inviteDialogVisible" title="邀请成员" width="560px">
      <div class="invite-search">
        <el-input
          v-model="inviteKeyword"
          placeholder="输入用户 ID、用户名或邮箱"
          @keyup.enter="searchInviteUsers"
        />
        <el-button @click="searchInviteUsers">搜索</el-button>
      </div>
      <el-table :data="safeUserSearchResults" height="320">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="member-cell">
              <el-avatar :size="28" :src="row.avatar">{{
                (row.name || row.username || '').slice(0, 1)
              }}</el-avatar>
              <span>{{ row.name || row.username }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="inviteUser(row.userId)">邀请</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, unref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { useTeamManagement } from '@/composables/team/useTeamManagement'
import { useTeamStorageAllocation } from '@/composables/team/useTeamStorageAllocation'
import { uploadTeamAvatar } from '@/services/avatarUpload'
import { useCurrentUserStore } from '@/store/currentUser'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'
import TeamBasicInfoPanel from '@/components/team-settings/TeamBasicInfoPanel.vue'
import TeamMemberPanel from '@/components/team-settings/TeamMemberPanel.vue'
import TeamManagementPanel from '@/components/team-settings/TeamManagementPanel.vue'
import TeamRequestsPanel from '@/components/team-settings/TeamRequestsPanel.vue'
import TeamStoragePanel from '@/components/team-settings/TeamStoragePanel.vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
})
const emit = defineEmits(['update:visible'])
const router = useRouter()
const BYTES_PER_GB = 1024 * 1024 * 1024
const teamManagement = useTeamStore()
const currentUserStore = useCurrentUserStore()
const activeTab = ref('members')
const inviteDialogVisible = ref(false)
const storageLimitForms = reactive({})
const inviteKeyword = ref('')
const savingProfile = ref(false)
const uploadingTeamAvatar = ref(false)
const teamAvatarUploadProgress = ref(0)
const teamAvatarInputRef = ref(null)
const creatingMember = ref(false)
const profileForm = reactive({ name: '', avatar: '', description: '' })
const memberForm = reactive({ username: '', password: '', name: '', roleCode: 'team_member' })
const announcementForm = reactive({ title: '', content: '' })
const muteForm = reactive({ userId: null, reason: '' })
const inviteLinkForm = reactive({ expireHours: 24, maxUses: 0 })
const {
  currentUserId,
  canAccessPermissionCenter,
  canUpdateTeam,
  canCreateMember,
  canInviteMember,
  canAssignRole,
  canRemoveMember,
  canPublishAnnouncement,
  canManageMute,
  canManageInviteLink,
  canReviewJoinRequests,
  joinLinkText,
  roleText,
  displayName,
  loadTeamMembersSafe,
  loadTeamManagementSafe,
  openPermissionCenter,
  saveTeamProfile,
  publishAnnouncement: publishAnnouncementAction,
  muteMember: muteMemberAction,
  unmuteMember,
  createInviteLink: createInviteLinkAction,
  approveJoinRequest,
  rejectJoinRequest,
  leaveTeam,
  removeMember,
  createMemberAccount: createMemberAccountAction,
  searchUsers,
  inviteUser,
  startDirectChat,
} = useTeamManagement({
  teamStore: teamManagement,
  router,
  currentUserStore,
  close: () => emit('update:visible', false),
})

const storageAllocation = useTeamStorageAllocation({ teamStore: teamManagement })
const safeTeamMembers = computed(() => readArray(teamManagement.teamMembers))
const safeTeamMutes = computed(() => readArray(teamManagement.teamMutes))
const safeJoinRequests = computed(() => readArray(teamManagement.joinRequests))
const safeUserSearchResults = computed(() => readArray(teamManagement.userSearchResults))
const safeMemberStorageList = computed(() => readArray(storageAllocation.memberStorageList))
const canAllocateStorage = computed(() => Boolean(unref(storageAllocation.canAllocateStorage)))
const storageMembersLoading = computed(() => Boolean(unref(storageAllocation.loadingMembers)))
const storageLimitSaving = computed(() => Boolean(unref(storageAllocation.savingLimit)))

watch(
  () => [props.visible, teamManagement.selectedTeamId],
  async ([visible]) => {
    if (!visible || !teamManagement.selectedTeamId) {
      return
    }
    syncProfileForm()
    await loadTeamMembersSafe(teamManagement.selectedTeamId)
    await loadTeamManagementSafe(teamManagement.selectedTeamId)
  },
  { immediate: true },
)

watch(activeTab, async (tab) => {
  if (tab === 'storage' && teamManagement.selectedTeamId) {
    const members = await storageAllocation.loadMemberStorage()
    // 初始化限额表单（字节 → GB）
    for (const m of members) {
      storageLimitForms[m.userId] =
        m.personalStorageLimit != null ? Math.round(m.personalStorageLimit / BYTES_PER_GB) : null
    }
  }
})

function syncProfileForm() {
  profileForm.name = teamManagement.selectedTeam?.name || ''
  profileForm.avatar = teamManagement.selectedTeam?.avatar || ''
  profileForm.description = teamManagement.selectedTeam?.description || ''
}

async function saveProfile() {
  savingProfile.value = true
  try {
    await saveTeamProfile({
      name: profileForm.name,
      description: profileForm.description,
    })
  } finally {
    savingProfile.value = false
  }
}

function openTeamAvatarPicker() {
  if (uploadingTeamAvatar.value || !canUpdateTeam.value) return
  teamAvatarInputRef.value?.click()
}

async function handleTeamAvatarFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !teamManagement.selectedTeamId) return

  uploadingTeamAvatar.value = true
  teamAvatarUploadProgress.value = 0
  try {
    const avatar = await uploadTeamAvatar(teamManagement.selectedTeamId, file, (progress) => {
      const total = progress.total || file.size || 1
      teamAvatarUploadProgress.value = Math.min(99, Math.round((progress.loaded / total) * 100))
    })
    teamAvatarUploadProgress.value = 100
    const saved = await saveTeamProfile({
      name: profileForm.name,
      avatar,
      description: profileForm.description,
    })
    if (saved) {
      profileForm.avatar = avatar
    }
  } catch (error) {
    handleBusinessError(error, '团队头像上传失败')
  } finally {
    uploadingTeamAvatar.value = false
    teamAvatarUploadProgress.value = 0
  }
}

async function publishAnnouncement() {
  await publishAnnouncementAction(announcementForm)
}

async function muteMember() {
  await muteMemberAction(muteForm)
}

async function createInviteLink() {
  await createInviteLinkAction(inviteLinkForm)
}

async function createMemberAccount() {
  creatingMember.value = true
  try {
    await createMemberAccountAction(memberForm)
  } finally {
    creatingMember.value = false
  }
}

async function searchInviteUsers() {
  await searchUsers(inviteKeyword.value)
}

async function saveMemberLimit(row) {
  const gbValue = storageLimitForms[row.userId]
  // null 或 0 视为不限制，传给后端 null
  const bytes = gbValue > 0 ? gbValue * BYTES_PER_GB : null
  await storageAllocation.saveMemberLimit(row.userId, bytes)
}

function readArray(value) {
  // 对象里的 ref 不会在模板中作为顶层变量自动解包，表格数据进入组件前统一转数组。
  const resolved = unref(value)
  return Array.isArray(resolved) ? resolved : []
}
</script>

<style scoped>
.invite-search {
  display: flex;
  align-items: center;
  gap: 10px;
}

.member-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar-file-input {
  display: none;
}
</style>
