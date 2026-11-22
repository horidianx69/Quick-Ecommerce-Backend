import { useEffect, useRef } from 'react'
import {
  Loader2, Minus, Plus, X, Package, Milk, Cookie, CupSoda, Apple, Snowflake, SprayCan, Sparkles, Wheat, Croissant, Baby, PawPrint, Beef, Pill as PillIcon,
} from 'lucide-react'
import { STATUS_LABEL } from '../lib/format'

const cx = (...c) => c.filter(Boolean).join(' ')

export function Button({ variant = 'primary', size = 'md', loading, className, children, type = 'button', ...props }) {
  const v = {
    primary: 'bg-brand-500 text-white hover:bg-brand-600 shadow-sm shadow-brand-500/20',
    dark: 'bg-ink text-white hover:bg-black',
    secondary: 'bg-white text-ink border border-line hover:border-ink/30',
    ghost: 'text-ink hover:bg-black/5',
    danger: 'bg-white text-brand-600 border border-brand-200 hover:bg-brand-50',
    success: 'bg-ink text-white hover:bg-black',
  }[variant]
  const s = { sm: 'h-8 px-3 text-xs', md: 'h-10 px-4 text-sm', lg: 'h-12 px-6 text-base' }[size]
  return (
    <button
      type={type}
      {...props}
      disabled={loading || props.disabled}
      className={cx(
        'inline-flex shrink-0 items-center justify-center gap-2 rounded-xl font-semibold transition active:scale-[.98]',
        'disabled:pointer-events-none disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
        v, s, className,
      )}
    >
      {loading && <Loader2 className="size-4 animate-spin" />}
      {children}
    </button>
  )
}

const fieldCls = 'w-full rounded-xl border border-line bg-white px-3 text-sm outline-none transition placeholder:text-muted/70 focus:border-brand-500 focus:ring-4 focus:ring-brand-100 disabled:bg-stone-50'
export const Input = ({ className, ...p }) => <input {...p} className={cx(fieldCls, 'h-10', className)} />
export const Textarea = ({ className, ...p }) => <textarea {...p} className={cx(fieldCls, 'min-h-20 py-2', className)} />
export const Select = ({ className, children, ...p }) => (
  <select {...p} className={cx(fieldCls, 'h-10 pr-8', className)}>{children}</select>
)

export function Field({ label, hint, error, children, className }) {
  return (
    <label className={cx('grid gap-1.5 text-sm', className)}>
      <span className="font-medium">{label}</span>
      {children}
      {error ? <span className="text-xs text-brand-600">{error}</span> : hint && <span className="text-xs text-muted">{hint}</span>}
    </label>
  )
}

// Tailwind can't tell which of two bg-*/border-* classes should win, so the defaults step aside when overridden.
export function Card({ className = '', ...p }) {
  const noBorder = /(^|\s)border-0\b/.test(className)
  const borderColor = /(^|\s)border-(?!0\b)[a-z]/.test(className)
  const bg = /(^|\s)bg-/.test(className)
  return <div {...p} className={cx('rounded-2xl', !noBorder && 'border', !noBorder && !borderColor && 'border-line', !bg && 'bg-white', className)} />
}

const tone = {
  PLACED: 'bg-amber-100 text-amber-800', ACCEPTED: 'bg-sky-100 text-sky-800', READY: 'bg-violet-100 text-violet-800',
  PICKED_UP: 'bg-indigo-100 text-indigo-800', DELIVERED: 'bg-emerald-100 text-emerald-800',
  REJECTED: 'bg-stone-200 text-stone-700', CANCELLED: 'bg-stone-200 text-stone-700', EXPIRED: 'bg-stone-200 text-stone-700',
  PENDING: 'bg-amber-100 text-amber-800', APPROVED: 'bg-emerald-100 text-emerald-800', SUSPENDED: 'bg-red-100 text-red-800',
}
export function StatusBadge({ status, className }) {
  return (
    <span className={cx('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap', tone[status] ?? 'bg-stone-100', className)}>
      {STATUS_LABEL[status] ?? status}
    </span>
  )
}
export const Pill = ({ className, ...p }) => (
  <span {...p} className={cx('inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold', className)} />
)

export const Spinner = ({ className }) => <Loader2 className={cx('size-5 animate-spin text-muted', className)} />
export const FullPageSpinner = () => <div className="grid min-h-[50vh] place-items-center"><Spinner className="size-7" /></div>

export function Empty({ icon: Icon = Package, title, children, action }) {
  return (
    <div className="grid place-items-center gap-3 rounded-2xl border border-dashed border-line bg-white/60 px-6 py-14 text-center">
      <div className="grid size-12 place-items-center rounded-full bg-brand-50 text-brand-600"><Icon className="size-6" /></div>
      <div className="font-display text-lg font-semibold">{title}</div>
      {children && <p className="max-w-sm text-sm text-muted">{children}</p>}
      {action}
    </div>
  )
}

export function ErrorNote({ error }) {
  if (!error) return null
  return <div className="rounded-xl bg-brand-50 px-4 py-3 text-sm text-brand-700">{error.message}</div>
}

