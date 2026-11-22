import { createContext, useContext, useEffect, useState } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, getToken, setToken } from './api'
import { FullPageSpinner } from '../components/ui'

const AuthContext = createContext(null)

export const homeFor = (role) => ({ CUSTOMER: '/', SHOP: '/shop', DRIVER: '/driver', ADMIN: '/admin' })[role] ?? '/'

export function AuthProvider({ children }) {
  const qc = useQueryClient()
  const [token, setTok] = useState(getToken)
  const me = useQuery({
    queryKey: ['me', token],
    queryFn: () => api('/api/auth/me'),
    enabled: !!token,
    retry: false,
    staleTime: Infinity,
  })

  useEffect(() => {
    const onLogout = () => { setTok(null); qc.clear() }
    window.addEventListener('townline:logout', onLogout)
    return () => window.removeEventListener('townline:logout', onLogout)
  }, [qc])

  const finish = (res) => {
    setToken(res.token)
    qc.clear()
    qc.setQueryData(['me', res.token], res.user)
    setTok(res.token)
    return res.user
  }

  const value = {
    user: token ? me.data ?? null : null,
    loading: !!token && me.isPending,
    login: (email, password) => api('/api/auth/login', { method: 'POST', body: { email, password } }).then(finish),
    signup: (body) => api('/api/auth/signup', { method: 'POST', body }).then(finish),
    logout: () => { setToken(null); setTok(null); qc.clear() },
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export const useAuth = () => useContext(AuthContext)

/** Lets a page through only for one role; others go to their own home, guests to sign in. */
export function RequireRole({ role, children }) {
  const { user, loading } = useAuth()
  const loc = useLocation()
  if (loading) return <FullPageSpinner />
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />
  if (user.role !== role) return <Navigate to={homeFor(user.role)} replace />
  return children
}
