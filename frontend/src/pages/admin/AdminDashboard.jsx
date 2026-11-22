import { Link } from 'react-router'
import { ArrowRight } from 'lucide-react'
import { Card, ErrorNote, FullPageSpinner, PageHeader } from '../../components/ui'
import { useStats } from './AdminConsole'

const GROUPS = [
  { title: 'Orders right now', items: [
    ['waitingForShop', 'Waiting for a shop', '/admin/orders?status=PLACED'],
    ['needsDriver', 'Need a driver', '/admin/orders?needsDriver=true', true],
    ['onTheRoad', 'On the road', '/admin/orders?status=PICKED_UP'],
  ] },
  { title: 'Last 24 hours', items: [
    ['placedLast24h', 'Orders placed', '/admin/orders'],
    ['deliveredLast24h', 'Delivered', '/admin/orders?status=DELIVERED'],
  ] },
  { title: 'Waiting for you', items: [
    ['shopsPending', 'Shops to approve', '/admin/shops', true],
    ['driversPending', 'Drivers to approve', '/admin/drivers', true],
    ['productRequestsPending', 'Product requests', '/admin/catalog', true],
  ] },
  { title: 'Fleet', items: [['driversOnline', 'Drivers online', '/admin/drivers?status=APPROVED']] },
]

export default function AdminDashboard() {
  const stats = useStats()
  if (stats.isPending) return <FullPageSpinner />
  if (stats.error) return <ErrorNote error={stats.error} />
  return (
    <>
      <PageHeader title="Dashboard" subtitle="Refreshes every 10 seconds." />
      {GROUPS.map((g) => (
        <section key={g.title} className="grid gap-3">
          <h2 className="text-sm font-bold uppercase tracking-wider text-muted">{g.title}</h2>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
            {g.items.map(([key, label, to, urgent]) => {
              const v = stats.data[key]
              return (
                <Link key={key} to={to}>
                  <Card className={`group grid gap-1 p-5 transition hover:shadow-md ${urgent && v ? 'border-brand-200 bg-brand-50' : ''}`}>
                    <span className={`font-display text-4xl font-extrabold tabular ${urgent && v ? 'text-brand-600' : ''}`}>{v}</span>
                    <span className="flex items-center justify-between text-sm font-medium text-muted">{label}<ArrowRight className="size-4 opacity-0 transition group-hover:opacity-100" /></span>
                  </Card>
                </Link>
              )
            })}
          </div>
        </section>
      ))}
    </>
  )
}
