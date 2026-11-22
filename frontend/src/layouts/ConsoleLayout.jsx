import { NavLink, Outlet } from 'react-router'
import { LogOut } from 'lucide-react'
import { useAuth } from '../lib/auth'
import { Logo } from './CustomerLayout'

/** Sidebar layout shared by the shop website and the admin panel. */
export default function ConsoleLayout({ suffix, home, nav, banner, context }) {
  const { user, logout } = useAuth()
  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[248px_1fr]">
      <aside className="sticky top-0 z-30 border-b border-line bg-white lg:h-screen lg:border-b-0 lg:border-r">
        <div className="flex items-center justify-between gap-4 px-4 py-3 lg:px-5 lg:py-5">
          <Logo suffix={suffix} to={home} />
          <button onClick={logout} className="grid size-9 place-items-center rounded-xl hover:bg-black/5 lg:hidden" aria-label="Sign out"><LogOut className="size-4" /></button>
        </div>
        <nav className="no-scrollbar flex gap-1 overflow-x-auto px-3 pb-3 lg:grid lg:px-3">
          {nav.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end}
              className={({ isActive }) => `flex shrink-0 items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition ${isActive ? 'bg-brand-50 text-brand-700' : 'text-ink/80 hover:bg-black/5'}`}>
              <Icon className="size-4" />{label}
            </NavLink>
          ))}
        </nav>
        <div className="absolute inset-x-0 bottom-0 hidden border-t border-line p-4 lg:block">
          <div className="truncate text-sm font-semibold">{user?.name}</div>
          <div className="truncate text-xs text-muted">{user?.email}</div>
          <button onClick={logout} className="mt-3 flex items-center gap-2 text-sm font-semibold text-muted hover:text-ink"><LogOut className="size-4" />Sign out</button>
        </div>
      </aside>
      <main className="min-w-0">
        {banner}
        <div className="mx-auto grid max-w-6xl grid-cols-1 gap-6 px-4 py-6 lg:px-8 lg:py-8"><Outlet context={context} /></div>
      </main>
    </div>
  )
}
