import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'

import { useChatVisibilitySync } from '@/views/chat/composables/useChatVisibilitySync'

// J-5（2026-10-03）：ChatHome 在 layout 的 keepAliveNames 里被 keep-alive 缓存，
// 但 useChatVisibilitySync 只在 setup 时 setChatViewActive(true)，停用（切走路由）
// 时没有任何路径把它置回 false —— 用户没看的消息会被「自动已读」静默清零。
// 用真实 <KeepAlive> + 动态组件驱动 activated/deactivated 生命周期来复现。

function createFakeChatStore() {
  return {
    setChatViewActive: vi.fn(),
    setWindowFocused: vi.fn(),
    clearReadSyncTimers: vi.fn(),
    isConversationEffectivelyVisible: vi.fn(() => false),
    loadConversationMessages: vi.fn(() => Promise.resolve([])),
    activeConversationId: null,
  }
}

function createHarness() {
  const chatStore = createFakeChatStore()
  const ChatView = defineComponent({
    setup() {
      useChatVisibilitySync({ imChat: chatStore })
      return () => h('div', 'chat-view')
    },
  })
  const OtherView = defineComponent({
    setup() {
      return () => h('div', 'other-view')
    },
  })
  const Harness = defineComponent({
    components: { ChatView, OtherView },
    setup() {
      const current = ref('ChatView')
      return { current }
    },
    template: `
      <div>
        <keep-alive>
          <component :is="current" />
        </keep-alive>
      </div>
    `,
  })
  return { chatStore, Harness }
}

describe('useChatVisibilitySync 的 keep-alive 可见性同步', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应在挂载时声明聊天视图激活', () => {
    const { chatStore, Harness } = createHarness()
    mount(Harness)
    expect(chatStore.setChatViewActive).toHaveBeenCalledWith(true)
  })

  it('应在组件被 keep-alive 停用时置回非激活并清理读同步定时器（J-5）', async () => {
    const { chatStore, Harness } = createHarness()
    const wrapper = mount(Harness)
    expect(wrapper.text()).toContain('chat-view')
    const activatedCalls = chatStore.setChatViewActive.mock.calls.length

    // 切走路由 → ChatHome 被 keep-alive 停用（deactivated）
    wrapper.vm.current = 'OtherView'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('other-view')

    expect(chatStore.setChatViewActive).toHaveBeenCalledWith(false)
    expect(chatStore.clearReadSyncTimers).toHaveBeenCalledTimes(1)

    // 切回 → ChatHome 从缓存激活（activated），必须恢复可见
    wrapper.vm.current = 'ChatView'
    await wrapper.vm.$nextTick()
    expect(chatStore.setChatViewActive.mock.calls.length).toBeGreaterThan(activatedCalls)
    expect(chatStore.setChatViewActive).toHaveBeenLastCalledWith(true)
  })
})
