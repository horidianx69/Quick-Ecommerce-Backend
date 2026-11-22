import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Bike, Inbox, Timer } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction, useNow } from '../../lib/hooks'
import { money, timeAgo } from '../../lib/format'
import { Button, Card, Empty, ErrorNote, PageHeader, Pager, Spinner, StatusBadge, Tabs } from '../../components/ui'

const TABS = [
  { value: 'PLACED', label: 'New' },
  { value: 'ACCEPTED', label: 'Packing' },
  { value: 'READY', label: 'Ready' },
  { value: 'PICKED_UP', label: 'Out for delivery' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: '', label: 'All' },
]
const EXPIRY_MS = 10 * 60_000 // backend expires unanswered orders after 10 minutes

function Countdown({ createdAt }) {
  const now = useNow()
  const left = Math.max(0, new Date(createdAt).getTime() + EXPIRY_MS - now)
  const m = Math.floor(left / 60000), s = Math.floor((left % 60000) / 1000)
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-bold tabular ${left < 120_000 ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-800'}`}>
      <Timer className="size-3.5" />{left ? `${m}:${String(s).padStart(2, '0')} to answer` : 'Expiring…'}
    </span>
  )
}

function OrderCard({ o }) {
  const [missing, setMissing] = useState(new Set())
  const inv = [['shopOrders']]
  const accept = useAction(() => api(`/api/shops/me/orders/${o.id}/accept`, { method: 'POST', body: { unavailableItemIds: [...missing] } }), { invalidate: inv, success: `Order #${o.id} accepted. Start packing!` })
  const reject = useAction(() => api(`/api/shops/me/orders/${o.id}/reject`, { method: 'POST' }), { invalidate: inv, success: `Order #${o.id} rejected, stock returned` })
  const ready = useAction(() => api(`/api/shops/me/orders/${o.id}/ready`, { method: 'POST' }), { invalidate: inv, success: `Order #${o.id} marked ready for pickup` })
  const toggle = (id) => setMissing((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n })
  const payout = o.status === 'PLACED'
    ? o.items.filter((i) => !missing.has(i.id)).reduce((n, i) => n + i.quantity * i.unitPriceCents, 0)
    : o.payoutCents
  const allMissing = missing.size === o.items.length

  return (
    <Card className={`grid gap-4 p-5 ${o.status === 'PLACED' ? 'border-amber-300 ring-4 ring-amber-100' : ''}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-display text-xl font-extrabold">#{o.id}</span>
          <StatusBadge status={o.status} />
          {o.status === 'PLACED' && <Countdown createdAt={o.createdAt} />}
        </div>
        <span className="text-sm text-muted">{timeAgo(o.createdAt)}</span>
      </div>
      <ul className="grid gap-2">
        {o.items.map((i) => (
          <li key={i.id} className="flex items-center gap-3 text-sm">
            {o.status === 'PLACED' && (
              <input type="checkbox" checked={missing.has(i.id)} onChange={() => toggle(i.id)} className="size-4 accent-brand-500" aria-label={`Mark ${i.name} as not in stock`} />
            )}
            <span className={`flex-1 ${missing.has(i.id) || i.unavailable ? 'text-muted line-through' : ''}`}>
              <b className="tabular">{i.quantity} ×</b> {i.name}
            </span>
            <span className="text-muted tabular">{money(i.quantity * i.unitPriceCents)}</span>
          </li>
        ))}
      </ul>
      {o.status === 'PLACED' && <p className="text-xs text-muted">Tick anything you don't have. Those items aren't charged to the customer.</p>}
      {o.note && <p className="rounded-xl bg-paper px-3 py-2 text-sm">Customer note: {o.note}</p>}
      {o.cancelReason && <p className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">Cancelled by admin: {o.cancelReason}</p>}
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
        <div className="text-sm">
          <span className="text-muted">You're paid </span><b className="tabular">{money(payout)}</b>
          {['ACCEPTED', 'READY'].includes(o.status) && (
            <span className={`ml-3 inline-flex items-center gap-1 text-xs font-semibold ${o.driverAssigned ? 'text-emerald-700' : 'text-muted'}`}>
              <Bike className="size-3.5" />{o.driverAssigned ? 'Driver on the way' : 'Finding a driver'}
            </span>
          )}
        </div>
        <div className="flex gap-2">
          {o.status === 'PLACED' && <>
            <Button variant="danger" loading={reject.isPending} onClick={() => reject.mutate()}>Reject</Button>
            <Button variant="success" loading={accept.isPending} disabled={allMissing} onClick={() => accept.mutate()}>Accept order</Button>
          </>}
          {o.status === 'ACCEPTED' && <Button loading={ready.isPending} onClick={() => ready.mutate()}>Packed, ready for pickup</Button>}
        </div>
      </div>
    </Card>
  )
}

export default function ShopOrders() {
  const [status, setStatus] = useState('PLACED')
  const [page, setPage] = useState(0)
  const orders = useQuery({
    queryKey: ['shopOrders', status, page],
    queryFn: () => api('/api/shops/me/orders', { query: { status, page, size: 20 } }),
    refetchInterval: 5000,
  })
  const newCount = useQuery({ queryKey: ['shopOrders', 'PLACED', 'count'], queryFn: () => api('/api/shops/me/orders', { query: { status: 'PLACED', size: 1 } }) })
  const tabs = TABS.map((t) => (t.value === 'PLACED' ? { ...t, count: newCount.data?.page?.totalElements } : t))
  return (
    <>
      <PageHeader title="Orders" subtitle="New orders appear here automatically. Answer within 10 minutes or they expire." />
      <Tabs tabs={tabs} value={status} onChange={(v) => { setStatus(v); setPage(0) }} />
      <ErrorNote error={orders.error} />
      {orders.isPending ? <Spinner /> : !orders.data.content.length ? (
        <Empty icon={Inbox} title={status === 'PLACED' ? 'No new orders' : 'Nothing here'}>{status === 'PLACED' ? "We'll show new orders here as soon as they come in." : 'Orders in this step will show here.'}</Empty>
      ) : (
        <div className="grid gap-4">
          {orders.data.content.map((o) => <OrderCard key={o.id} o={o} />)}
          <Pager page={orders.data.page} onPage={setPage} />
        </div>
      )}
    </>
  )
}
