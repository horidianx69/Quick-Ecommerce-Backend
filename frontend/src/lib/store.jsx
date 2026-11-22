import { createContext, useContext, useEffect, useState } from 'react'

// Small persisted state for the customer side: the delivery point and the cart.
function usePersisted(key, initial) {
  const [value, setValue] = useState(() => {
    try { const raw = localStorage.getItem(key); return raw ? JSON.parse(raw) : initial } catch { return initial }
  })
  useEffect(() => { try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* storage blocked */ } }, [key, value])
  return [value, setValue]
}

/* ---------- Delivery point ---------- */
// Every browse call needs lat/lng. Default is Surrey, BC, where the demo shops are.
export const DEFAULT_PLACE = {
  label: 'Surrey, BC', latitude: 49.2, longitude: -122.86,
  addressLine: '', city: 'Surrey', province: 'BC', postalCode: '',
  // Dev only: a dummy address next to the demo shops, so test orders need no typing.
  ...(import.meta.env.DEV && { addressLine: '10250 140 St, Unit 12', postalCode: 'V3T 4M3' }),
}
const PlaceContext = createContext(null)

export function PlaceProvider({ children }) {
  const [place, setPlaceRaw] = usePersisted('quike.place', DEFAULT_PLACE)
  const setPlace = (patch) => setPlaceRaw((p) => ({ ...p, ...patch }))
  return <PlaceContext.Provider value={{ place, setPlace }}>{children}</PlaceContext.Provider>
}
export const usePlace = () => useContext(PlaceContext)

/* ---------- Cart ---------- */
// One cart = one shop (backend rule). Up to 50 lines, 1-99 of each.
const CartContext = createContext(null)
const EMPTY = { shopId: null, shop: null, items: [] }

export function CartProvider({ children }) {
  const [cart, setCart] = usePersisted('quike.cart', EMPTY)
  const [open, setOpen] = useState(false)
  const { place } = usePlace()

  const api = {
    ...cart,
    open, setOpen,
    count: cart.items.reduce((n, i) => n + i.qty, 0),
    subtotal: cart.items.reduce((n, i) => n + i.qty * i.priceCents, 0),
    qtyOf: (spId) => cart.items.find((i) => i.shopProductId === spId)?.qty ?? 0,
    /** Returns false if the cart holds another shop's items and `replace` is not set. */
    add(item, shop, replace = false) {
      if (cart.shopId && cart.shopId !== shop.shopId && cart.items.length && !replace) return false
      setCart((c) => {
        // A new cart remembers the delivery pin it was filled from, so checkout can put the pin back if it drifts out of range.
        const pin = { latitude: place.latitude, longitude: place.longitude, label: place.label }
        const base = c.shopId === shop.shopId && !replace ? c : { shopId: shop.shopId, shop, pin, items: [] }
        if (base.items.length >= 50) return base
        return { ...base, shop, items: [...base.items, { ...item, qty: 1 }] }
      })
      return true
    },
    setQty(spId, qty) {
      setCart((c) => {
        const items = qty <= 0
          ? c.items.filter((i) => i.shopProductId !== spId)
          : c.items.map((i) => (i.shopProductId === spId ? { ...i, qty: Math.min(99, qty) } : i))
        return items.length ? { ...c, items } : EMPTY
      })
    },
    clear: () => setCart(EMPTY),
  }
  return <CartContext.Provider value={api}>{children}</CartContext.Provider>
}
export const useCart = () => useContext(CartContext)
