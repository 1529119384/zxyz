/**
 * `EmailRecordPanel` 渲染层回归测试 —— 为 2026-09-21 的一次**生产事故**补的钉子。
 *
 * 事故经过：P2-4 把 email-service 的 `EmailRecordPageVO{records}` 统一成 zxyz-common 的
 * `PageResult{page,pageSize,total,list}`（列表字段自此只有 `list`），但**消费端漏改了一处** ——
 * 这个面板当时读的是 `data.records`。`Array.isArray(undefined)` 恒为 false，
 * 于是列表静默变成空表：不抛错、不告警、CI 全绿。
 *
 * 为什么 `models/emailRecord.spec.js` 挡不住：那份单测只证明 `mapEmailRecordPage`
 * 自己两条分支都对，**证明不了面板真的调用了它**。消费端漏改时它照样全绿。
 * 这一份补的就是「消费者到底有没有接上」这一段。
 *
 * 因此这里断言的都是**输出侧**事实：表格实际收到的行、列里实际渲染出的文字、
 * 分页器实际拿到的 total / pageSize，以及请求实际发出的参数。
 *
 * ⚠️ 不能 `vi.mock('element-plus', ...)` 整模块替换：`unplugin-vue-components`
 * 会把模板里的 `el-*` 编译成 element-plus 的具名导入，整模块替换会让组件在 import
 * 阶段就炸。改为 `global.stubs` 逐个桩。
 * ⚠️ `v-loading` 是**指令**不是组件，桩不了；这里显式给一份空指令。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { provide, reactive, watch } from 'vue'

import { fetchEmailRecordDetail, fetchEmailRecords } from '@/api/emailAdmin'
import EmailRecordPanel from '@/views/setting/components/EmailRecordPanel.vue'

vi.mock('@/api/emailAdmin', () => ({
  fetchEmailRecords: vi.fn(),
  fetchEmailRecordDetail: vi.fn(),
}))
vi.mock('dompurify', () => ({ default: { sanitize: (html) => html } }))

const TABLE_ROWS = Symbol('test:tableRows')

/**
 * el-table / el-table-column 桩：表把 `data` 经 provide 递下去，列再按行渲染作用域插槽。
 * 事故的表征正是「表格里一行都没有」，所以断言必须能落到「第 N 行渲染出了什么」——
 * 只看 `props('data')` 会漏掉「插槽根本没渲染」那一类。
 *
 * ⚠️ 递下去的必须是**响应式容器**，不能是 `props.data` 的快照（`computed` 也不行：
 * options API 的 `inject` 拿到的是已解包的值）。本面板的数据由
 * `usePagedList({ immediate: true })` 异步取回，且是**整体替换** `list.value`
 * （不是原地 push）—— 挂载那一刻 `data` 还是 `[]`，谁在 setup 期间把这个值存下来，
 * 谁就永远只看到空表。（`RoleManagementPanel.spec.js` 的同类桩没踩到这个坑，
 * 因为那边的 `roles` 是同步 props 传进来的，mount 时就已就位。）
 */
const ElTableStub = {
  name: 'ElTable',
  props: {
    data: { type: Array, default: () => [] },
    height: { type: [String, Number], default: '' },
  },
  setup(props) {
    const table = reactive({ rows: [] })
    watch(
      () => props.data,
      (value) => {
        table.rows = Array.isArray(value) ? value : []
      },
      { immediate: true },
    )
    provide(TABLE_ROWS, table)
    return {}
  },
  template: `<div class="stub-table">
      <div v-if="data.length" class="stub-table__rows">
        <div v-for="(row, index) in data" :key="index" class="stub-table__row" />
      </div>
      <slot />
    </div>`,
}

const ElTableColumnStub = {
  name: 'ElTableColumn',
  props: ['label', 'prop', 'width', 'minWidth', 'showOverflowTooltip', 'fixed'],
  inject: { table: { from: TABLE_ROWS, default: null } },
  computed: {
    rows() {
      return this.table ? this.table.rows : []
    },
  },
  template: `<div class="stub-col" :data-label="label">
      <div v-for="(row, index) in rows" :key="index" class="stub-col__row">
        <slot :row="row" />
      </div>
    </div>`,
}

