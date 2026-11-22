import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Bike } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction } from '../../lib/hooks'
import { Button, Empty, ErrorNote, PageHeader, Pager, Spinner, StatusBadge, Tabs } from '../../components/ui'
import { Table, Td } from './AdminConsole'

function Actions({ d }) {
  const set = useAction((status) => api(`/api/admin/drivers/${d.userId}/status`, { method: 'PUT', body: { status } }), {
    invalidate: [['adminDrivers'], ['adminStats']], success: (_, s) => `${d.name} ${s === 'APPROVED' ? 'approved' : 'suspended'}`,
  })
  return (
    <div className="flex justify-end gap-2">
      {d.status !== 'APPROVED' && <Button size="sm" variant="success" loading={set.isPending} onClick={() => set.mutate('APPROVED')}>Approve</Button>}
      {d.status !== 'SUSPENDED' && <Button size="sm" variant="danger" loading={set.isPending} onClick={() => set.mutate('SUSPENDED')}>Suspend</Button>}
    </div>
  )
}

export default function AdminDrivers() {
  const [params] = useSearchParams()
  const [status, setStatus] = useState(params.get('status') ?? 'PENDING')
  const [page, setPage] = useState(0)
  const list = useQuery({ queryKey: ['adminDrivers', status, page], queryFn: () => api('/api/admin/drivers', { query: { status, page, size: 25 } }), refetchInterval: 10_000 })
  return (
    <>
      <PageHeader title="Drivers" subtitle="Suspending a driver takes them offline at once." />
      <Tabs value={status} onChange={(v) => { setStatus(v); setPage(0) }} tabs={[{ value: 'PENDING', label: 'Waiting' }, { value: 'APPROVED', label: 'Approved' }, { value: 'SUSPENDED', label: 'Suspended' }]} />
      <ErrorNote error={list.error} />
      {list.isPending ? <Spinner /> : !list.data.content.length ? <Empty icon={Bike} title="No drivers here" /> : (
        <>
          <Table head={['Driver', 'Phone', 'Online', 'Status', '']} minWidth={640}>
            {list.data.content.map((d) => (
              <tr key={d.userId}>
                <Td><div className="font-semibold">{d.name}</div><div className="text-xs text-muted">{d.email} · #{d.userId}</div></Td>
                <Td className="text-muted">{d.phone}</Td>
                <Td>{d.online ? <span className="font-semibold text-emerald-700">● Online</span> : <span className="text-muted">Offline</span>}</Td>
                <Td><StatusBadge status={d.status} /></Td>
                <Td><Actions d={d} /></Td>
              </tr>
            ))}
          </Table>
          <Pager page={list.data.page} onPage={setPage} />
        </>
      )}
    </>
  )
}
