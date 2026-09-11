import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

import { ROOT_ID, ROOT_PATH, useCorePathNavigation } from '@/composables/useCorePathNavigation'

// 这里刻意**不 mock** @/utils/pathUtils：路径归一化/拼接/面包屑本身就是这个组合
// 函数的核心语义，mock 掉等于把要验证的东西换成了断言 mock 的返回值。
function setup(options = {}) {
  const api = useCorePathNavigation(options)
  return { api }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useCorePathNavigation: 初始状态', () => {
  it('默认是根路径 / 根 ID / 空映射', () => {
    const { api } = setup()
    expect(ROOT_PATH).toBe('')
    expect(ROOT_ID).toBe(-1)
    expect(api.currentPath.value).toBe('')
    expect(api.currentParentId.value).toBe(-1)
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('支持 initialPath / initialParentId / initialPathToIdMap', () => {
    const { api } = setup({
      initialPath: '/a/b',
      initialParentId: 7,
      initialPathToIdMap: { '/a': 1 },
    })
    expect(api.currentPath.value).toBe('/a/b')
    expect(api.currentParentId.value).toBe(7)
    expect(api.pathToIdMap.value).toEqual({ '/a': 1 })
  })

  it('initialPathToIdMap 被拷贝，外部对象后续变更不会渗入', () => {
    const external = { '/a': 1 }
    const { api } = setup({ initialPathToIdMap: external })
    external['/b'] = 2
    expect(api.pathToIdMap.value).toEqual({ '/a': 1 })
  })

  it('传入外部 ref 时复用同一个引用（不外建）', () => {
    const currentPath = ref('/x')
    const currentParentId = ref(3)
    const pathToIdMap = ref({ '/x': 3 })
    const { api } = setup({ currentPath, currentParentId, pathToIdMap })

    expect(api.currentPath).toBe(currentPath)
    expect(api.currentParentId).toBe(currentParentId)
    expect(api.pathToIdMap).toBe(pathToIdMap)

    currentPath.value = '/y'
    expect(api.currentPath.value).toBe('/y')
  })
})

describe('useCorePathNavigation: crumbArr / crumbPath', () => {
  it('crumbArr 由 currentPath 解析而来，且会跟随变化', () => {
    const { api } = setup()
    expect(api.crumbArr.value).toEqual([])

    api.currentPath.value = '/a/b/c'
    expect(api.crumbArr.value).toEqual(['a', 'b', 'c'])
  })

  it('默认不解码百分号编码（decode 默认 false）', () => {
    const { api } = setup()
    api.currentPath.value = '/%E4%B8%AD%E6%96%87'
    expect(api.crumbArr.value).toEqual(['%E4%B8%AD%E6%96%87'])
  })

  it('decode:true 时对分段解码', () => {
    const { api } = setup({ decode: true })
    api.currentPath.value = '/%E4%B8%AD%E6%96%87'
    expect(api.crumbArr.value).toEqual(['中文'])
  })

  it('decode:true 遇到非法百分号转义会抛 URIError（调用方需自行保证入参合法）', () => {
    const { api } = setup({ decode: true })
    api.currentPath.value = '/%E4%'
    expect(() => api.crumbArr.value).toThrow(URIError)
  })

  it('crumbPath(index) 回拼到该层级', () => {
    const { api } = setup()
    api.currentPath.value = '/a/b/c'
    expect(api.crumbPath(0)).toBe('/a')
    expect(api.crumbPath(1)).toBe('/a/b')
    expect(api.crumbPath(2)).toBe('/a/b/c')
  })

  it('crumbPath 用 decode:false，故编码分段原样保留', () => {
    const { api } = setup({ decode: true })
    api.currentPath.value = '/%E4%B8%AD%E6%96%87/x'
    // crumbArr 已解码，但回拼时以 decode:false 交给 joinPath，故得到解码后的字面量
    expect(api.crumbPath(0)).toBe('/中文')
  })
})

describe('useCorePathNavigation: buildFolderPath', () => {
  it('没有行时原样返回当前路径', () => {
    const { api } = setup()
    api.currentPath.value = '/a'
    expect(api.buildFolderPath(null)).toBe('/a')
    expect(api.buildFolderPath(undefined)).toBe('/a')
  })

  it('有行时在当前路径下拼接 fileName', () => {
    const { api } = setup()
    api.currentPath.value = '/a'
    expect(api.buildFolderPath({ fileName: 'b' })).toBe('/a/b')
  })

  it('根路径下拼接也带前导斜杠', () => {
    const { api } = setup()
    expect(api.buildFolderPath({ fileName: 'b' })).toBe('/b')
  })
})

describe('useCorePathNavigation: setPath / setParentId', () => {
  it('setPath 归一化后写入（去重斜杠、过滤空段）', () => {
    const { api } = setup()
    expect(api.setPath('a//b/')).toBe('/a/b')
    expect(api.currentPath.value).toBe('/a/b')
  })

  it('setPath 把反斜杠视为分隔符', () => {
    const { api } = setup()
    api.setPath('a\\b')
    expect(api.currentPath.value).toBe('/a/b')
  })

  it('setPath 传入空值会回到根路径', () => {
    const { api } = setup()
    api.setPath('/a')
    expect(api.setPath('')).toBe('')
    expect(api.currentPath.value).toBe('')
  })

  it('外部提供 setCurrentPath 时改为委派，且不再自行写 currentPath', () => {
    const setCurrentPath = vi.fn()
    const { api } = setup({ setCurrentPath })

    expect(api.setPath('a/b')).toBeUndefined()
    expect(setCurrentPath).toHaveBeenCalledWith('/a/b')
    expect(api.currentPath.value).toBe('')
  })

  it('setParentId 直接写入并返回入参', () => {
    const { api } = setup()
    expect(api.setParentId(9)).toBe(9)
    expect(api.currentParentId.value).toBe(9)
  })

  it('外部提供 setCurrentParentId 时委派', () => {
    const setCurrentParentId = vi.fn()
    const { api } = setup({ setCurrentParentId })
    expect(api.setParentId(9)).toBeUndefined()
    expect(setCurrentParentId).toHaveBeenCalledWith(9)
    expect(api.currentParentId.value).toBe(-1)
  })
})

describe('useCorePathNavigation: replacePathToIdMap / rememberPathId / forgetPathId', () => {
  it('replacePathToIdMap 覆盖并拷贝（外部对象后续变更不渗入）', () => {
    const { api } = setup()
    const next = { '/a': 1 }
    api.replacePathToIdMap(next)
    next['/b'] = 2
    expect(api.pathToIdMap.value).toEqual({ '/a': 1 })
  })

  it('replacePathToIdMap 不传参等价于清空', () => {
    const { api } = setup({ initialPathToIdMap: { '/a': 1 } })
    api.replacePathToIdMap()
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('外部提供 customReplacePathToIdMap 时委派', () => {
    const custom = vi.fn()
    const { api } = setup({ replacePathToIdMap: custom, initialPathToIdMap: { '/a': 1 } })
    api.replacePathToIdMap({ '/b': 2 })
    expect(custom).toHaveBeenCalledWith({ '/b': 2 })
    expect(api.pathToIdMap.value).toEqual({ '/a': 1 })
  })

  it('rememberPathId 归一化路径后写映射并返回 id', () => {
    const { api } = setup()
    expect(api.rememberPathId('a/b/', 12)).toBe(12)
    expect(api.pathToIdMap.value).toEqual({ '/a/b': 12 })
  })

  it('rememberPathId 对空路径不记录', () => {
    const { api } = setup()
    expect(api.rememberPathId('', 12)).toBeUndefined()
    expect(api.rememberPathId('/', 12)).toBeUndefined()
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('外部提供 onRememberPathId 时委派（并收到归一化后的路径）', () => {
    const onRememberPathId = vi.fn()
    const { api } = setup({ onRememberPathId })
    api.rememberPathId('a//b', 5)
    expect(onRememberPathId).toHaveBeenCalledWith('/a/b', 5)
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('forgetPathId 删除已存在的映射并返回归一化路径', () => {
    const { api } = setup({ initialPathToIdMap: { '/a': 1, '/b': 2 } })
    expect(api.forgetPathId('a/')).toBe('/a')
    expect(api.pathToIdMap.value).toEqual({ '/b': 2 })
  })

  it('forgetPathId 对不存在的路径或空路径为无操作', () => {
    const { api } = setup({ initialPathToIdMap: { '/a': 1 } })
    expect(api.forgetPathId('/zzz')).toBeUndefined()
    expect(api.forgetPathId('')).toBeUndefined()
    expect(api.pathToIdMap.value).toEqual({ '/a': 1 })
  })

  it('外部提供 onForgetPathId 时委派', () => {
    const onForgetPathId = vi.fn()
    const { api } = setup({ onForgetPathId, initialPathToIdMap: { '/a': 1 } })
    api.forgetPathId('/a')
    expect(onForgetPathId).toHaveBeenCalledWith('/a')
    expect(api.pathToIdMap.value).toEqual({ '/a': 1 })
  })
})

describe('useCorePathNavigation: resetNavigation', () => {
  it('清空映射、路径回根、父 ID 回根', async () => {
    const { api } = setup({
      initialPath: '/a/b',
      initialParentId: 5,
      initialPathToIdMap: { '/a': 1 },
    })

    await api.resetNavigation()

    expect(api.currentPath.value).toBe('')
    expect(api.currentParentId.value).toBe(-1)
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('提供 loadFolder 时以根 ID 触发加载', async () => {
    const loadFolder = vi.fn(() => Promise.resolve())
    const { api } = setup()
    await api.resetNavigation({ loadFolder })
    expect(loadFolder).toHaveBeenCalledWith(-1)
  })

  it('不提供 loadFolder 也能跑完', async () => {
    const { api } = setup()
    await expect(api.resetNavigation()).resolves.toBeUndefined()
  })
})

describe('useCorePathNavigation: enterFolder', () => {
  const folderRow = { id: 21, type: 0, fileName: 'docs' }

  it('row 为空或不是文件夹（type !== 0）时返回 null', async () => {
    const { api } = setup()
    await expect(api.enterFolder(null)).resolves.toBeNull()
    await expect(api.enterFolder({ id: 1, type: 1, fileName: 'f.txt' })).resolves.toBeNull()
    await expect(api.enterFolder({ id: 1, fileName: 'x' })).resolves.toBeNull()
    expect(api.currentPath.value).toBe('')
  })

  it('进入成功后更新路径与父 ID，并返回上下文', async () => {
    const { api } = setup()
    const result = await api.enterFolder(folderRow)

    expect(result).toEqual({ row: folderRow, nextPath: '/docs', nextParentId: 21 })
    expect(api.currentPath.value).toBe('/docs')
    expect(api.currentParentId.value).toBe(21)
    expect(api.pathToIdMap.value).toEqual({ '/docs': 21 })
  })

  it('进入子目录时在当前路径下继续拼接', async () => {
    const { api } = setup({ initialPath: '/docs' })
    await api.enterFolder({ id: 22, type: 0, fileName: 'inner' })
    expect(api.currentPath.value).toBe('/docs/inner')
    expect(api.pathToIdMap.value).toEqual({ '/docs/inner': 22 })
  })

  it('rememberPath:false 时不写映射', async () => {
    const { api } = setup()
    await api.enterFolder(folderRow, { rememberPath: false })
    expect(api.currentPath.value).toBe('/docs')
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('loadFolder 以新文件夹 id 调用', async () => {
    const loadFolder = vi.fn(() => Promise.resolve())
    const { api } = setup()
    await api.enterFolder(folderRow, { loadFolder })
    expect(loadFolder).toHaveBeenCalledWith(21)
  })

  it('onBeforeEnter 在路径变更之前触发，onAfterEnter 在其之后', async () => {
    const { api } = setup()
    const seen = []
    const onBeforeEnter = vi.fn(() => {
      seen.push(`before:${api.currentPath.value}`)
    })
    const onAfterEnter = vi.fn(() => {
      seen.push(`after:${api.currentPath.value}`)
    })

    await api.enterFolder(folderRow, { onBeforeEnter, onAfterEnter })

    expect(seen).toEqual(['before:', 'after:/docs'])
    expect(onBeforeEnter).toHaveBeenCalledWith({
      row: folderRow,
      nextPath: '/docs',
      nextParentId: 21,
    })
    expect(onAfterEnter).toHaveBeenCalledWith({
      row: folderRow,
      nextPath: '/docs',
      nextParentId: 21,
    })
  })

  it('onBeforeEnter 抛错时中止，路径不变', async () => {
    const { api } = setup()
    const boom = new Error('nope')
    await expect(
      api.enterFolder(folderRow, {
        onBeforeEnter: () => Promise.reject(boom),
      }),
    ).rejects.toThrow('nope')
    expect(api.currentPath.value).toBe('')
    expect(api.currentParentId.value).toBe(-1)
  })

  it('loadFolder 抛错时路径已更新（更新在前、加载在后）', async () => {
    const { api } = setup()
    await expect(
      api.enterFolder(folderRow, { loadFolder: () => Promise.reject(new Error('x')) }),
    ).rejects.toThrow('x')
    expect(api.currentPath.value).toBe('/docs')
  })
})

describe('useCorePathNavigation: goToPath', () => {
  it('空路径等价于重置导航', async () => {
    const { api } = setup({
      initialPath: '/a',
      initialParentId: 4,
      initialPathToIdMap: { '/a': 4 },
    })

    const result = await api.goToPath('')

    expect(result).toEqual({ resolvedPath: '', resolvedId: -1 })
    expect(api.currentPath.value).toBe('')
    expect(api.currentParentId.value).toBe(-1)
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('命中内部映射时直接取 id', async () => {
    const resolvePathId = vi.fn()
    const { api } = setup({ initialPathToIdMap: { '/a/b': 33 } })

    const result = await api.goToPath('/a/b', { resolvePathId })

    expect(result).toEqual({ resolvedPath: '/a/b', resolvedId: 33 })
    expect(resolvePathId).not.toHaveBeenCalled()
    expect(api.currentParentId.value).toBe(33)
  })

  it('内部无映射时回退到 externalPathToIdMap（且不调 resolvePathId）', async () => {
    const resolvePathId = vi.fn()
    const { api } = setup()

    const result = await api.goToPath('/a/b', {
      externalPathToIdMap: { '/a/b': 44 },
      resolvePathId,
    })

    expect(result.resolvedId).toBe(44)
    expect(resolvePathId).not.toHaveBeenCalled()
  })

  it('两处都无映射时用 resolvePathId 异步解析，并把结果写入映射', async () => {
    const resolvePathId = vi.fn(() => Promise.resolve(55))
    const { api } = setup()

    const result = await api.goToPath('a/b/', { resolvePathId })

    expect(resolvePathId).toHaveBeenCalledWith('/a/b')
    expect(result).toEqual({ resolvedPath: '/a/b', resolvedId: 55 })
    expect(api.currentParentId.value).toBe(55)
    expect(api.pathToIdMap.value).toEqual({ '/a/b': 55 })
  })

  it('解析不到 id 时父 ID 回退为根，且不写映射', async () => {
    const { api } = setup()

    const result = await api.goToPath('/unknown')

    expect(result).toEqual({ resolvedPath: '/unknown', resolvedId: -1 })
    expect(api.currentParentId.value).toBe(-1)
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('resolvePathId 返回 undefined 时同样回退为根', async () => {
    const { api } = setup()
    const result = await api.goToPath('/a', { resolvePathId: () => Promise.resolve(undefined) })
    expect(result.resolvedId).toBe(-1)
    expect(api.pathToIdMap.value).toEqual({})
  })

  it('loadFolder 收到解析出的 id；解析不到时收到根 ID', async () => {
    const loadFolder = vi.fn(() => Promise.resolve())
    const { api } = setup({ initialPathToIdMap: { '/a': 9 } })

    await api.goToPath('/a', { loadFolder })
    expect(loadFolder).toHaveBeenLastCalledWith(9)

    await api.goToPath('/nope', { loadFolder })
    expect(loadFolder).toHaveBeenLastCalledWith(-1)
  })

  it('onAfterGoToPath 收到完成上下文', async () => {
    const onAfterGoToPath = vi.fn()
    const { api } = setup({ initialPathToIdMap: { '/a': 9 } })

    await api.goToPath('/a', { onAfterGoToPath })

    expect(onAfterGoToPath).toHaveBeenCalledWith({ resolvedPath: '/a', resolvedId: 9 })
  })

  it('pathToIdMap 命中的 id 为 0 时也算命中（用 undefined 判定而非真假值）', async () => {
    const resolvePathId = vi.fn()
    const { api } = setup({ initialPathToIdMap: { '/zero': 0 } })

    const result = await api.goToPath('/zero', { resolvePathId })

    expect(result.resolvedId).toBe(0)
    expect(api.currentParentId.value).toBe(0)
    expect(resolvePathId).not.toHaveBeenCalled()
  })
})

describe('useCorePathNavigation: 对外接口面', () => {
  it('暴露约定的全部键', () => {
    const { api } = setup()
    for (const key of [
      'currentPath',
      'currentParentId',
      'pathToIdMap',
      'crumbArr',
      'crumbPath',
      'buildFolderPath',
      'setPath',
      'setParentId',
      'replacePathToIdMap',
      'rememberPathId',
      'forgetPathId',
      'resetNavigation',
      'enterFolder',
      'goToPath',
    ]) {
      expect(key in api, `缺少 ${key}`).toBe(true)
    }
  })

  it('两个实例之间状态互不影响', () => {
    const a = setup()
    const b = setup()
    a.api.setPath('/only-a')
    expect(b.api.currentPath.value).toBe('')
  })
})
