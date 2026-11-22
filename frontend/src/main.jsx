import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'sonner'
import { AuthProvider } from './lib/auth'
import { CartProvider, PlaceProvider } from './lib/store'
import App from './App'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      refetchOnWindowFocus: true,
      // 4xx answers are final (not found, not allowed); only retry network/server hiccups.
      retry: (count, e) => count < 2 && !(e?.status >= 400 && e?.status < 500),
    },
  },
})

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <PlaceProvider>
            <CartProvider>
              <App />
              <Toaster position="top-center" richColors closeButton />
            </CartProvider>
          </PlaceProvider>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
