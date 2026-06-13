import { computed } from 'vue'
import { ElMessage } from 'element-plus'

import { useArchiveDownload } from '@/composables/useArchiveDownload'
import { normalizeArchiveName } from '@/utils/archive/backendArchive'
import {
  collectPublicShareArchiveEntries,
  getDefaultShareArchiveName,
} from '@/utils/archive/shareArchive'
import { joinPath } from '@/utils/pathUtils'

/**
 * @typedef {Object} UseShareArchiveDownloadOptions
 * @property {import('vue').Ref<string>} [shareKey] - 分享链接的 key。
 * @property {import('vue').Ref<string>} [currentPath] - 当前浏览的分享目录路径。
 */

/**
 * 公开分享页打包下载，基于 useArchiveDownload 封装分享场景的参数解析。
 *
 * @param {UseShareArchiveDownloadOptions} [options={}] - 配置项。
 * @returns {ReturnType<typeof useArchiveDownload>} 打包下载状态与操作方法。
 */
export function useShareArchiveDownload(options = {}) {
  const { shareKey, currentPath } = options

  const normalizedShareKey = computed(() => String(shareKey?.value || ''))
  const normalizedCurrentPath = computed(() => String(currentPath?.value || ''))

  return useArchiveDownload({
    resolveOpenState: (payload) => {
      if (!payload) {
        return {
          opened: true,
          defaultName: getDefaultShareArchiveName(normalizedCurrentPath.value),
          context: {
            path: normalizedCurrentPath.value,
            name: normalizedCurrentPath.value
              ? getDefaultShareArchiveName(normalizedCurrentPath.value)
              : '当前目录',
            type: 'current',
          },
        }
      }

      const targetPath = joinPath(normalizedCurrentPath.value, payload.fileName, {
        leadingSlash: false,
        decode: false,
      })

      return {
        opened: true,
        defaultName: payload.fileName || 'share-download',
        context: {
          path: targetPath,
          name: payload.fileName,
          type: 'folder',
        },
      }
    },
    buildRunnerOptions: (archiveName, context) => {
      const archivePath = context?.path || ''
      const archiveTargetName = context?.name || '当前目录'
      const archiveTargetType = context?.type || 'current'

      return {
        collectEntries: () =>
          collectPublicShareArchiveEntries(normalizedShareKey.value, archivePath),
        archiveName: normalizeArchiveName(archiveName),
        onEmpty: () => {
          ElMessage.warning(
            archiveTargetType === 'folder'
              ? `文件夹“${archiveTargetName}”中没有可打包下载的文件`
              : '当前目录没有可打包下载的文件',
          )
        },
        onSuccess: () => {
          ElMessage.success(
            archiveTargetType === 'folder'
              ? `文件夹“${archiveTargetName}”打包下载已开始`
              : '当前目录打包下载已开始',
          )
        },
      }
    },
  })
}
