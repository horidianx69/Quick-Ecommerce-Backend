// Demo accounts created by `npm run seed`. Dev only: the login page shows these as one-click buttons
// when running `npm run dev`. The admin comes from backend/src/test/resources/application-dev.properties.
export const DEMO_ACCOUNTS = [
  { role: 'CUSTOMER', label: 'Customer', email: 'customer@demo.test', password: 'demo-pass-123', name: 'Cara Customer' },
  { role: 'SHOP', label: 'Shop owner', email: 'shop@demo.test', password: 'demo-pass-123', name: 'Sam Shopkeeper' },
  { role: 'DRIVER', label: 'Driver', email: 'driver@demo.test', password: 'demo-pass-123', name: 'Dev Driver' },
  { role: 'ADMIN', label: 'Admin', email: 'admin@quickcommerce.test', password: 'dev-admin-pass-1', name: 'Admin' },
]
