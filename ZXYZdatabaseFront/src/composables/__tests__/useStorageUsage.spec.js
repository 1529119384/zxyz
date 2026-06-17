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

import { fetchStorageUsage } from '@/api/files'
import { useStorageUsage } from '@/composables/useStorageUsage'

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
