import request, { UPLOAD_REQUEST_TIMEOUT } from '@/utils/request'

export const fetchUserSettings = () => request.get('/api/users/settings')

export const updateUserSettings = (data) => request.patch('/api/users/settings', data)

export const getUserAvatarUploadSign = (data) =>
  request.post('/api/users/avatar/upload-sign', data, {
    timeout: UPLOAD_REQUEST_TIMEOUT,
  })

export const changePassword = (data) => request.patch('/api/users/password', data)

export const bindEmail = (data) => request.patch('/api/users/email', data)

export const bindPhone = (data) => request.patch('/api/users/phone', data)

export const setDefaultTeam = (data) => request.patch('/api/users/default-team', data)

export const searchUsers = (keyword) =>
  request.get('/api/users/search', {
    params: { keyword },
  })
