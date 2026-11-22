// Fills a fresh local backend with demo data: 3 approved shops in Surrey BC (open 24/7),
// a catalog of ~30 products with stock, and demo customer / shop / driver accounts.
// Run with the backend up:  npm run seed      (API=http://host:port npm run seed to point elsewhere)
// Safe to run twice: existing accounts, products and listings are reused.
import { DEMO_ACCOUNTS } from '../src/lib/demo.js'

const API = process.env.API ?? 'http://localhost:8080'
const PASSWORD = 'demo-pass-123'

async function call(method, path, token, body) {
  const res = await fetch(API + path, {
    method,
    headers: { ...(token && { Authorization: `Bearer ${token}` }), ...(body !== undefined && { 'Content-Type': 'application/json' }) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) { const e = new Error(`${method} ${path} → ${res.status} ${data?.detail ?? text}`); e.status = res.status; throw e }
  return data
}
async function account({ email, password, name, role }) {
  try { return (await call('POST', '/api/auth/signup', null, { email, password, name, role })).token }
  catch (e) { if (e.status !== 400 && e.status !== 409) throw e; return (await call('POST', '/api/auth/login', null, { email, password })).token }
}

const SHOPS = [
  { email: 'shop@demo.test', owner: 'Sam Shopkeeper', name: 'King George Corner Store', addressLine: '10355 King George Blvd', postalCode: 'V3T 2W5', latitude: 49.1913, longitude: -122.8490, markup: 1.0 },
  { email: 'shop2@demo.test', owner: 'Priya Patel', name: 'Guildford Quick Mart', addressLine: '15155 104 Ave', postalCode: 'V3R 1N5', latitude: 49.1890, longitude: -122.8010, markup: 0.92 },
  { email: 'shop3@demo.test', owner: 'Luc Tremblay', name: 'Fleetwood Fresh & Go', addressLine: '8828 160 St', postalCode: 'V4N 0V8', latitude: 49.1640, longitude: -122.7760, markup: 1.08 },
]

// [name, brand, category, tax, shop price in cents]
const PRODUCTS = [
  ['Maple Syrup Amber 1 L', 'Kirkland', 'Pantry', 'ZERO_RATED', 1199],
  ['Basmati Rice 4 kg', 'Tilda', 'Pantry', 'ZERO_RATED', 1399],
  ['Kraft Dinner Original 6-pack', 'Kraft', 'Pantry', 'ZERO_RATED', 749],
  ['Smooth Peanut Butter 1 kg', 'Kraft', 'Pantry', 'ZERO_RATED', 649],
  ['Poutine Gravy Mix', 'St-Hubert', 'Pantry', 'ZERO_RATED', 229],
  ['2% Milk 4 L', 'Dairyland', 'Dairy', 'ZERO_RATED', 599],
  ['Salted Butter 454 g', 'Lactantia', 'Dairy', 'ZERO_RATED', 599],
  ['Old Cheddar 400 g', 'Black Diamond', 'Dairy', 'ZERO_RATED', 699],
  ['Greek Yogurt Plain 750 g', 'Oikos', 'Dairy', 'ZERO_RATED', 549],
  ['Large Eggs, dozen', 'Burnbrae Farms', 'Dairy', 'ZERO_RATED', 449],
  ['Ketchup Chips 235 g', "Lay's", 'Snacks', 'TAXABLE', 349],
  ['Coffee Crisp 4-pack', 'Nestlé', 'Snacks', 'TAXABLE', 449],
  ['Smarties Share Pack', 'Nestlé', 'Snacks', 'TAXABLE', 299],
  ['Butter Popcorn 3-pack', 'Orville', 'Snacks', 'TAXABLE', 399],
  ['Ginger Ale 12 × 355 mL', 'Canada Dry', 'Drinks', 'TAXABLE', 699],
  ['Orange Juice 1.54 L', 'Tropicana', 'Drinks', 'ZERO_RATED', 499],
  ['Sparkling Water Lime 8-pack', 'Bubly', 'Drinks', 'TAXABLE', 549],
  ['Cold Brew Coffee 1 L', "Tim Hortons", 'Drinks', 'ZERO_RATED', 599],
  ['Bananas, bunch', 'Fresh', 'Produce', 'ZERO_RATED', 199],
  ['Hass Avocados 4-pack', 'Fresh', 'Produce', 'ZERO_RATED', 499],
  ['Gala Apples 3 lb', 'BC Tree Fruits', 'Produce', 'ZERO_RATED', 549],
  ['Rising Crust Pepperoni Pizza', 'Delissio', 'Frozen', 'ZERO_RATED', 799],
  ['Vanilla Ice Cream 1.5 L', "Chapman's", 'Frozen', 'ZERO_RATED', 549],
  ['Wild Blueberries 600 g', 'Oxford', 'Frozen', 'ZERO_RATED', 649],
  ['Sliced White Bread', "Dempster's", 'Bakery', 'ZERO_RATED', 349],
  ['Montreal-Style Bagels 6', 'St-Viateur', 'Bakery', 'ZERO_RATED', 599],
  ['Paper Towels 6 rolls', 'Bounty', 'Household', 'TAXABLE', 1099],
  ['Dish Soap 600 mL', 'Dawn', 'Household', 'TAXABLE', 399],
  ['Laundry Pods 42 ct', 'Tide', 'Household', 'TAXABLE', 1499],
  ['Toothpaste Total 120 mL', 'Colgate', 'Personal care', 'TAXABLE', 349],
]
const ALL_DAY = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((dayOfWeek) => ({ dayOfWeek, opens: '00:00', closes: '23:59:59' }))

const admin = DEMO_ACCOUNTS.find((a) => a.role === 'ADMIN')
const ADMIN = (await call('POST', '/api/auth/login', null, { email: admin.email, password: admin.password })).token
console.log('✓ admin signed in')

// Catalog: create what's missing.
const existing = new Map()
for (let page = 0; ; page++) {
  const r = await call('GET', `/api/admin/catalog?status=APPROVED&size=100&page=${page}`, ADMIN)
  r.content.forEach((p) => existing.set(p.name, p.id))
  if (page + 1 >= r.page.totalPages) break
}
const ids = {}
for (const [name, brand, category, taxCategory] of PRODUCTS) {
  ids[name] = existing.get(name) ?? (await call('POST', '/api/admin/catalog', ADMIN, { name, brand, category, taxCategory, status: 'APPROVED' })).id
}
console.log(`✓ catalog: ${PRODUCTS.length} products`)

// Shops: account, profile, approval, hours, listings. Each shop stocks most products at its own prices.
for (const [si, s] of SHOPS.entries()) {
  const token = await account({ email: s.email, password: PASSWORD, name: s.owner, role: 'SHOP' })
  const shop = await call('PUT', '/api/shops/me', token, {
    name: s.name, phone: `+1 604 555 01${10 + si}`, addressLine: s.addressLine, city: 'Surrey', province: 'BC',
    postalCode: s.postalCode, latitude: s.latitude, longitude: s.longitude,
  })
  await call('PUT', `/api/admin/shops/${shop.id}/status`, ADMIN, { status: 'APPROVED' })
  await call('PUT', `/api/admin/shops/${shop.id}/hours`, ADMIN, { hours: ALL_DAY })
  let listed = 0
  for (const [pi, [name, , , , price]] of PRODUCTS.entries()) {
    if ((pi + si) % 4 === 3) continue // vary what each shop carries
    try {
      await call('POST', '/api/shops/me/products', token, { productId: ids[name], priceCents: Math.round(price * s.markup), quantity: 8 + ((pi * 7 + si * 3) % 30) })
      listed++
    } catch (e) { if (e.status !== 409) throw e }
  }
  console.log(`✓ shop "${s.name}" approved, open 24/7, ${listed} new listings`)
}

// A pending product request so the admin has something to review.
const shopToken = await account({ email: SHOPS[0].email, password: PASSWORD, name: SHOPS[0].owner, role: 'SHOP' })
await call('POST', '/api/shops/me/product-requests', shopToken, { name: 'Nanaimo Bars 6-pack', brand: 'Local bakery', category: 'Bakery' }).catch(() => {})

// Customer and driver.
const c = DEMO_ACCOUNTS.find((a) => a.role === 'CUSTOMER')
await account(c)
const d = DEMO_ACCOUNTS.find((a) => a.role === 'DRIVER')
const driverToken = await account(d)
const driver = await call('PUT', '/api/drivers/me', driverToken, { phone: '+1 604 555 0142' })
await call('PUT', `/api/admin/drivers/${driver.userId}/status`, ADMIN, { status: 'APPROVED' })
console.log('✓ customer and approved driver ready')

console.log('\nDemo logins (all on the login page as one-click buttons in dev):')
for (const a of DEMO_ACCOUNTS) console.log(`  ${a.label.padEnd(11)} ${a.email}`)
console.log('Passwords are in src/lib/demo.js')
