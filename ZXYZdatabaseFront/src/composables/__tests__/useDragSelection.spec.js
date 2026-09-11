import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'

import { useDragSelection } from '@/composables/useDragSelection'

// happy-dom 里 getBoundingClientRect 恒为全 0，所以必须给参与几何计算的那几个
// 元素手动打桩，否则"框选命中"这类逻辑根本无从验证。
function rect(left, top, width, height) {
  return {
    left,
    top,
    right: left + width,
    bottom: top + height,
    width,
    height,
    x: left,
    y: top,
    toJSON() {
      return { left, top, width, height }
    },
  }
}

const ROW_HEIGHT = 40
const ROW_WIDTH = 500

/**
 * 搭一份贴近 Element Plus 表格结构的 DOM。注意层级：
 *   container
 *     └ .table-wrapper            ← tableWrapperRef 指的是**外层**
 *         └ .el-table__body-wrapper
 *             └ table > tbody > tr.el-table__row × N
 * 代码里是 `tableWrapperRef.value.querySelector('.el-table__body-wrapper')`，
 * 所以 tableWrapperRef 必须是 body 包裹层的**父节点**，不能直接指它本身。
 * 行与 body 包裹层都带真实 rect，行 i 的纵向区间是 [i*40, i*40+40)。
 */
function buildDom(rowCount = 3) {
  const container = document.createElement('div')
  const tableWrapper = document.createElement('div')
  tableWrapper.className = 'table-wrapper'
  const wrapper = document.createElement('div')
  wrapper.className = 'el-table__body-wrapper'
  const table = document.createElement('table')
  const tbody = document.createElement('tbody')

  const rows = []
  for (let i = 0; i < rowCount; i += 1) {
    const tr = document.createElement('tr')
    tr.className = 'el-table__row'
    const td = document.createElement('td')
    tr.appendChild(td)
    tbody.appendChild(tr)
    rows.push(tr)
  }

  table.appendChild(tbody)
  wrapper.appendChild(table)
  tableWrapper.appendChild(wrapper)
  container.appendChild(tableWrapper)
  document.body.appendChild(container)

  wrapper.scrollLeft = 0
  wrapper.scrollTop = 0
  wrapper.getBoundingClientRect = () => rect(0, 0, ROW_WIDTH, rowCount * ROW_HEIGHT)
  container.getBoundingClientRect = () => rect(0, 0, ROW_WIDTH, rowCount * ROW_HEIGHT)
  rows.forEach((row, index) => {
    row.getBoundingClientRect = () => rect(0, index * ROW_HEIGHT, ROW_WIDTH, ROW_HEIGHT)
  })

  return { container, tableWrapper, wrapper, rows, rowCount }
}

/** 把组合函数挂到真实组件实例上，才能覆盖 watch(immediate) 与 onBeforeUnmount。 */
function withSetup(composable) {
  let api
  const app = createApp({
    setup() {
      api = composable()
      return () => h('div')
    },
  })
  const root = document.createElement('div')
  document.body.appendChild(root)
  app.mount(root)
  return { api: () => api, app, root }
}

let dom
let rafQueue

function setup(overrides = {}) {
  dom = buildDom(overrides.rowCount ?? 3)
  rafQueue = []

  const selectedIds = ref(overrides.selectedIds ?? [])
  const setSelectedIds = vi.fn((ids) => {
    selectedIds.value = ids
  })
  const closeContextMenu = vi.fn()
  const isCheckboxClick = vi.fn(() => false)
  const filteredList = ref(
    overrides.filteredList ?? [
      { id: 11, name: 'a' },
      { id: 22, name: 'b' },
      { id: 33, name: 'c' },
    ],
  )

  const dragContainerRef = ref(dom.container)
  const tableWrapperRef = ref(dom.tableWrapper)

  const { api, app, root } = withSetup(() =>
    useDragSelection({
      dragContainerRef,
      tableWrapperRef,
      filteredList,
      isCheckboxClick,
      selectedIds,
      setSelectedIds,
      closeContextMenu,
      ...overrides.options,
    }),
  )

  return {
    api,
    app,
    root,
    dragContainerRef,
    tableWrapperRef,
    filteredList,
    selectedIds,
    setSelectedIds,
    closeContextMenu,
    isCheckboxClick,
  }
}

