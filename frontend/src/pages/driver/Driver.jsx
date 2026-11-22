import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Clock, KeyRound, LocateFixed, LogOut, MapPin, Navigation, Package, Phone, Power, Store, Wallet } from 'lucide-react'
import { api, apiOrNull } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { useAction } from '../../lib/hooks'
import { DEFAULT_PLACE } from '../../lib/store'
import { dateTime, km, money, timeAgo } from '../../lib/format'
import { Button, Card, Empty, Field, FullPageSpinner, Input, Spinner, StatusBadge, Tabs } from '../../components/ui'
import { Logo } from '../../layouts/CustomerLayout'
import MapView from '../../components/MapView'
import ApplicationStatus, { OnboardingShell, OnboardingSteps } from '../../components/ApplicationStatus'

const PING_MS = 5000 // backend allows 60 pings a minute; every 5 s is plenty
const directions = (s) => `https://www.google.com/maps/dir/?api=1&destination=${s.latitude},${s.longitude}`

function Shell({ children, driver }) {
  const { user, logout } = useAuth()
  return (
    <div className="min-h-screen bg-paper">
      <header className="sticky top-0 z-40 border-b border-line bg-white">
        <div className="mx-auto flex max-w-lg items-center justify-between gap-3 px-4 py-3">
          <Logo suffix="Rider" to="/driver" />
          <div className="flex items-center gap-2">
            {driver?.status === 'APPROVED' && (
              <span className={`flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold ${driver.online ? 'bg-emerald-100 text-emerald-800' : 'bg-stone-100 text-muted'}`}>
                <span className={`size-2 rounded-full ${driver.online ? 'animate-pulse bg-emerald-500' : 'bg-stone-400'}`} />{driver.online ? 'Online' : 'Offline'}
              </span>
            )}
            <button onClick={logout} className="grid size-9 place-items-center rounded-xl hover:bg-black/5" aria-label={`Sign out ${user?.name}`}><LogOut className="size-4" /></button>
          </div>
        </div>
      </header>
      <main className="mx-auto grid max-w-lg grid-cols-1 gap-4 px-4 py-5">{children}</main>
    </div>
  )
}

function PhoneForm({ driver }) {
  const [phone, setPhone] = useState(driver?.phone ?? '')
  const save = useAction(() => api('/api/drivers/me', { method: 'PUT', body: { phone } }), {
    invalidate: [['driver']], success: driver ? 'Phone updated' : 'Application sent to the QuikE team',
  })
  return (
    <form className="grid gap-3" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
      <Field label="Phone number" hint="Shops and support may call you during a delivery."><Input required type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+1 604 555 0142" /></Field>
      <Button type="submit" loading={save.isPending}>{driver ? 'Update phone' : 'Send application'}</Button>
    </form>
  )
}

function StopCard({ title, stop, icon: Icon, children }) {
  return (
    <div className="grid gap-2 rounded-2xl bg-paper p-4">
      <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-muted"><Icon className="size-4" />{title}</div>
      <div className="font-semibold">{stop.name}</div>
      <div className="text-sm text-muted">{stop.addressLine}, {stop.city} {stop.postalCode}</div>
      <div className="flex flex-wrap gap-2">
        <a href={directions(stop)} target="_blank" rel="noreferrer"><Button size="sm" variant="dark"><Navigation className="size-4" />Directions</Button></a>
        {stop.phone && <a href={`tel:${stop.phone}`}><Button size="sm" variant="secondary"><Phone className="size-4" />{stop.phone}</Button></a>}
      </div>
      {children}
    </div>
  )
}

