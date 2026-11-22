import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

/** A button action that calls the API, refreshes the given queries, and toasts the result. */
export function useAction(fn, { invalidate = [], success, onSuccess, onError } = {}) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: (data, vars) => {
      invalidate.forEach((key) => qc.invalidateQueries({ queryKey: key }))
      if (success) toast.success(typeof success === 'function' ? success(data, vars) : success)
      onSuccess?.(data, vars)
    },
    onError: (e, vars) => { if (!onError?.(e, vars)) toast.error(e.message) },
  })
}

export function useDebounced(value, ms = 300) {
  const [v, setV] = useState(value)
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t) }, [value, ms])
  return v
}

/** Re-render every `ms` so "expires in" and "x min ago" labels stay current. */
export function useNow(ms = 1000) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(t) }, [ms])
  return now
}
