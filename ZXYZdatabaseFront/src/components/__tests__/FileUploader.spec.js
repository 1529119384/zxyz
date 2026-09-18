/**
 * `FileUploader` 渲染层测试 —— `ISSUE/24` §六 G-6(b) 点名的三条关键路径之一（上传）。
 *
 * 这个组件是 `useFileUpload` 的**薄壳**，所以容易误以为"composable 测过就够了"。
 * 恰恰相反：真正决定用户体验与数据安全的分支全在壳里——
 * `doUpload()` 拿到 `{ successList, failList }` 之后**按三种成立条件给出三种提示，
 * 并且决定要不要关窗**。其中最要命的一条是：
 *
 *   「全部失败」时**必须保持弹窗打开**，否则用户连重试的机会都没有。
 *
 * 这条语义在任何地方都没有类型约束，改坏了 CI 也不会响 —— 故在此钉死。
 * 另外钉住「未选文件 / 上传中」两种按钮禁用态（防呆 + 防重复提交）。
 *
 * ⚠️ 不能 `vi.mock('element-plus', ...)` 整模块替换：`unplugin-vue-components`
 * 把模板里的 `el-dialog` 等编译成 `import { ElDialog } from 'element-plus'` 具名导入，
 * 整模块 mock 会让组件在 import 阶段就炸。改为只 spy `ElMessage` 的三个方法。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import { useFileUpload } from '@/composables/useFileUpload'
import FileUploader from '@/components/FileUploader.vue'
import { handleBusinessError } from '@/utils/error'
import { logger } from '@/utils/logger'

vi.mock('@/composables/useFileUpload', () => ({ useFileUpload: vi.fn() }))
vi.mock('@/composables/useCurrentSpaceContext', () => ({
  useProvidedSpaceContext: vi.fn(() => ({})),
}))
vi.mock('@/store/currentId', () => ({
  useCurrentIdStore: vi.fn(() => ({ currentId: 'root' })),
}))
vi.mock('@/utils/error', () => ({ handleBusinessError: vi.fn() }))
vi.mock('@/utils/logger', () => ({
  logger: { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

// 只 spy，不替换（理由见文件头注释）。
vi.spyOn(ElMessage, 'success').mockImplementation(() => {})
vi.spyOn(ElMessage, 'warning').mockImplementation(() => {})
vi.spyOn(ElMessage, 'error').mockImplementation(() => {})

const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue', 'title'],
  emits: ['update:modelValue', 'closed'],
  template: '<div class="stub-dialog"><slot /><slot name="footer" /></div>',
}

const ElButtonStub = {
  name: 'ElButton',
  props: ['loading', 'disabled', 'type'],
  template: '<button type="button" :disabled="disabled"><slot /></button>',
}

const globalStubs = {
  'el-dialog': ElDialogStub,
  'el-button': ElButtonStub,
  'el-progress': { name: 'ElProgress', props: ['percentage', 'status', 'strokeWidth'], template: '<div class="stub-progress" />' },
  // 需要渲染默认插槽：移除按钮的 @click 挂在 el-icon 根节点上，槽不渲染就点不到
  'el-icon': { name: 'ElIcon', template: '<span><slot /></span>' },
}

function fileListRef(names = [{ name: 'a.txt', size: 1024 }]) {
  return ref(names)
}

/** 伪造 `useFileUpload` 的返回：全是 ref / 函数，形状与真实实现一致。 */
function stateRefs(overrides = {}) {
  return {
    fileUploadDialog: ref(true),
    fileInput: ref(null),
    fileList: fileListRef(),
    uploading: ref(false),
    progress: ref(0),
    totalFileSize: ref(1024),
    uploadedBytes: ref(0),
    triggerSelect: vi.fn(),
    handleDragOver: vi.fn(),
    getFileKey: (file) => file.name,
    getPredictedName: vi.fn(() => ''),
    isPredictedRenamed: vi.fn(() => false),
    resetFileUploadState: vi.fn(),
    handleSelect: vi.fn(),
    handleDrop: vi.fn(),
    removeFile: vi.fn(),
    handleCancelFileUpload: vi.fn(),
    doUpload: vi.fn().mockResolvedValue({ successList: [], failList: [] }),
    openFileUpload: vi.fn(),
    ...overrides,
  }
}

