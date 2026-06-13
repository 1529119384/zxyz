import { useClipboard } from '@vueuse/core'

const { copy: vueUseCopy, isSupported } = useClipboard()

export async function copyText(text) {
  if (!isSupported) {
    throw new Error('当前环境不支持剪贴板操作')
  }
  await vueUseCopy(String(text ?? ''))
}
