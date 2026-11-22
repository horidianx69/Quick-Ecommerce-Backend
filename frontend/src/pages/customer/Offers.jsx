import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router'
import { ArrowLeft, ChevronRight, Store } from 'lucide-react'
import { api } from '../../lib/api'
import { DELIVERY_KM } from '../../brand'
import { usePlace } from '../../lib/store'
import { km, money } from '../../lib/format'
import { Card, Empty, ErrorNote, ProductArt, Spinner } from '../../components/ui'
import { AddButton, cartItem, EtaChip, shopOf } from '../../components/ProductCard'

// The offers endpoint returns prices only, so the product's name comes from the card the
// customer tapped (router state), or from any browse page already in the cache.
function findProduct(qc, productId) {
  for (const [, data] of qc.getQueriesData({ queryKey: ['feed'] }).concat(qc.getQueriesData({ queryKey: ['aisle'] }))) {
    const hit = data?.pages?.flatMap((p) => p.content).find((c) => c.productId === productId)
    if (hit) return hit
  }
  return null
}

export default function Offers() {
  const { productId } = useParams()
  const { state } = useLocation()
  const qc = useQueryClient()
  const { place } = usePlace()
  const product = state?.product ?? findProduct(qc, Number(productId)) ?? { productId: Number(productId), name: `Product #${productId}` }
  const offers = useQuery({
    queryKey: ['offers', productId, place.latitude, place.longitude],
    queryFn: () => api(`/api/browse/products/${productId}/offers`, { query: { lat: place.latitude, lng: place.longitude } }),
  })
  const list = offers.data ?? []
  const cheapest = list.length ? Math.min(...list.map((o) => o.priceCents)) : null

  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <Link to="/" className="flex w-fit items-center gap-1 text-sm font-semibold text-muted hover:text-ink"><ArrowLeft className="size-4" />Back to shopping</Link>
      <div className="grid gap-6 sm:grid-cols-[260px_1fr]">
        <ProductArt imageUrl={product.imageUrl} name={product.name} category={product.category} className="rounded-3xl" />
        <div className="grid content-start gap-2">
          {product.brand && <div className="text-sm font-semibold uppercase tracking-wide text-muted">{product.brand}</div>}
          <h1 className="text-3xl font-extrabold leading-tight">{product.name}</h1>
          {product.category && <div className="text-sm text-muted">{product.category}</div>}
          <p className="mt-2 text-sm text-muted">Pick a shop. The nearest is usually fastest, another might be cheaper. Your cart can hold items from one shop at a time.</p>
        </div>
      </div>
      <section className="grid gap-3">
        <h2 className="text-xl font-extrabold">Available from {list.length || ''} nearby shop{list.length === 1 ? '' : 's'}</h2>
        <ErrorNote error={offers.error} />
        {offers.isPending ? <Spinner /> : list.length === 0 ? (
          <Empty icon={Store} title="Not available near you right now">No open shop within {DELIVERY_KM} km has this in stock.</Empty>
        ) : list.map((o, i) => (
          <Card key={o.shopProductId} className="flex flex-wrap items-center gap-4 p-4">
            <div className="grid size-11 place-items-center rounded-xl bg-paper"><Store className="size-5" /></div>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2 font-semibold">
                {i === 0 ? 'Nearest shop' : `Shop ${i + 1}`}
                {o.priceCents === cheapest && list.length > 1 && <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-bold text-emerald-800">Lowest price</span>}
              </div>
              <div className="flex items-center gap-2 text-sm text-muted">{km(o.distanceKm)} away <EtaChip minutes={o.etaMinutes} /></div>
              <Link to={`/s/${o.shopId}`} className="mt-1 inline-flex items-center text-xs font-semibold text-brand-600 hover:underline">See everything in this shop<ChevronRight className="size-3.5" /></Link>
            </div>
            <div className="text-lg font-bold tabular">{money(o.priceCents)}</div>
            <AddButton item={cartItem(product, o)} shop={shopOf(o)} />
          </Card>
        ))}
      </section>
    </div>
  )
}
