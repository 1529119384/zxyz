/**
 * 仓库内的**第一个组件渲染层测试**（此前 0 个，正是 doc 10 点名的缺口「组件渲染层」）。
 *
 * 之所以在这里破例立起样板，是因为 07-P0-3 的修复点在 `onMounted` 里发起的请求上，
 * 其可观测后果只出现在渲染结果中：加载失败时根节点会因 teams/linkedAccounts 同时为空
 * 而整体不渲染，成功时则渲染出下拉/静态分支。纯函数级测试覆盖不到这条链路。
 *
 * 依赖全部走模块 mock：
 * - 四个 Pinia store 与 useImWorkspace 都 mock 成普通函数 ⇒ 无需 createPinia；
 * - element-plus 整体 mock（组件只用到 ElMessage），模板里的 el-* 通过 global.stubs 兜住，
 *   否则会打出一片 "Failed to resolve component" 噪音。
 * - logger 是 Object.freeze 的，无法 spyOn，只能整模块 mock。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

vi.mock('vue-router', () => ({
  useRouter: vi.fn(() => ({ replace: vi.fn() })),
}))

vi.mock('@/api/account', () => ({
  fetchLinkedAccounts: vi.fn(),
  switchLinkedAccount: vi.fn(),
  trustLinkedAccount: vi.fn(),
}))

vi.mock('@/composables/useImWorkspace', () => ({
  useImWorkspace: vi.fn(() => ({ switchTeam: vi.fn() })),
}))

vi.mock('@/store/chat', () => ({
  useChatStore: vi.fn(() => ({
    disconnect: vi.fn(),
    loadConversations: vi.fn(),
    ensureConnected: vi.fn(),
  })),
}))

vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: vi.fn(() => ({ setProfile: vi.fn() })),
}))

vi.mock('@/store/session', () => ({
  useSessionStore: vi.fn(() => ({ ensureSessionReady: vi.fn() })),
}))

vi.mock('@/store/team', () => ({
  useTeamStore: vi.fn(() => ({ teams: [], selectedTeam: null })),
}))

vi.mock('@/utils/error', () => ({
  handleBusinessError: vi.fn(),
}))

vi.mock('@/utils/logger', () => ({
  logger: { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

import { fetchLinkedAccounts } from '@/api/account'
import TeamSwitcher from '@/components/TeamSwitcher.vue'
import { useTeamStore } from '@/store/team'
import { logger } from '@/utils/logger'

// el-dropdown 需要渲染默认插槽，可切换分支里的按钮才可见；
// 其余组件（含 el-dialog / el-dropdown-item）不渲染插槽即可，用 true 最省事。
const globalStubs = {
  'el-dropdown': { template: '<div class="stub-dropdown"><slot /></div>' },
  'el-dropdown-menu': true,
  'el-dropdown-item': true,
  'el-avatar': true,
  'el-dialog': true,
  'el-input': true,
  'el-button': true,
}

function mountSwitcher() {
  return mount(TeamSwitcher, { global: { stubs: globalStubs } })
}

describe('TeamSwitcher', () => {
  beforeEach(() => {
    // clearAllMocks 清不掉 mockReturnValue，默认实现必须逐个重置。
    useTeamStore.mockReturnValue({ teams: [], selectedTeam: null })
    fetchLinkedAccounts.mockResolvedValue({ data: [] })
  })

  it('加载到关联账号后渲染根节点，且不产生告警日志', async () => {
    fetchLinkedAccounts.mockResolvedValue({ data: [{ id: 1, name: '小号' }] })

    const wrapper = mountSwitcher()
    await flushPromises()

    expect(fetchLinkedAccounts).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.team-switcher').exists()).toBe(true)
    expect(logger.warn).not.toHaveBeenCalled()
  })

  it('加载失败时回退为空列表并留下告警日志（07-P0-3：不再静默吞异常）', async () => {
    const error = new Error('Network error')
    fetchLinkedAccounts.mockRejectedValue(error)

    const wrapper = mountSwitcher()
    await flushPromises()

    // 团队为空 + 账号回退为空 ⇒ 根节点整体不渲染（v-if），
    // 同时因为异常已在组件内被捕获，不会冒出未处理的 Promise 拒绝。
    expect(wrapper.find('.team-switcher').exists()).toBe(false)
    // 关键断言：回退语义保持，但失败必须留痕。
    expect(logger.warn).toHaveBeenCalledTimes(1)
    expect(logger.warn).toHaveBeenCalledWith('加载可切换账号失败，已回退为空列表:', error)
  })

  it('接口返回非数组时按空列表处理，且不记告警（这不是异常）', async () => {
    fetchLinkedAccounts.mockResolvedValue({ data: null })

    const wrapper = mountSwitcher()
    await flushPromises()

    expect(wrapper.find('.team-switcher').exists()).toBe(false)
    expect(logger.warn).not.toHaveBeenCalled()
  })

  it('仅有团队时渲染静态展示分支（不可切换）', async () => {
    useTeamStore.mockReturnValue({ teams: [{ id: 1, name: 'A' }], selectedTeam: { name: 'A' } })

    const wrapper = mountSwitcher()
    await flushPromises()

    expect(wrapper.find('.team-switcher__static').exists()).toBe(true)
    expect(wrapper.find('.team-switcher__button').exists()).toBe(false)
  })

  it('团队数 >= 2 时渲染可点击的切换按钮', async () => {
    useTeamStore.mockReturnValue({
      teams: [
        { id: 1, name: 'A' },
        { id: 2, name: 'B' },
      ],
      selectedTeam: { name: 'A' },
    })

    const wrapper = mountSwitcher()
    await flushPromises()

    expect(wrapper.find('.team-switcher__button').exists()).toBe(true)
    expect(wrapper.find('.team-switcher__static').exists()).toBe(false)
  })
})
