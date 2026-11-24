# QuikE project guidance

- Use the Ponytail skill for QuikE work. Prefer the smallest complete change, reuse existing code, and verify the behavior that matters. For whole backend reviews, use Ponytail Audit and rank findings by impact with code and test evidence.
- QuikE serves independent Canadian shops that buy stock in bulk and resell it locally. Owners register shops and enter product details by hand. Barcode scanning and automatic product registration are planned for later.
- The product serves customers, shop owners, delivery partners, and admins. Work in `backend/` by default. Use `frontend/` as context for API compatibility. The frontend is maintained separately, so coordinate interface changes and edit frontend code only when asked.
- For changes to stock, orders, payments, or dispatch, check retries, concurrent requests, and rollback behavior. Keep external payment calls outside database transactions. Run the relevant Maven tests against PostgreSQL and report any limits of the verification.
