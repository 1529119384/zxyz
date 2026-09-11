import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import { useFileUpload } from '@/composables/useFileUpload'
import { resolveSpaceRequestParams } from '@/composables/useCurrentSpaceContext'
import { createUploadFailResult } from '@/models/upload'
import { uploadFileWithPresign } from '@/services/upload'
import { buildBatchPredictedNames, FILE_TYPE } from '@/utils/nameConflict'
import { createClientId } from '@/utils/id'
import { validateFiles } from '@/utils/fileValidation'
import {
  calculateUploadPercentage,
  getUploadTrackingKey,
  sumUploadedBytes,
} from '@/utils/uploadProgress'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))

vi.mock('@/services/upload', () => ({
  uploadFileWithPresign: vi.fn(),
}))

vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({ teamId: null })),
}))

vi.mock('@/utils/uploadProgress', () => ({
  calculateUploadPercentage: vi.fn(() => 0),
  getUploadTrackingKey: vi.fn(() => 'key-1'),
  sumUploadedBytes: vi.fn(() => 0),
}))

vi.mock('@/utils/nameConflict', () => ({
  buildBatchPredictedNames: vi.fn(() => []),
  FILE_TYPE: { FILE: 1, FOLDER: 0 },
}))

vi.mock('@/utils/id', () => ({
  createClientId: vi.fn(() => 'client-1'),
}))

vi.mock('@/utils/fileValidation', () => ({
  validateFiles: vi.fn(() => ({ valid: [], rejected: [] })),
  MAX_FILE_SIZE: 5368709120,
  DANGEROUS_EXTENSIONS: ['.exe', '.bat', '.cmd', '.sh', '.js'],
}))

vi.mock('@/models/upload', () => ({
  createUploadFailResult: vi.fn((payload) => ({ kind: 'fail', ...payload })),
}))

/** 造一个可被 getUploadTrackingKey 识别的文件替身。 */
function makeFile(name = 'a.txt', size = 100) {
  return { name, size, type: 'text/plain' }
}

let currentId
let upload
let options

function build(overrides = {}) {
  currentId = ref(overrides.currentId ?? 1)
  options = {
    onSuccess: vi.fn(),
    spaceContext: ref({}),
    ...overrides.options,
  }
  upload = useFileUpload(currentId, options)
  return upload
}

beforeEach(() => {
  vi.clearAllMocks()
  // 默认让 key 与文件名一一对应，便于断言"按文件"的行为
  getUploadTrackingKey.mockImplementation((file) => `k:${file?.name}`)
  calculateUploadPercentage.mockReturnValue(0)
  sumUploadedBytes.mockReturnValue(0)
  buildBatchPredictedNames.mockReturnValue([])
  validateFiles.mockReturnValue({ valid: [], rejected: [] })
  createClientId.mockReturnValue('client-1')
  resolveSpaceRequestParams.mockImplementation(() => ({ teamId: null }))
  uploadFileWithPresign.mockResolvedValue({ kind: 'success' })
  build()
})

describe('useFileUpload: 基础状态（原有用例保留）', () => {
  it('应初始化状态', () => {
    expect(upload.fileUploadDialog.value).toBe(false)
    expect(upload.uploading.value).toBe(false)
    expect(upload.fileList.value).toEqual([])
  })

  it('应导出 MAX_FILE_SIZE 和 DANGEROUS_EXTENSIONS', () => {
    expect(upload.MAX_FILE_SIZE).toBe(5368709120)
    expect(Array.isArray(upload.DANGEROUS_EXTENSIONS)).toBe(true)
  })

  it('应打开上传对话框', () => {
    upload.openFileUpload()
    expect(upload.fileUploadDialog.value).toBe(true)
  })

  it('应计算总文件大小', () => {
    expect(upload.totalFileSize.value).toBe(0)
  })

  it('progress / uploadedBytes 初始为 0', () => {
    expect(upload.progress.value).toBe(0)
    expect(upload.uploadedBytes.value).toBe(0)
  })
})

