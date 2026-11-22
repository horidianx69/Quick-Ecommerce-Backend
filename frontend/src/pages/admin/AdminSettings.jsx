import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Trash2 } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction } from '../../lib/hooks'
import { money, toCents, toDollars } from '../../lib/format'
import { Button, Card, ErrorNote, Field, FullPageSpinner, Input, PageHeader, Select } from '../../components/ui'
import { useCategories } from '../customer/Home'

const INV = [['adminSettings']]

function Example({ markup }) {
  return <span className="text-xs text-muted">A $10.00 shop price sells for {money(Math.round(1000 * markup / 100))}</span>
}

export default function AdminSettings() {
  const settings = useQuery({ queryKey: ['adminSettings'], queryFn: () => api('/api/admin/settings') })
  const cats = useCategories()
  const [f, setF] = useState(null)
  const [newCat, setNewCat] = useState({ category: '', markupPercent: '130' })
  useEffect(() => {
    if (settings.data) setF({ defaultMarkupPercent: String(settings.data.defaultMarkupPercent), deliveryFee: toDollars(settings.data.deliveryFeeCents), driverPay: toDollars(settings.data.driverPayCents) })
  }, [settings.data])
  const save = useAction(() => api('/api/admin/settings', {
    method: 'PUT', body: { defaultMarkupPercent: parseInt(f.defaultMarkupPercent, 10), deliveryFeeCents: toCents(f.deliveryFee), driverPayCents: toCents(f.driverPay) },
  }), { invalidate: INV, success: 'Saved. New orders use these prices; past orders keep theirs.' })
  const setMarkup = useAction(({ category, markupPercent }) => api(`/api/admin/settings/markups/${encodeURIComponent(category)}`, { method: 'PUT', body: { markupPercent: parseInt(markupPercent, 10) } }), {
    invalidate: INV, success: 'Category markup saved', onSuccess: () => setNewCat({ category: '', markupPercent: '130' }),
  })
  const removeMarkup = useAction((category) => api(`/api/admin/settings/markups/${encodeURIComponent(category)}`, { method: 'DELETE' }), { invalidate: INV, success: 'Back to the default markup' })

  if (settings.isPending || !f) return settings.error ? <ErrorNote error={settings.error} /> : <FullPageSpinner />
  const s = settings.data
  const used = new Set(s.categoryMarkups.map((m) => m.category))

  return (
    <>
      <PageHeader title="Prices & fees" subtitle="Changes apply to new orders right away. Orders already placed keep the prices they were placed with." />
      <Card className="p-5 sm:p-6">
        <form className="grid gap-4 sm:grid-cols-3" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
          <Field label="Default markup (%)" hint={<Example markup={Number(f.defaultMarkupPercent) || 100} />}>
            <Input required type="number" min="100" max="1000" value={f.defaultMarkupPercent} onChange={(e) => setF({ ...f, defaultMarkupPercent: e.target.value })} />
          </Field>
          <Field label="Delivery fee ($)" hint="What the customer pays per order">
            <Input required type="number" min="0" step="0.01" value={f.deliveryFee} onChange={(e) => setF({ ...f, deliveryFee: e.target.value })} />
          </Field>
          <Field label="Driver pay ($)" hint="What the driver earns per delivery">
            <Input required type="number" min="0" step="0.01" value={f.driverPay} onChange={(e) => setF({ ...f, driverPay: e.target.value })} />
          </Field>
          <Button type="submit" loading={save.isPending} className="sm:col-span-3 sm:justify-self-start">Save</Button>
        </form>
      </Card>

      <Card className="grid gap-4 p-5 sm:p-6">
        <div>
          <h2 className="text-lg font-bold">Markup by category</h2>
          <p className="text-sm text-muted">Use a different markup for some aisles. Everything else uses the default ({s.defaultMarkupPercent}%).</p>
        </div>
        {s.categoryMarkups.length > 0 && (
          <ul className="divide-y divide-line rounded-xl border border-line">
            {s.categoryMarkups.map((m) => (
              <li key={m.category} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
                <span className="font-semibold">{m.category}</span>
                <span className="flex items-center gap-3"><b className="tabular">{m.markupPercent}%</b><Example markup={m.markupPercent} />
                  <Button variant="ghost" size="sm" onClick={() => removeMarkup.mutate(m.category)} aria-label={`Remove markup for ${m.category}`}><Trash2 className="size-4" /></Button>
                </span>
              </li>
            ))}
          </ul>
        )}
        <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); setMarkup.mutate(newCat) }}>
          <Field label="Category" className="min-w-48 flex-1">
            <Select required value={newCat.category} onChange={(e) => setNewCat({ ...newCat, category: e.target.value })}>
              <option value="">Choose…</option>
              {cats.data?.filter((c) => !used.has(c)).map((c) => <option key={c}>{c}</option>)}
            </Select>
          </Field>
          <Field label="Markup (%)" className="w-32"><Input required type="number" min="100" max="1000" value={newCat.markupPercent} onChange={(e) => setNewCat({ ...newCat, markupPercent: e.target.value })} /></Field>
          <Button type="submit" loading={setMarkup.isPending}>Add</Button>
        </form>
      </Card>
    </>
  )
}
