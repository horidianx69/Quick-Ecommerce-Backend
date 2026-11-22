import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Receipt } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction } from '../../lib/hooks'
import { dateTime, FINAL, money, ORDER_STEPS, STATUS_LABEL, timeAgo } from '../../lib/format'
import { Button, Empty, ErrorNote, Field, Input, Modal, PageHeader, Pager, Select, Spinner, StatusBadge } from '../../components/ui'
import { Table, Td } from './AdminConsole'

const ALL_STATUSES = [...ORDER_STEPS, 'REJECTED', 'CANCELLED', 'EXPIRED']
const INV = [['adminOrders'], ['adminOrder'], ['adminStats']]

function OrderDrawer({ id, onClose }) {
  const order = useQuery({ queryKey: ['adminOrder', id], queryFn: () => api(`/api/admin/orders/${id}`), enabled: !!id, refetchInterval: 5000 })
  const drivers = useQuery({ queryKey: ['adminDrivers', 'APPROVED', 'all'], queryFn: () => api('/api/admin/drivers', { query: { status: 'APPROVED', size: 100 } }), enabled: !!id })
  const [reason, setReason] = useState('')
  const [driverId, setDriverId] = useState('')
  const cancel = useAction(() => api(`/api/admin/orders/${id}/cancel`, { method: 'POST', body: { reason } }), { invalidate: INV, success: 'Order cancelled', onSuccess: () => setReason('') })
  const assign = useAction(() => api(`/api/admin/orders/${id}/assign`, { method: 'POST', body: { driverId: Number(driverId) } }), { invalidate: INV, success: 'Driver assigned' })
  const unassign = useAction(() => api(`/api/admin/orders/${id}/unassign`, { method: 'POST' }), { invalidate: INV, success: 'Driver removed; the job is back on the list' })
  const o = order.data
  const beforePickup = o && ['ACCEPTED', 'READY'].includes(o.status)

  return (
    <Modal side open={!!id} onClose={onClose} title={`Order #${id}`}>
      {!o ? <Spinner /> : (
        <div className="grid gap-5 text-sm">
          <div className="flex flex-wrap items-center gap-2"><StatusBadge status={o.status} /><span className="text-muted">{dateTime(o.createdAt)}</span></div>
          <dl className="grid grid-cols-[90px_1fr] gap-y-1.5">
            <dt className="text-muted">Shop</dt><dd className="font-semibold">{o.shop?.name} <span className="font-normal text-muted">#{o.shop?.id}</span></dd>
            <dt className="text-muted">Customer</dt><dd className="font-semibold">{o.customer?.name} <span className="font-normal text-muted">#{o.customer?.id}</span></dd>
            <dt className="text-muted">Driver</dt><dd className="font-semibold">{o.driver ? <>{o.driver.name} <span className="font-normal text-muted">#{o.driver.id}</span></> : <span className="text-muted">none yet</span>}</dd>
            <dt className="text-muted">Address</dt><dd>{o.address.addressLine}, {o.address.city} {o.address.province} {o.address.postalCode}</dd>
            {o.note && <><dt className="text-muted">Note</dt><dd>{o.note}</dd></>}
            {o.cancelReason && <><dt className="text-muted">Reason</dt><dd className="text-red-700">{o.cancelReason}</dd></>}
          </dl>
          <div className="grid gap-1">
            <div className="grid grid-cols-[1fr_70px_70px] gap-2 text-xs font-semibold uppercase tracking-wider text-muted"><span>Item</span><span className="text-right">Shop</span><span className="text-right">Customer</span></div>
            {o.items.map((i) => (
              <div key={i.id} className={`grid grid-cols-[1fr_70px_70px] gap-2 ${i.unavailable ? 'text-muted line-through' : ''}`}>
                <span>{i.quantity} × {i.name}</span><span className="text-right tabular">{money(i.shopPriceCents)}</span><span className="text-right tabular">{money(i.customerPriceCents)}</span>
              </div>
            ))}
          </div>
          <div className="grid gap-1 rounded-xl bg-paper p-3">
            {[['Subtotal', o.subtotalCents], ['Delivery fee', o.deliveryFeeCents], ['Customer total', o.totalCents], ['Shop payout', o.shopPayoutCents], ['Driver pay', o.driverPayCents]].map(([l, v]) => (
              <div key={l} className="flex justify-between"><span className="text-muted">{l}</span><b className="tabular">{money(v)}</b></div>
            ))}
            <div className="flex justify-between border-t border-line pt-1"><span className="text-muted">Platform keeps</span><b className="tabular">{money(o.totalCents - o.shopPayoutCents - o.driverPayCents)}</b></div>
          </div>

          {beforePickup && (
            <div className="grid gap-2 rounded-xl border border-line p-3">
              <div className="font-semibold">Give to a driver</div>
              <div className="flex gap-2">
                <Select value={driverId} onChange={(e) => setDriverId(e.target.value)} aria-label="Driver">
                  <option value="">Choose an approved driver…</option>
                  {drivers.data?.content.map((d) => <option key={d.userId} value={d.userId}>{d.name}{d.online ? ' · online' : ''}</option>)}
                </Select>
                <Button disabled={!driverId} loading={assign.isPending} onClick={() => assign.mutate()}>Assign</Button>
              </div>
              {o.driver && <Button variant="secondary" size="sm" loading={unassign.isPending} onClick={() => unassign.mutate()}>Take it off {o.driver.name}</Button>}
            </div>
          )}
          {!FINAL.includes(o.status) && (
            <form className="grid gap-2 rounded-xl border border-red-200 p-3" onSubmit={(e) => { e.preventDefault(); cancel.mutate() }}>
              <Field label="Cancel this order" hint={o.status === 'PICKED_UP' ? 'Already picked up: stock will not come back.' : 'Stock goes back to the shop.'}>
                <Input required value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason, shown to the customer and shop" />
              </Field>
              <Button type="submit" variant="danger" loading={cancel.isPending}>Cancel order</Button>
            </form>
          )}
        </div>
      )}
    </Modal>
  )
}

export default function AdminOrders() {
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const needsDriver = params.get('needsDriver') === 'true'
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState(null)
  const orders = useQuery({
    queryKey: ['adminOrders', status, needsDriver, page],
    queryFn: () => api('/api/admin/orders', { query: { status, needsDriver: needsDriver || undefined, page, size: 25 } }),
    refetchInterval: 10_000,
  })
  const setFilter = (k, v) => { const p = new URLSearchParams(params); v ? p.set(k, v) : p.delete(k); setParams(p); setPage(0) }

  return (
    <>
      <PageHeader title="Orders" subtitle="Every order on the platform, newest first."
        actions={<>
          <Select value={status} onChange={(e) => setFilter('status', e.target.value)} className="!w-44" aria-label="Status">
            <option value="">All statuses</option>
            {ALL_STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </Select>
          <Button variant={needsDriver ? 'dark' : 'secondary'} onClick={() => setFilter('needsDriver', needsDriver ? '' : 'true')}>Needs a driver</Button>
        </>} />
      <ErrorNote error={orders.error} />
      {orders.isPending ? <Spinner /> : !orders.data.content.length ? <Empty icon={Receipt} title="No orders match" /> : (
        <>
          <Table head={['#', 'Placed', 'Shop', 'Customer', 'Driver', 'Status', 'Total']}>
            {orders.data.content.map((o) => (
              <tr key={o.id} onClick={() => setOpen(o.id)} className="cursor-pointer hover:bg-paper">
                <Td className="font-semibold">{o.id}</Td>
                <Td className="text-muted">{timeAgo(o.createdAt)}</Td>
                <Td>{o.shop?.name}</Td>
                <Td>{o.customer?.name}</Td>
                <Td className={o.driver ? '' : 'text-muted'}>{o.driver?.name ?? '–'}</Td>
                <Td><StatusBadge status={o.status} /></Td>
                <Td className="font-semibold tabular">{money(o.totalCents)}</Td>
              </tr>
            ))}
          </Table>
          <Pager page={orders.data.page} onPage={setPage} />
        </>
      )}
      <OrderDrawer id={open} onClose={() => setOpen(null)} />
    </>
  )
}
