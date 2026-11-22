import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Bike, Check, ChevronRight, KeyRound, Receipt } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction } from '../../lib/hooks'
import { dateTime, FINAL, money, ORDER_STEPS, timeAgo } from '../../lib/format'
import { Button, Card, Empty, ErrorNote, FullPageSpinner, Modal, Pager, StatusBadge } from '../../components/ui'
import MapView from '../../components/MapView'

export function OrderList() {
  const [page, setPage] = useState(0)
  const orders = useQuery({ queryKey: ['orders', page], queryFn: () => api('/api/orders', { query: { page, size: 20 } }), refetchInterval: 15_000 })
  return (
    <div className="mx-auto grid max-w-3xl gap-5">
      <h1 className="text-3xl font-extrabold">Your orders</h1>
      <ErrorNote error={orders.error} />
      {orders.isPending ? <FullPageSpinner /> : !orders.data.content.length ? (
        <Empty icon={Receipt} title="No orders yet" action={<Link to="/"><Button>Start shopping</Button></Link>}>Your orders and their delivery codes will show up here.</Empty>
      ) : (
        <>
          {orders.data.content.map((o) => (
            <Link key={o.id} to={`/orders/${o.id}`}>
              <Card className="flex items-center gap-4 p-4 transition hover:shadow-md">
                <div className="grid size-11 place-items-center rounded-xl bg-paper"><Receipt className="size-5" /></div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2 font-semibold">{o.shopName} <StatusBadge status={o.status} /></div>
                  <div className="truncate text-sm text-muted">#{o.id} · {o.items.reduce((n, i) => n + i.quantity, 0)} items · {timeAgo(o.createdAt)}</div>
                </div>
                <div className="font-bold tabular">{money(o.totalCents)}</div>
                <ChevronRight className="size-5 text-muted" />
              </Card>
            </Link>
          ))}
          <Pager page={orders.data.page} onPage={setPage} />
        </>
      )}
    </div>
  )
}

const HEADLINE = {
  PLACED: ['Waiting for the shop', 'The shop has 10 minutes to accept your order.'],
  ACCEPTED: ['The shop is packing your order', 'A rider is heading to the shop.'],
  READY: ['Packed and waiting for the rider', 'Your rider will pick it up any moment.'],
  PICKED_UP: ['On the way to you', 'Have your delivery code ready.'],
  DELIVERED: ['Delivered', 'Enjoy! Thanks for shopping local.'],
  REJECTED: ['The shop could not take this order', "You haven't been charged."],
  CANCELLED: ['Order cancelled', "You haven't been charged."],
  EXPIRED: ["The shop didn't answer in time", "The order expired and you haven't been charged."],
}
const STEP_LABEL = { PLACED: 'Placed', ACCEPTED: 'Accepted', READY: 'Packed', PICKED_UP: 'On the way', DELIVERED: 'Delivered' }

function Progress({ status }) {
  const at = ORDER_STEPS.indexOf(status)
  if (at < 0) return null
  return (
    <ol className="grid grid-cols-5 gap-1">
      {ORDER_STEPS.map((s, i) => (
        <li key={s} className="grid gap-2">
          <div className={`h-1.5 rounded-full ${i <= at ? 'bg-brand-500' : 'bg-line'} ${i === at && status !== 'DELIVERED' ? 'animate-pulse' : ''}`} />
          <span className={`text-[11px] font-semibold sm:text-xs ${i <= at ? 'text-ink' : 'text-muted'}`}>{STEP_LABEL[s]}</span>
        </li>
      ))}
    </ol>
  )
}

