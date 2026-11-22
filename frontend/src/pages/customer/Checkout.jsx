import { useRef, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, MapPin, MapPinOff } from 'lucide-react'
import { api, apiOrNull } from '../../lib/api'
import { DELIVERY_KM } from '../../brand'
import { useCart, usePlace } from '../../lib/store'
import { km, money, PROVINCES } from '../../lib/format'
import { Button, Card, ErrorNote, Field, Input, ProductArt, Select, Stepper, Textarea } from '../../components/ui'
import { EtaChip } from '../../components/ProductCard'
import MapView from '../../components/MapView'

export default function Checkout() {
  const cart = useCart()
  const { place, setPlace } = usePlace()
  // Does the cart's shop deliver to the current pin? The backend answers 404 when the pin is
  // outside the delivery range (or it has just closed), so check before the order can fail.
  const reach = useQuery({
    queryKey: ['reach', cart.shopId, place.latitude, place.longitude],
    queryFn: () => apiOrNull(`/api/browse/shops/${cart.shopId}/products`, { query: { lat: place.latitude, lng: place.longitude, size: 1 } }),
    enabled: !!cart.shopId,
  })
  const outOfRange = reach.data === null
  const nav = useNavigate()
  const qc = useQueryClient()
  // One key per checkout attempt: retrying after a network error can never create a second order.
  const idempotencyKey = useRef(crypto.randomUUID())
  const [addr, setAddr] = useState({ addressLine: place.addressLine, city: place.city, province: place.province || 'BC', postalCode: place.postalCode })
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [shortLine, setShortLine] = useState(null)

  const placed = useRef(false) // after a successful order the cart empties; don't bounce home before showing the order
  if (!cart.items.length && !placed.current) return <Navigate to="/" replace />
  const set = (k) => (e) => setAddr({ ...addr, [k]: e.target.value })

  const placeOrder = async (e) => {
    e.preventDefault()
    setBusy(true); setError(null); setShortLine(null)
    try {
      const order = await api('/api/orders', {
        method: 'POST',
        body: {
          shopId: cart.shopId,
          idempotencyKey: idempotencyKey.current,
          items: cart.items.map((i) => ({ shopProductId: i.shopProductId, quantity: i.qty })),
          address: { ...addr, postalCode: addr.postalCode.toUpperCase(), latitude: place.latitude, longitude: place.longitude },
          note: note || null,
        },
      })
      placed.current = true
      setPlace(addr)
      cart.clear()
      qc.invalidateQueries({ queryKey: ['orders'] })
      toast.success('Order placed! Waiting for the shop to accept.')
      nav(`/orders/${order.id}`, { replace: true })
    } catch (err) {
      setError(err)
      if (err.body?.shopProductId) setShortLine(err.body.shopProductId) // that line is out of stock
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={placeOrder} className="mx-auto grid max-w-5xl gap-6">
      <Link to="/" className="flex w-fit items-center gap-1 text-sm font-semibold text-muted hover:text-ink"><ArrowLeft className="size-4" />Keep shopping</Link>
      <h1 className="text-3xl font-extrabold">Checkout</h1>
      <div className="grid items-start gap-6 lg:grid-cols-[1fr_380px]">
        <div className="grid gap-6">
          <Card className="grid gap-4 p-5">
            <h2 className="flex items-center gap-2 text-lg font-bold"><MapPin className="size-5 text-brand-500" />Delivery address</h2>
            <MapView height={200} center={[place.latitude, place.longitude]} points={[{ key: 'drop', ...place, color: '#18171c' }]}
              onPick={(p) => setPlace(p)} />
            {outOfRange ? (
              <div className="grid gap-2 rounded-xl bg-brand-50 px-4 py-3 text-sm text-brand-700">
                <span className="flex items-center gap-2 font-semibold"><MapPinOff className="size-4 shrink-0" />The shop in your cart doesn't deliver to this pin</span>
                <span>Shops deliver within {DELIVERY_KM} km. Move the pin closer, or empty your cart and shop from stores near this pin.</span>
                {cart.pin && (
                  <Button size="sm" variant="secondary" className="justify-self-start" onClick={() => setPlace(cart.pin)}>
                    <MapPin className="size-4" />Put the pin back at {cart.pin.label}
                  </Button>
                )}
              </div>
            ) : reach.data?.shop && (
              <p className="text-xs text-muted">The shop is {km(reach.data.shop.distanceKm)} from this pin, about {reach.data.shop.etaMinutes} min away.</p>
            )}
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Street address" className="sm:col-span-2"><Input required value={addr.addressLine} onChange={set('addressLine')} placeholder="77 Main St, Unit 4" /></Field>
              <Field label="City"><Input required value={addr.city} onChange={set('city')} /></Field>
              <Field label="Province"><Select value={addr.province} onChange={set('province')}>{PROVINCES.map((p) => <option key={p}>{p}</option>)}</Select></Field>
              <Field label="Postal code" hint="Like V3T 1A1"><Input required value={addr.postalCode} onChange={set('postalCode')} pattern="[A-Za-z]\d[A-Za-z] ?\d[A-Za-z]\d" /></Field>
            </div>
            <Field label="Note for the driver (optional)"><Textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} placeholder="Ring the bell, side door…" /></Field>
          </Card>
          <Card className="grid gap-3 p-5">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold">{cart.count} item{cart.count > 1 ? 's' : ''}</h2>
              {cart.shop?.etaMinutes && <EtaChip minutes={cart.shop.etaMinutes} />}
            </div>
            <ul className="grid gap-3">
              {cart.items.map((i) => (
                <li key={i.shopProductId} className={`flex items-center gap-3 rounded-xl p-1 ${shortLine === i.shopProductId ? 'bg-brand-50 ring-2 ring-brand-200' : ''}`}>
                  <ProductArt imageUrl={i.imageUrl} name={i.name} category={i.category} className="!w-14 shrink-0" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold">{i.name}</div>
                    <div className="text-xs text-muted">{shortLine === i.shopProductId ? 'Not enough in stock, lower the amount or remove it' : `${money(i.priceCents)} each`}</div>
                  </div>
                  <Stepper size="sm" value={i.qty} onChange={(n) => cart.setQty(i.shopProductId, n)} />
                  <span className="w-16 text-right text-sm font-semibold tabular">{money(i.qty * i.priceCents)}</span>
                </li>
              ))}
            </ul>
          </Card>
        </div>
        <Card className="sticky top-24 grid gap-4 p-5">
          <h2 className="text-lg font-bold">Summary</h2>
          <div className="grid gap-2 text-sm">
            <div className="flex justify-between"><span className="text-muted">Items</span><span className="tabular">{money(cart.subtotal)}</span></div>
            <div className="flex justify-between"><span className="text-muted">Delivery fee</span><span className="text-muted">added when you order</span></div>
          </div>
          <p className="rounded-xl bg-paper px-3 py-2 text-xs text-muted">No payment yet: orders are free to place while payments are being built. If the shop is missing an item, you won't be charged for it.</p>
          <ErrorNote error={error} />
          <Button type="submit" size="lg" loading={busy} disabled={outOfRange}>Place order · {money(cart.subtotal)}+</Button>
        </Card>
      </div>
    </form>
  )
}
