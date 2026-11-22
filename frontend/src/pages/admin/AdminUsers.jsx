import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search, Users } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction, useDebounced } from '../../lib/hooks'
import { dateTime } from '../../lib/format'
import { Button, Empty, ErrorNote, Input, PageHeader, Pager, Pill, Select, Spinner } from '../../components/ui'
import { Table, Td } from './AdminConsole'

function Toggle({ u }) {
  const set = useAction(() => api(`/api/admin/users/${u.id}/disabled`, { method: 'PUT', body: { disabled: !u.disabled } }), {
    invalidate: [['adminUsers']], success: (r) => `${r.name} ${r.disabled ? 'suspended. Their logins stop working now.' : 'restored'}`,
  })
  if (u.role === 'ADMIN') return null
  return <Button size="sm" variant={u.disabled ? 'secondary' : 'danger'} loading={set.isPending} onClick={() => set.mutate()}>{u.disabled ? 'Restore' : 'Suspend'}</Button>
}

export default function AdminUsers() {
  const [role, setRole] = useState('')
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const [page, setPage] = useState(0)
  const list = useQuery({ queryKey: ['adminUsers', role, dq, page], queryFn: () => api('/api/admin/users', { query: { role, q: dq, page, size: 25 } }) })
  return (
    <>
      <PageHeader title="Users" subtitle="Every account. A suspended account can't sign in, and its current logins stop working."
        actions={<>
          <div className="relative w-64"><Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted" /><Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} placeholder="Email or name" className="pl-9" aria-label="Search users" /></div>
          <Select value={role} onChange={(e) => { setRole(e.target.value); setPage(0) }} className="!w-40" aria-label="Role">
            <option value="">All roles</option><option value="CUSTOMER">Customers</option><option value="SHOP">Shops</option><option value="DRIVER">Drivers</option><option value="ADMIN">Admins</option>
          </Select>
        </>} />
      <ErrorNote error={list.error} />
      {list.isPending ? <Spinner /> : !list.data.content.length ? <Empty icon={Users} title="No users match" /> : (
        <>
          <Table head={['Name', 'Role', 'Joined', 'Account', '']} minWidth={640}>
            {list.data.content.map((u) => (
              <tr key={u.id}>
                <Td><div className="font-semibold">{u.name}</div><div className="text-xs text-muted">{u.email} · #{u.id}</div></Td>
                <Td><Pill className="bg-paper">{u.role.toLowerCase()}</Pill></Td>
                <Td className="text-muted">{dateTime(u.createdAt)}</Td>
                <Td>{u.disabled ? <Pill className="bg-red-100 text-red-800">Suspended</Pill> : <Pill className="bg-emerald-100 text-emerald-800">Active</Pill>}</Td>
                <Td className="text-right"><Toggle u={u} /></Td>
              </tr>
            ))}
          </Table>
          <Pager page={list.data.page} onPage={setPage} />
        </>
      )}
    </>
  )
}
