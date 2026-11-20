# Third-party notices

TN-HUD's own code is MIT licensed (see `LICENSE`). These parts come from others:

| Component | Where | License / terms |
|---|---|---|
| **Share Tech Mono** font, © 2012 Carrois Type Design | `app/assets/ShareTechMono-Regular.ttf` | SIL Open Font License 1.1 — `third_party/OFL-ShareTechMono.txt` (also bundled in the APK) |
| **Star and constellation data** from d3-celestial, © 2015 Olaf Frohn (derived from the Hipparcos / Yale Bright Star catalogues) | packed into `app/assets/stars.bin` | BSD 3-Clause — `third_party/LICENSE-d3-celestial.txt` |
| **SGP4** orbit propagator | `app/src/.../Sgp4.java` | Own Java implementation of the published algorithm: Vallado, Crawford, Hujsak & Kelso (2006), *Revisiting Spacetrack Report #3* |
| Low-precision **ephemerides** (Sun, Moon, planets) | `app/src/.../Astro.java` | Own implementation of published formulas (Meeus, *Astronomical Algorithms*; Schlyter) |

## Online data used at runtime

The app fetches public data; no account or API key is involved and only the device's IP address is sent.

| Source | Used for | Terms |
|---|---|---|
| [Open-Meteo](https://open-meteo.com/) | weather, cloud cover, hourly forecast, city search | Data under CC BY 4.0 — attribution: “Weather data by Open-Meteo.com” |
| [NOAA Space Weather Prediction Center](https://www.swpc.noaa.gov/) | Kp (now and forecast), solar wind, GOES X-ray flux, NOAA scales | US Government work, public domain |
| [CelesTrak](https://celestrak.org/) | orbital elements (space stations, recent launches), fetched at most once a day | Free public data; please keep the once-a-day rate |
| Android geocoder | place names for live location | Platform service |

Voyager distances are computed from a linear model calibrated to NASA/JPL published figures.
