import { Navigate, Route, Routes, useLocation } from 'react-router'
import { homeFor, RequireRole, useAuth } from './lib/auth'
import { FullPageSpinner } from './components/ui'
import CustomerLayout from './layouts/CustomerLayout'
import Landing from './pages/Landing'
import { Login, Signup } from './pages/Auth'
import Home from './pages/customer/Home'
import Offers from './pages/customer/Offers'
import ShopAisle from './pages/customer/ShopAisle'
import Checkout from './pages/customer/Checkout'
import { OrderDetail, OrderList } from './pages/customer/Orders'
import ShopConsole from './pages/shop/ShopConsole'
import ShopOrders from './pages/shop/ShopOrders'
import ShopProducts from './pages/shop/ShopProducts'
import ShopProfile from './pages/shop/ShopProfile'
import Driver from './pages/driver/Driver'
import AdminConsole from './pages/admin/AdminConsole'
import AdminDashboard from './pages/admin/AdminDashboard'
import AdminOrders from './pages/admin/AdminOrders'
import AdminShops from './pages/admin/AdminShops'
import AdminCatalog from './pages/admin/AdminCatalog'
import AdminDrivers from './pages/admin/AdminDrivers'
import AdminUsers from './pages/admin/AdminUsers'
import AdminSettings from './pages/admin/AdminSettings'

/** "/" is the landing page for guests and the store for customers; other customer pages ask guests to sign in. */
function CustomerArea() {
  const { user, loading } = useAuth()
  const loc = useLocation()
  if (loading) return <FullPageSpinner />
  if (!user) return loc.pathname === '/' ? <Landing /> : <Navigate to={`/login?next=${encodeURIComponent(loc.pathname)}`} replace />
  if (user.role !== 'CUSTOMER') return <Navigate to={homeFor(user.role)} replace />
  return <CustomerLayout />
}

export default function App() {
  return (
    <Routes>
      <Route path="/welcome" element={<Navigate to="/" replace />} />
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />
      <Route path="/signup/rider" element={<Signup role="DRIVER" />} />
      <Route path="/signup/owner" element={<Signup role="SHOP" />} />

      {/* Customer app */}
      <Route element={<CustomerArea />}>
        <Route index element={<Home />} />
        <Route path="p/:productId" element={<Offers />} />
        <Route path="s/:shopId" element={<ShopAisle />} />
        <Route path="checkout" element={<Checkout />} />
        <Route path="orders" element={<OrderList />} />
        <Route path="orders/:id" element={<OrderDetail />} />
      </Route>

      {/* Shop website */}
      <Route path="/shop" element={<RequireRole role="SHOP"><ShopConsole /></RequireRole>}>
        <Route index element={<ShopOrders />} />
        <Route path="products" element={<ShopProducts />} />
        <Route path="profile" element={<ShopProfile />} />
      </Route>

      {/* Driver app */}
      <Route path="/driver" element={<RequireRole role="DRIVER"><Driver /></RequireRole>} />

      {/* Admin panel */}
      <Route path="/admin" element={<RequireRole role="ADMIN"><AdminConsole /></RequireRole>}>
        <Route index element={<AdminDashboard />} />
        <Route path="orders" element={<AdminOrders />} />
        <Route path="shops" element={<AdminShops />} />
        <Route path="catalog" element={<AdminCatalog />} />
        <Route path="drivers" element={<AdminDrivers />} />
        <Route path="users" element={<AdminUsers />} />
        <Route path="settings" element={<AdminSettings />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