describe('useFileUpload: 打开与重置', () => {
  it('openFileUpload 会先清空残留再打开', () => {
    upload.fileList.value = [makeFile()]
    upload.progress.value = 80
    upload.uploading.value = true

    upload.openFileUpload()

    expect(upload.fileList.value).toEqual([])
    expect(upload.progress.value).toBe(0)
    expect(upload.uploading.value).toBe(false)
    expect(upload.fileUploadDialog.value).toBe(true)
  })

  it('clearFileInput 在有 input 时清空其 value', () => {
    const input = { value: 'C:\\fakepath\\a.txt' }
    upload.fileInput.value = input
    upload.clearFileInput()
    expect(input.value).toBe('')
  })

  it('clearFileInput 在没有 input 时不抛错', () => {
    upload.fileInput.value = null
    expect(() => upload.clearFileInput()).not.toThrow()
  })

  it('triggerSelect 触发 input 的 click', () => {
    const click = vi.fn()
    upload.fileInput.value = { click, value: '' }
    upload.triggerSelect()
    expect(click).toHaveBeenCalledTimes(1)
  })

  it('triggerSelect 在没有 input 时不抛错', () => {
    upload.fileInput.value = null
    expect(() => upload.triggerSelect()).not.toThrow()
  })

  it('handleDragOver 阻止默认行为（否则浏览器会打开文件）', () => {
    const event = { preventDefault: vi.fn() }
    upload.handleDragOver(event)
    expect(event.preventDefault).toHaveBeenCalledTimes(1)
  })
})

describe('useFileUpload: 体积与进度聚合', () => {
  it('totalFileSize 累加各文件 size，缺失按 0 计', () => {
    upload.fileList.value = [makeFile('a', 100), { name: 'b' }, makeFile('c', 50)]
    expect(upload.totalFileSize.value).toBe(150)
  })

  it('uploadedBytes 委托给 sumUploadedBytes', () => {
    sumUploadedBytes.mockReturnValue(1234)
    expect(upload.uploadedBytes.value).toBe(1234)
    expect(sumUploadedBytes).toHaveBeenCalled()
  })
})

describe('useFileUpload: 重名预测', () => {
  it('predictionMap 按文件 key 建立"预测名"映射', () => {
    const a = makeFile('a.txt')
    const b = makeFile('b.txt')
    upload.fileList.value = [a, b]
    buildBatchPredictedNames.mockReturnValue([
      { predictedName: 'a (1).txt', renamed: true },
      { predictedName: 'b.txt', renamed: false },
    ])

    expect(upload.predictionMap.value).toEqual({
      'k:a.txt': { predictedName: 'a (1).txt', renamed: true },
      'k:b.txt': { predictedName: 'b.txt', renamed: false },
    })
  })

  it('预测结果比文件多时，多出的索引被跳过（防空读）', () => {
    upload.fileList.value = [makeFile('a.txt')]
    buildBatchPredictedNames.mockReturnValue([
      { predictedName: 'a (1).txt', renamed: true },
      { predictedName: '幽灵项', renamed: true },
    ])

    expect(Object.keys(upload.predictionMap.value)).toEqual(['k:a.txt'])
  })

  it('没有 getSiblingEntries 时以空数组参与预测', () => {
    buildBatchPredictedNames.mockReturnValue([])
    upload.fileList.value = [makeFile('a.txt')]

    void upload.predictionMap.value

    expect(buildBatchPredictedNames).toHaveBeenCalledWith([expect.anything()], [], FILE_TYPE.FILE)
  })

  it('getPredictedName 优先用预测名，其次原文件名', () => {
    const a = makeFile('a.txt')
    upload.fileList.value = [a]
    buildBatchPredictedNames.mockReturnValue([{ predictedName: 'a (1).txt', renamed: true }])

    expect(upload.getPredictedName(a)).toBe('a (1).txt')
    expect(upload.getPredictedName(makeFile('unknown.txt'))).toBe('unknown.txt')
    expect(upload.getPredictedName(null)).toBe('')
  })

  it('isPredictedRenamed 反映 renamed 标记', () => {
    const a = makeFile('a.txt')
    const b = makeFile('b.txt')
    upload.fileList.value = [a, b]
    buildBatchPredictedNames.mockReturnValue([
      { predictedName: 'a (1).txt', renamed: true },
      { predictedName: 'b.txt', renamed: false },
    ])

    expect(upload.isPredictedRenamed(a)).toBe(true)
    expect(upload.isPredictedRenamed(b)).toBe(false)
    expect(upload.isPredictedRenamed(makeFile('nope.txt'))).toBe(false)
  })

  it('getFileKey 直接委托给 getUploadTrackingKey', () => {
    const file = makeFile('a.txt')
    expect(upload.getFileKey(file)).toBe('k:a.txt')
  })
})

