import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { cancelMyShare, fetchMyShareList } from '@/api/share'
import { buildShareMessage } from '@/models/share'
import { copyText } from '@/utils/clipboard'
import { handleBusinessError } from '@/utils/error'

/**
 * 我的分享列表 composable，管理分享记录的加载、分页、复制和取消操作。
 *
 * @returns {{ loading: import('vue').Ref<boolean>, list: import('vue').Ref<Array>, total: import('vue').Ref<number>, page: import('vue').Ref<number>, pageSize: import('vue').Ref<number>, loadList: Function, handleCurrentChange: Function, handleSizeChange: Function, copyShareRecord: Function, cancelShareRecord: Function }} 我的分享列表状态与操作方法。
 */
export function useMyShareList() {
  const loading = ref(false)
  const list = ref([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(10)

  async function loadList() {
    loading.value = true

    try {
      const response = await fetchMyShareList({
        page: page.value,
        pageSize: pageSize.value,
      })

      list.value = response?.data?.rows || []
      total.value = response?.data?.total || 0
    } catch (error) {
      list.value = []
      total.value = 0
      handleBusinessError(error, '加载分享列表失败，请稍后重试')
    } finally {
      loading.value = false
    }
  }

  async function handleCurrentChange(nextPage) {
    page.value = nextPage
    await loadList()
  }

  async function handleSizeChange(nextPageSize) {
    pageSize.value = nextPageSize
    page.value = 1
    await loadList()
  }

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
      await loadList()
    } catch (error) {
      if (error === 'cancel' || error === 'close' || error?.message === 'cancel') {
        return
      }

      handleBusinessError(error, '取消分享失败，请稍后重试')
    }
  }

  onMounted(loadList)

  return {
    loading,
    list,
    total,
    page,
    pageSize,
    loadList,
    handleCurrentChange,
    handleSizeChange,
    copyShareRecord,
    cancelShareRecord,
  }
}
