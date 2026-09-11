// @ts-check
import request from '@/utils/request'

export const createEmailVerificationCode = () => request.post('/api/users/email/verification-code')

export const createPhoneVerificationCode = () => request.post('/api/users/phone/verification-code')

/**
 * @param {Record<string, unknown>} payload - 验证码校验请求体
 */
export const verifyContact = (payload) => request.post('/api/users/contact/verify', payload)

export const fetchLinkedAccounts = () => request.get('/api/users/linked-accounts')

/**
 * @param {string|number} targetUserId - 目标账号 ID
 * @param {Record<string, unknown>} payload - 信任请求体
 */
export const trustLinkedAccount = (targetUserId, payload) =>
  request.post(`/api/users/linked-accounts/${targetUserId}/trust`, payload)

/**
 * @param {string|number} targetUserId - 目标账号 ID
 */
export const switchLinkedAccount = (targetUserId) =>
  request.post(`/api/users/linked-accounts/${targetUserId}/switch`)
