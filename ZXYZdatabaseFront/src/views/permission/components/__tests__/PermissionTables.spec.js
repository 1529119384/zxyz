/**
 * `PermissionAuditTable` / `PermissionDictionaryTable` 测试。
 *
 * 这两个组件承担着**权限不足时的显示语义**：`canRead=false` 必须同时做到
 * ① 表格数据强制置空 ② 空态文案换成「无查看权限」③ 面板提示出现。
 * 抽组件时最容易只做 ③ 而漏掉 ①②（那会让无权限的人在前端拿到数据），
 * 所以这里逐条钉住。
 */
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

import PermissionAuditTable from '@/views/permission/components/PermissionAuditTable.vue'
import PermissionDictionaryTable from '@/views/permission/components/PermissionDictionaryTable.vue'

const ElTableStub = {
  name: 'ElTable',
  props: ['data', 'height', 'emptyText'],
  template: '<div class="stub-table"><slot /></div>',
}

/** 固定喂一行假数据给默认插槽，以便验证 formatPermission 真的被调用。 */
const ElTableColumnStub = {
  name: 'ElTableColumn',
  props: ['label', 'prop', 'minWidth', 'showOverflowTooltip'],
  template:
    '<div class="stub-col" :data-label="label"><slot :row="{ permissionName: \'A\', permissionCode: \'c\' }" /></div>',
}

const globalStubs = {
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
}

const ROWS = [{ operationType: 'create', targetType: 'role' }]

describe('PermissionAuditTable', () => {
  function mountTable(props = {}) {
    return mount(PermissionAuditTable, {
      props: {
        title: '系统审计',
        subtitle: '记录系统权限中心的角色和授权变更。',
        tip: '你没有系统审计查看权限，当前分区不可用。',
        emptyText: '暂无系统审计',
        rows: ROWS,
        ...props,
      },
      global: { stubs: globalStubs },
    })
  }

  it('有权限时透传数据与「暂无」空态文案，且不显示提示', () => {
    const wrapper = mountTable({ canRead: true })
    const table = wrapper.findComponent(ElTableStub)

    expect(table.props('data')).toEqual(ROWS)
    expect(table.props('emptyText')).toBe('暂无系统审计')
    expect(wrapper.find('.permission-tip').exists()).toBe(false)
  })

  it('无权限时数据被强制置空、空态改为「无查看权限」、提示出现', () => {
    const wrapper = mountTable({ canRead: false })
    const table = wrapper.findComponent(ElTableStub)

    expect(table.props('data')).toEqual([])
    expect(table.props('emptyText')).toBe('无查看权限')
    expect(wrapper.find('.permission-tip').text()).toBe('你没有系统审计查看权限，当前分区不可用。')
  })

  it('rows 未传时默认空数组（父组件数据未就绪不报错）', () => {
    const wrapper = mountTable({ canRead: true, rows: undefined })
    expect(wrapper.findComponent(ElTableStub).props('data')).toEqual([])
  })

  it('面板标题来自 props，且固定横向占满（variant=wide）', () => {
    const wrapper = mountTable({ canRead: true, title: '团队审计' })
    expect(wrapper.find('h3').text()).toBe('团队审计')
    expect(wrapper.find('.panel--wide').exists()).toBe(true)
  })
})

describe('PermissionDictionaryTable', () => {
  function mountDict(props = {}) {
    return mount(PermissionDictionaryTable, {
      props: {
        title: '系统权限字典',
        permissions: [{ permissionName: '文件写入', permissionCode: 'file:write' }],
        emptyText: '暂无系统权限',
        formatPermission: (row) => `${row.permissionName} (${row.permissionCode})`,
        ...props,
      },
      global: { stubs: globalStubs },
    })
  }

  it('有权限时透传数据', () => {
    const wrapper = mountDict({ canRead: true })
    expect(wrapper.findComponent(ElTableStub).props('data')).toHaveLength(1)
    expect(wrapper.findComponent(ElTableStub).props('emptyText')).toBe('暂无系统权限')
  })

  it('无权限时数据置空且空态为「无查看权限」', () => {
    const wrapper = mountDict({ canRead: false })
    expect(wrapper.findComponent(ElTableStub).props('data')).toEqual([])
    expect(wrapper.findComponent(ElTableStub).props('emptyText')).toBe('无查看权限')
  })

  it('行渲染通过 formatPermission prop 完成（可替换，便于单测）', () => {
    const formatPermission = vi.fn((row) => `${row.permissionName} / ${row.permissionCode}`)
    const wrapper = mountDict({ canRead: true, formatPermission })

    expect(formatPermission).toHaveBeenCalledWith({ permissionName: 'A', permissionCode: 'c' })
    expect(wrapper.find('.stub-col').text()).toBe('A / c')
  })

  it('提权提示为 prop 注入，非空时渲染', () => {
    const wrapper = mountDict({ canRead: false, tip: '你没有团队权限中心查看权限，当前分区不可用。' })
    expect(wrapper.find('.permission-tip').text()).toContain('你没有团队权限中心查看权限')
  })
})
