/**
 * `RoleManagementPanel` 渲染层测试 —— `ISSUE/24` §六 G-6(b) 点名的三条关键路径之一（权限面板）。
 *
 * 这个面板的实质是**把"权限"渲染成界面**，所以它出错的方式全是权限语义事故：
 * 只读用户看到了数据、无查看权限的人拿到了角色清单、内置角色被删掉。
 * 这些都不是类型问题，也不会让构建失败 —— 只能靠断言钉住。
 *
 * ## 为什么要自己搭 el-table 的桩
 * 面板的绝大部分判定发生在 `el-table-column` 的**作用域插槽**里（类型标签、权限标签折叠、
 * 编辑/删除按钮的禁用）。把 `el-table` 桩成不渲染插槽，这些分支就全都测不到。
 * 因此这里让 `el-table` 桩把 `data` **provide** 下去，`el-table-column` 桩再
 * inject 回来、按行渲染自己的作用域插槽 —— 断言就能落到"第 N 行第 M 列"的真实粒度。
 * （表的 `data` 只随 props 变化、测试中不中途改，故 provide 值无需做成响应式。
 *   第一条用例断言了「内置/自定义」标签确实渲染出来，任何桩失效都会在此炸掉而不是静默通过。）
 *
 * ⚠️ 不能 `vi.mock('element-plus', ...)` 整模块替换（理由同 InputDialog.spec.js）：
 * 模板里的 `el-*` 由 `unplugin-vue-components` 编译成 `element-plus` 的具名导入。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'

import RoleManagementPanel from '@/components/RoleManagementPanel.vue'

vi.spyOn(ElMessage, 'warning').mockImplementation(() => {})

const TABLE_ROWS = Symbol('test:tableRows')

/**
 * 把 `data` provide 给列桩；列桩再按行渲染作用域插槽。
 * `this.data` 在 provide 阶段已由 props 解析得到，测试中不再变更。
 */
const ElTableStub = {
  name: 'ElTable',
  props: {
    data: { type: Array, default: () => [] },
    rowKey: { type: String, default: '' },
    border: { type: Boolean, default: false },
    emptyText: { type: String, default: '' },
  },
  provide() {
    return { [TABLE_ROWS]: this.data }
  },
  template: `<div class="stub-table">
      <div v-if="data.length" class="stub-table__rows">
        <div v-for="(row, index) in data" :key="index" class="stub-table__row" />
      </div>
      <div v-else class="stub-table__empty">{{ emptyText }}</div>
      <slot />
    </div>`,
}

const ElTableColumnStub = {
  name: 'ElTableColumn',
  props: ['label', 'prop', 'width', 'minWidth', 'showOverflowTooltip', 'fixed'],
  inject: { rows: { from: TABLE_ROWS, default: () => [] } },
  template: `<div class="stub-col" :data-label="label">
      <div v-for="(row, index) in rows" :key="index" class="stub-col__row">
        <slot :row="row" />
      </div>
    </div>`,
}

const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue', 'title', 'width'],
  emits: ['update:modelValue', 'closed'],
  // modelValue 转 false 时补发 closed，覆盖组件的 @closed="resetForm"
  watch: {
    modelValue(value) {
      if (!value) this.$emit('closed')
    },
  },
  template: '<div v-if="modelValue" class="stub-dialog"><slot /><slot name="footer" /></div>',
}

const ElButtonStub = {
  name: 'ElButton',
  props: ['loading', 'disabled', 'type', 'icon', 'link', 'size'],
  template: '<button type="button" :disabled="disabled"><slot /></button>',
}

