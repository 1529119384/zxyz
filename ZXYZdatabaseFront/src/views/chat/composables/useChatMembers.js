import { computed, nextTick, ref } from 'vue'

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
  const isMemberListConversation = computed(() =>
    ['TEAM', 'PROJECT'].includes(activeConversation.value?.type),
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

  function displayMemberName(member) {
    return member.name || member.username || `用户 ${member.userId}`
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
