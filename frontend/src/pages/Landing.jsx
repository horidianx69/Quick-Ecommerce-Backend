import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ArrowRight, Bike, ChevronLeft, ChevronRight, Clock, KeyRound, MapPin, Moon, Plus, ShoppingBasket, Store, Zap } from 'lucide-react'
import { Logo } from '../layouts/CustomerLayout'
import { iconFor, ProductArt } from '../components/ui'
import { money } from '../lib/format'
import { BRAND, DELIVERY_KM } from '../brand'

// Guests can't call the browse API (it needs a customer login), so the landing page shows a fixed showcase.
// Every product click goes to sign in.
const SHOWCASE = [
  ['2% Milk 4 L', 'Dairyland', 'Dairy', 899], ['Ketchup Chips 235 g', "Lay's", 'Snacks', 524], ['Maple Syrup Amber 1 L', 'Kirkland', 'Pantry', 1799],
  ['Ginger Ale 12 × 355 mL', 'Canada Dry', 'Drinks', 1049], ['Coffee Crisp 4-pack', 'Nestlé', 'Snacks', 674], ['Hass Avocados 4-pack', 'Fresh', 'Produce', 749],
  ['Vanilla Ice Cream 1.5 L', "Chapman's", 'Frozen', 824], ['Montreal-Style Bagels 6', 'St-Viateur', 'Bakery', 899], ['Old Cheddar 400 g', 'Black Diamond', 'Dairy', 1049],
  ['Kraft Dinner Original 6-pack', 'Kraft', 'Pantry', 1124], ['Paper Towels 6 rolls', 'Bounty', 'Household', 1649], ['Cold Brew Coffee 1 L', 'Tim Hortons', 'Drinks', 899],
  ['Rising Crust Pepperoni Pizza', 'Delissio', 'Frozen', 1199], ['Bananas, bunch', 'Fresh', 'Produce', 299], ['Large Eggs, dozen', 'Burnbrae Farms', 'Dairy', 674],
  ['Poutine Gravy Mix', 'St-Hubert', 'Pantry', 344],
].map(([name, brand, category, priceCents]) => ({ name, brand, category, priceCents }))
const AISLES = ['Dairy', 'Snacks', 'Drinks', 'Produce', 'Frozen', 'Bakery', 'Pantry', 'Household', 'Personal care']

const SLIDES = [
  { tone: 'red', icon: Zap, eyebrow: 'Fast', title: 'Milk ran out? Back in minutes.', text: `Shops within ${DELIVERY_KM} km, a local rider, and your order at the door before the kettle cools.`, cta: 'Start shopping', to: '/signup' },
  { tone: 'black', icon: Moon, eyebrow: 'Late night', title: 'Snack attack at 11 pm.', text: "Chips, ice cream and cold drinks from the corner stores that are still open near you.", cta: 'See what’s open', to: '/signup' },
  { tone: 'white', icon: Store, eyebrow: 'Local', title: 'Every order supports a shop down the street.', text: 'Independent Canadian shops set their own prices and keep their own customers.', cta: 'Sell on QuikE', to: '/signup/owner' },
]
const TONE = {
  red: 'bg-brand-500 text-white',
  black: 'bg-ink text-white',
  white: 'bg-white text-ink border-2 border-ink',
}

function LandingNav() {
  return (
    <header className="sticky top-0 z-40 border-b border-line bg-white/95 backdrop-blur">
      <div className="mx-auto flex max-w-7xl items-center gap-6 px-4 py-3">
        <Logo to="/" />
        <nav className="hidden items-center gap-5 text-sm font-semibold text-ink/80 md:flex">
          <a href="#aisles" className="hover:text-ink">Shop</a>
          <a href="#how" className="hover:text-ink">How it works</a>
          <Link to="/signup/owner" className="hover:text-ink">For shops</Link>
          <Link to="/signup/rider" className="hover:text-ink">Ride with us</Link>
        </nav>
        <div className="ml-auto flex items-center gap-2">
          <Link to="/signup" className="hidden h-10 items-center rounded-xl px-4 text-sm font-semibold hover:bg-black/5 sm:inline-flex">Sign up</Link>
          <Link to="/login" className="inline-flex h-10 items-center rounded-xl bg-brand-500 px-5 text-sm font-semibold text-white hover:bg-brand-600">Sign in</Link>
        </div>
      </div>
    </header>
  )
}

