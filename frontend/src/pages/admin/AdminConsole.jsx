import { useQuery } from '@tanstack/react-query'
import { Bike, LayoutDashboard, Package, Receipt, Settings, Store, Users } from 'lucide-react'
import { api } from '../../lib/api'
import ConsoleLayout from '../../layouts/ConsoleLayout'
import { Card } from '../../components/ui'

export const useStats = () => useQuery({ queryKey: ['adminStats'], queryFn: () => api('/api/admin/stats'), refetchInterval: 10_000 })

export function Table({ head, children, minWidth = 720 }) {
  return (
    <Card className="overflow-x-auto">
      <table className="w-full text-sm" style={{ minWidth }}>
        <thead className="border-b border-line bg-paper text-left text-xs uppercase tracking-wider text-muted">
          <tr>{head.map((h, i) => <th key={i} className="px-4 py-3 font-semibold">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-line">{children}</tbody>
      </table>
    </Card>
  )
}
export const Td = ({ className = '', ...p }) => <td {...p} className={`px-4 py-3 align-middle ${className}`} />

const n = (v) => (v ? ` (${v})` : '')

export default function AdminConsole() {
  const s = useStats().data
  return (
    <ConsoleLayout suffix="Admin" home="/admin" nav={[
      { to: '/admin', end: true, label: 'Dashboard', icon: LayoutDashboard },
      { to: '/admin/orders', label: `Orders${n(s?.needsDriver)}`, icon: Receipt },
      { to: '/admin/shops', label: `Shops${n(s?.shopsPending)}`, icon: Store },
      { to: '/admin/catalog', label: `Catalog${n(s?.productRequestsPending)}`, icon: Package },
      { to: '/admin/drivers', label: `Drivers${n(s?.driversPending)}`, icon: Bike },
      { to: '/admin/users', label: 'Users', icon: Users },
      { to: '/admin/settings', label: 'Prices & fees', icon: Settings },
    ]} />
  )
}
