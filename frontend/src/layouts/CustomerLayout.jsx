import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate, useSearchParams } from 'react-router'
import { ChevronDown, LogOut, MapPin, Receipt, Search, ShoppingBag, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { useAuth } from '../lib/auth'
import { useCart, usePlace } from '../lib/store'
import { money } from '../lib/format'
import { Button, Empty, Field, Input, Modal, ProductArt, Stepper } from '../components/ui'
import { EtaChip } from '../components/ProductCard'
import MapView from '../components/MapView'
import { BRAND, DELIVERY_KM } from '../brand'

export function Logo({ suffix, to = '/' }) {
  return (
    <Link to={to} className="flex shrink-0 items-center gap-1.5" aria-label={`${BRAND.name} home`}>
      <img src="/brand/quike-logo-header.png" alt={BRAND.name} className="h-12 w-auto" />
      {suffix && <span className="rounded-md bg-ink px-1.5 py-0.5 text-[11px] font-bold uppercase tracking-wider text-white">{suffix}</span>}
    </Link>
  )
}

function SearchBox() {
  const [params] = useSearchParams()
  const nav = useNavigate()
  const [q, setQ] = useState(params.get('q') ?? '')
  useEffect(() => setQ(params.get('q') ?? ''), [params])
  return (
    <form role="search" className="relative w-full" onSubmit={(e) => { e.preventDefault(); nav(q.trim() ? `/?q=${encodeURIComponent(q.trim())}` : '/') }}>
      <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted" />
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search milk, chips, maple syrup…" aria-label="Search products"
        className="h-11 w-full rounded-2xl border border-line bg-paper pl-10 pr-4 text-sm outline-none transition focus:border-brand-500 focus:bg-white focus:ring-4 focus:ring-brand-100" />
    </form>
  )
}

function LocationModal({ open, onClose }) {
  const { place, setPlace } = usePlace()
  const [draft, setDraft] = useState(place)
  useEffect(() => { if (open) setDraft(place) }, [open]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Modal open={open} onClose={onClose} title="Where should we deliver?"
      footer={<Button className="w-full" size="lg" onClick={() => { setPlace(draft); onClose(); toast.success(`Delivering to ${draft.label}`) }}>Deliver here</Button>}>
      <div className="grid gap-4">
        <MapView center={[draft.latitude, draft.longitude]} points={[{ key: 'me', ...draft, color: '#18171c' }]}
          onPick={(p) => setDraft((d) => ({ ...d, ...p }))} />
        <Field label="Name this place" hint={`Shops within ${DELIVERY_KM} km of the pin that are open right now will show up.`}>
          <Input value={draft.label} onChange={(e) => setDraft({ ...draft, label: e.target.value })} />
        </Field>
      </div>
    </Modal>
  )
}

function CartDrawer() {
  const cart = useCart()
  const nav = useNavigate()
  return (
    <Modal side open={cart.open} onClose={() => cart.setOpen(false)} title="Your cart"
      footer={cart.items.length > 0 && (
        <div className="grid gap-3">
          <div className="flex justify-between text-sm"><span className="text-muted">Item total</span><span className="font-semibold tabular">{money(cart.subtotal)}</span></div>
          <div className="flex justify-between text-sm"><span className="text-muted">Delivery fee</span><span className="text-muted">shown at checkout</span></div>
          <Button size="lg" className="w-full" onClick={() => { cart.setOpen(false); nav('/checkout') }}>Go to checkout · {money(cart.subtotal)}</Button>
        </div>
      )}>
      {cart.items.length === 0 ? (
        <Empty icon={ShoppingBag} title="Your cart is empty">Add a few things and they'll show up here.</Empty>
      ) : (
        <div className="grid gap-4">
          <div className="flex items-center justify-between rounded-2xl bg-paper px-4 py-3">
            <div className="text-sm"><div className="font-semibold">From one local shop</div><div className="text-muted">{cart.shop?.distanceKm?.toFixed(1)} km away</div></div>
            {cart.shop?.etaMinutes && <EtaChip minutes={cart.shop.etaMinutes} />}
          </div>
          <ul className="grid gap-3">
            {cart.items.map((i) => (
              <li key={i.shopProductId} className="flex items-center gap-3">
                <ProductArt imageUrl={i.imageUrl} name={i.name} category={i.category} className="!w-14 shrink-0" />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">{i.name}</div>
                  <div className="text-xs text-muted tabular">{money(i.priceCents)} each</div>
                </div>
                <Stepper size="sm" value={i.qty} onChange={(n) => cart.setQty(i.shopProductId, n)} />
              </li>
            ))}
          </ul>
          <Button variant="ghost" size="sm" className="justify-self-start text-muted" onClick={cart.clear}><Trash2 className="size-4" />Empty cart</Button>
        </div>
      )}
    </Modal>
  )
}

export default function CustomerLayout() {
  const { user, logout } = useAuth()
  const { place } = usePlace()
  const cart = useCart()
  const [locOpen, setLocOpen] = useState(false)
  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-40 border-b border-line bg-white/95 backdrop-blur">
        {/* Left corner: logo. Middle: delivery point, search, cart. Right corner: orders and sign out. */}
        <div className="flex items-center gap-6 px-4 py-3 lg:gap-10 lg:px-8">
          <div className="hidden shrink-0 sm:block"><Logo /></div>
          <div className="flex min-w-0 flex-1 flex-wrap items-center justify-center gap-x-4 gap-y-3">
            <button onClick={() => setLocOpen(true)} className="flex min-w-0 shrink-0 items-center gap-2 rounded-xl px-2 py-1 text-left hover:bg-black/5">
              <MapPin className="size-5 shrink-0 text-brand-500" />
              <span className="min-w-0">
                <span className="block text-[11px] font-semibold uppercase tracking-wide text-muted">Delivering to</span>
                <span className="flex items-center gap-1 truncate text-sm font-bold">{place.label}<ChevronDown className="size-4" /></span>
              </span>
            </button>
            <div className="order-last w-full md:order-none md:w-auto md:max-w-2xl md:flex-1"><SearchBox /></div>
            <Button onClick={() => cart.setOpen(true)} className="ml-auto !rounded-2xl md:ml-0">
              <ShoppingBag className="size-4" />
              {cart.count ? <span className="tabular">{cart.count} · {money(cart.subtotal)}</span> : 'Cart'}
            </Button>
          </div>
          <nav className="hidden shrink-0 items-center gap-1 sm:flex">
            <NavLink to="/orders" className="flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-semibold hover:bg-black/5"><Receipt className="size-4" />Orders</NavLink>
            <button onClick={() => { logout(); toast('Signed out') }} title={`Sign out ${user?.name ?? ''}`} className="flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-semibold hover:bg-black/5"><LogOut className="size-4" />Sign out</button>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 pb-24 pt-5"><Outlet /></main>
      <nav className="fixed inset-x-0 bottom-0 z-40 flex border-t border-line bg-white sm:hidden" style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}>
        <NavLink to="/" end className={({ isActive }) => `flex-1 py-3 text-center text-xs font-semibold ${isActive ? 'text-brand-600' : 'text-muted'}`}>Shop</NavLink>
        <NavLink to="/orders" className={({ isActive }) => `flex-1 py-3 text-center text-xs font-semibold ${isActive ? 'text-brand-600' : 'text-muted'}`}>Orders</NavLink>
        <button onClick={() => { logout(); toast('Signed out') }} className="flex-1 py-3 text-center text-xs font-semibold text-muted">Sign out</button>
      </nav>
      <CartDrawer />
      <LocationModal open={locOpen} onClose={() => setLocOpen(false)} />
    </div>
  )
}