function Hero() {
  return (
    <section className="mx-auto grid max-w-7xl items-center gap-10 px-4 pb-12 pt-10 lg:grid-cols-[1.05fr_1fr] lg:pt-16">
      <div className="grid gap-6">
        <span className="inline-flex w-fit items-center gap-2 rounded-full bg-ink px-3 py-1 text-xs font-bold uppercase tracking-wider text-white"><Clock className="size-3.5" />Need it? QuikE it.</span>
        <h1 className="text-5xl font-extrabold leading-[0.95] tracking-tight sm:text-6xl lg:text-7xl">
          Canada’s corner store,<br /><span className="text-brand-500">at your door.</span>
        </h1>
        <p className="max-w-xl text-lg text-muted">{BRAND.tagline} Groceries, snacks and household basics from independent shops near you, brought by a local rider.</p>
        <div className="flex flex-wrap gap-3">
          <Link to="/signup" className="inline-flex h-12 items-center gap-2 rounded-2xl bg-brand-500 px-6 font-semibold text-white shadow-lg shadow-brand-500/25 hover:bg-brand-600">Start shopping <ArrowRight className="size-4" /></Link>
          <Link to="/login" className="inline-flex h-12 items-center rounded-2xl border-2 border-ink px-6 font-semibold hover:bg-ink hover:text-white">Sign in</Link>
        </div>
        <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted">
          <span className="flex items-center gap-2"><MapPin className="size-4 text-brand-500" />Shops within {DELIVERY_KM} km</span>
          <span className="flex items-center gap-2"><KeyRound className="size-4 text-brand-500" />4-digit code at the door</span>
        </div>
      </div>
      <div className="relative isolate" aria-hidden>
        <div className="absolute inset-6 -z-10 rotate-3 rounded-[2.5rem] bg-brand-500" />
        <div className="grid grid-cols-2 gap-4 rounded-[2.5rem] bg-ink p-5 sm:p-7">
          {SHOWCASE.slice(0, 4).map((p, i) => (
            <div key={p.name} className={`grid gap-2 rounded-2xl bg-white p-3 ${i % 2 ? 'translate-y-4' : ''}`}>
              <ProductArt name={p.name} category={p.category} />
              <div className="truncate text-xs font-semibold">{p.name}</div>
              <div className="flex items-center justify-between"><b className="text-sm tabular">{money(p.priceCents)}</b><span className="grid size-7 place-items-center rounded-lg bg-brand-500 text-white"><Plus className="size-4" /></span></div>
            </div>
          ))}
          <div className="col-span-2 mt-3 flex items-center justify-between rounded-2xl bg-white/10 px-4 py-3 text-white">
            <span className="text-sm">Your rider is on the way</span>
            <span className="flex items-center gap-1 rounded-full bg-brand-500 px-3 py-1 text-sm font-bold"><Zap className="size-4 fill-white" />13 min</span>
          </div>
        </div>
      </div>
    </section>
  )
}