function mouseDown(target, init = {}) {
  target.dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 0, clientY: 0, ...init }),
  )
}

function mouseMove(clientX, clientY) {
  document.body.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX, clientY }))
}

function mouseUp() {
  document.body.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
}

beforeEach(() => {
  // 把 rAF 变成可手动 flush 的队列，避免断言时序依赖真实帧。
  rafQueue = []
  vi.stubGlobal('requestAnimationFrame', (cb) => {
    rafQueue.push(cb)
    return rafQueue.length
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
  dom = null
})

/** 走完一次完整拖拽：按下 → 移动到目标点 → 抬起。 */
async function performDrag({ from = { x: 10, y: 10 }, to = { x: 60, y: 60 }, ints = {} } = {}) {
  mouseDown(dom.rows[0], { clientX: from.x, clientY: from.y, ...ints })
  mouseMove(from.x, from.y)
  mouseMove(to.x, to.y)
  mouseUp()
}

describe('useDragSelection: 初始状态与 selectionBoxStyle', () => {
  it('初始 dragState 全为关闭/零位', () => {
    const { api } = setup()
    expect(api().dragState.value).toEqual({
      active: false,
      visible: false,
      suppressClick: false,
      startX: 0,
      startY: 0,
      currentX: 0,
      currentY: 0,
    })
  })

  it('selectionBoxStyle 取两点的左上角 + 宽高绝对值（反向拖拽也成立）', () => {
    const { api } = setup()
    api().dragState.value = {
      active: true,
      visible: true,
      suppressClick: false,
      startX: 100,
      startY: 200,
      currentX: 40,
      currentY: 150,
    }

    expect(api().selectionBoxStyle.value).toEqual({
      left: '40px',
      top: '150px',
      width: '60px',
      height: '50px',
    })
  })

  it('selectionBoxStyle 在正向拖拽时取 start 为左上角', () => {
    const { api } = setup()
    api().dragState.value = {
      active: true,
      visible: true,
      suppressClick: false,
      startX: 10,
      startY: 20,
      currentX: 90,
      currentY: 80,
    }

    expect(api().selectionBoxStyle.value).toEqual({
      left: '10px',
      top: '20px',
      width: '80px',
      height: '60px',
    })
  })
})

describe('useDragSelection: getRowFromTarget', () => {
  it('命中的行元素返回 filteredList 同下标项', () => {
    const { api } = setup()
    expect(api().getRowFromTarget(dom.rows[1])).toMatchObject({ id: 22 })
  })

  it('从行内子元素上取也算命中（closest 冒泡到 tr）', () => {
    const { api } = setup()
    const td = dom.rows[2].querySelector('td')
    expect(api().getRowFromTarget(td)).toMatchObject({ id: 33 })
  })

  it('不在任何行内时返回 null', () => {
    const { api } = setup()
    expect(api().getRowFromTarget(dom.wrapper)).toBeNull()
  })

  it('行元素不在表格内（indexOf 为 -1）时返回 null', () => {
    const { api } = setup()
    const stray = document.createElement('tr')
    stray.className = 'el-table__row'
    document.body.appendChild(stray)
    expect(api().getRowFromTarget(stray)).toBeNull()
  })

  it('行存在但 filteredList 没有对应项时返回 null', () => {
    const { api } = setup({ filteredList: [{ id: 11 }], rowCount: 3 })
    expect(api().getRowFromTarget(dom.rows[2])).toBeNull()
  })
})

describe('useDragSelection: isBodyWrapperTarget / shouldSuppressRowClick', () => {
  it('wrapper 内部元素为 true，外部为 false', () => {
    const { api } = setup()
    expect(api().isBodyWrapperTarget(dom.rows[0])).toBe(true)
    expect(api().isBodyWrapperTarget(document.body)).toBe(false)
  })

  it('没有 wrapper 时为 false（不抛错）', () => {
    const { api, tableWrapperRef } = setup()
    tableWrapperRef.value = null
    expect(api().isBodyWrapperTarget(document.body)).toBe(false)
  })

  it('shouldSuppressRowClick 跟随 dragState.suppressClick', () => {
    const { api } = setup()
    expect(api().shouldSuppressRowClick()).toBe(false)
    api().dragState.value.suppressClick = true
    expect(api().shouldSuppressRowClick()).toBe(true)
  })
})

describe('useDragSelection: mousedown 的进入条件', () => {
  it('左键在容器内按下会激活拖拽并关闭右键菜单', async () => {
    const { api, closeContextMenu } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })

    expect(api().dragState.value.active).toBe(true)
    expect(closeContextMenu).toHaveBeenCalledTimes(1)
  })

  it('非左键（如右键）不激活', async () => {
    const { api, closeContextMenu } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { button: 2 })

    expect(api().dragState.value.active).toBe(false)
    expect(closeContextMenu).not.toHaveBeenCalled()
  })

  it('目标在容器之外时不激活', async () => {
    const { api, closeContextMenu } = setup()
    await nextTick()
    const outside = document.createElement('div')
    document.body.appendChild(outside)

    mouseDown(outside)

    expect(api().dragState.value.active).toBe(false)
    expect(closeContextMenu).not.toHaveBeenCalled()
  })

  it('落在交互元素（button/input/checkbox 等）上时不激活', async () => {
    const { api } = setup()
    await nextTick()

    const button = document.createElement('button')
    dom.rows[0].querySelector('td').appendChild(button)
    mouseDown(button)

    expect(api().dragState.value.active).toBe(false)
  })

  it('isCheckboxClick 判为真时不激活（即使目标不是交互元素）', async () => {
    const { api, isCheckboxClick } = setup()
    await nextTick()
    isCheckboxClick.mockReturnValue(true)

    mouseDown(dom.rows[0])

    expect(api().dragState.value.active).toBe(false)
    expect(isCheckboxClick).toHaveBeenCalled()
  })

  it('按下时记录起点，且初始 visible 为 false', async () => {
    const { api } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 42, clientY: 24 })

    const state = api().dragState.value
    expect(state.startX).toBe(42)
    expect(state.startY).toBe(24)
    expect(state.currentX).toBe(42)
    expect(state.visible).toBe(false)
  })

  it('没有 wrapper/container 时按下不激活（拿不到几何信息）', async () => {
    const { api, tableWrapperRef } = setup()
    await nextTick()
    tableWrapperRef.value = null

    mouseDown(dom.rows[0])

    expect(api().dragState.value.active).toBe(false)
  })
})

