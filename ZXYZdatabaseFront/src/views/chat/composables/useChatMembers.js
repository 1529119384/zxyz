import { computed, nextTick, ref } from 'vue'

import { PROJECT, TEAM } from '@/constants/conversationTypes'
import { getMemberDisplayName } from '@/models/imPresentation'
import { handleBusinessError } from '@/utils/error'

export function useChatMembers({
  chatStore,
  teamStore,
  activeConversation,
  moreDrawerVisible,
  scrollToBottom,
}) {
  const membersExpanded = ref(false)
  const memberCardVisible = ref(false)
  const memberCardVirtualRef = ref(null)
  const selectedMember = ref(null)
  // 07-A-3：此前是裸字符串 `['TEAM', 'PROJECT']`，把 constants/conversationTypes 里
  // 已有的取值口径又抄了一遍 —— 后端若加一种「有成员列表」的类型，两处会各自漂移。
  const isMemberListConversation = computed(() =>
    [TEAM, PROJECT].includes(activeConversation.value?.type),
  )
  const visibleGroupMembers = computed(() =>
    membersExpanded.value ? teamStore.teamMembers : teamStore.teamMembers.slice(0, 8),
  )
  const canExpandGroupMembers = computed(
    () =>
      isMemberListConversation.value &&
      !membersExpanded.value &&
      teamStore.teamMembers.length > visibleGroupMembers.value.length,
  )

  // 07-P1-7：实现提到 models/imPresentation（ChatMoreDrawer 也要用，此前靠 prop 传函数），
  // 这里只做转发，避免复制出第二份回落顺序。
  function displayMemberName(member) {
    return getMemberDisplayName(member)
  }

  function mentionName(userId) {
    const member = teamStore.teamMembers.find((item) => Number(item.userId) === Number(userId))
    return member ? displayMemberName(member) : `用户 ${userId}`
  }

  function openMemberCard(member, event) {
    selectedMember.value = member
    memberCardVirtualRef.value =
      event?.currentTarget || (typeof document === 'undefined' ? null : document.activeElement)
    memberCardVisible.value = true
  }

  function closeMemberCard() {
    memberCardVisible.value = false
  }

  function expandMembers() {
    membersExpanded.value = true
  }

  function resetMemberPanel() {
    membersExpanded.value = false
    memberCardVisible.value = false
    memberCardVirtualRef.value = null
    selectedMember.value = null
  }

  async function startDirectChatFromMember(member) {
    const teamId = Number(activeConversation.value?.teamId || teamStore.selectedTeamId)
    const targetUserId = Number(member?.userId)
    if (
      !Number.isSafeInteger(teamId) ||
      teamId <= 0 ||
      !Number.isSafeInteger(targetUserId) ||
      targetUserId <= 0
    ) {
      return
    }
    try {
      await chatStore.createDirectConversationAndOpen(teamId, targetUserId)
      moreDrawerVisible.value = false
      closeMemberCard()
      await nextTick()
      scrollToBottom()
    } catch (error) {
      handleBusinessError(error, '创建私聊失败')
    }
  }

  return {
    isMemberListConversation,
    visibleGroupMembers,
    canExpandGroupMembers,
    displayMemberName,
    mentionName,
    memberCardVisible,
    memberCardVirtualRef,
    selectedMember,
    openMemberCard,
    closeMemberCard,
    expandMembers,
    resetMemberPanel,
    startDirectChatFromMember,
  }
}
