import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Store } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction } from '../../lib/hooks'
import { DAYS, dayLabel } from '../../lib/format'
import { Button, Empty, ErrorNote, Input, Modal, PageHeader, Pager, Spinner, StatusBadge, Tabs } from '../../components/ui'
import MapView from '../../components/MapView'
import { Table, Td } from './AdminConsole'

const INV = [['adminShops'], ['adminShop'], ['adminStats']]
const ALL_DAY = ['00:00', '23:59:59'] // backend's "open all day": a window can't run past midnight

function StatusButtons({ shop }) {
  const set = useAction((status) => api(`/api/admin/shops/${shop.id}/status`, { method: 'PUT', body: { status } }), {
    invalidate: INV, success: (s) => `${s.name} is now ${s.status.toLowerCase()}`,
  })
  return (
    <div className="flex flex-wrap gap-2">
      {shop.status !== 'APPROVED' && <Button size="sm" variant="success" loading={set.isPending} onClick={() => set.mutate('APPROVED')}>Approve</Button>}
      {shop.status !== 'SUSPENDED' && <Button size="sm" variant="danger" loading={set.isPending} onClick={() => set.mutate('SUSPENDED')}>Suspend</Button>}
      {shop.status === 'SUSPENDED' && <Button size="sm" variant="secondary" loading={set.isPending} onClick={() => set.mutate('PENDING')}>Back to pending</Button>}
    </div>
  )
}

function HoursEditor({ shop }) {
  const toRows = (hours) => DAYS.map((d) => {
    const h = hours.find((x) => x.dayOfWeek === d)
    return { day: d, open: !!h, allDay: h ? h.opens.startsWith('00:00') && h.closes.startsWith('23:59') : false, opens: h?.opens.slice(0, 5) ?? '09:00', closes: h?.closes.slice(0, 5) ?? '21:00' }
  })
  const [rows, setRows] = useState(() => toRows(shop.hours ?? []))
  useEffect(() => setRows(toRows(shop.hours ?? [])), [shop.hours])
  const upd = (i, patch) => setRows((r) => r.map((x, j) => (j === i ? { ...x, ...patch } : x)))
  const save = useAction(() => api(`/api/admin/shops/${shop.id}/hours`, {
    method: 'PUT',
    body: { hours: rows.filter((r) => r.open).map((r) => ({ dayOfWeek: r.day, opens: r.allDay ? ALL_DAY[0] : r.opens, closes: r.allDay ? ALL_DAY[1] : r.closes })) },
  }), { invalidate: INV, success: 'Opening hours saved' })

  return (
    <div className="grid gap-3">
      <div className="flex items-center justify-between">
        <h3 className="font-bold">Opening hours <span className="text-xs font-normal text-muted">({shop.timeZone})</span></h3>
        <Button size="sm" variant="secondary" onClick={() => setRows((r) => r.map((x) => ({ ...x, open: true, allDay: true })))}>Open 24/7</Button>
      </div>
      <div className="grid gap-2">
        {rows.map((r, i) => (
          <div key={r.day} className="grid grid-cols-[56px_auto_1fr] items-center gap-3 text-sm">
            <label className="flex items-center gap-2 font-semibold"><input type="checkbox" checked={r.open} onChange={(e) => upd(i, { open: e.target.checked })} className="accent-brand-500" />{dayLabel(r.day)}</label>
            <label className={`flex items-center gap-1 text-xs ${r.open ? '' : 'opacity-40'}`}><input type="checkbox" disabled={!r.open} checked={r.allDay} onChange={(e) => upd(i, { allDay: e.target.checked })} className="accent-brand-500" />24 h</label>
            {r.open && !r.allDay ? (
              <div className="flex items-center gap-2">
                <Input type="time" value={r.opens} onChange={(e) => upd(i, { opens: e.target.value })} className="!h-9" aria-label={`${r.day} opens`} />
                <span className="text-muted">to</span>
                <Input type="time" value={r.closes} onChange={(e) => upd(i, { closes: e.target.value })} className="!h-9" aria-label={`${r.day} closes`} />
              </div>
            ) : <span className="text-muted">{r.open ? 'All day' : 'Closed'}</span>}
          </div>
        ))}
      </div>
      <Button loading={save.isPending} onClick={() => save.mutate()}>Save hours</Button>
    </div>
  )
}

function ShopDrawer({ id, onClose }) {
  const shop = useQuery({ queryKey: ['adminShop', id], queryFn: () => api(`/api/admin/shops/${id}`), enabled: !!id })
  const s = shop.data
  return (
    <Modal side open={!!id} onClose={onClose} title={s?.name ?? 'Shop'}>
      {!s ? <Spinner /> : (
        <div className="grid gap-5 text-sm">
          <div className="flex items-center justify-between gap-2"><StatusBadge status={s.status} /><StatusButtons shop={s} /></div>
          <div className="grid gap-1">
            <div className="font-semibold">{s.addressLine}</div>
            <div className="text-muted">{s.city}, {s.province} {s.postalCode} · {s.phone}</div>
          </div>
          <MapView height={200} center={[s.latitude, s.longitude]} points={[{ key: 's', latitude: s.latitude, longitude: s.longitude }]} />
          <HoursEditor shop={s} />
        </div>
      )}
    </Modal>
  )
}

export default function AdminShops() {
  const [status, setStatus] = useState('PENDING')
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState(null)
  const shops = useQuery({ queryKey: ['adminShops', status, page], queryFn: () => api('/api/admin/shops', { query: { status, page, size: 25 } }) })
  return (
    <>
      <PageHeader title="Shops" subtitle="Approve new shops and set their opening hours. A shop with no hours is closed to customers." />
      <Tabs value={status} onChange={(v) => { setStatus(v); setPage(0) }} tabs={[{ value: 'PENDING', label: 'Waiting' }, { value: 'APPROVED', label: 'Approved' }, { value: 'SUSPENDED', label: 'Suspended' }, { value: '', label: 'All' }]} />
      <ErrorNote error={shops.error} />
      {shops.isPending ? <Spinner /> : !shops.data.content.length ? <Empty icon={Store} title="No shops here" /> : (
        <>
          <Table head={['Shop', 'Where', 'Phone', 'Hours', 'Status', '']}>
            {shops.data.content.map((s) => (
              <tr key={s.id} className="hover:bg-paper">
                <Td><button onClick={() => setOpen(s.id)} className="font-semibold hover:underline">{s.name}</button><div className="text-xs text-muted">#{s.id}</div></Td>
                <Td>{s.city}, {s.province}</Td>
                <Td className="text-muted">{s.phone}</Td>
                <Td className={s.hours?.length ? '' : 'font-semibold text-brand-600'}>{s.hours?.length ? `${s.hours.length} days` : 'Not set'}</Td>
                <Td><StatusBadge status={s.status} /></Td>
                <Td><div className="flex justify-end gap-2"><StatusButtons shop={s} /><Button size="sm" variant="secondary" onClick={() => setOpen(s.id)}>Manage</Button></div></Td>
              </tr>
            ))}
          </Table>
          <Pager page={shops.data.page} onPage={setPage} />
        </>
      )}
      <ShopDrawer id={open} onClose={() => setOpen(null)} />
    </>
  )
}
