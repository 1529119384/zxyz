/**
 * `FileContextMenu` 渲染层测试 —— `ISSUE/24` §六 G-6(b) 点名的三条关键路径之一（文件操作）。
 *
 * 选它的理由：这个组件**不画界面而已，它决定"谁能看到哪个动作"**。
 * `canWrite` / `mode` / `contextType` / 选中项类型（文件 / 文件夹 / 混合 / 虚拟项目条目）
 * 四个维度交叉出十来个分支，任何一处判错都会变成
 * 「只读用户看到了删除」或「回收站里出现了重命名」这类**权限语义事故**，
 * 而这类错误在编译期与类型检查里都不会响。
 *
 * 测试口径是**菜单项标签序列**（含分隔线）+ 关键项点击后发出的 `action` 码：
 * 前者钉住"该出现什么"，后者钉住"点了会干什么"。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

import { FILE_CONTEXT_ACTIONS } from '@/models/fileActions'
import { createProjectFolderEntry, createProjectRootEntry } from '@/utils/projectVirtualFolder'
import FileContextMenu from '@/components/FileContextMenu.vue'
import { logger } from '@/utils/logger'

vi.mock('@/utils/logger', () => ({
  logger: { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

// 菜单用 <Teleport to="body">；测试里 stub 掉 teleport 让内容留在 wrapper 内，断言更直接。
const globalStubs = {
  teleport: true,
  'el-divider': { name: 'ElDivider', template: '<hr class="stub-divider" />' },
}

function mountMenu(props = {}) {
  return mount(FileContextMenu, {
    props: { visible: true, ...props },
    global: { stubs: globalStubs },
  })
}

function itemButtons(wrapper) {
  return wrapper.findAll('button.context-menu__item')
}

function labels(wrapper) {
  return itemButtons(wrapper).map((button) => button.text())
}

function dividerCount(wrapper) {
  return wrapper.findAll('.stub-divider').length
}

/** 点某个标签的菜单项，返回它发出的 action 载荷（取最后一次）。 */
async function clickLabel(wrapper, label) {
  const button = itemButtons(wrapper).find((item) => item.text() === label)
  if (!button) {
    throw new Error(`菜单里没有「${label}」，实际为：${JSON.stringify(labels(wrapper))}`)
  }
  await button.trigger('mousedown')
  return wrapper.emitted('action').at(-1)[0]
}

const FILE_ITEM = { id: 1, fileName: 'a.txt', type: 1 }
const FOLDER_ITEM = { id: 2, fileName: 'A', type: 0 }

beforeEach(() => {
  vi.clearAllMocks()
})

describe('FileContextMenu: 可见性', () => {
  it('visible=false 时不渲染任何菜单项（也不留分隔线）', () => {
    const wrapper = mountMenu({ visible: false })

    expect(wrapper.find('.context-menu').exists()).toBe(false)
    expect(itemButtons(wrapper)).toHaveLength(0)
    expect(dividerCount(wrapper)).toBe(0)
  })

  it('按 position 定位到 left/top', () => {
    const wrapper = mountMenu({ position: { x: 40, y: 80 } })
    const style = wrapper.find('.context-menu').attributes('style')

    expect(style).toContain('left: 40px')
    expect(style).toContain('top: 80px')
  })
})

describe('FileContextMenu: 空白处（blank）', () => {
  it('可写时给出新建文件夹 / 上传文件 / 上传文件夹，再分段给出刷新', () => {
    const wrapper = mountMenu({ contextType: 'blank', canWrite: true })

    expect(labels(wrapper)).toEqual(['新建文件夹', '上传文件', '上传文件夹', '刷新'])
    // 写入动作与「刷新」之间恰好一条分隔线
    expect(dividerCount(wrapper)).toBe(1)
  })

  it('只读时写入动作整段消失，只剩刷新（且不留悬空分隔线）', () => {
    const wrapper = mountMenu({ contextType: 'blank', canWrite: false })

    expect(labels(wrapper)).toEqual(['刷新'])
    expect(dividerCount(wrapper)).toBe(0)
  })

  it('写入动作发出的是 createFolder / uploadFile / uploadFolder', async () => {
    const wrapper = mountMenu({ contextType: 'blank', canWrite: true })

    expect((await clickLabel(wrapper, '新建文件夹')).action).toBe(FILE_CONTEXT_ACTIONS.CREATE_FOLDER)
    expect((await clickLabel(wrapper, '上传文件')).action).toBe(FILE_CONTEXT_ACTIONS.UPLOAD_FILE)
    expect((await clickLabel(wrapper, '上传文件夹')).action).toBe(FILE_CONTEXT_ACTIONS.UPLOAD_FOLDER)
  })
})

