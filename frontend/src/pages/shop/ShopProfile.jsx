import { useState } from 'react'
import { useOutletContext } from 'react-router'
import { MapPin } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction } from '../../lib/hooks'
import { DAYS, dayLabel, PROVINCES } from '../../lib/format'
import { DEFAULT_PLACE } from '../../lib/store'
import { Button, Card, Field, Input, PageHeader, Select, StatusBadge } from '../../components/ui'
import MapView from '../../components/MapView'

const blank = { name: '', phone: '', addressLine: '', city: '', province: 'BC', postalCode: '', latitude: DEFAULT_PLACE.latitude, longitude: DEFAULT_PLACE.longitude }

export function HoursList({ hours = [] }) {
  return (
    <ul className="grid gap-1 text-sm">
      {DAYS.map((d) => {
        const h = hours.find((x) => x.dayOfWeek === d)
        return (
          <li key={d} className="flex justify-between">
            <span className="font-medium">{dayLabel(d)}</span>
            <span className={h ? 'tabular' : 'text-muted'}>{h ? `${h.opens.slice(0, 5)} – ${h.closes.slice(0, 5)}` : 'Closed'}</span>
          </li>
        )
      })}
    </ul>
  )
}

/** Create or update the shop. Used in owner onboarding and on the profile page. */
export function ShopForm({ shop, submitLabel }) {
  const [f, setF] = useState(() => (shop ? Object.fromEntries(Object.keys(blank).map((k) => [k, shop[k]])) : blank))
  const save = useAction(() => api('/api/shops/me', { method: 'PUT', body: { ...f, postalCode: f.postalCode.toUpperCase() } }), {
    invalidate: [['myShop']],
    success: shop ? 'Shop details saved' : 'Application sent to the QuikE team',
  })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  return (
    <form className="grid gap-4 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
      <Field label="Shop name"><Input required value={f.name} onChange={set('name')} /></Field>
      <Field label="Shop phone"><Input required type="tel" value={f.phone} onChange={set('phone')} placeholder="+1 604 555 0100" /></Field>
      <Field label="Street address" className="sm:col-span-2"><Input required value={f.addressLine} onChange={set('addressLine')} /></Field>
      <Field label="City"><Input required value={f.city} onChange={set('city')} /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Province"><Select value={f.province} onChange={set('province')}>{PROVINCES.map((p) => <option key={p}>{p}</option>)}</Select></Field>
        <Field label="Postal code"><Input required value={f.postalCode} onChange={set('postalCode')} pattern="[A-Za-z]\d[A-Za-z] ?\d[A-Za-z]\d" placeholder="V3T 1A1" /></Field>
      </div>
      <div className="grid gap-2 sm:col-span-2">
        <span className="flex items-center gap-2 text-sm font-medium"><MapPin className="size-4 text-brand-500" />Pin your shop's exact spot</span>
        <MapView height={280} center={[f.latitude, f.longitude]} points={[{ key: 'shop', latitude: f.latitude, longitude: f.longitude }]}
          onPick={(p) => setF((x) => ({ ...x, ...p }))} />
        <span className="text-xs text-muted">Customers never see your address or exact spot, only a distance. Riders see it after taking a job.</span>
      </div>
      <Button type="submit" size="lg" loading={save.isPending} className="sm:col-span-2">{submitLabel ?? (shop ? 'Save changes' : 'Send application')}</Button>
    </form>
  )
}

export default function ShopProfile() {
  const { shop } = useOutletContext()
  return (
    <>
      <PageHeader title={shop.name} subtitle="Your shop's details. Customers only ever see a distance, never your address."
        actions={<StatusBadge status={shop.status} className="!text-sm" />} />
      <div className="grid items-start gap-6 lg:grid-cols-[1fr_320px]">
        <Card className="p-5 sm:p-6"><ShopForm shop={shop} /></Card>
        <Card className="grid gap-3 p-5">
          <h2 className="font-bold">Opening hours</h2>
          <HoursList hours={shop.hours} />
          <p className="text-xs text-muted">Hours are set by the QuikE team. Customers only see your shop while it's open. Times are in {shop.timeZone}.</p>
        </Card>
      </div>
    </>
  )
}
