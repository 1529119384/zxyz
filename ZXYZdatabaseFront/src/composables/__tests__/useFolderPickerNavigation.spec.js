import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('@/api/files', () => ({
  fetchFileList: vi.fn(),
}))

vi.mock('@/composables/useCorePathNavigation', () => ({
  useCorePathNavigation: vi.fn(() => ({
    currentPath: ref('/'),
    currentParentId: ref(-1),
    crumbArr: ref([]),
    crumbPath: vi.fn(),
    pathToIdMap: ref({}),
    resetNavigation: vi.fn(),
    enterFolder: vi.fn(),
    goToPath: vi.fn(),
  })),
  ROOT_ID: -1,
}))

vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({})),
}))

import { useFolderPickerNavigation } from '@/composables/useFolderPickerNavigation'
import { fetchFileList } from '@/api/files'

describe('useFolderPickerNavigation', () => {
  let nav

  beforeEach(() => {
    vi.clearAllMocks()
    nav = useFolderPickerNavigation({ spaceContext: ref({}) })
  })

  it('应初始化状态', () => {
    expect(nav.list.value).toEqual([])
    expect(nav.loading.value).toBe(false)
  })

  it('应加载文件夹内容', async () => {
    fetchFileList.mockResolvedValue({ data: [{ id: 1, fileName: 'docs' }] })
    await nav.loadFolder(1)
    expect(fetchFileList).toHaveBeenCalled()
    expect(nav.loading.value).toBe(false)
  })

  it('应在加载完成后设置 loading 为 false', async () => {
    fetchFileList.mockResolvedValue({ data: [] })
    await nav.loadFolder()
    expect(nav.loading.value).toBe(false)
  })

  it('应在加载失败时也设置 loading 为 false', async () => {
    fetchFileList.mockRejectedValue(new Error('network'))
    try { await nav.loadFolder() } catch {}
    expect(nav.loading.value).toBe(false)
  })

  it('应处理空响应数据', async () => {
    fetchFileList.mockResolvedValue({})
    await nav.loadFolder()
    expect(nav.list.value).toEqual([])
  })

  it('应暴露 reset 方法', () => {
    expect(typeof nav.reset).toBe('function')
  })

  it('应暴露 enterFolder 方法', () => {
    expect(typeof nav.enterFolder).toBe('function')
  })
})
