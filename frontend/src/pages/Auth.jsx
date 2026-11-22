import { useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { Bike, Store } from 'lucide-react'
import { homeFor, useAuth } from '../lib/auth'
import { DEMO_ACCOUNTS } from '../lib/demo'
import { Button, ErrorNote, Field, Input } from '../components/ui'
import { OnboardingSteps } from '../components/ApplicationStatus'

function Shell({ title, subtitle, top, children, footer }) {
  return (
    <div className="grid min-h-screen place-items-center bg-white px-4 py-10">
      <div className="grid w-full max-w-md gap-6">
        <Link to="/" className="justify-self-center"><img src="/brand/quike-logo.png" alt="QuikE, Need it? QuikE it." className="h-24 w-auto" /></Link>
        {top}
        <div className="grid gap-5 rounded-3xl border border-line bg-white p-6 shadow-xl shadow-ink/5 sm:p-8">
          <div className="grid gap-1 text-center">
            <h1 className="text-2xl font-extrabold">{title}</h1>
            {subtitle && <p className="text-sm text-muted">{subtitle}</p>}
          </div>
          {children}
        </div>
        {footer && <div className="text-center text-sm text-muted">{footer}</div>}
      </div>
    </div>
  )
}

function useAfterAuth() {
  const nav = useNavigate()
  const [params] = useSearchParams()
  return (user) => {
    const next = params.get('next')
    // Only follow `next` into the signed-in user's own area.
    nav(next && next.startsWith(homeFor(user.role)) ? next : homeFor(user.role), { replace: true })
  }
}

export function Login() {
  const { user, login } = useAuth()
  const after = useAfterAuth()
  const [form, setForm] = useState({ email: '', password: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  if (user) return <Navigate to={homeFor(user.role)} replace />

  const submit = async (creds) => {
    setBusy(true); setError(null)
    try { after(await login(creds.email, creds.password)) } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <Shell title="Sign in" subtitle="Welcome back to QuikE."
      footer={<>New to QuikE? <Link to="/signup" className="font-semibold text-brand-600">Create an account</Link></>}>
      <form className="grid gap-4" onSubmit={(e) => { e.preventDefault(); submit(form) }}>
        <Field label="Email"><Input type="email" autoComplete="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
        <Field label="Password"><Input type="password" autoComplete="current-password" required value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></Field>
        <ErrorNote error={error} />
        <Button type="submit" size="lg" loading={busy}>Sign in</Button>
      </form>
      {import.meta.env.DEV && (
        <details className="rounded-xl border border-dashed border-line px-3 py-2 text-xs text-muted">
          <summary className="cursor-pointer font-semibold">Dev only: demo accounts (run npm run seed first)</summary>
          <div className="mt-2 grid grid-cols-2 gap-2">
            {DEMO_ACCOUNTS.map((a) => <Button key={a.email} variant="secondary" size="sm" disabled={busy} onClick={() => submit(a)}>{a.label}</Button>)}
          </div>
        </details>
      )}
    </Shell>
  )
}

const VARIANTS = {
  CUSTOMER: { title: 'Create your account', subtitle: 'Groceries from local shops, delivered in minutes.', button: 'Create account' },
  DRIVER: { title: 'Sign up as a rider', subtitle: 'Create your account, add your details, and our team reviews your application.', button: 'Continue' },
  SHOP: { title: 'Sign up as an owner', subtitle: 'Create your account, tell us about your shop, and our team reviews your application.', button: 'Continue' },
}

/** Everyone signs up as a customer by default; riders and owners use their own links. Admins are created by the team. */
export function Signup({ role = 'CUSTOMER' }) {
  const { user, signup } = useAuth()
  const after = useAfterAuth()
  const [form, setForm] = useState({ name: '', email: '', password: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  if (user) return <Navigate to={homeFor(user.role)} replace />

  const v = VARIANTS[role]
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })
  const submit = async (e) => {
    e.preventDefault(); setBusy(true); setError(null)
    try { after(await signup({ ...form, role })) } catch (err) { setError(err) } finally { setBusy(false) }
  }
  return (
    <Shell title={v.title} subtitle={v.subtitle}
      top={role !== 'CUSTOMER' && <OnboardingSteps step={0} kind={role === 'SHOP' ? 'shop' : 'rider'} />}
      footer={<>Already have an account? <Link to="/login" className="font-semibold text-brand-600">Sign in</Link></>}>
      <form className="grid gap-4" onSubmit={submit}>
        <Field label={role === 'SHOP' ? 'Your name (the owner)' : 'Your name'}><Input required autoComplete="name" value={form.name} onChange={set('name')} /></Field>
        <Field label="Email"><Input type="email" required autoComplete="email" value={form.email} onChange={set('email')} /></Field>
        <Field label="Password" hint="At least 8 characters"><Input type="password" required minLength={8} autoComplete="new-password" value={form.password} onChange={set('password')} /></Field>
        <ErrorNote error={error} />
        <Button type="submit" size="lg" loading={busy}>{v.button}</Button>
      </form>
      {role === 'CUSTOMER' ? (
        <div className="grid gap-3 border-t border-line pt-5">
          <p className="text-center text-xs font-semibold uppercase tracking-wider text-muted">Want to earn with QuikE?</p>
          <div className="grid grid-cols-2 gap-2">
            <Link to="/signup/rider" className="flex h-11 items-center justify-center gap-2 rounded-xl border-2 border-ink text-sm font-semibold hover:bg-ink hover:text-white"><Bike className="size-4" />Sign up as a rider</Link>
            <Link to="/signup/owner" className="flex h-11 items-center justify-center gap-2 rounded-xl border-2 border-ink text-sm font-semibold hover:bg-ink hover:text-white"><Store className="size-4" />Sign up as an owner</Link>
          </div>
        </div>
      ) : (
        <p className="border-t border-line pt-4 text-center text-sm text-muted">Just want to shop? <Link to="/signup" className="font-semibold text-brand-600">Sign up as a customer</Link></p>
      )}
    </Shell>
  )
}
