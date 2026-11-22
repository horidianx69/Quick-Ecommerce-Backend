import { Link } from 'react-router'
import { toast } from 'sonner'
import { Store, Zap } from 'lucide-react'
import { useCart } from '../lib/store'
import { money } from '../lib/format'
import { ProductArt, Stepper } from './ui'

/** What goes into the cart from a browse card or an offer. */
export const cartItem = (product, offer) => ({
  shopProductId: offer.shopProductId,
  productId: product.productId,
  name: product.name,
  brand: product.brand,
  imageUrl: product.imageUrl,
  category: product.category,
  priceCents: offer.priceCents,
})
export const shopOf = (offer) => ({ shopId: offer.shopId, distanceKm: offer.distanceKm, etaMinutes: offer.etaMinutes })

export function AddButton({ item, shop, wide = false }) {
  const cart = useCart()
  const qty = cart.qtyOf(item.shopProductId)
  if (qty) return <Stepper value={qty} onChange={(n) => cart.setQty(item.shopProductId, n)} />
  const add = () => {
    if (cart.add(item, shop)) return
    toast('Your cart is from another shop', {
      description: 'Each order comes from one shop. Start a new cart with this item?',
      action: { label: 'Start new cart', onClick: () => cart.add(item, shop, true) },
    })
  }
  return (
    <button onClick={add}
      className={`h-9 rounded-xl border-2 border-brand-500 bg-white px-4 text-sm font-extrabold tracking-wide text-brand-600 transition hover:bg-brand-50 active:scale-95 ${wide ? 'w-full' : ''}`}>
      ADD
    </button>
  )
}

export function EtaChip({ minutes, className = '' }) {
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-full bg-ink px-2 py-0.5 text-[11px] font-bold text-white ${className}`}>
      <Zap className="size-3 fill-white" />{minutes} min
    </span>
  )
}

export default function ProductCard({ card }) {
  const { offer } = card
  return (
    <div className="flex flex-col gap-2 rounded-2xl border border-line bg-white p-2.5 transition hover:shadow-lg hover:shadow-ink/5">
      <Link to={`/p/${card.productId}`} state={{ product: card }} className="relative block">
        <ProductArt imageUrl={card.imageUrl} name={card.name} category={card.category} />
        <EtaChip minutes={offer.etaMinutes} className="absolute left-2 top-2" />
      </Link>
      <div className="grid min-h-[3.4rem] content-start gap-0.5 px-0.5">
        {card.brand && <div className="truncate text-[11px] font-medium uppercase tracking-wide text-muted">{card.brand}</div>}
        <Link to={`/p/${card.productId}`} state={{ product: card }} className="line-clamp-2 text-sm font-semibold leading-snug hover:underline">{card.name}</Link>
      </div>
      <div className="mt-auto flex items-center justify-between gap-2 px-0.5">
        <span className="font-bold tabular">{money(offer.priceCents)}</span>
        <AddButton item={cartItem(card, offer)} shop={shopOf(offer)} />
      </div>
      {card.otherShops > 0 && (
        <Link to={`/p/${card.productId}`} state={{ product: card }} className="flex items-center gap-1 px-0.5 text-xs font-medium text-brand-600 hover:underline">
          <Store className="size-3.5" />{card.otherShops} more shop{card.otherShops > 1 ? 's' : ''} nearby
        </Link>
      )}
    </div>
  )
}

export function ProductGrid({ children }) {
  return <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">{children}</div>
}
