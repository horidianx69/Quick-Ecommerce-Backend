# QuikE frontend

React + Vite web app for all four roles of the Quick Commerce backend: customer storefront, shop website, rider app and admin panel. The name lives in `src/brand.js`; the red, white and black palette is in `src/index.css`.

## Who sees what

- **Guests** get the landing page at `/` (hero, carousel, moving product rows, posters, footer). Any product click goes to sign in. The products there are a fixed showcase in `src/pages/Landing.jsx`, because the backend only shows real products to signed-in customers.
- **Sign up** makes a customer by default. Below the form are two links: *Sign up as a rider* (`/signup/rider`) and *Sign up as an owner* (`/signup/owner`).
- **Owners and riders** go account → details → review. Until the admin approves them they see a status screen: *Pending review*, *Rejected*, or, once approved, their dashboard opens by itself. The backend has no separate "rejected" state, so a suspended shop or rider shows as *Rejected*.
- **Admins** can't sign up. The backend creates the admin at startup from `ADMIN_EMAIL` / `ADMIN_PASSWORD`; more admins go straight into the database.

## Run it

You need the backend running first (from `backend/`, with Java 21 on your PATH):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
mvn spring-boot:test-run
```

Then, from `frontend/`:

```bash
npm install
npm run seed   # demo shops, products, customer, driver (the backend's local database starts empty on every restart)
npm run dev    # http://localhost:5173
```

On the login page (dev only) there are one-click buttons for the demo customer, shop owner, driver and admin. Their passwords are in `src/lib/demo.js`.

The dev server forwards `/api` to `localhost:8080`, so no CORS setup is needed. For a deployed build, set `VITE_API_URL` to the backend's address and add the site to the backend's `CORS_ALLOWED_ORIGINS`.

## Libraries

| Library | Why |
|---|---|
| `react-router` | Pages and URLs |
| `@tanstack/react-query` | Fetching, caching, and polling (new orders, jobs, live tracking) |
| `tailwindcss` | Styling. Colours and fonts are in `src/index.css` |
| `lucide-react` | Icons |
| `sonner` | Toast messages |
| `leaflet`, `react-leaflet` | OpenStreetMap maps: shop pin, delivery point, driver tracking |

No component library: dialogs use the browser's own `<dialog>`, and the few shared pieces (Button, Input, Modal, Card…) are in `src/components/ui.jsx`.

## Where things are

```
src/
  lib/api.js          every backend call goes through api(): token, JSON, error messages
  lib/auth.jsx        login state, RequireRole (each area is locked to one role)
  lib/store.jsx       customer's delivery point and cart (saved in the browser)
  components/         UI kit, product card, map
  layouts/            customer header + cart drawer, sidebar for shop/admin
  pages/customer/     Home, Offers ("change shop"), ShopAisle, Checkout, Orders + tracking
  pages/shop/         Orders inbox, Products & stock, Profile
  pages/driver/       one mobile page: go online, jobs, pickup, delivery code, earnings
  pages/admin/        Dashboard, Orders, Shops (+ hours), Catalog, Drivers, Users, Prices & fees
scripts/seed.mjs      demo data
scripts/cut-logo.py   cuts ../QuikEFinal.jpeg into public/brand/ (needs Pillow)
public/brand/         logo pieces: full logo, header logo (no tagline), app icon, favicons
```

## Which screen calls which endpoint

| Screen | Endpoints |
|---|---|
| Landing | none (fixed showcase) |
| Login / signup | `POST /api/auth/login`, `/signup`, `GET /api/auth/me` |
| Customer home | `GET /api/browse/products`, `/api/catalog/categories` |
| Product page | `GET /api/browse/products/{id}/offers` |
| One shop's aisle | `GET /api/browse/shops/{id}/products` |
| Checkout | `POST /api/orders` (with an idempotency key, so a retry never double-orders) |
| Orders, order page | `GET /api/orders`, `/api/orders/{id}`, `/tracking`, `POST …/cancel` |
| Shop orders | `GET /api/shops/me/orders`, `POST …/accept`, `/reject`, `/ready` |
| Shop products | `GET/POST /api/shops/me/products`, `PUT …/{id}`, `POST …/{id}/stock`, `DELETE …/{id}`, `GET /api/catalog`, `POST /api/shops/me/product-requests` |
| Shop profile | `GET/PUT /api/shops/me` |
| Driver | `GET/PUT /api/drivers/me`, `POST …/online`, `/offline`, `/location`, `GET …/jobs`, `POST …/jobs/{id}/accept`, `GET …/delivery`, `POST …/release`, `/picked-up`, `/delivered`, `GET …/deliveries` |
| Admin | `/api/admin/stats`, `/orders` (+ cancel, assign, unassign), `/shops` (+ status, hours), `/catalog`, `/drivers`, `/users`, `/settings` (+ category markups) |

Every endpoint in the backend's OpenAPI spec is used.
