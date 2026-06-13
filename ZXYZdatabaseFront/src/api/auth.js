import request from '@/utils/request'

export const login = (payload) => {
  return request.post('/api/users/login', payload)
}

export const register = (payload) => {
  return request.post('/api/users/register', payload)
}

export const fetchCurrentUser = () => {
  return request.get('/api/users/me')
}

export const logout = () => {
  return request.post('/api/users/logout')
}