const ElInputStub = {
  name: 'ElInput',
  props: ['modelValue', 'disabled', 'placeholder', 'type', 'rows'],
  emits: ['update:modelValue'],
  template:
    '<input class="stub-input" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

const globalStubs = {
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-dialog': ElDialogStub,
  'el-button': ElButtonStub,
  'el-input': ElInputStub,
  'el-form': { name: 'ElForm', template: '<form class="stub-form"><slot /></form>' },
  'el-form-item': { name: 'ElFormItem', template: '<div class="stub-form-item"><slot /></div>' },
  // ⚠️ 必须带 setCheckedKeys：组件在 nextTick 里通过模板 ref 调它同步勾选态，
  // 桩上缺这个方法会抛 TypeError 并被 vitest 记为 Unhandled Rejection（不只是断言失败）。
  'el-tree': {
    name: 'ElTree',
    props: ['data', 'showCheckbox', 'nodeKey', 'defaultExpandAll', 'props', 'defaultCheckedKeys'],
    emits: ['check'],
    methods: { setCheckedKeys() {} },
    template: '<div class="stub-tree" />',
  },
  'el-empty': { name: 'ElEmpty', props: ['description'], template: '<div class="stub-empty" />' },
  'el-alert': { name: 'ElAlert', props: ['title', 'type'], template: '<div class="stub-alert" />' },
  'el-tag': { name: 'ElTag', props: ['type', 'size', 'effect'], template: '<span class="stub-tag"><slot /></span>' },
}

// 07-P1-7：面板把「文案」聚合成 `labels`、把「行为」聚合成 `actions`。
// 本函数**如实构造这两个对象**，只是让每条用例仍按「键」书写而不必写三层字面量。
// 映射集中在这一处，且每条用例断言的是**渲染出来的文字 / 回调收到的参数**，
// 所以这里若漏搬某个键，断言会立刻红（不会静默通过）。
const PANEL_LABEL_KEYS = [
  'title',
  'subtitle',
  'roleTypeLabel',
  'createLabel',
  'noAccessText',
  'readonlyText',
  'emptyText',
]
const PANEL_ACTION_KEYS = ['isBuiltinRole', 'formatPermission', 'saveRole', 'deleteRole']

function mountPanel(options = {}) {
  const labels = {}
  const actions = {}
  const rest = {}
  for (const [key, value] of Object.entries(options)) {
    if (PANEL_LABEL_KEYS.includes(key)) labels[key] = value
    else if (PANEL_ACTION_KEYS.includes(key)) actions[key] = value
    else rest[key] = value
  }

  return mount(RoleManagementPanel, {
    props: {
      labels: { title: '角色权限', ...labels },
      actions: {
        saveRole: vi.fn().mockResolvedValue(undefined),
        deleteRole: vi.fn().mockResolvedValue(undefined),
        ...actions,
      },
      ...rest,
    },
    global: { stubs: globalStubs },
  })
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

function table(wrapper) {
  return wrapper.findComponent({ name: 'ElTable' })
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

function columnRows(wrapper, label) {
  return column(wrapper, label).findAll('.stub-col__row')
}

/** 第 index 行「操作」列里的某个按钮。 */
function rowButton(wrapper, index, text) {
  const row = columnRows(wrapper, '操作')[index]
  const found = row.findAll('button').find((button) => button.text() === text)
  if (!found) {
    throw new Error(`第 ${index} 行没有「${text}」按钮`)
  }
  return found
}

const SIX_CODES = [
  'file:read',
  'file:write',
  'file:delete',
  'file:upload',
  'file:share',
  'file:move',
]

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RoleManagementPanel: 头部与权限提示', () => {
  it('渲染标题、副标题与可定制的创建按钮文案', () => {
    const wrapper = mountPanel({ title: '角色权限', subtitle: '说明文字', createLabel: '新增权限组' })

    expect(wrapper.find('.role-panel-header h3').text()).toBe('角色权限')
    expect(wrapper.find('.role-panel-header p').text()).toBe('说明文字')
    expect(buttonByText(wrapper, '新增权限组')).toBeTruthy()
  })

  it('无查看权限时给出 noAccessText，而不是 readonlyText', () => {
    const wrapper = mountPanel({
      canRead: false,
      noAccessText: '你没有查看角色的权限',
      readonlyText: '当前为只读',
    })

    const tips = wrapper.findAll('.permission-tip')
    expect(tips).toHaveLength(1)
    expect(tips[0].text()).toBe('你没有查看角色的权限')
  })

  it('有查看权限但不可管理时给出 readonlyText', () => {
    const wrapper = mountPanel({
      canRead: true,
      canManage: false,
      noAccessText: '你没有查看角色的权限',
      readonlyText: '当前为只读',
    })

    const tips = wrapper.findAll('.permission-tip')
    expect(tips).toHaveLength(1)
    expect(tips[0].text()).toBe('当前为只读')
  })

  it('既无 noAccessText 也无 readonlyText 时不渲染提示（不出现空提示条）', () => {
    const wrapper = mountPanel({ canRead: true, canManage: false })

    expect(wrapper.findAll('.permission-tip')).toHaveLength(0)
  })
})

describe('RoleManagementPanel: 表格数据门控', () => {
  const ROLES = [
    { id: 1, roleName: '超管', roleCode: 'ADMIN' },
    { id: 2, roleName: '审计', roleCode: 'AUDIT' },
  ]

  it('canRead=true 时把角色交给表格，空态用 emptyText', () => {
    const wrapper = mountPanel({ roles: ROLES })

    expect(table(wrapper).props('data')).toHaveLength(2)
    expect(table(wrapper).props('emptyText')).toBe('暂无角色')
  })

  it('canRead=false 时表格数据为空，且空态文案换成「无查看权限」（一个字都不泄漏）', () => {
    const wrapper = mountPanel({ roles: ROLES, canRead: false })

    expect(table(wrapper).props('data')).toEqual([])
    expect(table(wrapper).props('emptyText')).toBe('无查看权限')
  })

  it('roles 传 null / 非数组时降级为空数组且不抛错', () => {
    expect(table(mountPanel({ roles: null })).props('data')).toEqual([])
    expect(table(mountPanel({ roles: 'oops' })).props('data')).toEqual([])
  })
})

describe('RoleManagementPanel: 角色行渲染', () => {
  it('内置角色显示「内置」且删除按钮禁用；自定义角色显示「自定义」且可删', async () => {
    const deleteRole = vi.fn().mockResolvedValue(undefined)
    const roles = [
      { id: 1, roleName: '超管', roleCode: 'ADMIN', builtin: true, permissionCodes: [] },
      { id: 2, roleName: '审计', roleCode: 'AUDIT', permissionCodes: [] },
    ]
    const wrapper = mountPanel({
      roles,
      canManage: true,
      deleteRole,
      isBuiltinRole: (row) => Boolean(row.builtin),
    })

    // 桩自检：作用域插槽确实按行渲染了
    expect(columnRows(wrapper, '类型')).toHaveLength(2)
    expect(column(wrapper, '类型').findAll('.stub-tag').map((tag) => tag.text())).toEqual([
      '内置',
      '自定义',
    ])

    expect(rowButton(wrapper, 0, '删除').attributes('disabled')).toBeDefined()
    expect(rowButton(wrapper, 1, '删除').attributes('disabled')).toBeUndefined()

    // 即便绕过 UI 直接点，前端守卫也不允许删内置角色
    await rowButton(wrapper, 0, '删除').trigger('click')
    expect(deleteRole).not.toHaveBeenCalled()

    await rowButton(wrapper, 1, '删除').trigger('click')
    expect(deleteRole).toHaveBeenCalledWith(roles[1])
  })

  it('不可管理时删除与编辑按钮都禁用，点删除不触发删除（UI 与守卫双层）', async () => {
    const deleteRole = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountPanel({
      roles: [{ id: 1, roleName: '审计', roleCode: 'AUDIT', permissionCodes: [] }],
      canManage: false,
      deleteRole,
    })

    expect(rowButton(wrapper, 0, '删除').attributes('disabled')).toBeDefined()
    expect(rowButton(wrapper, 0, '编辑').attributes('disabled')).toBeDefined()

    await rowButton(wrapper, 0, '删除').trigger('click')
    expect(deleteRole).not.toHaveBeenCalled()
  })

  it('权限标签默认折叠到 4 个并给出「+N 更多」，点开后全部展开、按钮变「收起」', async () => {
    const roles = [{ id: 1, roleName: 'A', roleCode: 'A', permissionCodes: SIX_CODES }]
    const wrapper = mountPanel({
      roles,
      canManage: true,
      formatPermission: (code) => code.split(':')[1],
    })
    const permissionColumn = column(wrapper, '权限')

    expect(permissionColumn.findAll('.stub-tag').map((tag) => tag.text())).toEqual([
      'read',
      'write',
      'delete',
      'upload',
    ])
    const more = permissionColumn.findAll('button').find((b) => b.text().includes('更多'))
    expect(more.text()).toBe('+2 更多')

    await more.trigger('click')

    expect(permissionColumn.findAll('.stub-tag')).toHaveLength(6)
    expect(permissionColumn.findAll('button').map((b) => b.text())).toEqual(['收起'])

    // 再点回去
    await permissionColumn.findAll('button')[0].trigger('click')
    expect(permissionColumn.findAll('.stub-tag')).toHaveLength(4)
  })

  it('标签页 4 个时（等于上限）不出现「更多」按钮', () => {
    const wrapper = mountPanel({
      roles: [{ id: 1, roleName: 'A', roleCode: 'A', permissionCodes: SIX_CODES.slice(0, 4) }],
      canManage: true,
    })

    expect(column(wrapper, '权限').findAll('.stub-tag')).toHaveLength(4)
    expect(column(wrapper, '权限').findAll('button')).toHaveLength(0)
  })

  it('展开状态按角色隔离：展开第 1 行不影响第 2 行', async () => {
    const roles = [
      { id: 1, roleName: 'A', roleCode: 'A', permissionCodes: SIX_CODES },
      { id: 2, roleName: 'B', roleCode: 'B', permissionCodes: SIX_CODES },
    ]
    const wrapper = mountPanel({ roles, canManage: true })
    const rows = columnRows(wrapper, '权限')

    await rows[0].findAll('button')[0].trigger('click')

    expect(columnRows(wrapper, '权限')[0].findAll('.stub-tag')).toHaveLength(6)
    expect(columnRows(wrapper, '权限')[1].findAll('.stub-tag')).toHaveLength(4)
  })

  it('未配置权限 / 权限字段非法时显示「未配置权限」而不是崩掉', () => {
    const wrapper = mountPanel({
      roles: [
        { id: 1, roleName: 'A', roleCode: 'A' },
        { id: 2, roleName: 'B', roleCode: 'B', permissionCodes: 'oops' },
      ],
      canManage: true,
    })

    const rows = columnRows(wrapper, '权限')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('.empty-inline').text()).toBe('未配置权限')
    expect(rows[1].find('.empty-inline').text()).toBe('未配置权限')
    expect(column(wrapper, '权限').findAll('.stub-tag')).toHaveLength(0)
  })
})

