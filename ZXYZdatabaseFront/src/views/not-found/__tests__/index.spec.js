/**
 * 404 页渲染层测试 —— `src/views/**` 下的第一个测试文件。
 *
 * 这个页面逻辑极少，值得钉住的只有两件事：
 *   1. **出口是确定的**：用户走到 404 之后必须有一个明确去处（返回首页），
 *      不能又是一个死胡同 —— 那等于把「白屏」换成了「点了没反应的静态页」。
 *   2. **出口用 `replace` 而不是 `push`**：否则用户点浏览器「后退」会退回 404，
 *      观感上像死循环。这是本页唯一容易被后来者改错的地方。
 *
 * 顺带钉住「不回显用户访问的路径」这条设计决策（见视图头部注释：回显外部可控
 * 文案属于钓鱼面）。实现方式是**只 mock `useRouter`、不 mock `useRoute`**：
 * 若哪天有人在模板里加了 `route.fullPath`，这个测试会因为 `useRoute` 未定义而失败。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const h = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }))

// 只提供 useRouter ⇒ 组件若去用 useRoute 会立刻炸，从而守住「不回显路径」
vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: h.replace, push: h.push }),
}))

import NotFound from '@/views/not-found/index.vue'

const ElButtonStub = {
  name: 'ElButton',
  props: ['type'],
  template: '<button type="button"><slot /></button>',
}

function mountNotFound() {
  return mount(NotFound, { global: { stubs: { 'el-button': ElButtonStub } } })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('404 页', () => {
  it('渲染固定的 404 文案', () => {
    const wrapper = mountNotFound()
    expect(wrapper.find('.not-found-code').text()).toBe('404')
    expect(wrapper.find('h1').text()).toBe('页面不存在')
    expect(wrapper.find('button').text()).toBe('返回首页')
  })

  it('点「返回首页」用 replace 回 index（push 会往历史栈里塞一条 404）', async () => {
    const wrapper = mountNotFound()
    await wrapper.find('button').trigger('click')

    expect(h.replace).toHaveBeenCalledTimes(1)
    expect(h.replace).toHaveBeenCalledWith({ name: 'index' })
    expect(h.push).not.toHaveBeenCalled()
  })
})
