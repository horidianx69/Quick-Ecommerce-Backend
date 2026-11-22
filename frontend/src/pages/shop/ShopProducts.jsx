import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Check, Minus, PackagePlus, Plus, Search, Send, Trash2 } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction, useDebounced } from '../../lib/hooks'
import { money, toCents, toDollars } from '../../lib/format'
import { Button, Card, Empty, ErrorNote, Field, Input, Modal, PageHeader, Pager, ProductArt, Spinner } from '../../components/ui'
import { useCategories } from '../customer/Home'

const INV = [['shopProducts']]

function PriceCell({ p }) {
  const [v, setV] = useState(toDollars(p.priceCents))
  const save = useAction((cents) => api(`/api/shops/me/products/${p.id}`, { method: 'PUT', body: { priceCents: cents } }), { invalidate: INV, success: 'Price updated' })
  const changed = toCents(v) !== p.priceCents && Number(v) > 0
  return (
    <form className="flex items-center gap-1" onSubmit={(e) => { e.preventDefault(); if (changed) save.mutate(toCents(v)) }}>
      <span className="text-muted">$</span>
      <Input type="number" min="0.01" step="0.01" value={v} onChange={(e) => setV(e.target.value)} className="!h-9 w-24 tabular" aria-label={`Price of ${p.name}`} />
      {changed && <Button type="submit" size="sm" loading={save.isPending} aria-label="Save price"><Check className="size-4" /></Button>}
    </form>
  )
}

function StockCell({ p }) {
  const [n, setN] = useState('')
  const change = useAction((delta) => api(`/api/shops/me/products/${p.id}/stock`, { method: 'POST', body: { change: delta } }), {
    invalidate: INV, onSuccess: () => setN(''),
  })
  return (
    <div className="flex items-center gap-1">
      <Button variant="secondary" size="sm" className="!px-2" disabled={!p.quantity || change.isPending} onClick={() => change.mutate(-1)} aria-label="One less"><Minus className="size-4" /></Button>
      <span className={`w-10 text-center font-bold tabular ${p.quantity === 0 ? 'text-brand-600' : ''}`}>{p.quantity}</span>
      <Button variant="secondary" size="sm" className="!px-2" disabled={change.isPending} onClick={() => change.mutate(1)} aria-label="One more"><Plus className="size-4" /></Button>
      <form className="ml-2 flex gap-1" onSubmit={(e) => { e.preventDefault(); const d = parseInt(n, 10); if (d) change.mutate(d) }}>
        <Input type="number" placeholder="+12" value={n} onChange={(e) => setN(e.target.value)} className="!h-8 w-20 tabular" aria-label="Stock change, use minus to remove" />
        {n && <Button type="submit" size="sm" variant="dark" loading={change.isPending}>Apply</Button>}
      </form>
    </div>
  )
}