describe('RoleManagementPanel: 新增弹窗', () => {
  it('不可管理时创建按钮禁用且点击不开弹窗（守卫在 JS 层，不只在 UI 层）', async () => {
    const wrapper = mountPanel({ canManage: false })

    const create = buttonByText(wrapper, '新增角色')
    expect(create.attributes('disabled')).toBeDefined()

    await create.trigger('click')
    expect(dialog(wrapper).props('modelValue')).toBe(false)
  })

  it('可管理时点击打开弹窗，标题为「新增+roleTypeLabel」', async () => {
    const wrapper = mountPanel({ canManage: true, roleTypeLabel: '角色' })

    await buttonByText(wrapper, '新增角色').trigger('click')

    expect(dialog(wrapper).props('modelValue')).toBe(true)
    expect(dialog(wrapper).props('title')).toBe('新增角色')
  })

  it('新增态下角色编码位置是提示而不是输入框（编码由后端生成）', async () => {
    const wrapper = mountPanel({ canManage: true, permissions: [] })

    await buttonByText(wrapper, '新增角色').trigger('click')

    // 只有角色名称 + 描述两个输入框
    expect(wrapper.findAll('.stub-input')).toHaveLength(2)
    expect(wrapper.find('.stub-alert').exists()).toBe(true)
    expect(wrapper.find('.tree-toolbar').text()).toContain('已选 0 / 0')
    // 无权限可选时不渲染树
    expect(wrapper.find('.stub-tree').exists()).toBe(false)
  })

  it('有可配置权限时渲染树，并统计候选总数', async () => {
    const wrapper = mountPanel({
      canManage: true,
      permissions: [
        { permissionCode: 'file:read', permissionName: '读' },
        { permissionCode: 'file:write', permissionName: '写' },
      ],
    })

    await buttonByText(wrapper, '新增角色').trigger('click')

    expect(wrapper.find('.tree-toolbar').text()).toContain('已选 0 / 2')
    expect(wrapper.find('.stub-tree').exists()).toBe(true)
  })

  it('permissions 传 null 时降级为 0 且不抛错', async () => {
    const wrapper = mountPanel({ canManage: true, permissions: null })

    await buttonByText(wrapper, '新增角色').trigger('click')

    expect(wrapper.find('.tree-toolbar').text()).toContain('已选 0 / 0')
  })

  it('取消按钮关闭弹窗', async () => {
    const wrapper = mountPanel({ canManage: true })

    await buttonByText(wrapper, '新增角色').trigger('click')
    await buttonByText(wrapper, '取消').trigger('click')

    expect(dialog(wrapper).props('modelValue')).toBe(false)
  })

  it('关闭后再打开时表单被重置（不留上次输入）', async () => {
    const wrapper = mountPanel({ canManage: true })

    await buttonByText(wrapper, '新增角色').trigger('click')
    await wrapper.find('.stub-input').setValue('临时角色')
    expect(wrapper.find('.stub-input').element.value).toBe('临时角色')

    await buttonByText(wrapper, '取消').trigger('click')
    await buttonByText(wrapper, '新增角色').trigger('click')

    expect(wrapper.find('.stub-input').element.value).toBe('')
  })
})