export function Modal({ open, onClose, title, children, side = false, footer }) {
  const ref = useRef(null)
  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (open && !d.open) d.showModal()
    if (!open && d.open) d.close()
  }, [open])
  return (
    <dialog ref={ref} onClose={onClose} onClick={(e) => e.target === ref.current && onClose()} className={side ? 'drawer' : 'modal'}>
      {open && (
        <div className={cx('flex flex-col bg-white', side ? 'h-full' : 'max-h-[calc(100dvh-32px)]')}>
          <div className="flex items-center justify-between gap-4 border-b border-line px-5 py-4">
            <h2 className="text-lg font-semibold">{title}</h2>
            <button onClick={onClose} className="grid size-8 place-items-center rounded-full hover:bg-black/5" aria-label="Close"><X className="size-5" /></button>
          </div>
          <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
          {footer && <div className="border-t border-line px-5 py-4">{footer}</div>}
        </div>
      )}
    </dialog>
  )
}

export function Stepper({ value, onChange, size = 'md' }) {
  const h = size === 'sm' ? 'h-8' : 'h-9'
  return (
    <div className={cx('inline-flex items-center rounded-xl bg-brand-500 text-white', h)}>
      <button className={cx('grid w-8 place-items-center', h)} onClick={() => onChange(value - 1)} aria-label="Remove one"><Minus className="size-4" /></button>
      <span className="min-w-6 text-center text-sm font-bold tabular">{value}</span>
      <button className={cx('grid w-8 place-items-center disabled:opacity-50', h)} onClick={() => onChange(value + 1)} disabled={value >= 99} aria-label="Add one"><Plus className="size-4" /></button>
    </div>
  )
}

export function Tabs({ tabs, value, onChange }) {
  return (
    <div className="no-scrollbar -mx-1 flex gap-1 overflow-x-auto px-1">
      {tabs.map((t) => (
        <button
          key={t.value}
          onClick={() => onChange(t.value)}
          className={cx(
            'inline-flex h-9 shrink-0 items-center gap-2 rounded-full px-4 text-sm font-semibold transition',
            value === t.value ? 'bg-ink text-white' : 'bg-white text-ink border border-line hover:border-ink/30',
          )}
        >
          {t.label}
          {t.count > 0 && <span className={cx('rounded-full px-1.5 text-xs', value === t.value ? 'bg-white/20' : 'bg-brand-500 text-white')}>{t.count}</span>}
        </button>
      ))}
    </div>
  )
}

/** Spring's page metadata: { size, number, totalElements, totalPages } */
export function Pager({ page, onPage }) {
  if (!page || page.totalPages <= 1) return null
  return (
    <div className="flex items-center justify-between gap-3 pt-2 text-sm text-muted">
      <span>Page {page.number + 1} of {page.totalPages} · {page.totalElements} total</span>
      <div className="flex gap-2">
        <Button variant="secondary" size="sm" disabled={page.number === 0} onClick={() => onPage(page.number - 1)}>Previous</Button>
        <Button variant="secondary" size="sm" disabled={page.number + 1 >= page.totalPages} onClick={() => onPage(page.number + 1)}>Next</Button>
      </div>
    </div>
  )
}

export function PageHeader({ title, subtitle, actions }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="grid gap-1">
        <h1 className="text-2xl font-extrabold tracking-tight sm:text-3xl">{title}</h1>
        {subtitle && <p className="text-sm text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

/* ---------- Product art: real image if the catalog has one, else a tinted category tile ---------- */
const ICONS = [
  [/dairy|milk|cheese|egg/i, Milk], [/snack|chip|candy|chocolate|cookie/i, Cookie], [/drink|beverage|juice|soda|water|coffee|tea/i, CupSoda],
  [/produce|fruit|veg/i, Apple], [/frozen|ice/i, Snowflake], [/household|clean|laundry|paper/i, SprayCan], [/personal|beauty|care|bath/i, Sparkles],
  [/bakery|bread/i, Croissant], [/pantry|grocery|rice|pasta|cereal/i, Wheat], [/baby/i, Baby], [/pet/i, PawPrint], [/meat|deli/i, Beef], [/health|pharm/i, PillIcon],
]
const TINTS = ['#f2f2f2', '#fdeceb', '#ececec', '#fbe1df']
export const iconFor = (category = '') => ICONS.find(([re]) => re.test(category))?.[1] ?? Package
export function tintFor(category = '') {
  let h = 0
  for (const ch of category) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return TINTS[h % TINTS.length]
}

export function ProductArt({ imageUrl, name, category, className }) {
  const Icon = iconFor(category)
  if (imageUrl) return <img src={imageUrl} alt={name} loading="lazy" className={cx('aspect-square w-full rounded-xl bg-white object-contain', className)} />
  return (
    <div className={cx('grid aspect-square w-full place-items-center rounded-xl', className)} style={{ background: tintFor(category) }} aria-hidden>
      <Icon className="size-1/3 text-ink/70" strokeWidth={1.6} />
    </div>
  )
}
