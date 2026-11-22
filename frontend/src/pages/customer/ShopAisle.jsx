import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ArrowLeft, Search, SearchX, Store } from 'lucide-react'
import { usePlace } from '../../lib/store'
import { useDebounced } from '../../lib/hooks'
import { km } from '../../lib/format'
import { Button, Empty, ErrorNote, Input, Spinner } from '../../components/ui'
import ProductCard, { EtaChip, ProductGrid } from '../../components/ProductCard'
import { CategoryChips, useCategories, useFeed } from './Home'

/** Browse or search inside one shop. Shops stay anonymous to customers until they order. */
export default function ShopAisle() {
  const { shopId } = useParams()
  const { place } = usePlace()
  const [q, setQ] = useState('')
  const [category, setCategory] = useState('')
  const dq = useDebounced(q)
  const cats = useCategories()
  const feed = useFeed(`/api/browse/shops/${shopId}/products`, 'aisle', { lat: place.latitude, lng: place.longitude, q: dq, category })
  const shop = feed.data?.pages[0]?.shop
  const cards = feed.data?.pages.flatMap((p) => p.content) ?? []

  return (
    <div className="grid grid-cols-1 gap-5">
      <Link to="/" className="flex w-fit items-center gap-1 text-sm font-semibold text-muted hover:text-ink"><ArrowLeft className="size-4" />All shops</Link>
      <div className="flex flex-wrap items-center gap-4 rounded-3xl bg-ink p-6 text-white">
        <div className="grid size-14 place-items-center rounded-2xl bg-white/10"><Store className="size-7 text-brand-500" /></div>
        <div className="flex-1">
          <h1 className="text-2xl font-extrabold">Local shop</h1>
          {shop && <div className="flex items-center gap-2 text-sm text-white/70">{km(shop.distanceKm)} away <EtaChip minutes={shop.etaMinutes} /></div>}
        </div>
        <div className="relative w-full sm:w-72">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search this shop" className="!h-11 pl-9 text-ink" aria-label="Search this shop" />
        </div>
      </div>
      <CategoryChips categories={cats.data} value={category} onChange={setCategory} />
      <ErrorNote error={feed.error} />
      {feed.isPending ? <Spinner /> : cards.length === 0 ? (
        <Empty icon={SearchX} title="Nothing here">This shop has nothing matching right now, or it has just closed.</Empty>
      ) : (
        <>
          <ProductGrid>{cards.map((c) => <ProductCard key={c.productId} card={c} />)}</ProductGrid>
          {feed.hasNextPage && <Button variant="secondary" className="justify-self-center" loading={feed.isFetchingNextPage} onClick={() => feed.fetchNextPage()}>Show more</Button>}
        </>
      )}
    </div>
  )
}