const ElPaginationStub = {
  name: 'ElPagination',
  props: ['total', 'currentPage', 'pageSize', 'pageSizes', 'layout', 'background'],
  emits: ['current-change', 'size-change'],
  template: '<div class="stub-pagination" />',
}

const ElButtonStub = {
  name: 'ElButton',
  props: ['loading', 'disabled', 'type', 'icon', 'size', 'link'],
  template: '<button type="button" :disabled="disabled"><slot /></button>',
}

const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'disabled', 'placeholder', 'clearable', 'type'],
  emits: ['update:modelValue'],
  template: `<input
      class="stub-input"
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)" />`,
}

const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue', 'title', 'width'],
  emits: ['update:modelValue'],
  template: '<div v-if="modelValue" class="stub-dialog"><slot /><slot name="footer" /></div>',
}

const globalOptions = {
  stubs: {
    'el-table': ElTableStub,
    'el-table-column': ElTableColumnStub,
    'el-pagination': ElPaginationStub,
    'el-button': ElButtonStub,
    'el-input': ElInputStub,
    'el-select': {
      name: 'ElSelect',
      props: ['modelValue', 'clearable', 'placeholder'],
      emits: ['update:modelValue'],
      template: '<div class="stub-select"><slot /></div>',
    },
    'el-option': {
      name: 'ElOption',
      props: ['value', 'label'],
      template: '<div class="stub-option" />',
    },
    'el-form': { name: 'ElForm', template: '<form class="stub-form"><slot /></form>' },
    'el-form-item': {
      name: 'ElFormItem',
      props: ['label'],
      template: '<div class="stub-form-item"><slot /></div>',
    },
    'el-tag': {
      name: 'ElTag',
      props: ['type'],
      template: '<span class="stub-tag"><slot /></span>',
    },
    'el-dialog': ElDialogStub,
    'el-descriptions': {
      name: 'ElDescriptions',
      props: ['column', 'border'],
      template: '<div class="stub-descriptions"><slot /></div>',
    },
    'el-descriptions-item': {
      name: 'ElDescriptionsItem',
      props: ['label', 'span'],
      template: '<div class="stub-descriptions-item" :data-label="label"><slot /></div>',
    },
  },
  // v-loading 是指令：不显式提供会被 Vue 记为 unresolved directive。
  directives: { loading: {} },
}

const LIST_ROW = {
  id: 11,
  recipient: 'a@example.com',
  subject: '主题甲',
  status: 'SENT',
  businessType: 'SYSTEM_MESSAGE',
  attemptCount: 1,
  maxAttempts: 3,
}

const RECORDS_ROW = { id: 22, recipient: 'b@example.com', subject: '主题乙', status: 'FAILED' }

/** 后端统一信封：响应拦截器原样返回整个 payload（含 code/msg/data），调用方取 `response.data`。 */
const ok = (data) => ({ code: 1, msg: 'ok', data })

function mountPanel() {
  return mount(EmailRecordPanel, { global: globalOptions })
}

function table(wrapper) {
  return wrapper.findComponent({ name: 'ElTable' })
}

function pagination(wrapper) {
  return wrapper.findComponent({ name: 'ElPagination' })
}

function dialog(wrapper) {
  return wrapper.findComponent({ name: 'ElDialog' })
}

function column(wrapper, label) {
  const found = wrapper.findAll('.stub-col').find((c) => c.attributes('data-label') === label)
  if (!found) {
    throw new Error(
      `没有「${label}」列，实际为：${JSON.stringify(
        wrapper.findAll('.stub-col').map((c) => c.attributes('data-label')),
      )}`,
    )
  }
  return found
}

function buttonByText(wrapper, text) {
  const buttons = wrapper.findAll('button')
  const found = buttons.find((b) => b.text() === text)
  if (!found) {
    throw new Error(
      `没有文案为「${text}」的按钮，实际为：${JSON.stringify(buttons.map((b) => b.text()))}`,
    )
  }
  return found
}