describe('FileContextMenu: 单选文件（singleFile）', () => {
  const baseProps = { contextType: 'file', targetItem: FILE_ITEM }

  it('只读时的动作列表里**不含**任何写入动作', () => {
    const wrapper = mountMenu({ ...baseProps, canWrite: false })
    const found = labels(wrapper)

    expect(found).toEqual([
      '预览',
      '下载',
      '复制下载链接',
      '复制文件名称',
      '打包下载',
      '获取直链',
      '分享文件',
      '发送到会话',
      '同时获取',
      '刷新',
    ])
    // 逐个钉死：这几项一旦出现就是权限泄漏
    for (const forbidden of ['重命名', '移动', '复制', '新建文件夹', '上传文件', '上传文件夹']) {
      expect(found).not.toContain(forbidden)
    }
    // 注意：「复制下载链接 / 复制文件名称」是只读动作，与写入动作「复制」不是同一项
    expect(found).toContain('复制下载链接')
    expect(found).toContain('复制文件名称')
  })

  it('可写时在只读动作之后追加写入段，删带数量后缀', () => {
    const wrapper = mountMenu({ ...baseProps, canWrite: true })

    expect(labels(wrapper)).toEqual([
      '预览',
      '下载',
      '复制下载链接',
      '复制文件名称',
      '打包下载',
      '获取直链',
      '分享文件',
      '发送到会话',
      '同时获取',
      '重命名',
      '移动',
      '复制',
      '删除（1）',
      '新建文件夹',
      '上传文件',
      '上传文件夹',
      '刷新',
    ])
    expect(dividerCount(wrapper)).toBe(4)
  })

  it('可写时写入段发出 rename / move / copy / delete', async () => {
    const wrapper = mountMenu({ ...baseProps, canWrite: true })

    expect((await clickLabel(wrapper, '重命名')).action).toBe(FILE_CONTEXT_ACTIONS.RENAME)
    expect((await clickLabel(wrapper, '移动')).action).toBe(FILE_CONTEXT_ACTIONS.MOVE)
    expect((await clickLabel(wrapper, '复制')).action).toBe(FILE_CONTEXT_ACTIONS.COPY)
    expect((await clickLabel(wrapper, '删除（1）')).action).toBe(FILE_CONTEXT_ACTIONS.DELETE)
  })
})