describe('RoleManagementPanel: 保存契约', () => {
  async function openCreateAndSubmit(wrapper, roleName) {
    await buttonByText(wrapper, '新增角色').trigger('click')
    await wrapper.find('.stub-input').setValue(roleName)
    await buttonByText(wrapper, '保存').trigger('click')
  }

  it('角色名称为空 ⇒ 只提示不落库', async () => {
    const saveRole = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountPanel({ canManage: true, saveRole })

    await openCreateAndSubmit(wrapper, '')

    expect(ElMessage.warning).toHaveBeenCalledWith('请输入角色名称')
    expect(saveRole).not.toHaveBeenCalled()
    expect(dialog(wrapper).props('modelValue')).toBe(true)
  })

  it('纯空白也算空 ⇒ 同样拦下', async () => {
    const saveRole = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountPanel({ canManage: true, saveRole })

    await openCreateAndSubmit(wrapper, '   ')

    expect(ElMessage.warning).toHaveBeenCalledWith('请输入角色名称')
    expect(saveRole).not.toHaveBeenCalled()
  })

  it('保存成功（返回非 false）⇒ 提交 trim 后的净载荷并关闭弹窗', async () => {
    const saveRole = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountPanel({ canManage: true, saveRole })

    await openCreateAndSubmit(wrapper, '  审计员  ')

    expect(saveRole).toHaveBeenCalledWith({
      roleId: null,
      roleName: '审计员',
      roleCode: '',
      description: '',
      permissionCodes: [],
    })
    expect(dialog(wrapper).props('modelValue')).toBe(false)
  })

  it('保存被调用方判为失败（返回 false）⇒ 弹窗保持打开，输入不丢', async () => {
    const saveRole = vi.fn().mockResolvedValue(false)
    const wrapper = mountPanel({ canManage: true, saveRole })

    await openCreateAndSubmit(wrapper, '审计员')

    expect(saveRole).toHaveBeenCalledTimes(1)
    expect(dialog(wrapper).props('modelValue')).toBe(true)
    expect(wrapper.find('.stub-input').element.value).toBe('审计员')
  })

  it('保存过程中重复点击只提交一次（saving 自锁）', async () => {
    let release
    const saveRole = vi.fn(
      () =>
        new Promise((resolve) => {
          release = resolve
        }),
    )
    const wrapper = mountPanel({ canManage: true, saveRole })

    await buttonByText(wrapper, '新增角色').trigger('click')
    await wrapper.find('.stub-input').setValue('审计员')

    const save = buttonByText(wrapper, '保存')
    await save.trigger('click')
    await save.trigger('click')

    expect(saveRole).toHaveBeenCalledTimes(1)

    release(undefined)
    await Promise.resolve()
  })
})