describe('useDragSelection: 拖拽过程与命中判定', () => {
  it('位移不足 3px 时不显示选择框、不改选中', async () => {
    const { api, setSelectedIds } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(12, 11)

    expect(api().dragState.value.visible).toBe(false)
    expect(setSelectedIds).not.toHaveBeenCalled()
  })

  it('位移超过 3px 才显示选择框', async () => {
    const { api } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(20, 10)

    expect(api().dragState.value.visible).toBe(true)
  })

  it('框选命中前两行时提交它们，并把最后一行作为"拾取行"', async () => {
    const { api, setSelectedIds } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(60, 50)

    expect(setSelectedIds).toHaveBeenCalledWith([11, 22], 22)
  })

  it('框选只覆盖第一行时只提交第一行', async () => {
    const { api, setSelectedIds } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 5 })
    mouseMove(60, 20)

    expect(setSelectedIds).toHaveBeenCalledWith([11], 11)
  })

  it('框选不到任何行时提交空数组，拾取行回退为 merge 后的末项（此处为 null）', async () => {
    const { api, setSelectedIds } = setup()
    await nextTick()

    // 三点之上（y 为负）不会与任何一行的区间相交
    mouseDown(dom.rows[0], { clientX: 10, clientY: -80 })
    mouseMove(60, -40)

    expect(setSelectedIds).toHaveBeenCalledWith([], null)
  })

  it('ctrl/meta 为累加选择：与按下时的已选集合取并集去重', async () => {
    const { api, setSelectedIds } = setup({ selectedIds: [33] })
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10, ctrlKey: true })
    mouseMove(60, 50)

    // 命中 11、22，叠加按下前的 33
    expect(setSelectedIds).toHaveBeenCalledWith([33, 11, 22], 22)
  })

  it('metaKey 同样触发累加', async () => {
    const { api, setSelectedIds } = setup({ selectedIds: [33] })
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10, metaKey: true })
    mouseMove(60, 50)

    expect(setSelectedIds).toHaveBeenCalledWith([33, 11, 22], 22)
  })

  it('非累加模式下覆盖上一次的选中集合', async () => {
    const { api, setSelectedIds } = setup({ selectedIds: [33] })
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(60, 20)

    expect(setSelectedIds).toHaveBeenCalledWith([11], 11)
  })

  it('未按下就移动（inactive）不产生任何选中变更', async () => {
    const { setSelectedIds } = setup()
    await nextTick()

    mouseMove(100, 100)

    expect(setSelectedIds).not.toHaveBeenCalled()
  })

  it('filteredList 项缺少 id 时不纳入命中结果', async () => {
    const { setSelectedIds } = setup({ filteredList: [{ name: 'no-id' }, { id: 22 }, { id: 33 }] })
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(60, 50)

    expect(setSelectedIds).toHaveBeenCalledWith([22], 22)
  })
})

