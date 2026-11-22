import { useQuery } from '@tanstack/react-query'
import { Clock, Inbox, Package, Store } from 'lucide-react'
import { api, apiOrNull } from '../../lib/api'
import ConsoleLayout from '../../layouts/ConsoleLayout'
import { Card, FullPageSpinner } from '../../components/ui'
import ApplicationStatus, { OnboardingShell, OnboardingSteps } from '../../components/ApplicationStatus'
import { ShopForm } from './ShopProfile'

export default function ShopConsole() {
  // While the application is in review, check every 15 s so approval opens the dashboard by itself.
  const shop = useQuery({
    queryKey: ['myShop'],
    queryFn: () => apiOrNull('/api/shops/me'),
    refetchInterval: (q) => (q.state.data?.status === 'APPROVED' ? 60_000 : 15_000),
  })
  const approved = shop.data?.status === 'APPROVED'
  const inbox = useQuery({
    queryKey: ['shopOrders', 'PLACED', 'count'],
    queryFn: () => api('/api/shops/me/orders', { query: { status: 'PLACED', size: 1 } }),
    enabled: approved,
    refetchInterval: 5000,
  })

  if (shop.isPending) return <FullPageSpinner />

  // Step 2 of owner sign-up: shop details. Saving them sends the application.
  if (!shop.data) {
    return (
      <OnboardingShell wide>
        <OnboardingSteps step={1} kind="shop" />
        <Card className="grid gap-5 p-6 sm:p-8">
          <div className="grid gap-1">
            <h1 className="text-2xl font-extrabold">Tell us about your shop</h1>
            <p className="text-sm text-muted">The QuikE team reviews every shop before it goes live. You'll see the status on the next screen.</p>
          </div>
          <ShopForm />
        </Card>
      </OnboardingShell>
    )
  }

  if (!approved) {
    return (
      <OnboardingShell>
        <ApplicationStatus kind="shop" status={shop.data.status} name={shop.data.name}>
          <details className="rounded-2xl border border-line p-4">
            <summary className="cursor-pointer text-sm font-semibold">Check or edit your shop details</summary>
            <div className="mt-4"><ShopForm shop={shop.data} /></div>
          </details>
        </ApplicationStatus>
      </OnboardingShell>
    )
  }

  const newCount = inbox.data?.page?.totalElements ?? 0
  return (
    <ConsoleLayout
      suffix="Shops"
      home="/shop"
      banner={!shop.data.hours?.length && (
        <div className="flex items-center gap-2 bg-ink px-4 py-2.5 text-sm font-semibold text-white lg:px-8">
          <Clock className="size-4 shrink-0 text-brand-500" />Your opening hours haven't been set yet, so customers can't see your shop. The QuikE team sets them for you.
        </div>
      )}
      context={{ shop: shop.data }}
      nav={[
        { to: '/shop', end: true, label: newCount ? `Orders (${newCount} new)` : 'Orders', icon: Inbox },
        { to: '/shop/products', label: 'Products & stock', icon: Package },
        { to: '/shop/profile', label: 'Shop profile', icon: Store },
      ]}
    />
  )
}