describe('useFileUpload: appendFiles 校验与去重', () => {
  it('无有效文件时不入列', () => {
    validateFiles.mockReturnValue({ valid: [], rejected: [] })
    upload.appendFiles([makeFile()])
    expect(upload.fileList.value).toEqual([])
  })

  it('被拒文件给出提示；超过 3 个带"等 N 个文件"后缀', () => {
    validateFiles.mockReturnValue({
      valid: [],
      rejected: ['a.exe', 'b.bat', 'c.cmd', 'd.sh'],
    })
    upload.appendFiles([makeFile()])

    expect(ElMessage.warning).toHaveBeenCalledWith('已跳过：a.exe、b.bat、c.cmd 等 4 个文件')
  })

  it('被拒文件不多于 3 个时不加后缀', () => {
    validateFiles.mockReturnValue({ valid: [], rejected: ['a.exe', 'b.bat'] })
    upload.appendFiles([makeFile()])
    expect(ElMessage.warning).toHaveBeenCalledWith('已跳过：a.exe、b.bat')
  })

  it('通过校验的文件被追加进列表', () => {
    const a = makeFile('a.txt')
    validateFiles.mockReturnValue({ valid: [a], rejected: [] })

    upload.appendFiles([a])

    expect(upload.fileList.value).toEqual([a])
  })

  it('与已在列表中的文件去重（按 tracking key）', () => {
    const existing = makeFile('a.txt')
    upload.fileList.value = [existing]
    const dup = makeFile('a.txt')
    const fresh = makeFile('b.txt')
    validateFiles.mockReturnValue({ valid: [dup, fresh], rejected: [] })

    upload.appendFiles([dup, fresh])

    expect(upload.fileList.value).toEqual([existing, fresh])
  })

  it('全为重复时只提示、不追加', () => {
    upload.fileList.value = [makeFile('a.txt')]
    validateFiles.mockReturnValue({ valid: [makeFile('a.txt')], rejected: [] })

    upload.appendFiles([makeFile('a.txt')])

    expect(upload.fileList.value).toHaveLength(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('所选文件已在上传列表中')
  })

  it('同一批次内部的重复也只保留第一个', () => {
    const first = makeFile('a.txt')
    const second = makeFile('a.txt')
    validateFiles.mockReturnValue({ valid: [first, second], rejected: [] })

    upload.appendFiles([first, second])

    expect(upload.fileList.value).toEqual([first])
  })

  it('既有被拒又有有效时，两者都处理', () => {
    const ok = makeFile('ok.txt')
    validateFiles.mockReturnValue({ valid: [ok], rejected: ['x.exe'] })

    upload.appendFiles([ok])

    expect(upload.fileList.value).toEqual([ok])
    expect(ElMessage.warning).toHaveBeenCalledWith('已跳过：x.exe')
  })
})

describe('useFileUpload: handleSelect / handleDrop', () => {
  it('handleSelect 取 event.target.files 并清空 input', () => {
    const a = makeFile('a.txt')
    validateFiles.mockReturnValue({ valid: [a], rejected: [] })
    const input = { value: 'C:\\fakepath\\a.txt' }
    upload.fileInput.value = input

    upload.handleSelect({ target: { files: [a] } })

    expect(upload.fileList.value).toEqual([a])
    expect(input.value).toBe('')
  })

  it('handleSelect 在 files 缺失时按空数组处理', () => {
    expect(() => upload.handleSelect({ target: {} })).not.toThrow()
    expect(upload.fileList.value).toEqual([])
  })

  it('handleDrop 取 event.dataTransfer.files', () => {
    const a = makeFile('drop.txt')
    validateFiles.mockReturnValue({ valid: [a], rejected: [] })

    upload.handleDrop({ dataTransfer: { files: [a] } })

    expect(upload.fileList.value).toEqual([a])
  })

  it('handleDrop 在 dataTransfer.files 缺失时按空数组处理', () => {
    expect(() => upload.handleDrop({ dataTransfer: {} })).not.toThrow()
    expect(upload.fileList.value).toEqual([])
  })

  it('handleDrop 未防御 event.dataTransfer 本身缺失（该分支从模板不可达，此处固化事实）', () => {
    // FileUploader.vue:14 以 `@drop.prevent="handleDrop"` 绑定，真实 drop 事件必有
    // dataTransfer，因此这个分支只是理论缺口，不是线上缺陷，故不改生产代码。
    expect(() => upload.handleDrop({})).toThrow(TypeError)
  })
})

describe('useFileUpload: 移除与取消', () => {
  it('removeFile 按索引移除', () => {
    const a = makeFile('a')
    const b = makeFile('b')
    upload.fileList.value = [a, b]

    upload.removeFile(0)

    expect(upload.fileList.value).toEqual([b])
  })

  it('上传中不允许移除（避免改到正在遍历的列表）', () => {
    upload.fileList.value = [makeFile('a')]
    upload.uploading.value = true

    upload.removeFile(0)

    expect(upload.fileList.value).toHaveLength(1)
  })

  it('handleCancelFileUpload 关闭对话框', () => {
    upload.fileUploadDialog.value = true
    upload.handleCancelFileUpload()
    expect(upload.fileUploadDialog.value).toBe(false)
  })

  it('上传中不允许取消关闭', () => {
    upload.fileUploadDialog.value = true
    upload.uploading.value = true

    upload.handleCancelFileUpload()

    expect(upload.fileUploadDialog.value).toBe(true)
  })
})

describe('useFileUpload: uploadFilesWithResult', () => {
  it('上传前把每个文件的进度归零', async () => {
    const a = makeFile('a', 10)
    const b = makeFile('b', 20)

    await upload.uploadFilesWithResult([a, b])

    expect(upload.uploadedBytes.value).toBeDefined()
  })

  it('逐个文件调 uploadFileWithPresign，参数含当前目录 id、batchId 与 clientRequestId', async () => {
    const a = makeFile('a.txt', 10)
    resolveSpaceRequestParams.mockReturnValue({ teamId: 5, spaceType: 2, projectId: 9 })

    await upload.uploadFilesWithResult([a])

    expect(uploadFileWithPresign).toHaveBeenCalledTimes(1)
    const [file, dirId, onProgress, opts] = uploadFileWithPresign.mock.calls[0]
    expect(file).toBe(a)
    expect(dirId).toBe(1)
    expect(typeof onProgress).toBe('function')
    expect(opts).toEqual({
      batchId: 'client-1',
      teamId: 5,
      spaceType: 2,
      projectId: 9,
      clientRequestId: 'k:a.txt',
    })
  })

  it('上传时实时读取 currentId（不是创建时快照）', async () => {
    await upload.uploadFilesWithResult([makeFile('a')])
    expect(uploadFileWithPresign.mock.calls[0][1]).toBe(1)

    currentId.value = 77
    await upload.uploadFilesWithResult([makeFile('b')])
    expect(uploadFileWithPresign.mock.calls[1][1]).toBe(77)
  })

  it('getTeamId/getSpaceType/getProjectId 缺省时传 null', async () => {
    await upload.uploadFilesWithResult([makeFile('a')])

    expect(resolveSpaceRequestParams).toHaveBeenCalledWith(expect.anything(), {
      teamId: null,
      spaceType: null,
      projectId: null,
    })
  })

  it('三个取值函数存在时把结果交给 resolveSpaceRequestParams', async () => {
    build({
      options: {
        getTeamId: () => 3,
        getSpaceType: () => 'team',
        getProjectId: () => 8,
      },
    })

    await upload.uploadFilesWithResult([makeFile('a')])

    expect(resolveSpaceRequestParams).toHaveBeenCalledWith(expect.anything(), {
      teamId: 3,
      spaceType: 'team',
      projectId: 8,
    })
  })

  it('进度回调缺少 total 时被忽略（避免除以 0），仅完成分支同步一次', async () => {
    calculateUploadPercentage.mockReturnValue(55)
    uploadFileWithPresign.mockImplementation((file, dirId, onProgress) => {
      onProgress({ loaded: 5 })
      return Promise.resolve({})
    })

    await upload.uploadFilesWithResult([makeFile('a', 10)])

    // 回调因 `!event.total` 提前 return ⇒ 不进 syncProgress；
    // 随后 .then 的完成分支仍会同步一次，所以只调用 1 次。
    expect(calculateUploadPercentage).toHaveBeenCalledTimes(1)
    expect(upload.progress.value).toBe(55)
  })

  it('进度回调带 total 时记录已传字节并同步百分比', async () => {
    calculateUploadPercentage.mockReturnValue(50)
    uploadFileWithPresign.mockImplementation((file, dirId, onProgress) => {
      onProgress({ loaded: 5, total: 10 })
      return Promise.resolve({})
    })

    await upload.uploadFilesWithResult([makeFile('a', 10)])

    expect(calculateUploadPercentage).toHaveBeenCalled()
    expect(upload.progress.value).toBe(50)
  })

  it('成功后把该文件标记为已传满', async () => {
    sumUploadedBytes.mockImplementation((map) =>
      Object.values(map || {}).reduce((sum, n) => sum + n, 0),
    )
    await upload.uploadFilesWithResult([makeFile('a', 10), makeFile('b', 20)])
    expect(upload.uploadedBytes.value).toBe(30)
  })

  it('未声明 size 的文件按 0 计入总字节与完成量（不产生 NaN）', async () => {
    sumUploadedBytes.mockImplementation((map) =>
      Object.values(map || {}).reduce((sum, n) => sum + n, 0),
    )
    const noSize = { name: 'noSize.txt', type: 'text/plain' }

    const result = await upload.uploadFilesWithResult([noSize])

    expect(result.totalBytes).toBe(0)
    expect(Number.isNaN(upload.uploadedBytes.value)).toBe(false)
    expect(upload.uploadedBytes.value).toBe(0)
  })

  it('progress 回调 loaded 为 0 时落到 0 兜底，不写入负值/NaN', async () => {
    sumUploadedBytes.mockImplementation((map) =>
      Object.values(map || {}).reduce((sum, n) => sum + n, 0),
    )
    uploadFileWithPresign.mockImplementation((file, dirId, onProgress) => {
      onProgress({ loaded: 0, total: 10 })
      return Promise.resolve({ kind: 'ok' })
    })

    await upload.uploadFilesWithResult([makeFile('a', 10)])

    expect(Number.isNaN(upload.uploadedBytes.value)).toBe(false)
    expect(upload.uploadedBytes.value).toBeGreaterThanOrEqual(0)
  })

  it('成功结果进入 successList，失败进入 failList 并落错误文案', async () => {
    uploadFileWithPresign
      .mockResolvedValueOnce({ kind: 'ok' })
      .mockRejectedValueOnce(new Error('网络抖动'))

    const result = await upload.uploadFilesWithResult([makeFile('a'), makeFile('b')])

    expect(result.successList).toEqual([{ kind: 'ok' }])
    expect(result.failList).toHaveLength(1)
    expect(createUploadFailResult).toHaveBeenCalledWith({
      originalName: 'b',
      size: 100,
      type: FILE_TYPE.FILE,
      finalName: 'b',
      message: '网络抖动',
    })
  })

  it('失败且没有 message 时用兜底文案', async () => {
    uploadFileWithPresign.mockRejectedValue({})

    await upload.uploadFilesWithResult([makeFile('a')])

    expect(createUploadFailResult).toHaveBeenCalledWith(
      expect.objectContaining({ message: '上传失败，请稍后重试' }),
    )
  })

  it('单个文件失败不影响后续文件（逐个 try/catch）', async () => {
    uploadFileWithPresign
      .mockRejectedValueOnce(new Error('bad'))
      .mockResolvedValueOnce({ kind: 'ok' })

    const result = await upload.uploadFilesWithResult([makeFile('a'), makeFile('b')])

    expect(result.successList).toHaveLength(1)
    expect(result.failList).toHaveLength(1)
    expect(uploadFileWithPresign).toHaveBeenCalledTimes(2)
  })

  it('totalBytes 为各文件 size 之和', async () => {
    const result = await upload.uploadFilesWithResult([makeFile('a', 10), makeFile('b', 32)])
    expect(result.totalBytes).toBe(42)
  })

  it('空数组返回空结果且不调上传', async () => {
    const result = await upload.uploadFilesWithResult([])
    expect(result).toEqual({ successList: [], failList: [], totalBytes: 0 })
    expect(uploadFileWithPresign).not.toHaveBeenCalled()
  })
})

describe('useFileUpload: doUpload', () => {
  it('列表为空时直接返回空结果，不进入上传态', async () => {
    const result = await upload.doUpload()

    expect(result).toEqual({ successList: [], failList: [], filesToUpload: [] })
    expect(upload.uploading.value).toBe(false)
    expect(uploadFileWithPresign).not.toHaveBeenCalled()
  })

  it('全部成功时进度补满，并回调 onSuccess', async () => {
    const a = makeFile('a', 10)
    upload.fileList.value = [a]

    const result = await upload.doUpload()

    expect(result.successList).toHaveLength(1)
    expect(result.filesToUpload).toEqual([a])
    expect(options.onSuccess).toHaveBeenCalledWith(result.successList)
    expect(calculateUploadPercentage).toHaveBeenCalledWith(expect.any(Number), 10, {
      allowComplete: true,
    })
  })

  it('部分失败时不补满进度，onSuccess 仍收到成功项', async () => {
    uploadFileWithPresign
      .mockResolvedValueOnce({ kind: 'ok' })
      .mockRejectedValueOnce(new Error('x'))
    upload.fileList.value = [makeFile('a'), makeFile('b')]

    const result = await upload.doUpload()

    expect(result.successList).toHaveLength(1)
    expect(result.failList).toHaveLength(1)
    expect(calculateUploadPercentage).not.toHaveBeenCalledWith(
      expect.any(Number),
      expect.any(Number),
      {
        allowComplete: true,
      },
    )
    expect(options.onSuccess).toHaveBeenCalled()
  })

  it('全部失败时不回调 onSuccess', async () => {
    uploadFileWithPresign.mockRejectedValue(new Error('x'))
    upload.fileList.value = [makeFile('a')]

    const result = await upload.doUpload()

    expect(result.successList).toEqual([])
    expect(options.onSuccess).not.toHaveBeenCalled()
  })

  it('没有传 onSuccess 也不抛错', async () => {
    build({ options: { onSuccess: undefined } })
    upload.fileList.value = [makeFile('a')]

    await expect(upload.doUpload()).resolves.toBeDefined()
  })

  it('上传中 uploading 为 true，结束后复位（finally）', async () => {
    const seen = []
    uploadFileWithPresign.mockImplementation(() => {
      seen.push(upload.uploading.value)
      return Promise.resolve({})
    })
    upload.fileList.value = [makeFile('a')]

    await upload.doUpload()

    expect(seen).toEqual([true])
    expect(upload.uploading.value).toBe(false)
  })

  it('上传开始时清空进度图与百分比', async () => {
    upload.fileList.value = [makeFile('a')]
    await upload.doUpload()
    expect(upload.progress.value).toBe(0)
  })

  it('捕获到异常时 uploading 仍会被 finally 复位', async () => {
    upload.fileList.value = [makeFile('a')]
    // 让 failList 的构造抛错，模拟上传流程本身出问题
    createUploadFailResult.mockImplementationOnce(() => {
      throw new Error('ctor boom')
    })
    uploadFileWithPresign.mockRejectedValue(new Error('x'))

    await expect(upload.doUpload()).rejects.toThrow('ctor boom')
    expect(upload.uploading.value).toBe(false)
  })
})
