import { Check, Clock, LogOut, Send, X } from 'lucide-react'
import { useAuth } from '../lib/auth'
import { Logo } from '../layouts/CustomerLayout'
import { Card } from './ui'

/** Account → details → review, shown while an owner or rider is signing up. */
export function OnboardingSteps({ step, kind }) {
  const steps = ['Account', kind === 'shop' ? 'Shop details' : 'Rider details', 'Review']
  return (
    <ol className="flex items-center justify-center gap-2 text-xs font-semibold">
      {steps.map((s, i) => (
        <li key={s} className="flex items-center gap-2">
          <span className={`grid size-6 place-items-center rounded-full ${i < step ? 'bg-ink text-white' : i === step ? 'bg-brand-500 text-white' : 'bg-paper text-muted'}`}>
            {i < step ? <Check className="size-3.5" /> : i + 1}
          </span>
          <span className={i === step ? 'text-ink' : 'text-muted'}>{s}</span>
          {i < steps.length - 1 && <span className="h-px w-6 bg-line" />}
        </li>
      ))}
    </ol>
  )
}

/** Plain page frame for owner and rider onboarding (no dashboard navigation yet). */
export function OnboardingShell({ children, wide = false }) {
  const { user, logout } = useAuth()
  return (
    <div className="min-h-screen bg-paper">
      <header className="border-b border-line bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-3">
          <Logo to="/" />
          <button onClick={logout} className="flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-semibold hover:bg-black/5" title={user?.email}>
            <LogOut className="size-4" />Sign out
          </button>
        </div>
      </header>
      <main className={`mx-auto grid grid-cols-1 gap-6 px-4 py-8 ${wide ? 'max-w-5xl' : 'max-w-xl'}`}>{children}</main>
    </div>
  )
}

const COPY = {
  PENDING: {
    label: 'Pending review',
    title: 'Your application is with our team',
    text: "We've sent your application to the QuikE admin team. This page updates by itself as soon as there's a decision.",
  },
  SUSPENDED: {
    label: 'Rejected',
    title: 'Your application was not approved',
    text: "Your application was rejected, or your account has been suspended. Contact QuikE support to find out why and what you can change.",
  },
  APPROVED: { label: 'Approved', title: "You're approved!", text: 'Opening your dashboard…' },
}

/**
 * Where an owner's or rider's application stands. The backend has three states:
 * PENDING (in review), APPROVED, and SUSPENDED, which also covers rejected applications.
 */
export default function ApplicationStatus({ kind, status, name, children }) {
  const c = COPY[status] ?? COPY.PENDING
  const rejected = status === 'SUSPENDED'
  const approved = status === 'APPROVED'
  const steps = [
    { label: 'Application sent', state: 'done', icon: Send, note: name },
    { label: 'Pending review', state: approved || rejected ? 'done' : 'current', icon: Clock, note: 'The admin team checks your details' },
    rejected
      ? { label: 'Rejected', state: 'rejected', icon: X, note: 'Not approved' }
      : { label: 'Approved', state: approved ? 'done' : 'todo', icon: Check, note: kind === 'shop' ? 'Your shop goes live for customers' : 'You can go online and take jobs' },
  ]
  return (
    <>
      <OnboardingSteps step={approved ? 3 : 2} kind={kind} />
      <Card className="grid gap-6 p-6 sm:p-8">
        <div className="grid gap-2">
          <span className={`w-fit rounded-full px-3 py-1 text-xs font-bold uppercase tracking-wider ${rejected ? 'bg-brand-500 text-white' : approved ? 'bg-ink text-white' : 'bg-brand-50 text-brand-700'}`}>
            {c.label}
          </span>
          <h1 className="text-2xl font-extrabold sm:text-3xl">{c.title}</h1>
          <p className="text-sm text-muted">{c.text}</p>
        </div>
        <ol className="grid gap-0">
          {steps.map(({ label, state, icon: Icon, note }, i) => (
            <li key={label} className="relative grid grid-cols-[36px_1fr] gap-4 pb-6 last:pb-0">
              {i < steps.length - 1 && <span className={`absolute left-[17px] top-9 h-[calc(100%-36px)] w-0.5 ${state === 'done' ? 'bg-ink' : 'bg-line'}`} />}
              <span className={`grid size-9 place-items-center rounded-full ${
                state === 'done' ? 'bg-ink text-white' : state === 'current' ? 'animate-pulse bg-brand-500 text-white' : state === 'rejected' ? 'bg-brand-500 text-white' : 'border-2 border-line text-muted'}`}>
                <Icon className="size-4" />
              </span>
              <div className="grid content-center">
                <span className={`font-semibold ${state === 'todo' ? 'text-muted' : ''}`}>{label}</span>
                {note && <span className="text-xs text-muted">{note}</span>}
              </div>
            </li>
          ))}
        </ol>
        {children}
      </Card>
    </>
  )
}
