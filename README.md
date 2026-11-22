# Quick Ecommerce Canada — Backend

A delivery platform for small local shops in Canada. Shops list what they have in stock. Customers nearby order it from an app. A driver picks the order up and brings it to the door.

This repository is the **backend**: one Spring Boot app and one Postgres database that every screen talks to.

- [What the project is](#what-the-project-is)
- [What is built](#what-is-built)
- [How it is put together](#how-it-is-put-together)
- [How it works](#how-it-works)
- [Endpoints](#endpoints)
- [How to start the project](#how-to-start-the-project)
- [Configuration](#configuration)
- [Tests](#tests)
- [Not built yet](#not-built-yet)

---

## What the project is

In smaller Canadian towns, many people run their own private shops. They buy stock cheaply (Costco and similar) and sell it at a markup. This platform puts those shops online, the way Blinkit or Swiggy Instamart do:

- **Shops** sign up on a website, list their products, and keep stock up to date.
- **Customers** open the app, see products from shops within 5 km, and order.
- **Drivers** pick up the order from the shop and deliver it.
- **Admin** (us) approves shops and drivers, and owns the shared product list.

The platform adds its markup on top of the shop's price (1.5x by default, set by admin per category). The shop is paid its own price.

**Privacy rule that shapes everything:** a shop's real location is private. Customers only ever see a distance and a delivery time, never the shop's address or exact position.

---

## What is built

| Part | Status | What it does |
|---|---|---|
| Auth | Done | Sign up, log in, roles (shop, customer, driver, admin), login limits |
| Shop | Done | Shop profile and location, admin approval |
| Catalog | Done | Shared product list, shop requests for new products, admin review |
| Shop products and stock | Done | Each shop's price and stock count for catalog products |
| Orders | Done | Place, accept, reject, mark ready, cancel, auto-expire |
| Browsing | Done | Product-first feed, change shop, search inside one shop |
| Drivers and delivery | Done | Driver approval, online status, location pings, job list, pickup, delivery code |
| Customer tracking | Done | Driver's live position on the map, after pickup only |
| Admin | Done | Dashboard, order board, cancel, assign drivers by hand, prices and fees, opening hours, suspend accounts |
| Health check | Done | `/actuator/health` for the host and load balancer |
| Payments | Not yet | Orders are free to place for now |
| Sales tax | Not yet | Waiting on the launch province |
| Password reset | Not yet | Needs an email provider |

The frontends (shop website, admin, customer app, driver app) are separate and not in this repository. They call the endpoints below.

---

## How it is put together

```
Shop website + admin (Next.js) ─┐
Customer app (Expo) ────────────┼──►  Spring Boot backend  ──►  Postgres
Driver app (Expo) ──────────────┘     (this repository)
```

**Tech:** Java 21, Spring Boot 4.1, Spring Security (JWT), Spring Data JPA, Flyway, Postgres 17, springdoc-openapi.

**Why one app and one database:** the backend is the only thing that touches the database. Every shop's data lives in the same tables, marked with a `shop_id`. There is no copy step, so a stock change shows to customers immediately.

### Folder layout

```
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
    db/migration/V1..V5.sql     database tables, applied by Flyway at startup
  src/test/java/com/quickcommerce/
    *Test.java                  tests, run against a real Postgres
    DevApplication.java         starts the app locally with a throwaway database
```

Each part keeps its own tables. Other parts reach it only through its service class (for example, orders take stock through `StockService`, never by writing to the stock table).

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

```
PLACED ──► ACCEPTED ──► READY ──► PICKED_UP ──► DELIVERED
   │
   ├──► REJECTED   (shop said no)
   ├──► CANCELLED  (customer cancelled before the shop answered, or admin cancelled any time before delivery)
   └──► EXPIRED    (shop did not answer within 10 minutes)
```

1. **Customer places an order.** The backend works out every price itself. Stock is taken in the same database step that creates the order, so two customers can never buy the last item.
2. **Shop accepts or rejects.** It can mark items it turns out not to have; those are not charged and not paid out. Rejecting, cancelling or expiring puts the stock back.
3. **A driver takes the job.** Jobs open as soon as the shop accepts, so the driver travels while the shop packs.
4. **Shop marks it ready, driver picks it up.**
5. **At the door,** the customer reads a 4-digit code from their app. The driver enters it to finish the delivery.

Each step works only from the step before. Steps cannot be skipped or done twice.

Admin can step in at any point before delivery: cancel the order with a reason (stock goes back unless it was already picked up), or give it to a driver by hand.

### Prices, fees and opening hours

Admin sets these in the app; nothing needs a redeploy.

- **Markup:** a default (150 = 1.5x), plus an optional different markup per catalog category. Never below 100, so the customer never pays less than the shop's price.
- **Delivery fee** the customer pays, and **driver pay** per delivery.
- **Opening hours** for each shop: one time window per day, in the shop's local time zone (taken from its province). A shop is only shown to customers, and can only take orders, while it is open. A shop with no hours is closed.

A placed order keeps its prices, fee and driver pay forever, so a later settings change never changes a past order.

### Who sees what

| | Customer | Shop | Driver | Admin |
|---|---|---|---|---|
| Shop name | After ordering | Own shop | After accepting the job | Yes |
| Shop address and exact location | Never | Own shop | After accepting the job | Yes |
| Distance to the shop | Yes (from a shifted point) | — | Yes | — |
| Customer name and address | Own | Never | After pickup | Yes |
| Which products are in the order | Yes | Yes | Never, only how many units | Yes |
| Prices | Customer price | Shop price and payout | Delivery pay only | All of them |
| Driver's live position | After pickup only | Never | Own | — |
| Delivery code | Yes | Never | Never (the customer reads it out) | Never |

**Why distances use a shifted point:** with exact distances, three lookups from different places would reveal where a shop is. So every distance a customer sees is measured from a point 150 to 400 m away from the real one, chosen once per shop.

### Safe under load

- **Stock** only changes through single database statements that refuse to go below zero. Tested with 40 customers racing for 10 items: exactly 10 orders.
- **Double taps:** the app sends an `idempotencyKey` with each checkout. Sending the same one again returns the first order instead of a second one.
- **Races between people** (shop accepts while the customer cancels, 20 drivers accept one job) always end with exactly one winner.
- **Limits:** 5 wrong passwords per email in 15 minutes, 60 signups or logins per IP per minute, 5 delivery code tries per order, request bodies up to 64 KB, pages up to 100 rows.
- **Overload:** if the database is busy, a request waits at most 3 seconds and then gets `503` with `Retry-After: 2`, instead of hanging. No query may run longer than 5 seconds.

---

## Endpoints

The full, always up-to-date contract is published by the running app:

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
| `GET` | `/api/shops/me/products` | My products with price and stock. |
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
- A shop never sees the customer's name, address or the customer price. Its order view shows its own prices, `payoutCents`, and `cancelReason` if admin cancelled.
- Orders the shop doesn't answer within 10 minutes expire, and their stock comes back.

### Admin (role `ADMIN`)

| Method | Path | What it does |
|---|---|---|
| `GET` | `/api/admin/stats` | Dashboard: orders waiting for a shop, needing a driver, on the road, placed and delivered in 24 h; shops, drivers and product requests pending; drivers online. |
| `GET` | `/api/admin/shops?status=PENDING` | Shops waiting for approval (or any status, or all). |
| `GET` | `/api/admin/shops/{id}` | One shop, with its real location and hours. |
| `PUT` | `/api/admin/shops/{id}/status` | `APPROVED`, `SUSPENDED` or `PENDING`. |
| `PUT` | `/api/admin/shops/{id}/hours` | Replace the shop's opening hours for the whole week. |
| `GET` | `/api/admin/orders` | Every order, newest first. Filters: `status`, `shopId`, `customerId`, `driverId`, `needsDriver=true`. |
| `GET` | `/api/admin/orders/{id}` | One order with everything: both prices, payout, driver pay, all three parties. |
| `POST` | `/api/admin/orders/{id}/cancel` | Cancel any unfinished order, with a reason. |
| `POST` | `/api/admin/orders/{orderId}/assign` | Give an order waiting for pickup to an approved driver (replaces any driver it had). |
| `POST` | `/api/admin/orders/{orderId}/unassign` | Take it off its driver before pickup; it goes back to the job list. |
| `GET` | `/api/admin/settings` | Default markup, delivery fee, driver pay, and per-category markups. |
| `PUT` | `/api/admin/settings` | Change the default markup, delivery fee and driver pay. |
| `PUT` | `/api/admin/settings/markups/{category}` | Set a markup for one category. |
| `DELETE` | `/api/admin/settings/markups/{category}` | That category goes back to the default markup. |
| `GET` | `/api/admin/users?role=&q=` | Accounts, searched by email or name. |
| `PUT` | `/api/admin/users/{id}/disabled` | Suspend (`true`) or restore (`false`) an account. Not for admins. |
| `GET` | `/api/admin/catalog?status=PENDING&q=` | Catalog by status. `PENDING` is the shops' product requests. |
| `POST` | `/api/admin/catalog` | Add a catalog product. |
| `PUT` | `/api/admin/catalog/{id}` | Edit a product. Approve or reject a request by changing `status`. |
| `GET` | `/api/admin/drivers?status=PENDING` | Drivers by status. |
| `PUT` | `/api/admin/drivers/{userId}/status` | `APPROVED` or `SUSPENDED`. Suspending takes the driver offline at once. |

```json
PUT  /api/admin/shops/1/status       { "status": "APPROVED" }
POST /api/admin/catalog              { "name": "Maple Syrup 1L", "brand": "Kirkland", "barcode": "096619756803",
                                       "category": "Pantry", "taxCategory": "ZERO_RATED", "status": "APPROVED" }
```

```json
PUT  /api/admin/shops/1/hours        { "hours": [ { "dayOfWeek": "MONDAY", "opens": "08:00", "closes": "22:00" },
                                                  { "dayOfWeek": "SATURDAY", "opens": "00:00", "closes": "23:59:59" } ] }
PUT  /api/admin/settings             { "defaultMarkupPercent": 150, "deliveryFeeCents": 499, "driverPayCents": 499 }
PUT  /api/admin/settings/markups/Dairy   { "markupPercent": 130 }
POST /api/admin/orders/7/cancel      { "reason": "Customer asked by phone" }
POST /api/admin/orders/7/assign      { "driverId": 12 }
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
| `POST` | `/api/orders` | Place an order. `201` new, `200` if the same `idempotencyKey` was already used. |
| `GET` | `/api/orders` | My orders, newest first. |
| `GET` | `/api/orders/{id}` | One order, including the `deliveryCode`. |
| `POST` | `/api/orders/{id}/cancel` | Cancel while the shop has not answered. |
| `GET` | `/api/orders/{id}/tracking` | Order status and, after pickup, the driver's position and minutes away. |

```json
POST /api/orders
{ "shopId": 1,
  "idempotencyKey": "3f2b8c1e-9d4a-4c55-8a10-2b7e5d0c9f61",
  "items": [ { "shopProductId": 1, "quantity": 2 } ],
  "address": { "addressLine": "77 Customer Rd", "city": "Surrey", "province": "BC", "postalCode": "V3T 2B2",
               "latitude": 49.2, "longitude": -122.86 },
  "note": "Ring the bell" }
```

- One cart = one shop for now. Up to 50 lines, 1 to 99 of each.
- `idempotencyKey`: make a new random UUID per checkout attempt, and send the same one again if you retry that attempt.
- If a line is out of stock the whole order fails with `409`, and the error's `shopProductId` says which line.
- A customer can have at most 3 orders waiting for a shop's answer.
- `409` "This shop is closed right now" if the shop closed since the customer browsed.
- If admin cancels, the order shows `status: CANCELLED` with a `cancelReason`.
- `driverName` is the rider's name once one has taken the job (`null` before). Shops never get it: the rider tells the shop the order number at the counter.
- Totals: `subtotalCents` + `deliveryFeeCents` = `totalCents`. Items the shop marked unavailable are not counted.

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
| `POST` | `/api/drivers/me/offline` | End the shift. |
| `POST` | `/api/drivers/me/location` | Location ping, every few seconds while online. `409` = not online, stop pinging. |
| `GET` | `/api/drivers/me/jobs` | Open jobs whose shop is within 8 km of me, oldest first. |
| `POST` | `/api/drivers/me/jobs/{orderId}/accept` | Take a job. `409` if another driver was faster. |
| `GET` | `/api/drivers/me/delivery` | My current delivery. `404` when I have none. |
| `POST` | `/api/drivers/me/delivery/release` | Give the job back before pickup. |
| `POST` | `/api/drivers/me/delivery/picked-up` | Collected from the shop (after the shop marks it ready). |
| `POST` | `/api/drivers/me/delivery/delivered` | Delivered, with the customer's code. |
| `GET` | `/api/drivers/me/deliveries` | My finished deliveries and pay. |

`payCents` everywhere is the driver pay admin set when the order was placed.

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

That's all for development. Tests and the local run download a real Postgres 17 on first use. No Docker or Postgres install is needed.

All commands below run from the `backend` folder.

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
- The frontend at `http://localhost:3000` is allowed to call it.

### Try it from the terminal

With the app running, this signs up a shop, has admin approve it, and lists the shop's products:

```bash
SHOP=$(curl -s -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"owner@demo.test","password":"demo-pass-123","name":"Demo Owner"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s -X PUT localhost:8080/api/shops/me -H "Authorization: Bearer $SHOP" -H 'Content-Type: application/json' \
  -d '{"name":"Demo Mart","phone":"+1 604 555 0199","addressLine":"1 King St","city":"Surrey","province":"BC","postalCode":"V3T 1A1","latitude":49.19,"longitude":-122.85,"hours":[]}'

ADMIN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@quickcommerce.test","password":"dev-admin-pass-1"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s -X PUT localhost:8080/api/admin/shops/1/status -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"status":"APPROVED"}'

curl -s localhost:8080/api/shops/me/products -H "Authorization: Bearer $SHOP"
```

### Health check

`GET /actuator/health` answers `{"status":"UP"}` without a login when the app and its database are working. Point your host's health check and load balancer at it. No other actuator endpoint is exposed.

### Run against a real Postgres

For a shared server or production, use a real Postgres 17 database. Flyway creates and updates the tables when the app starts.

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

**Behind a load balancer or proxy**, also set `FORWARD_HEADERS_STRATEGY=native`. Otherwise every user appears to come from the proxy's IP, and the per-IP login limit would block everyone.

---

## Configuration

Set these as environment variables. Everything else lives in [`application.properties`](backend/src/main/resources/application.properties).

| Variable | Default | What it is |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/quickcommerce` | Postgres connection |
| `DATABASE_USER` | `quickcommerce` | Postgres user |
| `DATABASE_PASSWORD` | empty | Postgres password |
| `JWT_SECRET` | **required** | Signs login tokens. At least 32 characters. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | empty | If both are set, this admin is created at startup |
| `MAX_DELIVERY_KM` | `5` | How far (straight line) a customer can be from a shop to see and order from it |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Websites allowed to call the API, comma-separated |
| `FORWARD_HEADERS_STRATEGY` | `none` | Set to `native` behind a proxy |
| `AUTH_IP_LIMIT_PER_MINUTE` | `60` | Signups and logins per IP per minute |
| `DB_POOL_SIZE` | `10` | Database connections |
| `DRIVER_MAX_PINGS_PER_MINUTE` | `60` | Location pings per driver per minute |
| `API_DOCS_ENABLED` | `true` | Set to `false` to hide Swagger UI in production |

Fixed in `application.properties`: shop answer time 10 minutes, driver job radius 8 km, ETA of 10 minutes prep + 3 minutes per km, tokens valid 12 hours.

Markup, delivery fee, driver pay and opening hours are not configuration: admin sets them through the API, and they live in the database (the first values are 1.5x, $4.99 and $4.99).

---

## Tests

`mvn test` runs 87 tests against a real Postgres, over real HTTP, the same way the apps call the API.

| Test | What it checks |
|---|---|
| `ShopBackendTest` | Signup, login, shop onboarding, approval, catalog, shop products |
| `OrderTest` | Placing, pricing, every order step, who can see what |
| `BrowseTest` | Feed, change shop, search in a shop, shop location privacy |
| `DriverTest` | Driver approval, job list, pickup, delivery code, tracking |
| `AdminTest` | Prices and fees, opening hours, order board, cancel, assigning drivers, suspending accounts, dashboard, health check |
| `SecurityTest` | Forged tokens, SQL injection, oversized bodies, error leaks |
| `ConcurrencyTest`, `OrderConcurrencyTest`, `DriverConcurrencyTest`, `AdminConcurrencyTest` | Many requests at the same moment: stock never oversold, one winner per race, no deadlocks, admin actions racing shops and drivers |
| `IpThrottleTest`, `PingThrottleTest`, `auth/ThrottleTest` | Rate limits |

---

## Not built yet

- **Payments.** Orders cost nothing to place. The places where the card will be held, charged and refunded are marked in `OrderService`. Needs a Stripe account.
- **Sales tax.** Depends on the launch province. Each product already has a tax category.
- **Password reset.** Needs an email provider.
- **Push notifications.** Shops, drivers and customers poll for updates.
- **Image upload.** Products have an `imageUrl` text field only.
- **Multi-shop carts.** One order goes to one shop for now.
- **Phone login for customers.** Customers use email and password.
- **Refresh tokens.** Logins last 12 hours.
