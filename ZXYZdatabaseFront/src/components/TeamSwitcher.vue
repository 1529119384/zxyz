<template>
  <div v-if="teamManagement.teams.length || linkedAccounts.length" class="team-switcher">
    <el-dropdown v-if="canSwitch" trigger="click" @command="handleCommand">
      <button class="team-switcher__button" type="button">
        <el-avatar :size="28" :src="teamManagement.selectedTeam?.avatar">
          {{ currentLabel.slice(0, 1) }}
        </el-avatar>
        <span>{{ currentLabel }}</span>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="team in teamManagement.teams"
            :key="`team-${team.id}`"
            :command="`team:${team.id}`"
          >
            当前账号：{{ team.name }}
          </el-dropdown-item>
          <el-dropdown-item
            v-for="account in linkedAccounts"
            :key="`account-${account.id}`"
            :command="`account:${account.id}`"
          >
            切换账号：{{ account.name || account.username }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <div v-else class="team-switcher__static">
      <el-avatar :size="28" :src="teamManagement.selectedTeam?.avatar">{{
        currentLabel.slice(0, 1)
      }}</el-avatar>
      <span>{{ currentLabel }}</span>
    </div>
    <el-dialog v-model="trustDialogVisible" title="首次切换验证" width="380px">
      <p class="trust-tip">
        请输入目标账号 {{ pendingAccount?.username }} 的密码，验证后后续可一键切换。
      </p>
      <el-input
        v-model="trustPassword"
        type="password"
        show-password
        autocomplete="current-password"
      />
      <template #footer>
        <el-button @click="trustDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="switching" @click="confirmTrustAndSwitch"
          >验证并切换</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { fetchLinkedAccounts, switchLinkedAccount, trustLinkedAccount } from '@/api/account'
import { useImWorkspace } from '@/composables/useImWorkspace'
import { useChatStore } from '@/store/chat'
import { useCurrentUserStore } from '@/store/currentUser'
import { useSessionStore } from '@/store/session'
import { useTeamStore } from '@/store/team'
import { handleBusinessError } from '@/utils/error'

const router = useRouter()
const teamManagement = useTeamStore()
const imChat = useChatStore()
const imWorkspace = useImWorkspace({ teamStore: teamManagement, chatStore: imChat })
const currentUserStore = useCurrentUserStore()
const sessionStore = useSessionStore()
const linkedAccounts = ref([])
const trustDialogVisible = ref(false)
const trustPassword = ref('')
const pendingAccount = ref(null)
const switching = ref(false)

const currentLabel = computed(
  () => teamManagement.selectedTeam?.name || teamManagement.teams[0]?.name || '当前团队',
)
const canSwitch = computed(
  () => teamManagement.teams.length >= 2 || linkedAccounts.value.length > 0,
)

onMounted(() => {
  loadLinkedAccounts()
})

async function loadLinkedAccounts() {
  try {
    const response = await fetchLinkedAccounts()
    linkedAccounts.value = Array.isArray(response?.data) ? response.data : []
  } catch {
    linkedAccounts.value = []
  }
}

async function handleCommand(command) {
  const [type, rawId] = String(command).split(':')
  const id = Number(rawId)
  if (type === 'team') {
    await switchTeam(id)
    return
  }
  if (type === 'account') {
    await startAccountSwitch(id)
  }
}

async function switchTeam(teamId) {
  await imWorkspace.switchTeam(teamId)
}

async function startAccountSwitch(accountId) {
  const account = linkedAccounts.value.find((item) => Number(item.id) === Number(accountId))
  if (!account) return
  if (!account.trusted) {
    pendingAccount.value = account
    trustPassword.value = ''
    trustDialogVisible.value = true
    return
  }
  await doSwitchAccount(account.id)
}

async function confirmTrustAndSwitch() {
  if (!pendingAccount.value) return
  if (!trustPassword.value.trim()) {
    ElMessage.warning('请输入目标账号密码')
    return
  }
  switching.value = true
  try {
    await trustLinkedAccount(pendingAccount.value.id, { password: trustPassword.value.trim() })
    await doSwitchAccount(pendingAccount.value.id)
    trustDialogVisible.value = false
  } catch (error) {
    handleBusinessError(error, '切换账号失败')
  } finally {
    switching.value = false
  }
}

async function doSwitchAccount(accountId) {
  switching.value = true
  try {
    const response = await switchLinkedAccount(accountId)
    // token 通过 Set-Cookie 自动存储，前端不再手动保存
    currentUserStore.setProfile(response?.data?.profile || null)
    imChat.disconnect()
    await Promise.all([sessionStore.ensureSessionReady({ force: true }), loadLinkedAccounts()])
    await imChat.loadConversations()
    imChat.ensureConnected()
    ElMessage.success('账号已切换')
    router.replace({ name: 'chatHome' })
  } catch (error) {
    handleBusinessError(error, '切换账号失败')
  } finally {
    switching.value = false
  }
}
</script>

<style scoped>
.team-switcher {
  padding: 12px;
  border-top: 1px solid #ebeef5;
}

.team-switcher__button,
.team-switcher__static {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
}

.team-switcher__button {
  cursor: pointer;
}

.team-switcher__button span,
.team-switcher__static span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trust-tip {
  margin: 0 0 12px;
  color: #606266;
  line-height: 1.5;
}
</style>
