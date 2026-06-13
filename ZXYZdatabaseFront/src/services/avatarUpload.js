import { getTeamAvatarUploadSign } from '@/api/team'
import { getUserAvatarUploadSign } from '@/api/user'
import { uploadToOss } from '@/utils/oss'
import { resolveExtension } from '@/utils/fileValidation'

export const AVATAR_UPLOAD_TIP =
  '支持 jpg、png、webp，单图最大 5MB；建议使用 200x200 以上正方形图片，推荐压缩到 500KB 内。'

const MAX_AVATAR_SIZE = 5 * 1024 * 1024
const MIN_AVATAR_SIDE = 200
const ALLOWED_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'webp'])
const ALLOWED_CONTENT_TYPES = new Set(['image/jpeg', 'image/jpg', 'image/png', 'image/webp'])

export async function uploadUserAvatar(file, onProgress) {
  return uploadAvatarWithSign(file, (payload) => getUserAvatarUploadSign(payload), onProgress)
}

export async function uploadTeamAvatar(teamId, file, onProgress) {
  return uploadAvatarWithSign(
    file,
    (payload) => getTeamAvatarUploadSign(teamId, payload),
    onProgress,
  )
}

async function uploadAvatarWithSign(file, getUploadSign, onProgress) {
  validateAvatarFile(file)
  await validateAvatarDimensions(file)

  const signResponse = await getUploadSign({
    fileName: file.name,
    fileSize: file.size,
    contentType: file.type || '',
  })
  const signInfo = signResponse?.data || {}
  if (!signInfo.uploadUrl || !signInfo.fileUrl || !signInfo.contentType) {
    throw new Error('头像上传签名返回不完整')
  }

  await uploadToOss(signInfo.uploadUrl, file, {
    contentType: signInfo.contentType,
    contentDisposition: signInfo.contentDisposition,
    onUploadProgress: onProgress,
  })

  return signInfo.fileUrl
}

function validateAvatarFile(file) {
  if (!file) {
    throw new Error('请选择头像文件')
  }
  if (file.size > MAX_AVATAR_SIZE) {
    throw new Error('头像文件不能超过 5MB')
  }
  const extension = resolveExtension(file.name)
  if (!ALLOWED_EXTENSIONS.has(extension)) {
    throw new Error('头像只支持 jpg、png、webp 格式')
  }
  if (file.type && !ALLOWED_CONTENT_TYPES.has(file.type.toLowerCase())) {
    throw new Error('头像只支持 jpg、png、webp 格式')
  }
}

function validateAvatarDimensions(file) {
  if (typeof Image === 'undefined' || typeof URL === 'undefined') {
    return Promise.resolve()
  }

  return new Promise((resolve, reject) => {
    const imageUrl = URL.createObjectURL(file)
    const image = new Image()

    image.onload = () => {
      URL.revokeObjectURL(imageUrl)
      if (image.width < MIN_AVATAR_SIDE || image.height < MIN_AVATAR_SIDE) {
        reject(new Error('头像尺寸不能小于 200x200'))
        return
      }
      resolve()
    }

    image.onerror = () => {
      URL.revokeObjectURL(imageUrl)
      reject(new Error('无法读取头像图片，请更换文件'))
    }

    image.src = imageUrl
  })
}
