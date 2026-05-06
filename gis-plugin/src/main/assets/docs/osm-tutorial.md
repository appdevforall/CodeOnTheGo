# Working with OpenStreetMap in Code on the Go

This is the Tier 3 tutorial that ships with the **GIS plugin**. It explains the moving parts behind the two map templates so you can fork them confidently.

## What is OpenStreetMap?

OpenStreetMap (OSM) is a free, public-domain map of the world built and maintained by volunteers. Unlike Google Maps or Apple Maps, the underlying data — every road, building, river, hospital, school — is **available for download** and can be **bundled into your app for offline use**. That is the whole reason this plugin exists: in low-bandwidth regions where many of App Dev For All's users do field work, the only sustainable map is one that already lives on the phone.

Two important things to know up front:

1. **OSM data is structured around tags.** Every feature on the map has a `key=value` tag describing what it is: `amenity=hospital`, `highway=primary`, `place=village`, `shop=bakery`. There's no rigid schema — the [OSM wiki](https://wiki.openstreetmap.org/wiki/Map_features) documents the conventions but anyone can invent a new tag. When you write a query against OSM, you write it against tags.
2. **OSM tiles are not OSM data.** Tiles are pre-rendered images (or vector geometries with style instructions) that you stack like a mosaic to show a map. The data behind them lives in a separate file (`.mbtiles`, `.osm.pbf`, etc.). Your generated app uses both: tiles to render the map; a small POI dataset (a JSON file) to overlay places of interest.

## How does MapLibre fit?

MapLibre is the open-source Android library that draws the map. The IDE template adds a `MapView` to your `activity_main.xml`; the library is responsible for rendering tiles at the right zoom level, handling pinch-to-zoom and pan gestures, and drawing markers / lines / polygons on top.

The template wires up the minimum:

- `MapLibre.getInstance(this)` — initialises the renderer (must run before `setContentView`)
- `MapView` — the actual map widget
- `mapView.onCreate / onStart / onResume / onPause / onStop / onLowMemory / onDestroy / onSaveInstanceState` — every Activity lifecycle callback **must** be forwarded to the `MapView`. Skipping any of these leaks GPU memory or crashes the renderer.
- A `style.json` in `assets/` — tells MapLibre where to fetch tiles and how to draw them.

## Where do tiles come from?

The generated app's `assets/style.json` defaults to the public **MapLibre demo tile server**. That works for a smoke test (the map renders) but **requires internet on first run**, which defeats the offline-first promise. Once the wizard's bbox-picker has downloaded a region into `/sdcard/CodeOnTheGo/maps/<region-id>/`, the template recipe will copy the resulting `tiles.mbtiles` into `assets/maps/region.mbtiles` and rewrite the style to point at it.

If you want to skip the wizard and bundle your own tile pack:

1. Build (or download) an `.mbtiles` file. Common sources:
   - [OpenMapTiles](https://openmaptiles.org/) — provides ready-made vector mbtiles for every country, free for non-commercial use.
   - [Geofabrik](https://download.geofabrik.de/) — provides `.osm.pbf` extracts that you process server-side via `tilemaker` to produce vector mbtiles.
2. Copy the `.mbtiles` into your generated project's `app/src/main/assets/maps/`.
3. In `style.json`, change the `sources` block to:
   ```json
   "sources": {
     "openmaptiles": {
       "type": "vector",
       "url": "mbtiles://maps/region.mbtiles"
     }
   }
   ```
4. Rebuild and reinstall.

## Where do POIs come from?

The read-only template's "places near me" drawer reads from `assets/pois.json`, which is an array of objects:

```json
[
  { "name": "Lalibela Health Center",
    "lat": 12.0319, "lon": 39.0467,
    "category": "amenity=clinic",
    "description": "Public clinic. Open daily 7am–6pm.",
    "source_url": "https://en.wikipedia.org/wiki/..." }
]
```

The wizard populates this from Wikipedia's REST API (`geosearch` for nearby pages, `summary` for the descriptions). To swap the POI source, replace `pois.json` with your own data in the same shape and rebuild. The loader is brute-force `O(n log n)` distance sort — fine for ≤10 k POIs. SQLite + R-tree only becomes worth it past that.

## Adding a new POI category

To filter POIs by category, modify `MainActivity` to filter `pois.json` by the `category` field. For Wikipedia-sourced POIs the category is always `place=settlement` (because Wikipedia gives you populated places, not OSM features). To get true OSM categories like `amenity=hospital`, query the [Overpass API](https://overpass-turbo.eu/) directly — it's an OSM-native query language that returns JSON. A query like:

```
[out:json][timeout:25];
node[amenity=hospital](12.02,39.04,12.05,39.07);
out body;
```

returns every hospital inside the given bounding box. Cache the JSON in your APK assets and you're shipping a real OSM-derived dataset.

## Tag reference (most useful for field-data apps)

| Tag | Meaning |
|---|---|
| `amenity=hospital`, `=clinic`, `=pharmacy` | Health |
| `amenity=school`, `=university`, `=library` | Education |
| `amenity=drinking_water`, `man_made=water_well` | Water |
| `shop=*` | Retail of any kind |
| `place=village`, `=town`, `=city` | Populated places |
| `highway=primary`, `=secondary`, `=tertiary`, `=track` | Roads, by importance |
| `leisure=park`, `landuse=forest` | Open space |

The full reference is at [OSM Wiki: Map features](https://wiki.openstreetmap.org/wiki/Map_features).

## Where to ask for help

- The MapLibre community: [maplibre.org](https://maplibre.org/community/)
- OSM users in your region — most regions have a local mailing list at `talk-{country}@openstreetmap.org`
- Code on the Go's own help — long-press any tooltip in the IDE for the relevant docs
