/**
 * `InputDialog` 渲染层测试。
 *
 * 选它的理由：这是全仓 `src/components/__tests__/` 的**第二个**组件测试
 * （此前只有 `TeamSwitcher.spec.js`，24 个组件里 23 个零测试）。
 * 它体量小但**分支真实**：校验器的四种返回形态、`submitting` 期间的双重拦截、
 * 以及「重新打开时重置输入」这条容易被改坏的状态逻辑 —— 都是纯函数测试覆盖不到的。
 *
 * ⚠️ 这里**不能**像 TeamSwitcher.spec.js 那样 `vi.mock('element-plus', ...)` 整模块替换：
 * `unplugin-vue-components` 的 ElementPlusResolver 会把模板里的 `el-input` 等
 * **编译成 `import { ElInput } from 'element-plus'` 的具名导入**，
 * 整模块 mock 掉之后组件自己在 import 阶段就炸
 * （`No "ElInput" export is defined on the "element-plus" mock`）。
 * 改为只 spy 需要断言的 `ElMessage`，其余保持真实模块。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'

import InputDialog from '@/components/InputDialog.vue'

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

const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'placeholder'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

const globalStubs = {
  'el-dialog': ElDialogStub,
  'el-button': ElButtonStub,
  'el-input': ElInputStub,
}

function mountDialog(props = {}) {
  return mount(InputDialog, {
    props: {
      visible: true,
      title: '新建文件夹',
      placeholder: '请输入名称',
      confirmText: '确定',
      ...props,
    },
    global: { stubs: globalStubs },
  })
}

/** 弹窗里的前两个按钮：第 0 个是取消、第 1 个是确认（stub 自己的按钮排在后面）。 */
function buttons(wrapper) {
  const found = wrapper.findAll('button')
  return { cancel: found[0], confirm: found[1] }
}

beforeEach(() => {
  // 只 spy，不替换模块：组件自身还要从 element-plus 取 ElInput/ElButton/ElDialog。
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => {})
})

describe('InputDialog: 渲染', () => {
  it('渲染占位符与两个按钮文案', () => {
    const wrapper = mountDialog()
    expect(wrapper.find('input').attributes('placeholder')).toBe('请输入名称')
    expect(buttons(wrapper).confirm.text()).toBe('确定')
    expect(buttons(wrapper).cancel.text()).toBe('取消')
  })

  it('defaultValue 作为初始输入值', () => {
    const wrapper = mountDialog({ defaultValue: '初始值' })
    expect(wrapper.find('input').element.value).toBe('初始值')
  })
})

describe('InputDialog: 校验分支', () => {
  it('没有 validator 且内容为空 ⇒ 提示「输入内容不能为空」，不发 submit', async () => {
    const wrapper = mountDialog()
    await buttons(wrapper).confirm.trigger('click')

    expect(ElMessage.warning).toHaveBeenCalledWith('输入内容不能为空')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('validator 返回 true / undefined / null 都视为通过', async () => {
    for (const result of [true, undefined, null]) {
      const wrapper = mountDialog({ defaultValue: 'x', validator: vi.fn(() => result) })
      await buttons(wrapper).confirm.trigger('click')
      expect(ElMessage.warning, `validator 返回 ${String(result)} 时不应提示`).not.toHaveBeenCalled()
      expect(wrapper.emitted('submit')?.[0]).toEqual(['x'])
      vi.clearAllMocks()
    }
  })

  it('validator 返回 false ⇒ 通用「输入不合法」', async () => {
    const wrapper = mountDialog({ defaultValue: 'x', validator: vi.fn(() => false) })
    await buttons(wrapper).confirm.trigger('click')

    expect(ElMessage.warning).toHaveBeenCalledWith('输入不合法')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('validator 返回字符串 ⇒ 原样作为提示；返回空串时视为通过', async () => {
    const wrapper = mountDialog({ defaultValue: 'x', validator: vi.fn(() => '名称重复') })
    await buttons(wrapper).confirm.trigger('click')
    expect(ElMessage.warning).toHaveBeenCalledWith('名称重复')

    vi.clearAllMocks()
    const ok = mountDialog({ defaultValue: 'x', validator: vi.fn(() => '') })
    await buttons(ok).confirm.trigger('click')
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(ok.emitted('submit')?.[0]).toEqual(['x'])
  })

  it('提交值经过 trim，且校验拿到的也是 trim 后的值', async () => {
    const validator = vi.fn(() => true)
    const wrapper = mountDialog({ defaultValue: '  含空格  ', validator })
    await buttons(wrapper).confirm.trigger('click')

    expect(validator).toHaveBeenCalledWith('含空格')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['含空格'])
  })

  it('输入框回车等效于点击确认', async () => {
    const wrapper = mountDialog({ defaultValue: '回车提交' })
    await wrapper.find('input').trigger('keyup.enter')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['回车提交'])
  })
})

describe('InputDialog: submitting 期间的拦截', () => {
  it('submitting=true 时确认不发 submit、取消不发 update:visible', async () => {
    const wrapper = mountDialog({ defaultValue: 'x', submitting: true })
    await buttons(wrapper).confirm.trigger('click')
    await buttons(wrapper).cancel.trigger('click')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })
})

describe('InputDialog: 关闭与重开', () => {
  it('取消发出 update:visible=false', async () => {
    const wrapper = mountDialog()
    await buttons(wrapper).cancel.trigger('click')
    expect(wrapper.emitted('update:visible')?.[0]).toEqual([false])
  })

  it('弹窗自身请求打开 ⇒ update:visible=true', async () => {
    const wrapper = mountDialog()
    await wrapper.find('.stub-dialog-show').trigger('click')
    expect(wrapper.emitted('update:visible')?.[0]).toEqual([true])
  })

  it('弹窗自身请求关闭（如点遮罩）⇒ 收敛为 update:visible=false', async () => {
    const wrapper = mountDialog()
    await wrapper.find('.stub-dialog-hide').trigger('click')
    expect(wrapper.emitted('update:visible')?.[0]).toEqual([false])
  })

  it('重新打开时输入值被重置为 defaultValue（不留上次残留）', async () => {
    const wrapper = mountDialog({ visible: false, defaultValue: '默认' })
    await wrapper.setProps({ visible: true })
    await wrapper.find('input').setValue('用户改过的值')
    expect(wrapper.find('input').element.value).toBe('用户改过的值')

    // 关闭再打开 ⇒ 回到 defaultValue
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    expect(wrapper.find('input').element.value).toBe('默认')
  })
})
