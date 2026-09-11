import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'

vi.mock('@/api/files', () => ({
  fetchStorageUsage: vi.fn(),
}))

vi.mock('@/models/space', () => ({
  getSpaceUsageTitle: vi.fn(() => '存储空间'),
}))

vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn((_ctx, params) => params || { spaceType: 1 }),
}))

// logger 是 Object.freeze 的，无法 spyOn，只能整模块 mock。
vi.mock('@/utils/logger', () => ({
  logger: { debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

import { fetchStorageUsage } from '@/api/files'
import { useStorageUsage } from '@/composables/useStorageUsage'
import { logger } from '@/utils/logger'

describe('useStorageUsage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should initialize with null storageUsage', () => {
    const { storageUsage } = useStorageUsage()
    expect(storageUsage.value).toBeNull()
  })

  it('should return 0 percentage when no data', () => {
    const { storageUsagePercentage } = useStorageUsage()
    expect(storageUsagePercentage.value).toBe(0)
  })

  it('should return 0 percentage when unlimited', async () => {
    const { storageUsage, storageUsagePercentage } = useStorageUsage()
    fetchStorageUsage.mockResolvedValue({
      data: { unlimited: true, usedStorage: 1000, storageLimit: 2000 },
    })
    await useStorageUsage().refreshStorageUsage()
    storageUsage.value = { unlimited: true, usedStorage: 1000, storageLimit: 2000 }
    await nextTick()
    expect(storageUsagePercentage.value).toBe(0)
  })

  it('should return 0 percentage when no storageLimit', async () => {
    const { storageUsage, storageUsagePercentage } = useStorageUsage()
    storageUsage.value = { unlimited: false, usedStorage: 1000, storageLimit: null }
    await nextTick()
    expect(storageUsagePercentage.value).toBe(0)
  })

  it('should calculate correct percentage', async () => {
    const { storageUsage, storageUsagePercentage } = useStorageUsage()
    storageUsage.value = { unlimited: false, usedStorage: 500, storageLimit: 1000 }
    await nextTick()
    expect(storageUsagePercentage.value).toBe(50)
  })

  it('should cap percentage at 100', async () => {
    const { storageUsage, storageUsagePercentage } = useStorageUsage()
    storageUsage.value = { unlimited: false, usedStorage: 2000, storageLimit: 1000 }
    await nextTick()
    expect(storageUsagePercentage.value).toBe(100)
  })

  it('should refresh storage usage successfully', async () => {
    const { storageUsage, refreshStorageUsage } = useStorageUsage()
    fetchStorageUsage.mockResolvedValue({
      data: { unlimited: false, usedStorage: 100, storageLimit: 500 },
    })
    await refreshStorageUsage()
    expect(fetchStorageUsage).toHaveBeenCalled()
    expect(storageUsage.value).toEqual({ unlimited: false, usedStorage: 100, storageLimit: 500 })
  })

  it('should set null on refresh failure', async () => {
    const { storageUsage, refreshStorageUsage } = useStorageUsage()
    fetchStorageUsage.mockRejectedValue(new Error('Network error'))
    await refreshStorageUsage()
    expect(storageUsage.value).toBeNull()
  })

  it('刷新失败时回退为 null 且留下告警日志（07-P0-3：不再静默吞异常）', async () => {
    const { storageUsage, refreshStorageUsage } = useStorageUsage()
    const error = new Error('Network error')
    fetchStorageUsage.mockRejectedValue(error)

    await refreshStorageUsage()

    // 回退语义保持不变……
    expect(storageUsage.value).toBeNull()
    // ……但失败必须可被观测，否则配额条空白无从归因。
    expect(logger.warn).toHaveBeenCalledTimes(1)
    expect(logger.warn).toHaveBeenCalledWith('加载存储用量失败，已回退为不展示:', error)
  })

  it('should show storage usage only at root folder', async () => {
    const { storageUsage, showStorageUsage, refreshStorageUsage } = useStorageUsage({
      getCurrentFolderId: () => -1,
    })
    fetchStorageUsage.mockResolvedValue({
      data: { unlimited: false, usedStorage: 100, storageLimit: 500 },
    })
    await refreshStorageUsage()
    await nextTick()
    expect(showStorageUsage.value).toBe(true)
  })

  it('should hide storage usage when not at root folder', async () => {
    const { storageUsage, showStorageUsage, refreshStorageUsage } = useStorageUsage({
      getCurrentFolderId: () => 42,
    })
    fetchStorageUsage.mockResolvedValue({
      data: { unlimited: false, usedStorage: 100, storageLimit: 500 },
    })
    await refreshStorageUsage()
    await nextTick()
    expect(showStorageUsage.value).toBe(false)
  })
})
