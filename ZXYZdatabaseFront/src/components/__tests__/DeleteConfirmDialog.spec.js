/**
 * `DeleteConfirmDialog` 渲染层测试。
 *
 * 全仓 `src/components/__tests__/` 的第三个组件测试。
 * 这里要钉住的是 **文案分支**（`type` ⇒ 「文件/文件夹」、`message` 覆盖）与
 * **`submitting` 期间的重复提交拦截** —— 前者是用户最容易看出问题的界面细节，
 * 后者是「删除按钮连点两次」这类真实事故的防线。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

import DeleteConfirmDialog from '@/components/DeleteConfirmDialog.vue'

const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: `<div class="stub-dialog">
      <slot />
      <slot name="footer" />
      <button class="stub-dialog-show" type="button" @click="$emit('update:modelValue', true)"></button>
      <button class="stub-dialog-hide" type="button" @click="$emit('update:modelValue', false)"></button>
    </div>`,
}

const ElButtonStub = {
  name: 'ElButton',
  props: ['loading', 'disabled', 'type'],
  template: '<button type="button" :disabled="disabled"><slot /></button>',
}

const globalStubs = {
  'el-dialog': ElDialogStub,
  'el-button': ElButtonStub,
}

function mountDialog(props = {}) {
  return mount(DeleteConfirmDialog, {
    props: { visible: true, ...props },
    global: { stubs: globalStubs },
  })
}

/** 前两个按钮：取消、确认（stub 自带按钮排在后面）。 */
function buttons(wrapper) {
  const found = wrapper.findAll('button')
  return { cancel: found[0], confirm: found[1] }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DeleteConfirmDialog: 确认文案', () => {
  it('默认按 type=1 拼「文件」文案', () => {
    const wrapper = mountDialog({ fileName: '报告.pdf' })
    expect(wrapper.find('.delete-confirm-title').text()).toBe('确认删除文件“报告.pdf”吗？')
  })

  it('type=0 时拼「文件夹」文案', () => {
    const wrapper = mountDialog({ fileName: '我的资料', type: 0 })
    expect(wrapper.find('.delete-confirm-title').text()).toBe('确认删除文件夹“我的资料”吗？')
  })

  it('显式传 message 时完全覆盖默认文案（type / fileName 都不再参与拼接）', () => {
    const wrapper = mountDialog({ fileName: 'x', type: 0, message: '自定义提示语' })
    expect(wrapper.find('.delete-confirm-title').text()).toBe('自定义提示语')
  })

  it('未传 fileName 时回退为「当前文件」', () => {
    const wrapper = mountDialog()
    expect(wrapper.find('.delete-confirm-title').text()).toBe('确认删除文件“当前文件”吗？')
  })

  it('渲染提示语与确认按钮文案', () => {
    const wrapper = mountDialog({ tip: '自定义提示', confirmText: '立即删除' })
    expect(wrapper.find('.delete-confirm-tip').text()).toBe('自定义提示')
    expect(buttons(wrapper).confirm.text()).toBe('立即删除')
  })
})

describe('DeleteConfirmDialog: 事件', () => {
  it('确认发出 submit（组件自身不执行删除）', async () => {
    const wrapper = mountDialog()
    await buttons(wrapper).confirm.trigger('click')
    expect(wrapper.emitted('submit')).toEqual([[]])
  })

  it('submitting=true 时确认与取消都被拦截', async () => {
    const wrapper = mountDialog({ submitting: true })
    await buttons(wrapper).confirm.trigger('click')
    await buttons(wrapper).cancel.trigger('click')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('submitting=true 时确认按钮被 disabled（UI 层也挡住连点）', () => {
    const wrapper = mountDialog({ submitting: true })
    expect(buttons(wrapper).confirm.attributes('disabled')).toBeDefined()
  })

  it('取消发出 update:visible=false', async () => {
    const wrapper = mountDialog()
    await buttons(wrapper).cancel.trigger('click')
    expect(wrapper.emitted('update:visible')?.[0]).toEqual([false])
  })

  it('弹窗请求打开 ⇒ update:visible=true；请求关闭 ⇒ false', async () => {
    const wrapper = mountDialog()
    await wrapper.find('.stub-dialog-show').trigger('click')
    await wrapper.find('.stub-dialog-hide').trigger('click')
    expect(wrapper.emitted('update:visible')).toEqual([[true], [false]])
  })
})