export function OrderDetail() {
  const { id } = useParams()
  const [confirmCancel, setConfirmCancel] = useState(false)
  const order = useQuery({
    queryKey: ['order', id],
    queryFn: () => api(`/api/orders/${id}`),
    refetchInterval: (q) => (q.state.data && FINAL.includes(q.state.data.status) ? false : 5000),
  })
  const status = order.data?.status
  const tracking = useQuery({
    queryKey: ['tracking', id],
    queryFn: () => api(`/api/orders/${id}/tracking`),
    enabled: status === 'PICKED_UP',
    refetchInterval: 4000, // driver pings every few seconds
  })
  const cancel = useAction(() => api(`/api/orders/${id}/cancel`, { method: 'POST' }), {
    invalidate: [['order', id], ['orders']], success: 'Order cancelled', onSuccess: () => setConfirmCancel(false),
  })

  if (order.isPending) return <FullPageSpinner />
  if (order.error) return <ErrorNote error={order.error} />
  const o = order.data
  const [title, sub] = HEADLINE[o.status]
  const driver = tracking.data?.driver
  const drop = o.address

  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <Link to="/orders" className="flex w-fit items-center gap-1 text-sm font-semibold text-muted hover:text-ink"><ArrowLeft className="size-4" />All orders</Link>
      <Card className="grid gap-5 p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="grid gap-1">
            <div className="text-sm text-muted">Order #{o.id} from {o.shopName} · {dateTime(o.createdAt)}</div>
            <h1 className="text-2xl font-extrabold sm:text-3xl">{title}</h1>
            <p className="text-sm text-muted">{o.cancelReason ? `Reason: ${o.cancelReason}` : sub}</p>
          </div>
          <StatusBadge status={o.status} />
        </div>
        <Progress status={o.status} />
        {['ACCEPTED', 'READY', 'PICKED_UP'].includes(o.status) && (
          <div className="flex items-center gap-2 text-sm font-semibold">
            <Bike className="size-4 text-brand-500" />
            {o.driverName ? `Your rider: ${o.driverName}` : 'Finding a rider near the shop…'}
          </div>
        )}
        {driver && (
          <div className="grid gap-3">
            <div className="flex items-center gap-2 font-semibold"><Bike className="size-5 text-brand-500" />Your rider is about {driver.etaMinutes} min away</div>
            <MapView height={300} center={[driver.latitude, driver.longitude]} points={[
              { key: 'drop', ...drop, color: '#18171c', label: 'You' },
              { key: 'driver', latitude: driver.latitude, longitude: driver.longitude, label: 'Rider', permanent: true },
            ]} />
          </div>
        )}
      </Card>

      <div className="grid items-start gap-6 md:grid-cols-[1fr_300px]">
        <Card className="grid gap-3 p-5">
          <h2 className="text-lg font-bold">Items</h2>
          <ul className="divide-y divide-line">
            {o.items.map((i) => (
              <li key={i.id} className={`flex justify-between gap-3 py-2 text-sm ${i.unavailable ? 'text-muted line-through' : ''}`}>
                <span>{i.quantity} × {i.name}{i.unavailable && <span className="ml-2 text-xs no-underline">(shop was out, not charged)</span>}</span>
                <span className="tabular">{money(i.quantity * i.unitPriceCents)}</span>
              </li>
            ))}
          </ul>
          <div className="grid gap-1 border-t border-line pt-3 text-sm">
            <div className="flex justify-between"><span className="text-muted">Items</span><span className="tabular">{money(o.subtotalCents)}</span></div>
            <div className="flex justify-between"><span className="text-muted">Delivery</span><span className="tabular">{money(o.deliveryFeeCents)}</span></div>
            <div className="flex justify-between text-base font-bold"><span>Total</span><span className="tabular">{money(o.totalCents)}</span></div>
          </div>
        </Card>
        <div className="grid gap-4">
          {!FINAL.includes(o.status) && o.deliveryCode && (
            <Card className="grid gap-2 border-0 bg-ink p-5 text-white">
              <div className="flex items-center gap-2 text-sm text-white/70"><KeyRound className="size-4" />Delivery code</div>
              <div className="font-display text-5xl font-extrabold tracking-[0.25em] text-white tabular">{o.deliveryCode}</div>
              <p className="text-xs text-white/60">Tell this to your rider at the door. Don't share it before they arrive.</p>
            </Card>
          )}
          {o.status === 'DELIVERED' && <Card className="flex items-center gap-3 p-5 font-semibold text-emerald-700"><Check className="size-5" />Delivered</Card>}
          <Card className="grid gap-1 p-5 text-sm">
            <div className="font-semibold">Delivering to</div>
            <div className="text-muted">{drop.addressLine}<br />{drop.city}, {drop.province} {drop.postalCode}</div>
            {o.note && <div className="mt-2 text-muted">Note: {o.note}</div>}
          </Card>
          {o.status === 'PLACED' && <Button variant="danger" onClick={() => setConfirmCancel(true)}>Cancel order</Button>}
        </div>
      </div>

      <Modal open={confirmCancel} onClose={() => setConfirmCancel(false)} title="Cancel this order?"
        footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={() => setConfirmCancel(false)}>Keep it</Button><Button loading={cancel.isPending} onClick={() => cancel.mutate()}>Yes, cancel</Button></div>}>
        <p className="text-sm text-muted">You can cancel until the shop accepts. Nothing has been charged.</p>
      </Modal>
    </div>
  )
}
