import { useEffect } from 'react'
import 'leaflet/dist/leaflet.css'
import { CircleMarker, MapContainer, TileLayer, Tooltip, useMap, useMapEvents } from 'react-leaflet'

function ClickToPick({ onPick }) {
  useMapEvents({ click: (e) => onPick({ latitude: +e.latlng.lat.toFixed(6), longitude: +e.latlng.lng.toFixed(6) }) })
  return null
}
function Follow({ lat, lng }) {
  const map = useMap()
  useEffect(() => { map.setView([lat, lng], map.getZoom()) }, [map, lat, lng])
  return null
}

/**
 * OpenStreetMap with round markers. `points`: [{ key, latitude, longitude, color, label }].
 * Pass `onPick` to let a click choose a position (shop pin, delivery point, driver position).
 */
export default function MapView({ center, points = [], onPick, height = 280, zoom = 14, follow = true }) {
  const [lat, lng] = center
  return (
    <div style={{ height }} className="relative z-0 overflow-hidden rounded-2xl border border-line">
      <MapContainer center={center} zoom={zoom} style={{ height: '100%', width: '100%' }} scrollWheelZoom={false}>
        <TileLayer attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>' url="https://tile.openstreetmap.org/{z}/{x}/{y}.png" />
        {points.filter((p) => p.latitude != null).map((p) => (
          <CircleMarker key={p.key} center={[p.latitude, p.longitude]} radius={p.radius ?? 10}
            pathOptions={{ color: '#fff', weight: 3, fillColor: p.color ?? '#e5372b', fillOpacity: 1 }}>
            {p.label && <Tooltip direction="top" offset={[0, -8]} permanent={p.permanent}>{p.label}</Tooltip>}
          </CircleMarker>
        ))}
        {onPick && <ClickToPick onPick={onPick} />}
        {follow && <Follow lat={lat} lng={lng} />}
      </MapContainer>
      {onPick && <div className="pointer-events-none absolute bottom-2 left-2 z-[500] rounded-lg bg-white/90 px-2 py-1 text-xs font-medium shadow">Click the map to move the pin</div>}
    </div>
  )
}
