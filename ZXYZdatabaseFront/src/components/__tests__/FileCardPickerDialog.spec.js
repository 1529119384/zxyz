import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'

// J-7（2026-10-03）：loadItems 此前只有 try/finally 无 catch —— fetchFileList 失败时
// loading 结束、列表空白、无任何提示（未处理 Promise rejection），表现为「功能坏了」；
// 且双击进目录 A 再快速回退/进 B 无过期防护，慢响应会把 A 的列表挂到 B 的路径下。
// 只 spy ElMessage，el-* 组件一律 stub（与 InputDialog.spec.js 同一做法）。
vi.mock('@/api/files', () => ({
  fetchFileList: vi.fn(),
}))

import FileCardPickerDialog from '@/components/FileCardPickerDialog.vue'
import { fetchFileList } from '@/api/files'

const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<div class="stub-dialog"><slot /><slot name="footer" /></div>',
}

const ElButtonStub = {
  name: 'ElButton',
  props: ['text', 'disabled', 'type'],
  template: '<button type="button" :disabled="disabled"><slot /></button>',
}

const ElTableStub = {
  name: 'ElTable',
  props: ['data', 'rowKey'],
  emits: ['selection-change', 'row-dblclick'],
  // 列 slot 由 el-table-column 的真实默认插槽渲染会拿到 undefined row，
  // 这里直接以文本展示 data，不透传具名插槽（本 spec 不验证表格内部渲染）。
  template: '<div class="stub-table">{{ (data || []).map(i => i.fileName).join(",") }}</div>',
}

const globalStubs = {
  'el-dialog': ElDialogStub,
  'el-button': ElButtonStub,
  'el-table': ElTableStub,
  'el-table-column': { name: 'ElTableColumn', template: '<div><slot /></div>' },
}

function mountDialog() {
  return mount(FileCardPickerDialog, {
    props: { visible: true, teamId: 7 },
    global: { stubs: globalStubs },
  })
}

describe('FileCardPickerDialog 加载失败与竞态防护', () => {
  let messageErrorSpy

  beforeEach(() => {
    vi.clearAllMocks()
    messageErrorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
  })

  it('应在加载失败时提示错误而不是静默空白（J-7）', async () => {
    fetchFileList.mockRejectedValue(new Error('网络异常'))

    mountDialog()
    await vi.waitFor(() => {
      // 与 handleBusinessError 同一约定：业务错误消息优先透传，无消息时才落 fallback
      expect(messageErrorSpy).toHaveBeenCalledWith('网络异常')
    })
  })

  it('应丢弃过期响应：先发起的慢响应不得覆盖后进入目录的列表（J-7）', async () => {
    let resolveRoot
    fetchFileList.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveRoot = () => resolve({ data: [{ id: 1, fileName: '根目录项', type: 1 }] })
        }),
    )

    const wrapper = mountDialog()
    // 等到根目录请求真正挂起（deferred 已创建）
    await Promise.resolve()

    // 模拟「回退后再次加载」：第二次请求立即返回目录 B 的列表
    fetchFileList.mockResolvedValueOnce({
      data: [{ id: 2, fileName: '目录B项', type: 1 }],
    })
    // 第二次加载经由 watcher（visible 重开）无法直接触发，改为直接驱动第二次 loadItems：
    // 通过重新触发 visible watch —— 先关再开
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })

    // 第二次加载已完成，列表是目录 B
    await vi.waitFor(() => {
      expect(wrapper.vm.items.map((i) => i.id)).toEqual([2])
    })

    // 此时根目录的慢响应才回来：不得覆盖目录 B 的列表
    resolveRoot()
    await Promise.resolve()
    await Promise.resolve()
    expect(wrapper.vm.items.map((i) => i.id)).toEqual([2])
  })
})