function CurrentDelivery({ d, pos }) {
  const [code, setCode] = useState('')
  const inv = [['delivery'], ['jobs'], ['deliveries']]
  const release = useAction(() => api('/api/drivers/me/delivery/release', { method: 'POST' }), { invalidate: inv, success: 'Job released back to the list' })
  const pickedUp = useAction(() => api('/api/drivers/me/delivery/picked-up', { method: 'POST' }), { invalidate: inv, success: 'Picked up. Head to the customer.' })
  const delivered = useAction(() => api('/api/drivers/me/delivery/delivered', { method: 'POST', body: { code } }), {
    invalidate: inv, success: (r) => `Delivered! You earned ${money(r.payCents)}`, onError: () => { setCode('') },
  })
  const stop = d.status === 'PICKED_UP' ? d.dropoff : d.pickup
  return (
    <Card className="grid gap-4 p-5">
      <div className="flex items-center justify-between">
        <div><div className="text-sm text-muted">Order #{d.orderId} · {d.units} unit{d.units > 1 ? 's' : ''}</div><h2 className="text-xl font-extrabold">{d.status === 'PICKED_UP' ? 'Deliver to customer' : 'Pick up from shop'}</h2></div>
        <div className="text-right"><div className="text-xs text-muted">You earn</div><div className="text-lg font-extrabold tabular">{money(d.payCents)}</div></div>
      </div>
      <MapView height={220} center={[stop?.latitude ?? pos.latitude, stop?.longitude ?? pos.longitude]} zoom={13} points={[
        { key: 'me', ...pos, color: '#2563eb', label: 'You' },
        d.pickup && d.status !== 'PICKED_UP' && { key: 'shop', ...d.pickup, label: 'Shop' },
        d.dropoff && { key: 'drop', ...d.dropoff, color: '#18171c', label: 'Customer' },
      ].filter(Boolean)} />

      {d.status !== 'PICKED_UP' ? (
        <>
          <StopCard title="Pickup" stop={d.pickup} icon={Store} />
          <div className="flex items-center justify-between gap-3 rounded-2xl bg-ink px-4 py-3 text-white">
            <span className="text-sm text-white/70">At the counter, tell the shop</span>
            <span className="font-display text-2xl font-extrabold">Order #{d.orderId}</span>
          </div>
          <div className="rounded-xl bg-brand-50 px-3 py-2 text-sm">Drop-off in {d.dropoffCity}, {km(d.dropKm)} from the shop. You'll see the address after pickup.</div>
          {d.status === 'ACCEPTED' && <p className="flex items-center gap-2 text-sm text-muted"><Clock className="size-4" />The shop is still packing. You can pick up once it's marked ready.</p>}
          <Button size="lg" disabled={d.status !== 'READY'} loading={pickedUp.isPending} onClick={() => pickedUp.mutate()}><Package className="size-5" />I've picked it up</Button>
          <Button variant="ghost" size="sm" loading={release.isPending} onClick={() => release.mutate()}>Can't make it? Give the job back</Button>
        </>
      ) : (
        <>
          <StopCard title="Drop-off" stop={d.dropoff} icon={MapPin}>
            {d.note && <div className="rounded-xl bg-white px-3 py-2 text-sm">Note: {d.note}</div>}
          </StopCard>
          <form className="grid gap-2" onSubmit={(e) => { e.preventDefault(); delivered.mutate() }}>
            <Field label="Customer's 4-digit code" hint="Ask the customer to read it from their app. 5 tries.">
              <Input required inputMode="numeric" pattern="\d{4}" maxLength={4} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                className="!h-14 text-center font-display text-3xl font-extrabold tracking-[0.4em]" placeholder="••••" />
            </Field>
            <Button type="submit" size="lg" variant="success" loading={delivered.isPending} disabled={code.length !== 4}><KeyRound className="size-5" />Complete delivery</Button>
          </form>
        </>
      )}
    </Card>
  )
}

function Jobs() {
  const jobs = useQuery({ queryKey: ['jobs'], queryFn: () => api('/api/drivers/me/jobs'), refetchInterval: 5000 })
  const accept = useAction((id) => api(`/api/drivers/me/jobs/${id}/accept`, { method: 'POST' }), {
    invalidate: [['jobs'], ['delivery']], success: 'Job is yours. Head to the shop!',
  })
  if (jobs.isPending) return <Spinner />
  if (!jobs.data?.length) return <Empty icon={Package} title="No jobs nearby yet">Jobs from shops within 8 km of you appear here as soon as a shop accepts an order. This list refreshes by itself.</Empty>
  return (
    <div className="grid gap-3">
      <h2 className="font-bold">{jobs.data.length} job{jobs.data.length > 1 ? 's' : ''} near you</h2>
      {jobs.data.map((j) => (
        <Card key={j.orderId} className="grid gap-3 p-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <div className="text-2xl font-extrabold tabular">{money(j.payCents)}</div>
              <div className="text-sm text-muted">{j.units} unit{j.units > 1 ? 's' : ''} · to {j.dropoffCity} · {timeAgo(j.createdAt)}</div>
            </div>
            <StatusBadge status={j.status} />
          </div>
          <div className="grid grid-cols-2 gap-2 text-sm">
            <div className="rounded-xl bg-paper px-3 py-2"><div className="text-xs text-muted">To the shop</div><b className="tabular">{km(j.pickupKm)}</b></div>
            <div className="rounded-xl bg-paper px-3 py-2"><div className="text-xs text-muted">Shop to customer</div><b className="tabular">{km(j.dropKm)}</b></div>
          </div>
          <Button loading={accept.isPending && accept.variables === j.orderId} disabled={accept.isPending} onClick={() => accept.mutate(j.orderId)}>Accept job</Button>
        </Card>
      ))}
    </div>
  )
}

function Earnings() {
  const list = useQuery({ queryKey: ['deliveries'], queryFn: () => api('/api/drivers/me/deliveries', { query: { size: 100 } }) })
  if (list.isPending) return <Spinner />
  const rows = list.data.content
  const today = rows.filter((r) => new Date(r.deliveredAt).toDateString() === new Date().toDateString())
  return (
    <div className="grid gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Card className="p-4"><div className="text-xs text-muted">Today</div><div className="text-2xl font-extrabold tabular">{money(today.reduce((n, r) => n + r.payCents, 0))}</div><div className="text-xs text-muted">{today.length} deliveries</div></Card>
        <Card className="p-4"><div className="text-xs text-muted">All time</div><div className="text-2xl font-extrabold tabular">{money(rows.reduce((n, r) => n + r.payCents, 0))}</div><div className="text-xs text-muted">{list.data.page.totalElements} deliveries</div></Card>
      </div>
      {!rows.length ? <Empty icon={Wallet} title="No deliveries yet">Finished deliveries and your pay show here.</Empty> : (
        <Card className="divide-y divide-line">
          {rows.map((r) => (
            <div key={r.orderId} className="flex items-center justify-between px-4 py-3 text-sm">
              <div><div className="font-semibold">Order #{r.orderId}</div><div className="text-xs text-muted">{dateTime(r.deliveredAt)} · {r.units} units</div></div>
              <b className="tabular">{money(r.payCents)}</b>
            </div>
          ))}
        </Card>
      )}
    </div>
  )
}

function Work({ driver }) {
  const qc = useQueryClient()
  const [pos, setPos] = useState(() => driver.latitude != null
    ? { latitude: driver.latitude, longitude: driver.longitude }
    : { latitude: DEFAULT_PLACE.latitude, longitude: DEFAULT_PLACE.longitude })
  const [gps, setGps] = useState(false)
  const [tab, setTab] = useState('work')
  const posRef = useRef(pos)
  posRef.current = pos

  // Follow the phone's GPS when switched on; otherwise the driver sets the position on the map (handy for testing on a laptop).
  useEffect(() => {
    if (!gps) return
    if (!navigator.geolocation) { toast.error('This device has no location access'); setGps(false); return }
    const id = navigator.geolocation.watchPosition(
      (p) => setPos({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
      () => { toast.error('Location permission denied'); setGps(false) },
      { enableHighAccuracy: true },
    )
    return () => navigator.geolocation.clearWatch(id)
  }, [gps])

  const ping = () => api('/api/drivers/me/location', { method: 'POST', body: posRef.current }).catch((e) => {
    if (e.status === 409) qc.invalidateQueries({ queryKey: ['driver'] }) // not online any more: stop pinging
  })
  useEffect(() => {
    if (!driver.online) return
    const t = setInterval(ping, PING_MS)
    return () => clearInterval(t)
  }, [driver.online]) // eslint-disable-line react-hooks/exhaustive-deps

  const online = useAction(() => api('/api/drivers/me/online', { method: 'POST', body: posRef.current }), { invalidate: [['driver'], ['jobs']], success: "You're online. Jobs near you will show up below." })
  const offline = useAction(() => api('/api/drivers/me/offline', { method: 'POST' }), { invalidate: [['driver']], success: "You're offline" })
  const delivery = useQuery({ queryKey: ['delivery'], queryFn: () => apiOrNull('/api/drivers/me/delivery'), refetchInterval: 5000 })

  return (
    <>
      <Card className={`grid gap-4 p-5 ${driver.online ? 'border-emerald-300' : ''}`}>
        <div className="flex items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-extrabold">{driver.online ? "You're online" : 'Ready to drive?'}</h1>
            <p className="text-sm text-muted">{driver.online ? 'Your position is shared every few seconds.' : 'Go online to see jobs within 8 km.'}</p>
          </div>
          {driver.online
            ? <Button variant="secondary" loading={offline.isPending} disabled={!!delivery.data} onClick={() => offline.mutate()} title={delivery.data ? 'Finish your delivery first' : ''}><Power className="size-4" />Go offline</Button>
            : <Button variant="success" size="lg" loading={online.isPending} onClick={() => online.mutate()}><Power className="size-5" />Go online</Button>}
        </div>
        <MapView height={180} zoom={13} center={[pos.latitude, pos.longitude]} points={[{ key: 'me', ...pos, color: '#2563eb' }]}
          onPick={gps ? undefined : (p) => { setPos(p); posRef.current = p; if (driver.online) ping() }} />
        <label className="flex items-center gap-2 text-sm font-medium">
          <input type="checkbox" checked={gps} onChange={(e) => setGps(e.target.checked)} className="size-4 accent-brand-500" />
          <LocateFixed className="size-4" />Use this device's GPS
        </label>
      </Card>

      <Tabs value={tab} onChange={setTab} tabs={[{ value: 'work', label: 'Jobs' }, { value: 'earnings', label: 'Earnings' }]} />
      {tab === 'earnings' ? <Earnings /> : delivery.isPending ? <Spinner /> : delivery.data ? (
        <CurrentDelivery d={delivery.data} pos={pos} />
      ) : driver.online ? <Jobs /> : (
        <Empty icon={Power} title="You're offline">Go online to start getting jobs.</Empty>
      )}
    </>
  )
}

export default function Driver() {
  const profile = useQuery({ queryKey: ['driver'], queryFn: () => apiOrNull('/api/drivers/me'), refetchInterval: 15_000 })
  const d = profile.data
  if (profile.isPending) return <FullPageSpinner />

  // Step 2 of rider sign-up: details. Saving them sends the application.
  if (!d) {
    return (
      <OnboardingShell>
        <OnboardingSteps step={1} kind="rider" />
        <Card className="grid gap-4 p-6 sm:p-8">
          <div className="grid gap-1">
            <h1 className="text-2xl font-extrabold">Your rider details</h1>
            <p className="text-sm text-muted">The QuikE team reviews every rider. You'll see the status on the next screen.</p>
          </div>
          <PhoneForm />
        </Card>
      </OnboardingShell>
    )
  }
  if (d.status !== 'APPROVED') {
    return (
      <OnboardingShell>
        <ApplicationStatus kind="rider" status={d.status} name={`Phone ${d.phone}`}>
          <details className="rounded-2xl border border-line p-4">
            <summary className="cursor-pointer text-sm font-semibold">Update your phone number</summary>
            <div className="mt-4"><PhoneForm driver={d} /></div>
          </details>
        </ApplicationStatus>
      </OnboardingShell>
    )
  }
  return <Shell driver={d}><Work driver={d} /></Shell>
}
