import request from '@/utils/request'

export const createEmailVerificationCode = () => request.post('/api/users/email/verification-code')

export const createPhoneVerificationCode = () => request.post('/api/users/phone/verification-code')

export const verifyContact = (payload) => request.post('/api/users/contact/verify', payload)

export const fetchLinkedAccounts = () => request.get('/api/users/linked-accounts')

export const trustLinkedAccount = (targetUserId, payload) =>
  request.post(`/api/users/linked-accounts/${targetUserId}/trust`, payload)

export const switchLinkedAccount = (targetUserId) =>
  request.post(`/api/users/linked-accounts/${targetUserId}/switch`)
