import { ElMessage, ElMessageBox } from 'element-plus'

import { cancelMyShare, fetchMyShareList } from '@/api/share'
import { usePagedList } from '@/composables/usePagedList'
import { buildShareMessage } from '@/models/share'
import { copyText } from '@/utils/clipboard'
import { handleBusinessError } from '@/utils/error'

/**
 * 我的分享列表 composable，管理分享记录的加载、分页、复制和取消操作。
 *
 * 分页骨架（状态 / 翻页事件 / 失败兜底）自 07-P1-4 起来自 usePagedList，
 * 这里只保留取数口径与业务动作 —— 此前它与 useRecycleBinList 各写了一份逐字重复的骨架。
 *
 * @returns {{ loading: import('vue').Ref<boolean>, list: import('vue').Ref<any[]>, total: import('vue').Ref<number>, page: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, loadList: Function, handleCurrentChange: Function, handleSizeChange: Function, copyShareRecord: Function, cancelShareRecord: Function }} 我的分享列表状态与操作方法。
 */
export function useMyShareList() {
  const paged = usePagedList(
    async ({ page, pageSize }) => {
      const response = await fetchMyShareList({ page, pageSize })

      // 后端信封的归一化在 models/share.js（list 优先、兼容旧的 rows）里做完了。
      return { list: response?.data?.list, total: response?.data?.total }
    },
    {
      context: 'myShare',
      errorMessage: '加载分享列表失败，请稍后重试',
      immediate: true,
    },
  )

  async function copyShareRecord(record) {
    try {
      await copyText(buildShareMessage(record.shareUrl))
      ElMessage.success('分享文案已复制')
    } catch (error) {
      handleBusinessError(error, '复制分享文案失败，请稍后重试')
    }
  }

  async function cancelShareRecord(record) {
    try {
      await ElMessageBox.confirm('取消后分享链接将失效，是否继续？', '取消分享', {
        type: 'warning',
        confirmButtonText: '确认取消',
        cancelButtonText: '再想想',
      })

      await cancelMyShare(record.shareId)
      ElMessage.success('取消分享成功')
      await paged.refresh()
    } catch (error) {
      if (error === 'cancel' || error === 'close' || error?.message === 'cancel') {
        return
      }

      handleBusinessError(error, '取消分享失败，请稍后重试')
    }
  }

  return {
    loading: paged.loading,
    list: paged.list,
    total: paged.total,
    page: paged.page,
    pageSize: paged.pageSize,
    loadList: paged.refresh,
    handleCurrentChange: paged.handleCurrentChange,
    handleSizeChange: paged.handleSizeChange,
    copyShareRecord,
    cancelShareRecord,
  }
}
