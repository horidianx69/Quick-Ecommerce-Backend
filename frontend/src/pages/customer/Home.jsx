import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Link, useSearchParams } from 'react-router'
import { MapPinOff, SearchX, X, Zap } from 'lucide-react'
import { api } from '../../lib/api'
import { DELIVERY_KM } from '../../brand'
import { usePlace } from '../../lib/store'
import { useAuth } from '../../lib/auth'
import { Button, Empty, ErrorNote, iconFor, Spinner, tintFor } from '../../components/ui'
import ProductCard, { ProductGrid } from '../../components/ProductCard'

export const useCategories = () =>
  useQuery({ queryKey: ['categories'], queryFn: () => api('/api/catalog/categories'), staleTime: 5 * 60_000 })

export function CategoryChips({ categories = [], value, onChange }) {
  return (
    <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 py-1">
      {['', ...categories].map((c) => {
        const Icon = c ? iconFor(c) : Zap
        const active = value === c
        return (
          <button key={c || 'all'} onClick={() => onChange(c)}
            className={`flex h-10 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold transition ${active ? 'border-ink bg-ink text-white' : 'border-line bg-white hover:border-ink/30'}`}>
            <Icon className="size-4" />{c || 'All'}
          </button>
        )
      })}
    </div>
  )
}

/** Infinite browse feed (the backend returns hasMore instead of page totals). */
export function useFeed(path, key, query) {
  return useInfiniteQuery({
    queryKey: [key, path, query],
    queryFn: ({ pageParam }) => api(path, { query: { ...query, page: pageParam, size: 24 } }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
  })
}

/** Dev only: the command that puts demo shops around this pin (see scripts/seed.mjs). */
function SeedHint({ place }) {
  const cmd = `npm run seed -- --near ${place.latitude.toFixed(5)},${place.longitude.toFixed(5)}`
  const copy = () => navigator.clipboard?.writeText(cmd).then(() => toast.success('Copied. Run it in the frontend folder, then refresh.'), () => toast.error('Copy failed. Select the command and copy it.'))
  return (
    <div className="grid w-full max-w-md gap-2 rounded-xl border border-dashed border-line bg-white p-3 text-left text-xs">
      <span className="font-semibold text-muted">Testing locally? Add 3 demo shops around this pin:</span>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 select-all overflow-x-auto whitespace-nowrap rounded-lg bg-paper px-2 py-1.5">{cmd}</code>
        <Button size="sm" variant="secondary" onClick={copy}>Copy</Button>
      </div>
    </div>
  )
}

export default function Home() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const category = params.get('category') ?? ''
  const { place } = usePlace()
  const { user } = useAuth()
  const cats = useCategories()
  const feed = useFeed('/api/browse/products', 'feed', { lat: place.latitude, lng: place.longitude, q, category })
  const cards = feed.data?.pages.flatMap((p) => p.content) ?? []
  const browsing = !q && !category

  const setCategory = (c) => {
    const next = new URLSearchParams(params)
    c ? next.set('category', c) : next.delete('category')
    setParams(next)
  }

  return (
    <div className="grid grid-cols-1 gap-6">
      {browsing && (
        <section className="relative overflow-hidden rounded-3xl bg-ink px-6 py-8 text-white sm:px-10 sm:py-10">
          <div className="relative z-10 grid max-w-xl gap-3">
            <span className="text-lg font-semibold">Hi {user?.name?.split(' ')[0]},</span>
            <h1 className="text-4xl font-extrabold leading-none tracking-tight sm:text-5xl">
              At your doorstep within <span className="text-brand-500">minutes*</span>
            </h1>
            <span className="text-sm font-semibold text-white/70">Delivering to {place.label}</span>
            <span className="text-[11px] text-white/45">*Terms and conditions apply.</span>
          </div>
          <Zap className="absolute -right-6 -top-8 size-56 rotate-12 fill-brand-500/25 text-white/0 sm:size-72" aria-hidden />
        </section>
      )}

      {browsing && cats.data?.length > 0 && (
        <section className="grid gap-3">
          <h2 className="text-xl font-extrabold">Shop by aisle</h2>
          <div className="grid grid-cols-4 gap-3 sm:grid-cols-6 lg:grid-cols-8">
            {cats.data.slice(0, 16).map((c) => {
              const Icon = iconFor(c)
              return (
                <button key={c} onClick={() => setCategory(c)} className="group grid gap-2 text-center">
                  <span className="grid aspect-square place-items-center rounded-2xl transition group-hover:-translate-y-0.5 group-hover:shadow-md" style={{ background: tintFor(c) }}>
                    <Icon className="size-1/3 text-ink/75" strokeWidth={1.6} />
                  </span>
                  <span className="truncate text-xs font-semibold">{c}</span>
                </button>
              )
            })}
          </div>
        </section>
      )}

      <div className="z-30 -mx-4 bg-paper/95 px-4 py-2 backdrop-blur md:sticky md:top-[73px]">
        <CategoryChips categories={cats.data} value={category} onChange={setCategory} />
      </div>

      <section className="grid gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-xl font-extrabold">
            {q ? <>Results for “{q}”</> : category || 'Popular near you'}
          </h2>
          {q && <Link to={category ? `/?category=${encodeURIComponent(category)}` : '/'} className="flex items-center gap-1 text-sm font-semibold text-muted hover:text-ink"><X className="size-4" />Clear search</Link>}
        </div>
        <ErrorNote error={feed.error} />
        {feed.isPending ? (
          <div className="grid place-items-center py-16"><Spinner className="size-7" /></div>
        ) : cards.length === 0 ? (
          browsing
            ? <Empty icon={MapPinOff} title="No shops are open near you right now" action={import.meta.env.DEV && <SeedHint place={place} />}>We show shops within {DELIVERY_KM} km of {place.label} that are open at this moment. Try another location from the top bar, or check back later.</Empty>
            : <Empty icon={SearchX} title="Nothing found">Try a shorter search or another aisle.</Empty>
        ) : (
          <>
            <ProductGrid>{cards.map((c) => <ProductCard key={c.productId} card={c} />)}</ProductGrid>
            {feed.hasNextPage && (
              <Button variant="secondary" className="justify-self-center" loading={feed.isFetchingNextPage} onClick={() => feed.fetchNextPage()}>Show more</Button>
            )}
          </>
        )}
      </section>
    </div>
  )
}