describe('FileContextMenu: 单选文件夹 / 多选 / 混合', () => {
  it('单选文件夹多出「打开 / 新标签页打开」，但无「预览 / 下载」', () => {
    const wrapper = mountMenu({ contextType: 'folder', targetItem: FOLDER_ITEM, canWrite: true })
    const found = labels(wrapper)

    expect(found).toEqual([
      '打开',
      '新标签页打开',
      '复制文件名称',
      '打包下载',
      '分享文件',
      '发送到会话',
      '重命名',
      '移动',
      '复制',
      '删除（1）',
      '新建文件夹',
      '上传文件',
      '上传文件夹',
      '刷新',
    ])
    expect(found).not.toContain('预览')
    expect(found).not.toContain('下载')
  })

  it('多选同类文件走 multiFile：批量下载 + 删除（N）', async () => {
    const selectedItems = [FILE_ITEM, { id: 3, fileName: 'b.txt', type: 1 }]
    const wrapper = mountMenu({ contextType: 'multi', selectedItems, canWrite: true })
    const found = labels(wrapper)

    expect(found).toEqual([
      '批量下载',
      '复制下载链接',
      '复制文件名称',
      '打包下载',
      '获取直链',
      '分享文件',
      '发送到会话',
      '同时获取',
      '移动',
      '复制',
      '删除（2）',
      '新建文件夹',
      '上传文件',
      '上传文件夹',
      '刷新',
    ])
    expect((await clickLabel(wrapper, '批量下载')).action).toBe(
      FILE_CONTEXT_ACTIONS.BATCH_DOWNLOAD,
    )
    // 多选删除仍走同一个 delete 动作（后端按 selectedItems 处理）
    expect((await clickLabel(wrapper, '删除（2）')).action).toBe(FILE_CONTEXT_ACTIONS.DELETE)
  })

  it('多选文件夹走 multiFolder：无「打开」，只读段与写入段都收窄', () => {
    const wrapper = mountMenu({
      contextType: 'multi',
      selectedItems: [FOLDER_ITEM, { id: 4, fileName: 'B', type: 0 }],
      canWrite: true,
    })

    expect(labels(wrapper)).toEqual([
      '复制文件名称',
      '打包下载',
      '分享文件',
      '发送到会话',
      '移动',
      '复制',
      '删除（2）',
      '新建文件夹',
      '上传文件',
      '上传文件夹',
      '刷新',
    ])
  })

  it('文件与文件夹混合选择走 mixed：既不「打开」也不「批量下载」', () => {
    const wrapper = mountMenu({
      contextType: 'multi',
      selectedItems: [FOLDER_ITEM, FILE_ITEM],
      canWrite: true,
    })
    const found = labels(wrapper)

    expect(found).toEqual([
      '复制文件名称',
      '打包下载',
      '分享文件',
      '发送到会话',
      '移动',
      '复制',
      '删除（2）',
      '新建文件夹',
      '上传文件',
      '上传文件夹',
      '刷新',
    ])
    expect(found).not.toContain('打开')
    expect(found).not.toContain('批量下载')
  })

  it('多选且只读时写入段整体消失', () => {
    const wrapper = mountMenu({
      contextType: 'multi',
      selectedItems: [FOLDER_ITEM, { id: 4, fileName: 'B', type: 0 }],
      canWrite: false,
    })

    expect(labels(wrapper)).toEqual(['复制文件名称', '打包下载', '分享文件', '发送到会话', '刷新'])
    expect(dividerCount(wrapper)).toBe(1)
  })
})

describe('FileContextMenu: 回收站模式（mode=recycle）', () => {
  it('可写时给出取消删除 / 彻底删除', async () => {
    const wrapper = mountMenu({
      mode: 'recycle',
      contextType: 'file',
      targetItem: FILE_ITEM,
      canWrite: true,
    })

    expect(labels(wrapper)).toEqual(['取消删除', '彻底删除', '刷新'])
    expect((await clickLabel(wrapper, '彻底删除')).action).toBe(
      FILE_CONTEXT_ACTIONS.DELETE_FOREVER,
    )
  })

  it('多选时文案带数量，且动作切换为 *_SELECTED', async () => {
    const wrapper = mountMenu({
      mode: 'recycle',
      contextType: 'multi',
      selectedItems: [FILE_ITEM, { id: 3, fileName: 'b.txt', type: 1 }],
      canWrite: true,
    })

    expect(labels(wrapper)).toEqual(['取消删除（2）', '彻底删除（2）', '刷新'])
    expect((await clickLabel(wrapper, '取消删除（2）')).action).toBe(
      FILE_CONTEXT_ACTIONS.RESTORE_SELECTED,
    )
    expect((await clickLabel(wrapper, '彻底删除（2）')).action).toBe(
      FILE_CONTEXT_ACTIONS.DELETE_FOREVER_SELECTED,
    )
  })

  it('只读时不给还原与彻底删除（回收站也不放过权限）', () => {
    const wrapper = mountMenu({
      mode: 'recycle',
      contextType: 'file',
      targetItem: FILE_ITEM,
      canWrite: false,
    })

    expect(labels(wrapper)).toEqual(['刷新'])
    expect(dividerCount(wrapper)).toBe(0)
  })

  it('空白处即使可写也只有刷新', () => {
    const wrapper = mountMenu({ mode: 'recycle', contextType: 'blank', canWrite: true })

    expect(labels(wrapper)).toEqual(['刷新'])
  })
})