beforeEach(() => {
  vi.clearAllMocks()
  fetchEmailRecords.mockResolvedValue(
    ok({ page: 1, pageSize: 10, total: 2, list: [LIST_ROW, RECORDS_ROW] }),
  )
  fetchEmailRecordDetail.mockResolvedValue(ok({ ...LIST_ROW, contentHtml: '<p>正文</p>' }))
})

describe('EmailRecordPanel: 分页信封（P2-4 事故回归）', () => {
  it('后端只回 list、没有 records 时，表格必须照常有行（这就是当时坏掉的那条）', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(table(wrapper).props('data')).toHaveLength(2)

    // 桩自检：作用域插槽确实按行渲染了。少了这条，「渲染出内容」的断言可能对着空 DOM 静默通过。
    expect(column(wrapper, '状态').findAll('.stub-col__row')).toHaveLength(2)
    expect(
      column(wrapper, '状态')
        .findAll('.stub-tag')
        .map((tag) => tag.text()),
    ).toEqual(['已发送', '发送失败'])
    expect(
      column(wrapper, '业务类型')
        .findAll('.stub-col__row')
        .map((row) => row.text()),
    ).toEqual(['SYSTEM_MESSAGE', '-'])
  })

  it('回落分支：P2-4 之前的 records 信封仍能渲染（跨版本兼容，别被顺手删掉）', async () => {
    fetchEmailRecords.mockResolvedValue(
      ok({ page: 1, pageSize: 10, total: 1, records: [RECORDS_ROW] }),
    )
    const wrapper = mountPanel()
    await flushPromises()

    expect(table(wrapper).props('data')).toHaveLength(1)
    expect(
      column(wrapper, '状态')
        .findAll('.stub-tag')
        .map((tag) => tag.text()),
    ).toEqual(['发送失败'])
  })

  it('信封畸形（list 不是数组、字段缺失）⇒ 空表、不抛错、分页 total 归零', async () => {
    fetchEmailRecords.mockResolvedValue(ok({ list: 'oops' }))
    const wrapper = mountPanel()
    await flushPromises()

    expect(table(wrapper).props('data')).toEqual([])
    expect(column(wrapper, '状态').findAll('.stub-col__row')).toEqual([])
    expect(pagination(wrapper).props('total')).toBe(0)
  })

  it('分页器拿到后端 total 与生效页长，且请求按 emailRecord 上下文发出 pageSize=10', async () => {
    fetchEmailRecords.mockResolvedValue(ok({ page: 1, pageSize: 10, total: 37, list: [LIST_ROW] }))
    const wrapper = mountPanel()
    await flushPromises()

    expect(pagination(wrapper).props('total')).toBe(37)
    expect(pagination(wrapper).props('pageSize')).toBe(10)
    expect(fetchEmailRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, pageSize: 10 }),
    )
  })
})

describe('EmailRecordPanel: 筛选与翻页', () => {
  it('点「查询」会带上筛选值并回到第 1 页（不能停在上一页的越界页码上）', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    pagination(wrapper).vm.$emit('current-change', 3)
    await flushPromises()
    expect(fetchEmailRecords).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3 }))

    const inputs = wrapper.findAll('.stub-input')
    await inputs[0].setValue('c@example.com')
    await buttonByText(wrapper, '查询').trigger('click')
    await flushPromises()

    expect(fetchEmailRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, recipient: 'c@example.com' }),
    )
  })

  it('换页长后回到第 1 页', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    pagination(wrapper).vm.$emit('current-change', 4)
    await flushPromises()
    pagination(wrapper).vm.$emit('size-change', 50)
    await flushPromises()

    expect(fetchEmailRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, pageSize: 50 }),
    )
  })
})

describe('EmailRecordPanel: 详情', () => {
  it('点「详情」会按 id 拉取并打开弹窗，弹窗里渲染出该条记录', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const detail = column(wrapper, '操作').findAll('.stub-col__row')[0].find('button')
    await detail.trigger('click')
    await flushPromises()

    expect(fetchEmailRecordDetail).toHaveBeenCalledWith(LIST_ROW.id)
    expect(dialog(wrapper).props('modelValue')).toBe(true)
    expect(wrapper.find('.stub-descriptions-item[data-label="收件人"]').text()).toContain(
      'a@example.com',
    )
  })
})
