const cad = new Intl.NumberFormat('en-CA', { style: 'currency', currency: 'CAD' })

/** Backend money is always integer cents. */
export const money = (cents) => (cents == null ? '–' : cad.format(cents / 100))
export const toCents = (dollars) => Math.round(parseFloat(dollars) * 100)
export const toDollars = (cents) => (cents == null ? '' : (cents / 100).toFixed(2))
export const km = (n) => (n == null ? '' : `${n.toFixed(1)} km`)

export function timeAgo(iso) {
  if (!iso) return ''
  const s = Math.round((Date.now() - new Date(iso)) / 1000)
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return new Date(iso).toLocaleDateString('en-CA', { month: 'short', day: 'numeric' })
}
export const dateTime = (iso) =>
  iso ? new Date(iso).toLocaleString('en-CA', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) : ''

export const ORDER_STEPS = ['PLACED', 'ACCEPTED', 'READY', 'PICKED_UP', 'DELIVERED']
export const FINAL = ['DELIVERED', 'REJECTED', 'CANCELLED', 'EXPIRED']

export const STATUS_LABEL = {
  PLACED: 'Placed', ACCEPTED: 'Accepted', READY: 'Packed', PICKED_UP: 'On the way', DELIVERED: 'Delivered',
  REJECTED: 'Rejected', CANCELLED: 'Cancelled', EXPIRED: 'Expired',
  PENDING: 'Pending', APPROVED: 'Approved', SUSPENDED: 'Suspended',
}

export const PROVINCES = ['AB', 'BC', 'MB', 'NB', 'NL', 'NS', 'NT', 'NU', 'ON', 'PE', 'QC', 'SK', 'YT']
export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
export const dayLabel = (d) => d[0] + d.slice(1, 3).toLowerCase()
