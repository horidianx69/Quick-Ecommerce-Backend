import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Package, Plus, Search } from 'lucide-react'
import { api } from '../../lib/api'
import { useAction, useDebounced } from '../../lib/hooks'
import { Button, Empty, ErrorNote, Field, Input, Modal, PageHeader, Pager, ProductArt, Select, Spinner, StatusBadge, Tabs } from '../../components/ui'
import { useCategories } from '../customer/Home'
import { Table, Td } from './AdminConsole'

const INV = [['adminCatalog'], ['adminStats'], ['categories']]
const blank = { name: '', brand: '', barcode: '', category: '', taxCategory: 'ZERO_RATED', imageUrl: '', status: 'APPROVED' }
// The PUT replaces the whole product, so always send every field.
const body = (p) => Object.fromEntries(Object.keys(blank).map((k) => [k, p[k] === '' ? null : p[k] ?? null]))

function ProductForm({ product, onDone }) {
  const cats = useCategories()
  const [f, setF] = useState(() => ({ ...blank, ...Object.fromEntries(Object.entries(product ?? {}).map(([k, v]) => [k, v ?? ''])) }))
  const save = useAction(() => (product?.id
    ? api(`/api/admin/catalog/${product.id}`, { method: 'PUT', body: body(f) })
    : api('/api/admin/catalog', { method: 'POST', body: body(f) })), { invalidate: INV, success: 'Product saved', onSuccess: onDone })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  return (
    <form className="grid gap-3" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
      {product?.requestedByShopId && <p className="rounded-xl bg-brand-50 px-3 py-2 text-sm">Requested by shop #{product.requestedByShopId}. Tidy up the name, then approve.</p>}
      <Field label="Name"><Input required value={f.name} onChange={set('name')} /></Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Brand"><Input value={f.brand} onChange={set('brand')} /></Field>
        <Field label="Barcode"><Input value={f.barcode} onChange={set('barcode')} /></Field>
        <Field label="Category" hint="Customers browse by this">
          <Input list="admin-cats" required value={f.category} onChange={set('category')} />
          <datalist id="admin-cats">{cats.data?.map((c) => <option key={c} value={c} />)}</datalist>
        </Field>
        <Field label="Tax">
          <Select value={f.taxCategory} onChange={set('taxCategory')}><option value="ZERO_RATED">Zero-rated (basic groceries)</option><option value="TAXABLE">Taxable</option></Select>
        </Field>
      </div>
      <Field label="Image URL" hint="Optional. A link to a product photo."><Input type="url" value={f.imageUrl} onChange={set('imageUrl')} /></Field>
      <Field label="Status">
        <Select value={f.status} onChange={set('status')}><option value="APPROVED">Approved: shops can sell it</option><option value="PENDING">Pending</option><option value="REJECTED">Rejected</option></Select>
      </Field>
      <Button type="submit" loading={save.isPending}>Save product</Button>
    </form>
  )
}

function QuickDecision({ p }) {
  const decide = useAction((status) => api(`/api/admin/catalog/${p.id}`, { method: 'PUT', body: body({ ...p, status }) }), {
    invalidate: INV, success: (r) => `${r.name} ${r.status.toLowerCase()}`,
  })
  return (
    <div className="flex gap-2">
      <Button size="sm" variant="success" loading={decide.isPending} onClick={() => decide.mutate('APPROVED')}>Approve</Button>
      <Button size="sm" variant="danger" loading={decide.isPending} onClick={() => decide.mutate('REJECTED')}>Reject</Button>
    </div>
  )
}

export default function AdminCatalog() {
  const [status, setStatus] = useState('PENDING')
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState(null) // product, or {} for new
  const list = useQuery({ queryKey: ['adminCatalog', status, dq, page], queryFn: () => api('/api/admin/catalog', { query: { status, q: dq, page, size: 25 } }) })
  return (
    <>
      <PageHeader title="Catalog" subtitle="The shared product list every shop picks from. Pending items are requests from shops."
        actions={<Button onClick={() => setEditing({})}><Plus className="size-4" />New product</Button>} />
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs value={status} onChange={(v) => { setStatus(v); setPage(0) }} tabs={[{ value: 'PENDING', label: 'Requests' }, { value: 'APPROVED', label: 'Approved' }, { value: 'REJECTED', label: 'Rejected' }]} />
        <div className="relative w-full sm:w-72">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted" />
          <Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} placeholder="Name, brand or barcode" className="pl-9" aria-label="Search catalog" />
        </div>
      </div>
      <ErrorNote error={list.error} />
      {list.isPending ? <Spinner /> : !list.data.content.length ? <Empty icon={Package} title={status === 'PENDING' ? 'No product requests' : 'No products'} /> : (
        <>
          <Table head={['Product', 'Category', 'Barcode', 'Tax', 'Status', '']}>
            {list.data.content.map((p) => (
              <tr key={p.id} className="hover:bg-paper">
                <Td>
                  <button onClick={() => setEditing(p)} className="flex items-center gap-3 text-left">
                    <ProductArt imageUrl={p.imageUrl} name={p.name} category={p.category} className="!w-10 shrink-0" />
                    <span><span className="block font-semibold hover:underline">{p.name}</span><span className="text-xs text-muted">{p.brand}{p.requestedByShopId && ` · requested by shop #${p.requestedByShopId}`}</span></span>
                  </button>
                </Td>
                <Td>{p.category}</Td>
                <Td className="text-muted tabular">{p.barcode ?? '–'}</Td>
                <Td className="text-xs">{p.taxCategory === 'TAXABLE' ? 'Taxable' : 'Zero-rated'}</Td>
                <Td><StatusBadge status={p.status} /></Td>
                <Td><div className="flex justify-end gap-2">{p.status === 'PENDING' && <QuickDecision p={p} />}<Button size="sm" variant="secondary" onClick={() => setEditing(p)}>Edit</Button></div></Td>
              </tr>
            ))}
          </Table>
          <Pager page={list.data.page} onPage={setPage} />
        </>
      )}
      <Modal open={!!editing} onClose={() => setEditing(null)} title={editing?.id ? 'Edit product' : 'New product'}>
        {editing && <ProductForm key={editing.id ?? 'new'} product={editing.id ? editing : null} onDone={() => setEditing(null)} />}
      </Modal>
    </>
  )
}