describe('useDragSelection: mouseup 收尾', () => {
  it('可见框选结束后置 suppressClick，并在下一帧复位', async () => {
    const { api } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(60, 60)
    mouseUp()

    expect(api().dragState.value.suppressClick).toBe(true)
    expect(api().dragState.value.active).toBe(false)
    expect(api().dragState.value.visible).toBe(false)

    rafQueue.forEach((cb) => cb())
    expect(api().dragState.value.suppressClick).toBe(false)
  })

  it('未显示选择框（只是点了一下）时不抑制行点击', async () => {
    const { api } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseUp()

    expect(api().dragState.value.suppressClick).toBe(false)
    expect(api().dragState.value.active).toBe(false)
  })

  it('mouseup 后文档上的监听被摘掉，继续移动不再改选中', async () => {
    const { setSelectedIds } = setup()
    await nextTick()

    await performDrag()
    const callsAfterDrag = setSelectedIds.mock.calls.length

    mouseMove(200, 200)

    expect(setSelectedIds.mock.calls.length).toBe(callsAfterDrag)
  })

  it('未激活时的 mouseup 无事发生', async () => {
    const { api } = setup()
    await nextTick()

    mouseUp()

    expect(api().dragState.value.suppressClick).toBe(false)
  })
})

describe('useDragSelection: 容器绑定与卸载清理', () => {
  it('挂载后容器上已绑定 mousedown（watch immediate），换容器后旧容器不再响应', async () => {
    const { api, dragContainerRef, closeContextMenu } = setup()
    await nextTick()

    mouseDown(dom.rows[0])
    expect(closeContextMenu).toHaveBeenCalledTimes(1)
    mouseUp() // 结束这次交互，避免残留 active 影响后面的断言
    expect(api().dragState.value.active).toBe(false)

    // 换一个空容器：旧容器上的监听应被移除
    const another = document.createElement('div')
    document.body.appendChild(another)
    dragContainerRef.value = another
    await nextTick()

    closeContextMenu.mockClear()
    mouseDown(dom.rows[0])
    expect(closeContextMenu).not.toHaveBeenCalled()
    expect(api().dragState.value.active).toBe(false)
  })

  it('容器置为 null 时不再绑定，且不抛错', async () => {
    const { dragContainerRef, closeContextMenu } = setup()
    await nextTick()

    dragContainerRef.value = null
    await nextTick()

    expect(() => mouseDown(dom.rows[0])).not.toThrow()
    expect(closeContextMenu).not.toHaveBeenCalled()
  })

  it('组件卸载会摘掉监听并中止进行中的拖拽', async () => {
    const { api, app, root } = setup()
    await nextTick()

    mouseDown(dom.rows[0], { clientX: 10, clientY: 10 })
    mouseMove(60, 60)
    expect(api().dragState.value.active).toBe(true)

    app.unmount()
    root.remove()

    // 卸载后 document 上的 mousemove 监听已 abort，不再改动状态
    const stateAfterUnmount = { ...api().dragState.value }
    mouseMove(200, 200)
    expect(api().dragState.value).toEqual(stateAfterUnmount)
  })
})