describe('RoleManagementPanel: 编辑契约', () => {
  it('点编辑打开弹窗、标题为「编辑…」、表单预填原值、保存回传 roleId', async () => {
    const saveRole = vi.fn().mockResolvedValue(undefined)
    const roles = [
      {
        id: 9,
        roleName: '审计',
        roleCode: 'AUDIT',
        description: '只读审计',
        permissionCodes: ['file:read'],
      },
    ]
    const wrapper = mountPanel({ roles, canManage: true, saveRole })

    await rowButton(wrapper, 0, '编辑').trigger('click')

    expect(dialog(wrapper).props('title')).toBe('编辑角色')
    // 编辑态多一个（禁用的）角色编码输入框
    expect(wrapper.findAll('.stub-input')).toHaveLength(3)
    expect(wrapper.findAll('.stub-input')[0].element.value).toBe('审计')
    expect(wrapper.findAll('.stub-input')[1].element.value).toBe('AUDIT')
    expect(wrapper.find('.stub-alert').exists()).toBe(false)
    expect(wrapper.find('.tree-toolbar').text()).toContain('已选 1 / 0')

    await buttonByText(wrapper, '保存').trigger('click')

    expect(saveRole).toHaveBeenCalledWith({
      roleId: 9,
      roleName: '审计',
      roleCode: 'AUDIT',
      description: '只读审计',
      permissionCodes: ['file:read'],
    })
    expect(dialog(wrapper).props('modelValue')).toBe(false)
  })

  it('编辑态取消后回到新增态（不残留 roleId）', async () => {
    const roles = [{ id: 9, roleName: '审计', roleCode: 'AUDIT', permissionCodes: [] }]
    const wrapper = mountPanel({ roles, canManage: true })

    await rowButton(wrapper, 0, '编辑').trigger('click')
    await buttonByText(wrapper, '取消').trigger('click')
    await buttonByText(wrapper, '新增角色').trigger('click')

    expect(dialog(wrapper).props('title')).toBe('新增角色')
    expect(wrapper.find('.stub-input').element.value).toBe('')
  })
})