function AddProductModal({ open, onClose }) {
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const [pick, setPick] = useState(null)
  const [price, setPrice] = useState('')
  const [qty, setQty] = useState('10')
  const results = useQuery({ queryKey: ['catalog', dq], queryFn: () => api('/api/catalog', { query: { q: dq, size: 20 } }), enabled: open })
  const add = useAction(() => api('/api/shops/me/products', { method: 'POST', body: { productId: pick.id, priceCents: toCents(price), quantity: parseInt(qty, 10) } }), {
    invalidate: INV, success: `${pick?.name} added`, onSuccess: () => { setPick(null); setPrice(''); onClose() },
  })
  return (
    <Modal open={open} onClose={onClose} title={pick ? `Sell ${pick.name}` : 'Add a product'}>
      {!pick ? (
        <div className="grid gap-3">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted" />
            <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search the catalog by name, brand or barcode" className="pl-9" />
          </div>
          {results.isPending ? <Spinner /> : !results.data?.content.length ? (
            <p className="py-6 text-center text-sm text-muted">Not in the catalog? Use “Request a product” instead.</p>
          ) : (
            <ul className="grid gap-1">
              {results.data.content.map((c) => (
                <li key={c.id}>
                  <button onClick={() => setPick(c)} className="flex w-full items-center gap-3 rounded-xl p-2 text-left hover:bg-paper">
                    <ProductArt imageUrl={c.imageUrl} name={c.name} category={c.category} className="!w-11 shrink-0" />
                    <span className="min-w-0 flex-1"><span className="block truncate text-sm font-semibold">{c.name}</span><span className="text-xs text-muted">{[c.brand, c.category].filter(Boolean).join(' · ')}</span></span>
                    <Plus className="size-4 text-muted" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : (
        <form className="grid gap-4" onSubmit={(e) => { e.preventDefault(); add.mutate() }}>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Your price ($)" hint="Customers pay this plus our markup"><Input required type="number" min="0.01" step="0.01" autoFocus value={price} onChange={(e) => setPrice(e.target.value)} /></Field>
            <Field label="Units in stock"><Input required type="number" min="0" value={qty} onChange={(e) => setQty(e.target.value)} /></Field>
          </div>
          <div className="flex justify-between gap-2">
            <Button variant="ghost" onClick={() => setPick(null)}>Back</Button>
            <Button type="submit" loading={add.isPending}>Start selling</Button>
          </div>
        </form>
      )}
    </Modal>
  )
}

function RequestModal({ open, onClose }) {
  const cats = useCategories()
  const [f, setF] = useState({ name: '', brand: '', barcode: '', category: '' })
  const send = useAction(() => api('/api/shops/me/product-requests', { method: 'POST', body: f }), {
    success: 'Sent! Our team will add it to the catalog.', onSuccess: () => { setF({ name: '', brand: '', barcode: '', category: '' }); onClose() },
  })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  return (
    <Modal open={open} onClose={onClose} title="Request a new product">
      <form className="grid gap-3" onSubmit={(e) => { e.preventDefault(); send.mutate() }}>
        <p className="text-sm text-muted">Can't find something in the catalog? Tell us and we'll add it for every shop.</p>
        <Field label="Product name"><Input required value={f.name} onChange={set('name')} placeholder="Poutine Gravy Mix 392 g" /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Brand"><Input value={f.brand} onChange={set('brand')} /></Field>
          <Field label="Barcode"><Input value={f.barcode} onChange={set('barcode')} inputMode="numeric" /></Field>
        </div>
        <Field label="Category">
          <Input list="cat-list" value={f.category} onChange={set('category')} />
          <datalist id="cat-list">{cats.data?.map((c) => <option key={c} value={c} />)}</datalist>
        </Field>
        <Button type="submit" loading={send.isPending}><Send className="size-4" />Send request</Button>
      </form>
    </Modal>
  )
}

export default function ShopProducts() {
  const [page, setPage] = useState(0)
  const [adding, setAdding] = useState(false)
  const [requesting, setRequesting] = useState(false)
  const [removing, setRemoving] = useState(null)
  const products = useQuery({ queryKey: ['shopProducts', page], queryFn: () => api('/api/shops/me/products', { query: { page, size: 50 } }) })
  const remove = useAction((id) => api(`/api/shops/me/products/${id}`, { method: 'DELETE' }), { invalidate: INV, success: 'Removed from your shop', onSuccess: () => setRemoving(null) })

  return (
    <>
      <PageHeader title="Products & stock" subtitle="Your price is what you're paid. Stock goes down by itself when orders come in."
        actions={<>
          <Button variant="secondary" onClick={() => setRequesting(true)}><Send className="size-4" />Request a product</Button>
          <Button onClick={() => setAdding(true)}><PackagePlus className="size-4" />Add product</Button>
        </>} />
      <ErrorNote error={products.error} />
      {products.isPending ? <Spinner /> : !products.data.content.length ? (
        <Empty icon={PackagePlus} title="No products yet" action={<Button onClick={() => setAdding(true)}>Add your first product</Button>}>Pick products from the shared catalog and set your price and stock.</Empty>
      ) : (
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-sm">
            <thead className="border-b border-line bg-paper text-left text-xs uppercase tracking-wider text-muted">
              <tr><th className="px-4 py-3">Product</th><th className="px-4 py-3">Your price</th><th className="px-4 py-3">In stock</th><th className="px-4 py-3" /></tr>
            </thead>
            <tbody className="divide-y divide-line">
              {products.data.content.map((p) => (
                <tr key={p.id}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <ProductArt imageUrl={p.imageUrl} name={p.name} category={p.category} className="!w-11 shrink-0" />
                      <div className="min-w-0"><div className="font-semibold">{p.name}</div><div className="text-xs text-muted">{[p.brand, p.category].filter(Boolean).join(' · ')}</div></div>
                    </div>
                  </td>
                  <td className="px-4 py-3"><PriceCell key={p.priceCents} p={p} /></td>
                  <td className="px-4 py-3"><StockCell p={p} /></td>
                  <td className="px-4 py-3 text-right">
                    <Button variant="ghost" size="sm" onClick={() => setRemoving(p)} aria-label={`Stop selling ${p.name}`}><Trash2 className="size-4" /></Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      {products.data && <Pager page={products.data.page} onPage={setPage} />}
      <AddProductModal open={adding} onClose={() => setAdding(false)} />
      <RequestModal open={requesting} onClose={() => setRequesting(false)} />
      <Modal open={!!removing} onClose={() => setRemoving(null)} title="Stop selling this product?"
        footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={() => setRemoving(null)}>Keep it</Button><Button loading={remove.isPending} onClick={() => remove.mutate(removing.id)}>Stop selling</Button></div>}>
        <p className="text-sm text-muted">{removing?.name} will disappear from your shop. You can add it again later. Current price: {money(removing?.priceCents)}.</p>
      </Modal>
    </>
  )
}