function Carousel() {
  const [i, setI] = useState(0)
  const [paused, setPaused] = useState(false)
  useEffect(() => {
    if (paused || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const t = setInterval(() => setI((n) => (n + 1) % SLIDES.length), 5000)
    return () => clearInterval(t)
  }, [paused])
  const go = (d) => setI((n) => (n + d + SLIDES.length) % SLIDES.length)
  return (
    <section className="mx-auto max-w-7xl px-4 py-6" aria-roledescription="carousel" aria-label="What QuikE does"
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onFocus={() => setPaused(true)} onBlur={() => setPaused(false)}>
      <div className="overflow-hidden rounded-[2rem]">
        <div className="flex transition-transform duration-500 ease-out" style={{ transform: `translateX(-${i * 100}%)` }}>
          {SLIDES.map((s, n) => {
            const Icon = s.icon
            return (
              <div key={s.title} className={`flex w-full shrink-0 flex-wrap items-center justify-between gap-6 rounded-[2rem] px-7 py-10 sm:px-12 sm:py-14 ${TONE[s.tone]}`}
                role="group" aria-roledescription="slide" aria-label={`${n + 1} of ${SLIDES.length}`} aria-hidden={n !== i}>
                <div className="grid max-w-2xl gap-3">
                  <span className="text-xs font-bold uppercase tracking-[0.2em] opacity-70">{s.eyebrow}</span>
                  <h2 className="text-3xl font-extrabold leading-tight sm:text-5xl">{s.title}</h2>
                  <p className="max-w-xl opacity-80">{s.text}</p>
                  <Link to={s.to} tabIndex={n === i ? 0 : -1}
                    className={`mt-2 inline-flex h-11 w-fit items-center gap-2 rounded-xl px-5 font-semibold ${s.tone === 'white' ? 'bg-ink text-white' : 'bg-white text-ink'}`}>
                    {s.cta} <ArrowRight className="size-4" />
                  </Link>
                </div>
                <Icon className="hidden size-40 opacity-90 md:block" strokeWidth={1.2} />
              </div>
            )
          })}
        </div>
      </div>
      <div className="mt-4 flex items-center justify-center gap-3">
        <button onClick={() => go(-1)} className="grid size-9 place-items-center rounded-full border border-line hover:border-ink" aria-label="Previous slide"><ChevronLeft className="size-4" /></button>
        {SLIDES.map((s, n) => (
          <button key={s.title} onClick={() => setI(n)} aria-label={`Show slide ${n + 1}`} aria-current={n === i}
            className={`h-2.5 rounded-full transition-all ${n === i ? 'w-8 bg-brand-500' : 'w-2.5 bg-line hover:bg-muted'}`} />
        ))}
        <button onClick={() => go(1)} className="grid size-9 place-items-center rounded-full border border-line hover:border-ink" aria-label="Next slide"><ChevronRight className="size-4" /></button>
      </div>
    </section>
  )
}

function ShowcaseCard({ p }) {
  const nav = useNavigate()
  return (
    <button onClick={() => nav('/login')} className="grid w-44 shrink-0 gap-2 rounded-2xl border border-line bg-white p-2.5 text-left transition hover:-translate-y-1 hover:shadow-lg sm:w-48"
      aria-label={`${p.name}, ${money(p.priceCents)}. Sign in to order`}>
      <ProductArt name={p.name} category={p.category} />
      <span className="truncate text-[11px] font-medium uppercase tracking-wide text-muted">{p.brand}</span>
      <span className="line-clamp-2 min-h-10 text-sm font-semibold leading-snug">{p.name}</span>
      <span className="flex items-center justify-between">
        <b className="tabular">{money(p.priceCents)}</b>
        <span className="rounded-lg border-2 border-brand-500 px-3 py-1 text-xs font-extrabold text-brand-600">ADD</span>
      </span>
    </button>
  )
}

function ProductRows() {
  const nav = useNavigate()
  const rows = [SHOWCASE.slice(0, 8), SHOWCASE.slice(8)]
  return (
    <section id="aisles" className="grid gap-6 py-12">
      <div className="mx-auto flex w-full max-w-7xl flex-wrap items-end justify-between gap-4 px-4">
        <div className="grid gap-1">
          <h2 className="text-3xl font-extrabold">Everything from the corner store</h2>
          <p className="text-muted">Example prices. Sign in to see what shops near you have today.</p>
        </div>
        <Link to="/login" className="flex items-center gap-1 font-semibold text-brand-600 hover:underline">Browse all <ArrowRight className="size-4" /></Link>
      </div>
      <div className="no-scrollbar mx-auto flex w-full max-w-7xl gap-2 overflow-x-auto px-4">
        {AISLES.map((a) => {
          const Icon = iconFor(a)
          return (
            <button key={a} onClick={() => nav('/login')} className="flex h-10 shrink-0 items-center gap-2 rounded-full border border-line bg-white px-4 text-sm font-semibold hover:border-ink">
              <Icon className="size-4" />{a}
            </button>
          )
        })}
      </div>
      {rows.map((row, r) => (
        <div key={r} className="marquee-row overflow-hidden">
          {/* The row is drawn twice so the loop is seamless at -50%. */}
          <div className={`marquee flex w-max gap-4 px-2 ${r ? 'marquee-reverse' : ''}`}>
            {[...row, ...row].map((p, n) => <ShowcaseCard key={n} p={p} />)}
          </div>
        </div>
      ))}
    </section>
  )
}

function Posters() {
  return (
    <section className="mx-auto grid max-w-7xl gap-4 px-4 py-12 md:grid-cols-3 md:grid-rows-2">
      <Link to="/signup/owner" className="group grid content-between gap-10 rounded-[2rem] bg-ink p-8 text-white md:col-span-2 md:row-span-2 md:p-12">
        <Store className="size-12 text-brand-500" />
        <div className="grid gap-3">
          <span className="text-xs font-bold uppercase tracking-[0.2em] text-white/60">For shop owners</span>
          <h2 className="max-w-lg text-4xl font-extrabold leading-tight md:text-6xl">Your shelves, on every phone in town.</h2>
          <p className="max-w-md text-white/70">List what you have in stock and set your own prices. You're paid your price for every order; we add our markup on top.</p>
          <span className="mt-2 flex items-center gap-2 font-semibold">Sign up as an owner <ArrowRight className="size-4 transition group-hover:translate-x-1" /></span>
        </div>
      </Link>
      <Link to="/signup/rider" className="group grid content-between gap-6 rounded-[2rem] bg-brand-500 p-8 text-white">
        <Bike className="size-10" />
        <div className="grid gap-2">
          <h2 className="text-2xl font-extrabold">Ride with QuikE</h2>
          <p className="text-white/80">Go online when it suits you. One order at a time, paid per delivery.</p>
          <span className="flex items-center gap-2 font-semibold">Sign up as a rider <ArrowRight className="size-4 transition group-hover:translate-x-1" /></span>
        </div>
      </Link>
      <Link to="/signup" className="group grid content-between gap-6 rounded-[2rem] border-2 border-ink bg-white p-8">
        <ShoppingBasket className="size-10 text-brand-500" />
        <div className="grid gap-2">
          <h2 className="text-2xl font-extrabold">No minimum basket</h2>
          <p className="text-muted">One carton of milk or a week of groceries. You pay the shelf price plus a flat delivery fee.</p>
          <span className="flex items-center gap-2 font-semibold">Create an account <ArrowRight className="size-4 transition group-hover:translate-x-1" /></span>
        </div>
      </Link>
    </section>
  )
}

function HowItWorks() {
  const steps = [
    ['Pick your items', `We show products from open shops within ${DELIVERY_KM} km, each from the nearest shop that has it.`],
    ['The shop packs it', 'A local shop accepts and packs your order while a rider heads over.'],
    ['Read your code at the door', 'Your rider hands it over when you read out the 4-digit code from the app.'],
  ]
  return (
    <section id="how" className="border-y border-line bg-paper">
      <div className="mx-auto grid max-w-7xl gap-8 px-4 py-14">
        <h2 className="text-3xl font-extrabold">How it works</h2>
        <ol className="grid gap-6 md:grid-cols-3">
          {steps.map(([t, d], n) => (
            <li key={t} className="grid content-start gap-3">
              <span className="grid size-10 place-items-center rounded-full bg-ink font-display text-lg font-extrabold text-white">{n + 1}</span>
              <h3 className="text-xl font-bold">{t}</h3>
              <p className="text-muted">{d}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}

function Footer() {
  const col = (title, links) => (
    <div className="grid content-start gap-3">
      <h3 className="text-sm font-bold uppercase tracking-wider text-white/50">{title}</h3>
      {links.map(([label, to]) => <Link key={label} to={to} className="text-sm text-white/80 hover:text-white">{label}</Link>)}
    </div>
  )
  return (
    <footer className="bg-ink text-white">
      <div className="mx-auto grid max-w-7xl gap-10 px-4 py-14 md:grid-cols-[1.5fr_1fr_1fr_1fr]">
        <div className="grid content-start gap-4">
          <div className="w-fit rounded-2xl bg-white px-4 py-3"><img src="/brand/quike-logo.png" alt="QuikE, Need it? QuikE it." className="h-20 w-auto" /></div>
          <p className="max-w-xs text-sm text-white/60">Groceries and everyday basics from independent Canadian shops, delivered by local riders.</p>
        </div>
        {col('Shop', [['Start shopping', '/signup'], ['Sign in', '/login']])}
        {col('Partners', [['Sign up as an owner', '/signup/owner'], ['Sign up as a rider', '/signup/rider']])}
        {col('Account', [['Create an account', '/signup'], ['Shop owner sign in', '/login'], ['Rider sign in', '/login']])}
      </div>
      <div className="border-t border-white/10">
        <div className="mx-auto flex max-w-7xl flex-wrap justify-between gap-2 px-4 py-5 text-xs text-white/50">
          <span>© {new Date().getFullYear()} QuikE. Made in Canada.</span>
          <span>All prices in CAD</span>
        </div>
      </div>
    </footer>
  )
}

export default function Landing() {
  return (
    <div className="min-h-screen bg-white">
      <LandingNav />
      <Hero />
      <Carousel />
      <ProductRows />
      <Posters />
      <HowItWorks />
      <Footer />
    </div>
  )
}