function mountUploader(state = stateRefs(), props = {}) {
  useFileUpload.mockReturnValue(state)
  const wrapper = mount(FileUploader, { props, global: { stubs: globalStubs } })
  return { wrapper, state }
}

function buttons(wrapper) {
  return wrapper.findAll('button')
}

function buttonByText(wrapper, text) {
  const found = buttons(wrapper).find((button) => button.text() === text)
  if (!found) {
    throw new Error(
      `没有文案为「${text}」的按钮，实际为：${JSON.stringify(buttons(wrapper).map((b) => b.text()))}`,
    )
  }
  return found
}

/** 上传按钮文案会在 uploading 时变成「上传中...」。 */
function uploadButton(wrapper) {
  return buttons(wrapper).find((button) => button.text().startsWith('上传'))
}

function cancelButton(wrapper) {
  return buttonByText(wrapper, '取消')
}

/** 取组件传给 composable 的第二个参数（options）。 */
function lastOptions() {
  return useFileUpload.mock.calls.at(-1)[1]
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('FileUploader: 上传结果三条分支', () => {
  it('全部成功 ⇒ success 提示 + 关闭弹窗', async () => {
    const { wrapper, state } = mountUploader(
      stateRefs({
        doUpload: vi.fn().mockResolvedValue({ successList: [{ fileName: 'a.txt' }], failList: [] }),
      }),
    )

    await uploadButton(wrapper).trigger('click')

    expect(ElMessage.success).toHaveBeenCalledTimes(1)
    expect(ElMessage.success.mock.calls[0][0]).toContain('全部文件上传成功')
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(state.fileUploadDialog.value).toBe(false)
  })

  it('部分成功 ⇒ warning 提示 + 关闭弹窗（成功数与失败数都写进文案）', async () => {
    const { wrapper, state } = mountUploader(
      stateRefs({
        doUpload: vi.fn().mockResolvedValue({
          successList: [{ fileName: 'a.txt' }],
          failList: [{ fileName: 'b.txt', message: '超出大小限制' }],
        }),
      }),
    )

    await uploadButton(wrapper).trigger('click')

    expect(ElMessage.warning).toHaveBeenCalledTimes(1)
    const text = ElMessage.warning.mock.calls[0][0]
    expect(text).toContain('部分文件上传成功')
    expect(text).toContain('成功 1 个')
    expect(text).toContain('失败 1 个')
    expect(text).toContain('b.txt')
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(state.fileUploadDialog.value).toBe(false)
  })

  it('全部失败 ⇒ error 提示，且**弹窗保持打开**（否则用户无法重试）', async () => {
    const { wrapper, state } = mountUploader(
      stateRefs({
        doUpload: vi
          .fn()
          .mockResolvedValue({ successList: [], failList: [{ fileName: 'b.txt', message: '超限' }] }),
      }),
    )

    await uploadButton(wrapper).trigger('click')

    expect(ElMessage.error).toHaveBeenCalledTimes(1)
    expect(ElMessage.error.mock.calls[0][0]).toContain('文件上传失败')
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(state.fileUploadDialog.value).toBe(true)
  })

  it('上传抛异常 ⇒ 记录失败文件名 + 业务错误兜底，且弹窗保持打开', async () => {
    const error = new Error('boom')
    const { wrapper, state } = mountUploader(
      stateRefs({ doUpload: vi.fn().mockRejectedValue(error) }),
    )

    await uploadButton(wrapper).trigger('click')

    expect(logger.error).toHaveBeenCalledWith('[文件上传流程失败]', {
      files: ['a.txt'],
      message: 'boom',
    })
    expect(handleBusinessError).toHaveBeenCalledWith(error, '文件上传失败，请重试')
    expect(state.fileUploadDialog.value).toBe(true)
    // 异常路径不应误报"成功"
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('异常对象没有 message 时也能留痕（不因取属性再抛）', async () => {
    const { wrapper } = mountUploader(stateRefs({ doUpload: vi.fn().mockRejectedValue('plain') }))

    await uploadButton(wrapper).trigger('click')

    expect(logger.error).toHaveBeenCalledWith('[文件上传流程失败]', {
      files: ['a.txt'],
      message: undefined,
    })
  })
})

describe('FileUploader: 按钮禁用态', () => {
  it('未选择文件时上传按钮 disabled，但仍可取消', async () => {
    const { wrapper, state } = mountUploader(stateRefs({ fileList: fileListRef([]) }))

    expect(uploadButton(wrapper).attributes('disabled')).toBeDefined()
    expect(cancelButton(wrapper).attributes('disabled')).toBeUndefined()

    await uploadButton(wrapper).trigger('click')
    expect(state.doUpload).not.toHaveBeenCalled()
  })

  it('上传中：按钮文案变「上传中...」、上传与取消双双 disabled（防重复提交）', () => {
    const { wrapper } = mountUploader(stateRefs({ uploading: ref(true) }))

    expect(uploadButton(wrapper).text()).toBe('上传中...')
    expect(uploadButton(wrapper).attributes('disabled')).toBeDefined()
    expect(cancelButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('上传中时点击上传按钮不会再次发起请求', async () => {
    const { wrapper, state } = mountUploader(stateRefs({ uploading: ref(true) }))

    await uploadButton(wrapper).trigger('click')

    expect(state.doUpload).not.toHaveBeenCalled()
  })
})

describe('FileUploader: 交互与展示', () => {
  it('点击拖拽区触发选择，drop 交给 handleDrop', async () => {
    const { wrapper, state } = mountUploader()

    await wrapper.find('.upload-drag').trigger('click')
    await wrapper.find('.upload-drag').trigger('drop')

    expect(state.triggerSelect).toHaveBeenCalledTimes(1)
    expect(state.handleDrop).toHaveBeenCalledTimes(1)
  })

  it('移除按钮按索引调用 removeFile，且不会冒泡触发选择', async () => {
    const { wrapper, state } = mountUploader()

    await wrapper.find('.remove-btn').trigger('click')

    expect(state.removeFile).toHaveBeenCalledWith(0)
    // @click.stop 生效 ⇒ 不会顺带打开文件选择框
    expect(state.triggerSelect).not.toHaveBeenCalled()
  })

  it('合计区展示文件数与总大小（两者都取自 composable，组件只负责渲染）', () => {
    const { wrapper } = mountUploader(
      stateRefs({
        fileList: fileListRef([
          { name: 'a.txt', size: 1024 },
          { name: 'b.txt', size: 1024 },
        ]),
        totalFileSize: ref(2048),
      }),
    )
    const summary = wrapper.find('.upload-summary').text()

    expect(summary).toContain('已选择 2 个文件')
    expect(summary).toContain('2.00 KB')
  })

  it('重名预测命中时才展示「预计名称」', () => {
    const renamed = mountUploader(
      stateRefs({ isPredictedRenamed: vi.fn(() => true), getPredictedName: vi.fn(() => 'a(1).txt') }),
    ).wrapper
    expect(renamed.find('.predicted-name').text()).toContain('a(1).txt')

    const plain = mountUploader(stateRefs()).wrapper
    expect(plain.find('.predicted-name').exists()).toBe(false)
  })

  it('上传中才展示进度区', () => {
    expect(mountUploader(stateRefs()).wrapper.find('.upload-progress').exists()).toBe(false)
    expect(
      mountUploader(stateRefs({ uploading: ref(true) })).wrapper.find('.upload-progress').exists(),
    ).toBe(true)
  })
})

describe('FileUploader: 与父组件的契约', () => {
  it('composable 回调 onSuccess ⇒ 组件发出 success 事件', () => {
    const { wrapper } = mountUploader()

    lastOptions().onSuccess()

    expect(wrapper.emitted('success')).toHaveLength(1)
  })

  it('getSiblingEntries 透传给 composable；未传时降级为空数组而不是 undefined', () => {
    const getSiblingEntries = vi.fn(() => [{ id: 1 }])
    mountUploader(stateRefs(), { getSiblingEntries })
    expect(lastOptions().getSiblingEntries()).toEqual([{ id: 1 }])

    mountUploader(stateRefs())
    expect(lastOptions().getSiblingEntries()).toEqual([])
  })

  it('通过 defineExpose 把 openFileUpload 暴露给父组件', () => {
    const { wrapper, state } = mountUploader()

    expect(wrapper.vm.openFileUpload).toBe(state.openFileUpload)
  })
})