describe('FileContextMenu: 虚拟项目条目', () => {
  it('项目根目录空白处：按 canManageProjects 决定「新建项目组」还是「申请项目组」', () => {
    const manager = mountMenu({
      virtualDirectory: 'projectRoot',
      contextType: 'blank',
      canManageProjects: true,
    })
    const applicant = mountMenu({
      virtualDirectory: 'projectRoot',
      contextType: 'blank',
      canManageProjects: false,
    })

    expect(labels(manager)).toEqual(['新建项目组', '刷新'])
    expect(labels(applicant)).toEqual(['申请项目组', '刷新'])
  })

  it('可选管理的项目组额外给出「项目配置」，但**不带**写入动作', () => {
    const wrapper = mountMenu({
      contextType: 'folder',
      targetItem: createProjectFolderEntry({ id: 7, name: '甲项目', manageable: true }),
      canWrite: true,
    })
    const found = labels(wrapper)

    expect(found).toEqual(['打开', '项目配置', '刷新'])
    expect(found).not.toContain('重命名')
    expect(found).not.toContain('删除（1）')
  })

  it('不可管理的项目组不给「项目配置」', () => {
    const wrapper = mountMenu({
      contextType: 'folder',
      targetItem: createProjectFolderEntry({ id: 7, name: '甲项目', manageable: false }),
      canWrite: true,
    })

    expect(labels(wrapper)).toEqual(['打开', '刷新'])
  })

  it('项目根条目（virtualType=projectRoot）不是 projectFolder ⇒ 同样不给「项目配置」', () => {
    const wrapper = mountMenu({
      contextType: 'folder',
      targetItem: createProjectRootEntry(1),
      canWrite: true,
    })

    expect(labels(wrapper)).toEqual(['打开', '刷新'])
  })

  it('在项目根目录下选中虚拟条目时，追加「新建/申请项目组」', async () => {
    const wrapper = mountMenu({
      virtualDirectory: 'projectRoot',
      contextType: 'folder',
      targetItem: createProjectFolderEntry({ id: 7, name: '甲项目', manageable: false }),
      canManageProjects: true,
      canWrite: true,
    })

    expect(labels(wrapper)).toEqual(['打开', '新建项目组', '刷新'])
    expect((await clickLabel(wrapper, '新建项目组')).action).toBe(
      FILE_CONTEXT_ACTIONS.CREATE_PROJECT_GROUP,
    )
  })
})

describe('FileContextMenu: 事件', () => {
  it('点击菜单项发出 action 快照，并同时且仅关闭一次（按钮自身 stop 掉冒泡）', async () => {
    const wrapper = mountMenu({
      contextType: 'file',
      targetItem: FILE_ITEM,
      canWrite: true,
    })

    await clickLabel(wrapper, '下载')

    expect(wrapper.emitted('action')[0]).toEqual([
      {
        action: FILE_CONTEXT_ACTIONS.DOWNLOAD,
        contextType: 'file',
        selectedItems: [],
        targetItem: FILE_ITEM,
      },
    ])
    // 若按钮没 stop 冒泡，会再被 document 上的 mousedown 监听关一次 ⇒ 这里钉住"只关一次"
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(logger.debug).toHaveBeenCalledTimes(1)
  })

  it('有多选时 action 载荷携带整个选区（而不是右键命中的那一项）', async () => {
    const selectedItems = [FILE_ITEM, { id: 3, fileName: 'b.txt', type: 1 }]
    const wrapper = mountMenu({
      contextType: 'multi',
      selectedItems,
      targetItem: FILE_ITEM,
      canWrite: true,
    })

    const payload = await clickLabel(wrapper, '批量下载')

    expect(payload.selectedItems).toEqual(selectedItems)
    expect(payload.targetItem).toEqual(FILE_ITEM)
  })

  it('点击菜单外部（mousedown）发出 close', async () => {
    const wrapper = mountMenu()

    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))

    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('右键（contextmenu）同样关闭菜单', async () => {
    const wrapper = mountMenu()

    document.body.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true }))

    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('visible=false 时外部点击不会发出 close', async () => {
    const wrapper = mountMenu({ visible: false })

    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    document.body.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true }))

    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
})
