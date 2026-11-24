# QuikE — Quick Ecommerce Canada

A delivery platform for small local shops in Canada. Shops list what they have in stock. Customers nearby order it from an app. A driver picks the order up and brings it to the door.

This repository holds the whole platform:

- **`backend/`**: one Spring Boot app and one Postgres database that every screen talks to.
- **`frontend/`**: one React web app with the screens for all four roles (customer, shop, rider, admin). It has its own [README](frontend/README.md).

- [What the project is](#what-the-project-is)
- [Project status](#project-status): what is done and what is left
- [How it is put together](#how-it-is-put-together)
- [The web app](#the-web-app)
- [How it works](#how-it-works)
- [Endpoints](#endpoints)
- [How to start the project](#how-to-start-the-project)
- [Deploying](#deploying)
- [Configuration](#configuration)
- [Stripe payments](#stripe-payments)
- [Tests and remaining verification](#tests)

---

## What the project is

In smaller Canadian towns, many people run their own private shops. They buy stock cheaply (Costco and similar) and sell it at a markup. This platform puts those shops online, the way Blinkit or Swiggy Instamart do:

- **Shops** sign up on a website, list their products, and keep stock up to date.
- **Customers** open the app, see products from shops within 5 km, and order.
- **Drivers** pick up the order from the shop and deliver it.
- **Admin** (us) approves shops and drivers, and owns the shared product list.

Prices work like this (admin can change every number):

- The shop lists a product at its own price, say **$3.49**.
- The shop is paid that price **less 15%**: **$2.96**.
- The customer pays **1.5×** the shop's price, rounded **down** to the nearest **.49 or .99**: $5.235 becomes **$4.99**.

**Privacy rule that shapes everything:** a shop's real location is private. Customers only ever see a distance and a delivery time, never the shop's address or exact position.

---

## Project status

QuikE serves independent Canadian shops, customers, drivers and admins. This backend release adds CAD payments, payment recovery and settlement, along with security and concurrency hardening. The React app needs the [payment integration](#payments-frontend-handoff) and the [pricing and pay updates](#api-changes-for-the-web-app) for a paid checkout pilot.

### Done

| Part | What it does |
|---|---|
| Accounts | Sign up and log in as shop, customer or driver; admin from config; login limits; suspended accounts are locked out at once |
| Shops | Profile and location, admin approval, opening hours set by admin in the shop's local time |
| Catalog and stock | Shared product list, shop requests for new products, admin review, each shop's price and stock |
| Orders | Place, accept (with missing items), reject, mark ready, cancel, auto-expire; safe against double taps and overselling |
| Browsing | Product-first feed, change shop, search inside one shop, distance and delivery time; shop location kept private |
| Drivers and delivery | Driver approval, online status, location pings, job list, pickup, delivery code, history |
| Customer tracking | Driver's live position on the map, after pickup only |
| Pricing and fees | Shop paid its price less 15%; customer pays 1.5× rounded down to .49/.99; delivery fee by order size; handling, small-cart, late-night and surge fees; tips; a price quote before ordering |
| Driver pay | Engaged time (from accepting a job until it ends) × $21.99/h plus $0.35/km, as BC and Ontario rules require; two-week pay statements |
| Driver rules | Violations (late delivery, lost location, "not received", giving jobs back, guessing the code); daily warning and 30-day ban review for admin; "Did you receive your order?" for suspicious deliveries |
| Reviews | Customers rate the shop, rider and products; riders rate the shop and customer; averages shown while browsing |
| Admin | Dashboard, daily charts data, order board, cancel, assign drivers by hand, every price, fee, pay and violation setting, driver alerts, pay periods, reviews |
| Payments | Stripe Checkout card authorization, adjusted capture after shop acceptance, voids and cancellation refunds, separate post-delivery tips, and admin-triggered Stripe Connect transfers |
| Payment recovery | PostgreSQL worker claims, Stripe idempotency keys, bounded retries, and admin review when a payment or transfer remains unresolved |
| Security and concurrency | Request and checkout limits, stock and order race protection, idempotency key validation, delivery-code throttling, HS256 token verification and security headers |
| Web app (`frontend/`) | Landing page; customer shopping, checkout, orders and live tracking; shop orders, products and profile; rider jobs, pickup and delivery; full admin console. Sign-up for riders and shop owners with a "pending review" screen until approved |

### Left

**Before launch**

| Item | Why |
|---|---|
| Frontend integration | Wire the [payment screens and states](#payments-frontend-handoff) and walk a paid order through every role. |
| Stripe Connect verification | Enable Connect in the sandbox and verify a shop transfer and a driver-period transfer against Stripe. |
| Staging verification | Run through the deployed proxy, database and worker configuration using the [test checklist](#remaining-testing-before-launch). |
| Map tile service | Set up the production map provider and verify usage limits. |

**Waiting on a decision**

| Feature | Needs |
|---|---|
| Sales tax in order totals | The launch province. Each product already has a tax category. |
| Password reset | An email provider |

**Deferred**

- **Push notifications.** Shops, drivers and customers poll for updates for now.
- **Image upload.** Products have an `imageUrl` text field only.

**Later**

- **Phone login for customers.** Needs an SMS provider. Customers use email and password for now, so drivers have no customer phone number to call.
- **Refresh tokens.** Logins last 12 hours.
- **Multi-shop carts.** One order goes to one shop.
- **Barcode-assisted product entry.** Shops currently register and list products manually.

**Next steps**

| Step | Status |
|---|---|
| Staging deployment | Follow [Deploying](#deploying) and start with Stripe sandbox credentials. |
| Pilot with 3 to 5 shops and your own riders | After frontend integration, sandbox transfer verification and staging tests. |
| Phone apps in the App Store and Google Play | Not built. Customers and riders use the web app in their phone's browser for now |

---

## How it is put together

```
Web app (frontend/)                       Spring Boot backend (backend/)
customer · shop · rider · admin  ──────►  REST API + rules  ──────►  Postgres
React, in any browser or phone            the only thing that touches the database
```

**Backend:** Java 21, Spring Boot 4.1, Spring Security (JWT), Spring Data JPA, Flyway, Postgres 17, springdoc-openapi.

**Web app:** React 19, Vite, React Router, TanStack Query, Tailwind CSS, Leaflet maps. Details in [frontend/README.md](frontend/README.md).

**Why one app and one database:** the backend is the only thing that touches the database. Every shop's data lives in the same tables, marked with a `shop_id`. There is no copy step, so a stock change shows to customers immediately.

### Folder layout

```
frontend/                       the web app (see frontend/README.md)
  src/pages/                    customer/, shop/, driver/, admin/ screens
  src/lib/api.js                every backend call goes through here
  scripts/seed.mjs              fills a local backend with demo shops, products and logins
backend/
  pom.xml
  src/main/java/com/quickcommerce/
    auth/       signup, login, tokens, login limits
    shop/       shop profile and admin approval
    catalog/    shared product list, shop products, stock
    order/      placing orders and every order status change
    browse/     the customer's product feed and search
    dispatch/   drivers, job list, delivery steps, tracking
    admin/      the admin dashboard summary
    common/     security setup, error format, paging, geo helpers
  src/main/resources/
    application.properties      settings (see Configuration)
    db/migration/              database schema, applied by Flyway at startup
  src/test/java/com/quickcommerce/
    *Test.java                  tests, run against a real Postgres
    DevApplication.java         starts the app locally with a throwaway database
```

Each part keeps its own tables. Other parts reach it only through its service class (for example, orders take stock through `StockService`, never by writing to the stock table).

---

## The web app

One React app serves everyone; each area is locked to one role after login.

| Who | What they get |
|---|---|
| Guest | Landing page; any product click goes to sign in |
| Customer | Product feed by aisle, search, "change shop", one shop's aisle, cart, checkout with a map pin, orders with live rider tracking and the delivery code |
| Shop owner | Sign-up, then "pending review" until admin approves. Orders inbox (new, packing, ready, out for delivery), products and stock, profile |
| Rider | Sign-up, then "pending review". One phone-sized page: go online, job list, pickup card, delivery card with the code box, earnings |
| Admin | Dashboard, orders (cancel, assign rider), shops (approve, hours), catalog, riders, users, prices and fees |

The app follows the same privacy rules as the backend (see [Who sees what](#who-sees-what)). At the shop counter, the rider says the order number, and the shop hands that order over.

The full page-by-endpoint map and a step-by-step test order are in [frontend/README.md](frontend/README.md). The web app has no automated tests yet; that walkthrough is how it is checked.

### API changes for the web app

The pricing, pay and review update changed some API answers. The web app still works, but these screens need updating:

| Screen | Change |
|---|---|
| Checkout | Call `POST /api/orders/quote` and show every fee before "Place order". Offer tip buttons from `tipOptionsCents`, and send `tipCents` |
| Customer order page | New fields: `handlingFeeCents`, `smallCartFeeCents`, `lateNightFeeCents`, `surgeFeeCents`, `tipCents`. `totalCents` now includes them. When `receiptStatus` is `ASKED`, show "Did you receive your order?" (`POST /api/orders/{id}/receipt`). After delivery, offer a tip and a review |
| Customer feed | New: `rating` on each product, `offer.shopRating` next to the distance |
| Shop products | New: `payoutCents` next to `priceCents`, so the shop sees its 15% cut |
| Shop orders | `unitPriceCents` is now the shop's payout per unit |
| Rider | `payCents` is an estimate until delivered. New: `tipCents` after delivery, pay statements, violations, and a review of the shop and customer. Going offline is refused while holding an order |
| Admin settings | `deliveryFeeCents` and `driverPayCents` are gone; the settings screen should show every field from `GET /api/admin/settings` |
| Admin | New: charts from `/api/admin/metrics`, driver alerts, pay periods, and reviews |

---

## How it works

### Roles

Every user has one role. The login token carries it, and each group of endpoints is locked to one role.

| Role | How they get it | What they can use |
|---|---|---|
| `SHOP` | Sign up (the default role) | `/api/shops/me/**` |
| `CUSTOMER` | Sign up with `"role": "CUSTOMER"` | `/api/orders/**`, `/api/browse/**` |
| `DRIVER` | Sign up with `"role": "DRIVER"` | `/api/drivers/me/**` |
| `ADMIN` | Created at startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD` | `/api/admin/**` |

Nobody can sign up as admin. A new shop or driver can do nothing useful until admin approves them. Admin can also suspend any non-admin account: it cannot log in, and tokens it already holds stop working at once.

### The life of an order

With Stripe enabled:

```
PAYMENT_PENDING → PLACED → CAPTURE_PENDING → ACCEPTED → READY → PICKED_UP → DELIVERED
```

The customer can cancel in `PAYMENT_PENDING` or `PLACED`. A shop can reject a `PLACED` order. Unpaid orders and orders the shop leaves unanswered can expire. The development flow with Stripe disabled starts at `PLACED` and goes straight to `ACCEPTED` after shop acceptance.

1. **Customer places an order.** The backend works out every price itself. Stock is taken in the same database step that creates the order, so two customers can never buy the last item.
2. **Shop accepts or rejects.** It can mark items it turns out not to have; those are not charged and not paid out. Rejecting, cancelling or expiring puts the stock back.
3. **A driver takes the job.** A paid job opens once capture succeeds and the order reaches `ACCEPTED`. The driver can travel while the shop packs.
4. **Shop marks it ready, driver picks it up.**
5. **At the door,** the customer reads a 4-digit code from their app. The driver enters it to finish the delivery.

Each step works only from the step before. Steps cannot be skipped or done twice.

Admins can cancel unfinished orders outside `CAPTURE_PENDING` or assign a driver before pickup. Cancellation restores remaining stock before pickup. During `CAPTURE_PENDING`, wait for the payment to resolve before attempting another action.

### Prices

For each product, from the shop's own price **P**:

| | Rule | Example (P = $3.49) |
|---|---|---|
| Shop is paid | P less the platform's cut (15%), rounded down to the cent | $2.96 |
| Customer pays | P × markup (150%), rounded **down** to the nearest .49 or .99 | $5.235 → **$4.99** |

If rounding down would put the customer price at or below the shop's payout (only for very cheap items), it rounds up to the next .49/.99 instead, so no item is sold at a loss. A category can have its own markup.

### Order fees

| Fee | Rule (starting values) |
|---|---|
| Delivery | Items under $50: **$5.99** · $50 to $99.99: **$4.99** · $100 and up: **free** |
| Handling | **$1.99** on every order |
| Small cart | **$2.99** when items total under **$15** |
| Late night | **$1.99** between **11 pm and 6 am** in the shop's time zone |
| Surge | **$1.99** when orders waiting for a driver near the shop are at least **2×** the online drivers near it. Admin can also switch it on or off by hand |
| Tip | Optional: **$1, $2, $5 or $10**, at checkout or within 24 h of delivery. Goes to the driver in full; the driver sees it only after delivering |

`POST /api/orders/quote` returns all of these for a cart before ordering. Every fee is fixed on the order when it's placed and never goes up afterwards, even if the shop can't supply an item.

### Driver pay

- **Engaged time:** from accepting a job until it ends (delivered, given back, taken off them, or cancelled), including the trip to the shop and waiting there. This is what BC's and Ontario's rules for app-based delivery work count.
- **Rate:** engaged hours × **$21.99** plus **$0.35 per km**. Km are the straight-line distance from where the driver accepted to the shop, then to the door, ×1.3 for roads.
- **Pay periods:** two weeks, starting Tuesday 1 December 2026, paid on the Tuesday after each period. Drivers see a statement per period (hours, km, pay, tips); admin sees every driver's and marks each one paid. With Stripe enabled, an admin queues the finished period for a transfer to the driver’s Stripe Connect balance.
- **Check before launch:** BC's minimum is $21.89 an hour of engaged time (from 1 June 2026, rising each June) plus $0.35/km. Confirm the current rules for your launch province with an employment lawyer or accountant.

### Driver rules

Each of these counts as a violation:

| Violation | When |
|---|---|
| Late delivery | Over **20 minutes** from pickup to door |
| Location lost | No location for over **10 minutes** while holding an order (phone dead, no signal) |
| Not received | The customer answers "No" to "Did you receive your order?" |
| Job given back | The driver gives back a job they accepted |
| Code guessing | The driver uses up the 5 delivery-code tries |

- **3 violations in one day:** admin gets a warning alert.
- **6 in 30 days:** admin gets a "ban review" alert, and decides to dismiss it or suspend the driver.
- **Driver's view:** drivers can see their own counts and recent violations.
- **Going offline:** a driver cannot go offline while holding an order.
- **"Did you receive your order?"** is asked when a driver marks an order delivered more than 200 m from the address, or lost location during the trip. Customers can also report "not received" within 24 h of any delivery. A "No" alerts admin.

### Reviews

1 to 5 stars, once per person per order, within 7 days of delivery.

| Who | Rates |
|---|---|
| Customer | The shop, the rider, and each product received |
| Rider | The shop (was the pickup smooth?) and the customer |

Customers see product ratings and the shop's rating while browsing, next to the distance, never the shop's name. Admin can read every review and remove abusive ones.

### Opening hours

Admin sets each shop's hours: one time window per day, in the shop's local time zone (taken from its province). A shop is shown to customers, and can take orders, only while it's open. A shop with no hours is closed.

### What admin can change

Everything above, without a redeploy, through `PUT /api/admin/settings`:

- the cut and markup, plus markups per category
- delivery tiers, and the handling, small-cart, late-night and surge fees
- the late-night hours, and surge mode and ratio
- the driver hourly and per-km rates
- the tip options
- the violation limits and times

A placed order keeps its prices and fees forever, so a change never alters a past order. `GET /api/admin/metrics` gives daily figures to chart the effect of a change:

- **Orders:** placed, delivered, lost.
- **Money:** sales, shop payouts, item margin, fees, tips, driver pay and net.
- **Delivery:** delivery times, late deliveries, surge orders.
- **Quality:** violations and ratings.

### Who sees what

| | Customer | Shop | Driver | Admin |
|---|---|---|---|---|
| Shop name | After ordering | Own shop | After accepting the job | Yes |
| Shop address and exact location | Never | Own shop | After accepting the job | Yes |
| Distance to the shop | Yes (from a shifted point) | — | Yes | — |
| Customer name and address | Own | Never | After pickup | Yes |
| Which products are in the order | Yes | Yes | Never, only how many units | Yes |
| Prices | Customer price and fees | Own price and payout | Own pay (an estimate until delivered) | All of them, plus margin and net |
| Tip | Own | Never | After delivering | Yes |
| Driver's live position | After pickup only | Never | Own | — |
| Delivery code | Yes | Never | Never (the customer reads it out) | Never |

**Why distances use a shifted point:** with exact distances, three lookups from different places would reveal where a shop is. So every distance a customer sees is measured from a point 150 to 400 m away from the real one, chosen once per shop.

### Safe under load

- **Stock** only changes through single database statements that refuse to go below zero. Tested with 40 customers racing for 10 items (exactly 10 orders) and 100 customers racing for the last one (exactly 1).
- **Double taps:** the app sends an `idempotencyKey` with each checkout. Sending the same one again returns the first order instead of a second one.
- **Races between people** (shop accepts while the customer cancels, 20 drivers accept one job) always end with exactly one winner.
- **Limits:** 300 requests per account per minute (600 per IP without a login), 10 checkouts and 60 price quotes per customer per minute, 5 wrong passwords per email in 15 minutes, 60 signups or logins per IP per minute, 5 delivery code tries per order in a 15-minute window, request bodies up to 64 KB, pages up to 100 rows. PostgreSQL stores delivery code attempts so they survive restarts and apply across replicas. Rate limits return `429`. The general request limiter includes `Retry-After`. Checkout and quotes share 5 slots by default. If all slots are busy, requests wait up to 0.2 seconds before they receive `503`.
- **Overload:** if the database is busy, a request waits at most 3 seconds and then gets `503` with `Retry-After: 2`, instead of hanging. No query may run longer than 5 seconds, or wait more than 2 seconds for a row another request has locked.

Measured on a laptop, with the app, database and load tool on one machine, so treat these as a floor rather than production numbers. Browsing and admin used 120 shops and 30,000 stocked products; the driver and tracking rows used 100 online drivers and 2,000 finished deliveries. No request failed.

| Request | Clients at once | Requests per second | 95% answered within |
|---|---|---|---|
| Home feed (with ratings) | 50 | 1,400 | 67 ms |
| Home feed (with ratings) | 200 | 1,380 | 279 ms |
| Search | 50 | 3,300 | 28 ms |
| Change-shop list | 200 | 12,100 | 50 ms |
| Price quote (with surge check) | 50 | 6,800 | 13 ms |
| Admin metrics, 30 days | 20 | 9,700 | 5 ms |
| Driver location ping | 100 | 12,400 | 15 ms |
| Driver job list | 400 | 11,000 | 53 ms |
| Customer tracking | 200 | 15,600 | 33 ms |
| Admin order board | 50 | 5,400 | 17 ms |
| Login (deliberately slow password check) | 50 users | 96 | 720 ms |

---

## Endpoints

The full, always up-to-date contract is published by the running app:

Both are on when running locally and off in production unless `API_DOCS_ENABLED=true`.

- **Swagger UI:** <http://localhost:8080/swagger-ui.html>
- **OpenAPI JSON:** <http://localhost:8080/v3/api-docs> (generate a typed frontend client from this)

### Basics

- **Login:** send the token from signup or login on every other request as `Authorization: Bearer <token>`. Tokens last 12 hours.
- **Money** is always in cents (`priceCents: 599` is $5.99).
- **Locations** are `latitude` and `longitude` in degrees.
- **Lists** return pages: `?page=0&size=20` (size up to 100). The answer looks like `{"content": [...], "page": {"size", "number", "totalElements", "totalPages"}}`. The browse feed is the exception: it returns `hasMore` instead of totals.
- **Errors** come back as JSON with `status`, `title` and `detail`. Form errors add `errors`, a map of field to message:

```json
{ "status": 400, "title": "Bad Request", "detail": "Invalid request content.",
  "errors": { "name": "must not be blank", "postalCode": "must be a Canadian postal code" } }
```

| Status | Meaning |
|---|---|
| `400` | Something in the request is wrong |
| `401` | Not logged in, or the token is invalid or expired |
| `403` | Logged in, but this role cannot use this endpoint |
| `404` | Not found, or not yours (other people's data always answers 404) |
| `409` | Not possible right now: out of stock, someone else got there first, wrong order step |
| `411` / `413` | Body has no declared size, or is over 64 KB |
| `429` | Too many attempts, slow down |
| `503` | Server busy, try again shortly |

### Auth (anyone)

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/auth/signup` | Create an account. Returns `201` with a token. |
| `POST` | `/api/auth/login` | Log in. Returns a token. |
| `GET` | `/api/auth/me` | The logged-in user. |

```json
POST /api/auth/signup
{ "email": "owner@example.com", "password": "at-least-8-chars", "name": "Asha", "role": "SHOP" }

→ 201 { "token": "eyJ...", "user": { "id": 2, "email": "owner@example.com", "name": "Asha", "role": "SHOP" } }
```

`role` is `SHOP` (the default when left out), `CUSTOMER` or `DRIVER`.

### Shop (role `SHOP`)

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/shops/me` | My shop. `404` until it is created. |
| `PUT` | `/api/shops/me` | Create or update my shop. New shops start as `PENDING`. Opening hours are set by admin. |
| `GET` | `/api/shops/me/products` | My products: my price (`priceCents`), what I'm paid per unit (`payoutCents`), and stock. |
| `POST` | `/api/shops/me/products` | Add a catalog product with my price and starting stock. |
| `PUT` | `/api/shops/me/products/{id}` | Change my price. |
| `POST` | `/api/shops/me/products/{id}/stock` | Change stock: `+n` when stock arrives, `-n` to correct. |
| `DELETE` | `/api/shops/me/products/{id}` | Stop selling a product. |
| `POST` | `/api/shops/me/product-requests` | Ask admin to add a product that is not in the catalog. |
| `GET` | `/api/shops/me/orders?status=PLACED` | My orders, newest first. `status=PLACED` is the inbox. |
| `GET` | `/api/shops/me/orders/{id}` | One order. |
| `POST` | `/api/shops/me/orders/{id}/accept` | Accept, optionally listing items I don't have. |
| `POST` | `/api/shops/me/orders/{id}/reject` | Reject. Stock goes back. |
| `POST` | `/api/shops/me/orders/{id}/ready` | Packed and waiting for the driver. |

```json
PUT /api/shops/me
{ "name": "Corner Stop", "phone": "+1 604 555 0100",
  "addressLine": "12 Main St", "city": "Surrey", "province": "BC", "postalCode": "V3T 1A1",
  "latitude": 49.19, "longitude": -122.85 }

POST /api/shops/me/products              { "productId": 1, "priceCents": 1200, "quantity": 10 }
PUT  /api/shops/me/products/1            { "priceCents": 1299 }
POST /api/shops/me/products/1/stock      { "change": 6 }
POST /api/shops/me/product-requests      { "name": "Poutine Sauce", "brand": "St-Hubert", "category": "Pantry" }
POST /api/shops/me/orders/7/accept       { "unavailableItemIds": [12] }
```

- `province` is a two-letter code (`BC`, `ON`, ...). `postalCode` is a Canadian code like `V3T 1A1`.
- `latitude` and `longitude` come from the map pin in the shop's sign-up form.
- The shop's `timeZone` is set from its province, and `hours` (read-only here) are what admin set.
- A shop never sees the customer's name, address or the customer price. Its order view shows its payout per unit (`unitPriceCents`), `payoutCents`, and `cancelReason` if admin cancelled.
- Orders the shop doesn't answer within 10 minutes expire, and their stock comes back.

### Admin (role `ADMIN`)

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/admin/stats` | Dashboard: orders waiting for a shop, needing a driver, on the road, placed and delivered in 24 h; shops, drivers and product requests pending; drivers online; open driver alerts. |
| `GET` | `/api/admin/metrics?from=&to=` | One row per day (default: last 30 days, at most 366) plus totals, for charts. |
| `GET` | `/api/admin/shops?status=PENDING` | Shops waiting for approval (or any status, or all). |
| `GET` | `/api/admin/shops/{id}` | One shop, with its real location and hours. |
| `PUT` | `/api/admin/shops/{id}/status` | `APPROVED`, `SUSPENDED` or `PENDING`. |
| `PUT` | `/api/admin/shops/{id}/hours` | Replace the shop's opening hours for the whole week. |
| `GET` | `/api/admin/orders` | Every order, newest first. Filters: `status`, `shopId`, `customerId`, `driverId`, `needsDriver=true`. |
| `GET` | `/api/admin/orders/{id}` | One order with everything: both prices, every fee, tip, shop payout, item margin, driver pay, net, all three parties. |
| `POST` | `/api/admin/orders/{id}/cancel` | Cancel an unfinished order with a reason. While capture is processing, poll payment state and retry when it resolves. |
| `POST` | `/api/admin/orders/{orderId}/assign` | Give an order waiting for pickup to an approved driver (replaces any driver it had). |
| `POST` | `/api/admin/orders/{orderId}/unassign` | Take it off its driver before pickup; it goes back to the job list. |
| `GET` | `/api/admin/settings` | Every setting (see [What admin can change](#what-admin-can-change)) and the per-category markups. |
| `PUT` | `/api/admin/settings` | Change any settings. Send only the fields to change. |
| `PUT` | `/api/admin/settings/markups/{category}` | Set a markup for one category. |
| `DELETE` | `/api/admin/settings/markups/{category}` | That category goes back to the default markup. |
| `GET` | `/api/admin/users?role=&q=` | Accounts, searched by email or name. |
| `PUT` | `/api/admin/users/{id}/disabled` | Suspend (`true`) or restore (`false`) an account. Not for admins. |
| `GET` | `/api/admin/catalog?status=PENDING&q=` | Catalog by status. `PENDING` is the shops' product requests. |
| `POST` | `/api/admin/catalog` | Add a catalog product. |
| `PUT` | `/api/admin/catalog/{id}` | Edit a product. Approve or reject a request by changing `status`. |
| `GET` | `/api/admin/drivers?status=PENDING` | Drivers by status. |
| `PUT` | `/api/admin/drivers/{userId}/status` | `APPROVED` or `SUSPENDED`. Suspending takes the driver offline at once. |
| `GET` | `/api/admin/drivers/{userId}/violations` | A driver's violation counts and list. |
| `GET` | `/api/admin/driver-alerts?open=true` | Daily warnings, ban reviews and "not received" reports, newest first. |
| `POST` | `/api/admin/driver-alerts/{id}/resolve` | `DISMISS`, or `SUSPEND` the driver. |
| `GET` | `/api/admin/payouts?periodStart=` | Every driver's pay for a two-week period (default: the last finished one). |
| `POST` | `/api/admin/payouts/{driverId}/{periodStart}/paid` | Record manual payment for a finished period with Stripe disabled. |
| `GET` | `/api/admin/reviews?subjectType=&subjectId=` | Reviews of a `SHOP`, `PRODUCT`, `DRIVER` or `CUSTOMER`, with the average. |
| `DELETE` | `/api/admin/reviews/{id}` | Remove a review; the average updates. |

```json
PUT  /api/admin/shops/1/status       { "status": "APPROVED" }
POST /api/admin/catalog              { "name": "Maple Syrup 1L", "brand": "Kirkland", "barcode": "096619756803",
                                       "category": "Pantry", "taxCategory": "ZERO_RATED", "status": "APPROVED" }
```

```json
PUT  /api/admin/shops/1/hours        { "hours": [ { "dayOfWeek": "MONDAY", "opens": "08:00", "closes": "22:00" },
                                                  { "dayOfWeek": "SATURDAY", "opens": "00:00", "closes": "23:59:59" } ] }
PUT  /api/admin/settings             { "shopCutPercent": 15, "deliveryFeeLowCents": 599, "surgeMode": "AUTO",
                                       "lateNightStart": "23:00", "lateNightEnd": "06:00", "tipOptionsCents": [100, 200, 500, 1000] }
PUT  /api/admin/settings/markups/Dairy   { "markupPercent": 130 }
POST /api/admin/orders/7/cancel      { "reason": "Customer asked by phone" }
POST /api/admin/orders/7/assign      { "driverId": 12 }
POST /api/admin/driver-alerts/3/resolve  { "action": "SUSPEND" }
PUT  /api/admin/users/9/disabled     { "disabled": true }
```

- `taxCategory` is `ZERO_RATED` (basic groceries) or `TAXABLE`. Catalog `status` is `PENDING`, `APPROVED` or `REJECTED`.
- Hours: days left out are closed. A window cannot run past midnight; for all day use `00:00` to `23:59:59`.
- Markups are percents from 100 to 1000. Category names must match the catalog exactly (see `GET /api/catalog/categories`).
- Cancelling before pickup puts the stock back. After pickup it does not, because the goods have left the shop.

### Catalog search (any logged-in user)

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/catalog?q=maple` | Approved catalog products by name, brand or exact barcode. Shops use this to find products to add. |
| `GET` | `/api/catalog/categories` | Every category with an approved product, A to Z. For category tabs. |

### Browsing (role `CUSTOMER`)

Every browse call takes the customer's delivery point as `lat` and `lng`. Only approved shops that are open right now and within the delivery range (5 km), approved products, and items in stock are shown.

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/browse/products?lat=&lng=&q=&category=` | Home feed and search. Each product once, from the nearest shop that has it. |
| `GET` | `/api/browse/products/{productId}/offers?lat=&lng=` | "Change shop": every nearby shop that has this product, nearest first. |
| `GET` | `/api/browse/shops/{shopId}/products?lat=&lng=&q=` | Browse or search inside one shop. |

```json
GET /api/browse/products?lat=49.2&lng=-122.86&q=maple

{ "content": [
    { "productId": 1, "name": "Maple Syrup 1L", "brand": "Kirkland", "imageUrl": null, "category": "Pantry",
      "offer": { "shopProductId": 1, "shopId": 1, "priceCents": 1800, "distanceKm": 1.3, "etaMinutes": 14 },
      "otherShops": 2 } ],
  "page": 0, "size": 20, "hasMore": false }
```

- `offer` is what goes in the cart: send its `shopId` and `shopProductId` to `POST /api/orders`. `priceCents` is the customer's price, the same one that is charged.
- `otherShops` > 0 means the app can show "change shop".
- `etaMinutes` = 10 minutes of prep + 3 minutes per km.
- No shop name, address or exact position is ever returned here.

### Orders (role `CUSTOMER`)

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/orders/quote` | What a cart would cost now: every fee, the total, and how much more for free delivery. Places nothing. |
| `POST` | `/api/orders` | Place an order. `201` new, `200` if the same `idempotencyKey` was already used. |
| `GET` | `/api/orders` | My orders, newest first. |
| `GET` | `/api/orders/{id}` | One order, including the `deliveryCode`. |
| `POST` | `/api/orders/{id}/cancel` | Cancel while the shop has not answered. |
| `GET` | `/api/orders/{id}/tracking` | Order status and, after pickup, the driver's position and minutes away. |
| `POST` | `/api/orders/{id}/tip` | Add a tip to an order from the legacy flow without Stripe. |
| `GET` | `/api/orders/{id}/payment` | Read the order's Stripe payment state and Checkout URL when available. |
| `POST`, `GET` | `/api/orders/{id}/tip/checkout` | Start and read a separate Stripe tip payment within 24 hours of delivery. |
| `POST` | `/api/orders/{id}/receipt` | Answer "Did you receive your order?" (`{"received": true}`), or report it missing within 24 h. |
| `POST` | `/api/orders/{id}/reviews` | Rate the shop, rider and products after delivery. |
| `GET` | `/api/orders/{id}/reviews` | What I wrote for this order (empty = not reviewed yet). |

```json
POST /api/orders
{ "shopId": 1,
  "idempotencyKey": "3f2b8c1e-9d4a-4c55-8a10-2b7e5d0c9f61",
  "items": [ { "shopProductId": 1, "quantity": 2 } ],
  "address": { "addressLine": "77 Customer Rd", "city": "Surrey", "province": "BC", "postalCode": "V3T 2B2",
               "latitude": 49.2, "longitude": -122.86 },
  "note": "Ring the bell", "tipCents": 200 }

POST /api/orders/7/reviews
{ "shopStars": 5, "driverStars": 4, "products": [ { "productId": 1, "stars": 5 } ], "comment": "Quick!" }
```

- One cart = one shop for now. Up to 50 lines, 1 to 99 of each.
- `idempotencyKey`: make a new random UUID per checkout attempt. An identical retry returns the original order. A changed request with the same key returns `409` for orders created after this change.
- If a line is out of stock or no longer approved, checkout fails with `409`. The error's `shopProductId` identifies the line. Quotes also reject listings that are no longer approved.
- Order totals over 2,147,483,647 cents are rejected before any stock change commits.
- A customer can have up to three orders across `PAYMENT_PENDING`, `PLACED` and `CAPTURE_PENDING`.
- `409` "This shop is closed right now" if the shop closed since the customer browsed.
- If admin cancels, the order shows `status: CANCELLED` with a `cancelReason`.
- `driverName` is the rider's name once one has taken the job (`null` before). Shops never get it: the rider tells the shop the order number at the counter.
- Totals: `subtotalCents` + `deliveryFeeCents` + `handlingFeeCents` + `smallCartFeeCents` + `lateNightFeeCents` + `surgeFeeCents` + `tipCents` = `totalCents`. Items the shop marked unavailable are not counted.
- `receiptStatus` is `ASKED` when the app should show "Did you receive your order?".

Tracking, while the order is on the way:

```json
GET /api/orders/7/tracking
{ "status": "PICKED_UP", "driverAssigned": true,
  "driver": { "latitude": 49.19, "longitude": -122.84, "locatedAt": "2026-11-17T05:43:11Z", "etaMinutes": 8 } }
```

`driver` is `null` before pickup (the driver is at the shop, and showing them would show where the shop is) and after delivery. The app polls this every few seconds.

### Driver (role `DRIVER`)

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/drivers/me` | My driver profile. |
| `PUT` | `/api/drivers/me` | Create or update my profile. New drivers start as `PENDING`. |
| `POST` | `/api/drivers/me/online` | Start a shift, with my current position. Approved drivers only. |
| `POST` | `/api/drivers/me/offline` | End the shift. Not while holding an order. |
| `POST` | `/api/drivers/me/location` | Location ping, every few seconds while online. `409` = not online, stop pinging. |
| `GET` | `/api/drivers/me/jobs` | Open jobs whose shop is within 8 km of me, oldest first. |
| `POST` | `/api/drivers/me/jobs/{orderId}/accept` | Take a job. `409` if another driver was faster. |
| `GET` | `/api/drivers/me/delivery` | My current delivery. `404` when I have none. |
| `POST` | `/api/drivers/me/delivery/release` | Give the job back before pickup. |
| `POST` | `/api/drivers/me/delivery/picked-up` | Collected from the shop (after the shop marks it ready). |
| `POST` | `/api/drivers/me/delivery/delivered` | Delivered, with the customer's code. |
| `GET` | `/api/drivers/me/deliveries` | My finished deliveries, with pay and tip. |
| `GET` | `/api/drivers/me/pay-statements?count=6` | Two-week pay statements, the current one first. |
| `GET` | `/api/drivers/me/violations` | My violations today and in 30 days, against the limits. |
| `POST` | `/api/drivers/me/deliveries/{orderId}/reviews` | Rate the shop and the customer of a delivery. |

`payCents` on the job list and the current delivery is an estimate (hourly rate and per-km allowance for the distances). After delivery it is the actual pay for the engaged time, and `tipCents` appears.

```json
PUT  /api/drivers/me                     { "phone": "+1 604 555 0142" }
POST /api/drivers/me/online              { "latitude": 49.2, "longitude": -122.86 }
POST /api/drivers/me/location            { "latitude": 49.201, "longitude": -122.861 }
POST /api/drivers/me/delivery/delivered  { "code": "4821" }
```

What the driver sees changes at each step:

| Step | `GET /jobs` or `/delivery` returns |
|---|---|
| Job list | `units`, `payCents`, `pickupKm`, `dropKm`, `dropoffCity`. No names or addresses. |
| After accepting | `pickup`: shop name, phone, address, exact `latitude`/`longitude` for navigation. `dropoff` is `null`. |
| After pickup | `dropoff`: customer name, address, exact position, and the delivery `note`. |

The driver never sees which products are in the bag, only how many units. Turn-by-turn directions are done by the phone's maps app using the coordinates above. A driver carries one order at a time, and gets 5 tries at the delivery code.

---

## How to start the project

### What you need

- **Java 21** (`java -version`)
- **Maven 3.9+** (`mvn -version`)
- **Node.js 22** (`node -v`), for the web app only

That's all for development. Tests and the local run download a real Postgres 17 on first use. No Docker or Postgres install is needed.

The backend commands below run from the `backend` folder; the web app's from `frontend`.

```bash
cd backend
```

### Run the tests

```bash
mvn test
```

The first run takes a few minutes to download dependencies and Postgres. After that it takes under a minute.

### Run the app locally

```bash
mvn spring-boot:test-run
```

- The API runs on <http://localhost:8080>; open <http://localhost:8080/swagger-ui.html> to try every endpoint in the browser.
- It uses a throwaway database: all data is lost when you stop the app.
- An admin account is created for you. Its email and password are in [`src/test/resources/application-dev.properties`](backend/src/test/resources/application-dev.properties).
- The web app (next section) reaches it through its own dev server, so no CORS setting is needed locally.

### Run the web app

With the backend running, in a second terminal:

```bash
cd frontend
```

```bash
npm install
```

```bash
npm run seed
```

```bash
npm run dev
```

- Open <http://localhost:5173>.
- `npm run seed` creates 3 open shops in Surrey, BC, 30 products, and demo customer, shop owner, rider and admin logins. Run it after every backend restart, because the local database starts empty.
- On the login page, open **Dev only: demo accounts** for one-click logins. These buttons do not exist in the production build.
- Customers only see shops within 5 km, so the demo works around the default pin in Surrey. To test elsewhere: `npm run seed -- --near <latitude>,<longitude>`.
- To try a whole order as each role in turn, follow "Test one order end to end" in [frontend/README.md](frontend/README.md).

### Try it from the terminal

With the app running, this signs up a shop, has admin approve it and open it every day, and shows the shop:

```bash
SHOP=$(curl -s -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"owner@demo.test","password":"demo-pass-123","name":"Demo Owner"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s -X PUT localhost:8080/api/shops/me -H "Authorization: Bearer $SHOP" -H 'Content-Type: application/json' \
  -d '{"name":"Demo Mart","phone":"+1 604 555 0199","addressLine":"1 King St","city":"Surrey","province":"BC","postalCode":"V3T 1A1","latitude":49.19,"longitude":-122.85}'

ADMIN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@quickcommerce.test","password":"dev-admin-pass-1"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s -X PUT localhost:8080/api/admin/shops/1/status -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"status":"APPROVED"}'

curl -s -X PUT localhost:8080/api/admin/shops/1/hours -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"hours":[{"dayOfWeek":"MONDAY","opens":"00:00","closes":"23:59:59"},{"dayOfWeek":"TUESDAY","opens":"00:00","closes":"23:59:59"},{"dayOfWeek":"WEDNESDAY","opens":"00:00","closes":"23:59:59"},{"dayOfWeek":"THURSDAY","opens":"00:00","closes":"23:59:59"},{"dayOfWeek":"FRIDAY","opens":"00:00","closes":"23:59:59"},{"dayOfWeek":"SATURDAY","opens":"00:00","closes":"23:59:59"},{"dayOfWeek":"SUNDAY","opens":"00:00","closes":"23:59:59"}]}'

curl -s localhost:8080/api/shops/me -H "Authorization: Bearer $SHOP"
```

---

## Deploying

There are two parts to deploy:

- **Backend:** one jar plus one Postgres 17 database. Any host that runs Java 21 works. Flyway creates and updates the tables when the app starts, so there is no separate database step.
- **Web app:** plain static files (HTML, JavaScript, images), so any static host works, for example S3 with CloudFront, Render static sites, Netlify or Cloudflare Pages.

### Checklist

1. **Database:** a managed Postgres 17, with daily backups switched on.
2. **Secrets:** set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, a fresh random `JWT_SECRET` (at least 32 characters), and `ADMIN_EMAIL` and `ADMIN_PASSWORD` (at least 12 characters, or the app will not start) for the first admin.
3. **HTTPS:** put the app behind a trusted proxy or load balancer. Restrict direct access to the backend, configure the proxy to replace client-supplied forwarding headers, and set `FORWARD_HEADERS_STRATEGY=native`.
4. **Web app address:** set `CORS_ALLOWED_ORIGINS` on the backend to the web app's address (for example `https://app.yourdomain.ca`).
5. **Health check:** point the host at `GET /actuator/health`. It answers `{"status":"UP"}` without a login when the app and its database are working, and gives no other details. No other actuator endpoint is exposed.
6. **API docs:** off by default. Set `API_DOCS_ENABLED=true` only while someone needs the Swagger page.
7. **After the first start:** log in as admin, set the price settings if 1.5x, $4.99 and $4.99 are not right, then approve shops and set their opening hours.
8. **Web app:** build it with the backend's address, then upload the `frontend/dist` folder to the static host (commands below). Switch the maps to a paid tile provider before a public launch.
9. **Spending cap:** set a budget alarm (and a hard cap where the host offers one) on the cloud account, so a flood of traffic cannot run up the bill.
10. **Time zones:** keep PostgreSQL time zone data current. Shop hours, late-night fees, daily metrics, and pay periods all use its local time rules.
11. **Payments:** configure [Stripe](#stripe-payments) against a dedicated staging database. Use sandbox credentials for frontend and deployed-environment verification. Enable live mode when the launch checks are complete.

### Run the jar

```bash
cd backend
```

```bash
mvn package -DskipTests
```

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/quickcommerce \
DATABASE_USER=quickcommerce \
DATABASE_PASSWORD=change-me \
JWT_SECRET=a-long-random-secret-of-at-least-32-characters \
ADMIN_EMAIL=admin@yourdomain.ca \
ADMIN_PASSWORD=change-me-too \
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

The app refuses to start without `JWT_SECRET` (at least 32 characters), so it can never run with a known secret.

**Behind a load balancer or proxy**, `FORWARD_HEADERS_STRATEGY=native` is required. Without it every user appears to come from the proxy's IP, and the per-IP login limit would block everyone.

### Build the web app

From the repository's top folder:

```bash
cd frontend
```

```bash
npm ci
```

```bash
VITE_API_URL=https://api.yourdomain.ca npm run build
```

The finished site is in `frontend/dist`. `VITE_API_URL` is read at build time, so rebuild whenever the backend's address changes. If the web app and the API are served from the same address (the host sends `/api` to the backend), leave `VITE_API_URL` out.

**More than one server:** the login limits and the suspended-account list are kept in each server's memory. With two servers each one counts on its own, and a suspension reaches the other server within 30 seconds.

---

## Configuration

Set these as environment variables. Everything else lives in [`application.properties`](backend/src/main/resources/application.properties).

| Variable | Default | What it is |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/quickcommerce` | Postgres connection |
| `DATABASE_USER` | `quickcommerce` | Postgres user |
| `DATABASE_PASSWORD` | empty | Postgres password |
| `JWT_SECRET` | **required** | Signs login tokens. At least 32 characters. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | empty | If both are set, startup creates the admin if needed. Use a valid email that does not belong to a non-admin account. |
| `MAX_DELIVERY_KM` | `5` | How far (straight line) a customer can be from a shop to see and order from it |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Websites allowed to call the API, comma-separated |
| `FORWARD_HEADERS_STRATEGY` | `none` | Set to `native` behind a proxy |
| `AUTH_IP_LIMIT_PER_MINUTE` | `60` | Signups and logins per IP per minute |
| `DB_POOL_SIZE` | `10` | Database connections, minimum 2 |
| `DRIVER_MAX_PINGS_PER_MINUTE` | `60` | Location pings per driver per minute |
| `API_DOCS_ENABLED` | `false` | Set to `true` to serve Swagger UI and the OpenAPI JSON |
| `RATE_LIMIT_PER_USER` | `300` | Requests per logged-in account per minute |
| `RATE_LIMIT_PER_IP` | `600` | Requests per IP per minute without a login |
| `CHECKOUTS_PER_MINUTE` | `10` | Orders one customer can place per minute |
| `QUOTES_PER_MINUTE` | `60` | Price quotes one customer can ask for per minute |
| `MAX_CONCURRENT_CHECKOUTS` | `5` | Shared checkout and quote limit per server, capped at `DB_POOL_SIZE - 1` |
| `HTTP_THREADS` | `200` | Requests handled at once, per server |
| `BUSINESS_TIME_ZONE` | `America/Vancouver` | Decides "today" for daily violation limits and charts, and when pay periods start |

The web app has one setting, `VITE_API_URL`: the backend's address, given at build time (see [Build the web app](#build-the-web-app)).

Fixed in `application.properties`: shop answer time 10 minutes, driver job radius 8 km, ETA of 10 minutes prep + 3 minutes per km, tokens valid 12 hours.

Prices, fees, driver pay, violation limits and opening hours are not configuration: admin sets them through the API, and they live in the database. Their starting values are in [Prices](#prices), [Order fees](#order-fees), [Driver pay](#driver-pay) and [Driver rules](#driver-rules).

---

## Stripe payments

Set `STRIPE_ENABLED=true`, `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` and `STRIPE_RETURN_URL` in the backend environment. `STRIPE_LIVE` defaults to `false`. Use a dedicated database for sandbox orders and payouts. Set the return URL to a frontend page that accepts `orderId` and optionally `kind=tip`. Use HTTPS for deployed environments. Local HTTP is supported in sandbox mode.

For local sandbox testing, run Stripe CLI with the sandbox API key and use its signing secret in the backend environment.

```sh
stripe listen --events checkout.session.completed,checkout.session.expired \
  --forward-to http://127.0.0.1:8080/api/payments/stripe/webhook
```

For a deployed backend, register the same two snapshot events at `POST /api/payments/stripe/webhook` and inject that endpoint's signing secret. Allow `Content-Type: application/json`, `Content-Length` and `Stripe-Signature` to reach the app. The backend verifies the original request body and signature and reconciles against Stripe. The worker polls outstanding Checkout Sessions if an event is delayed.

### Customer and shop flow

1. `POST /api/orders` reserves stock and returns `PAYMENT_PENDING`. Retry with the same order idempotency key. Poll `GET /api/orders/{id}/payment` and redirect to the returned `checkoutUrl` when the payment is `OPEN`. Stock is held for up to ten minutes while the customer completes checkout.
2. On return, poll the order and its payment. Stripe's card authorization moves the order to `PLACED` for the shop. Customers can cancel before payment or while the shop is deciding. A decline leaves the order in `PAYMENT_PENDING` so the customer can retry the same session.
3. Shop acceptance moves the order to `CAPTURE_PENDING`. The worker charges only available items plus the checkout fees and any tip. A confirmed capture moves the order to `ACCEPTED`, which lets the shop mark it ready and the driver take the job. Rejection, cancellation or expiration releases the hold or queues a refund for a captured order.
4. For delivered Stripe orders without a tip, `POST /api/orders/{id}/tip/checkout` with `{"tipCents":200}` starts a separate payment within 24 hours. Poll the same path with `GET`; redirect to its `checkoutUrl`. A successful capture credits the driver's pay period based on the time the tip is processed.

### Payments frontend handoff

| Screen | Required change |
|---|---|
| Customer checkout | Remove the free-checkout message. Show the backend quote in CAD. Keep the same idempotency key and checkout request for retries. After creating an order, poll its payment endpoint and open the Stripe-hosted URL once available. |
| Customer orders and return page | Read `orderId` and optional `kind=tip` from the configured return route. Fetch the authenticated order and payment state after return, refresh or sign-in. Show pay/resume and cancel while payment is pending, card hold while the shop decides, and charge processing during `CAPTURE_PENDING`. Render expired, voided, refunding, refunded and under-review outcomes. Show payment confirmation after the backend reports capture. |
| Customer tips | Start separate tips through `POST /api/orders/{id}/tip/checkout`. Poll that resource and use its Checkout URL. Update the displayed tip after capture. |
| Shop orders | Show `CAPTURE_PENDING` after acceptance, including when the order has left the new-order inbox. Refresh until capture resolves. Enable **Ready** when the order is `ACCEPTED`. Keep the unavailable-item selection that determines the final charge. |
| Driver | Keep assignment tied to the jobs API. Show confirmed tips from pay statements and use the existing delivery workflow. |
| Admin | Show payment, tip and transfer states, including the reconciliation queues below. Use the Stripe payout endpoints for linked recipients when Stripe is enabled. Show a transfer as queued or transferred based on its backend state. |

For `429` or transient `503` responses, back off and retry the same checkout attempt. For `409`, refresh the order or quote before offering the next action. An order marked `EXPIRED` together with payment `REVIEW_REQUIRED` should show that payment is under review.

### Admin payment and settlement

| Method | Path | Result |
|---|---|---|
| `GET` | `/api/admin/orders/{id}/payment` | Stripe payment state and provider IDs for one order |
| `GET` | `/api/admin/orders/{id}/payment/tip` | Stripe payment state and provider IDs for a separate tip |
| `PUT` | `/api/admin/stripe/recipients/{kind}/{id}` | Link a verified Canadian Stripe Connect account using `{"accountId":"acct_…"}`. Use the shop ID for `SHOP` or the driver user ID for `DRIVER`. |
| `POST` | `/api/admin/stripe/orders/{id}/transfer` | Queue the delivered order's shop earnings using its captured charge |
| `POST` | `/api/admin/stripe/driver-payouts/{driverId}/{periodStart}` | Queue a finished period's driver earnings and tips from the platform balance |
| `GET` | `/api/admin/stripe/transfers/{id}` | Read the settlement state and Stripe transfer ID |
| `GET` | `/api/admin/stripe/payment-reviews?page=0&size=20` | Payment and tip attempts that need reconciliation |
| `GET` | `/api/admin/stripe/transfer-reviews?page=0&size=20` | Transfers that need reconciliation in the configured Stripe mode |

Recipients complete Stripe Connect onboarding before an admin links their Stripe accounts. A confirmed transfer credits the recipient's Stripe balance. Their bank payout follows the connected account's payout schedule. The worker checks the Stripe charge before sending a shop transfer. An already refunded or disputed charge goes to admin review. Chargebacks and adjustments after delivery are handled in Stripe.

A capture still unresolved after 30 minutes goes to `REVIEW_REQUIRED`. The order expires and available stock is released. Treat a payment under review as unresolved in the customer UI. Admins can use the review endpoints and the Stripe IDs in the detail endpoints to confirm what happened before issuing any refund or transfer. The worker checks Stripe for an existing refund or transfer after a lost response. It stops sending new requests for an unknown result before Stripe's idempotency key can expire.

`STRIPE_WORKER_CONCURRENCY` defaults to four in-flight jobs per backend instance. Keep this within the service's PostgreSQL and Stripe budgets when adding replicas. Money operations are protected by database claims and Stripe idempotency keys. Checkout, capture, refunds and Connect transfers are covered by the local integration suite against PostgreSQL and an HTTP Stripe simulator.

---

## Tests

### Verified in this release

- **Backend suite.** 132 tests passed using the Maven test suite. Payment integration tests use real PostgreSQL, the HTTP API and a Stripe simulator. They cover webhook signatures, authorization, duplicate requests, competing state changes, retry and recovery, and Connect transfer bookkeeping.
- **Stripe sandbox.** Verified CAD 25.96 authorized, CAD 19.97 captured after removing an item, and CAD 19.97 refunded. Also verified a card decline, a 3-D Secure authorization followed by a released hold, and a CAD 2.00 post-delivery tip credited to the driver.
- **Local payment load.** A burst of 96 checkout requests from 24 customers produced 24 Checkout Sessions and 24 captures. No stock was oversold. This run used the Stripe simulator.

From `backend/`, run `mvn test` for the full suite or `mvn -Dtest=StripePaymentTest test` for payment and checkout concurrency coverage.

### Remaining testing before launch

1. **Frontend staging walkthrough.** Exercise a full order across customer, shop, driver and admin. Include tab refresh and return from Stripe, repeated clicks, a decline, cancelled and expired checkout, 3-D Secure, partial capture, refund, and a post-delivery tip.
2. **Stripe Connect sandbox.** Once Connect is enrolled, onboard eligible Canadian test recipients. Verify one delivered-order shop transfer and one closed-period driver transfer. Retry each through the admin UI and confirm one transfer, the correct CAD amount and matching Stripe and QuikE records. Connect transfers have passed the local simulator tests.
3. **Deployed webhook and security checks.** Verify HTTPS at the public endpoint, original signed body delivery through the proxy, handling of duplicate and delayed events, trusted forwarded headers, role access, and production API documentation settings.
4. **Staging load and recovery.** Use a Stripe simulator for sustained and burst traffic at the planned peak, including multiple backend replicas. Measure p95 latency, error rates, database pool usage and payment queue age. Restart a worker during capture or refund, simulate provider timeout and throttling, and check that stock, charges and payout records reconcile once.

### Backend test suites

| Test | What it checks |
|---|---|
| `ShopBackendTest` | Signup, login, shop onboarding, approval, catalog, shop products |
| `OrderTest` | Placing, pricing, every order step, who can see what |
| `BrowseTest` | Feed, change shop, search in a shop, shop location privacy |
| `DriverTest` | Driver approval, job list, pickup, delivery code, tracking |
| `FeesTest` | Delivery tiers, handling, small-cart, late-night and surge fees, the quote, tips |
| `DriverWorkTest` | Engaged-time pay, pay statements and payouts, violations, admin alerts, "Did you receive it?", metrics |
| `ReviewTest` | Reviews from customers and riders, averages in browsing, admin removal |
| `AdminTest` | Prices and fees, opening hours, order board, cancel, assigning drivers, suspending accounts, dashboard, health check |
| `StripePaymentTest` | Signed webhooks, ownership, payment races, recovery after lost Stripe responses, Connect transfers, tip accounting and a 96-request checkout burst |
| `SecurityTest` | Forged tokens, SQL injection, oversized bodies, error leaks, security headers, image links, locked rows failing fast |
| `ConcurrencyTest`, `OrderConcurrencyTest`, `DriverConcurrencyTest`, `AdminConcurrencyTest` | Many requests at the same moment: stock never oversold, one winner per race, no deadlocks, admin actions racing shops and drivers |
| `IpThrottleTest`, `PingThrottleTest`, `RateLimitTest`, `auth/ThrottleTest` | Rate limits, checkout limits, API docs off in production |
