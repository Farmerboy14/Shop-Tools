#!/usr/bin/env python3
"""Generate a GPX test drive for the Android emulator.

The route exercises the auto-counter end to end around a dump zone:

  start parked INSIDE the zone      -> must NOT count (arming rule)
  drive ~3 km out and come back     -> must count load #1
  wobble at the zone edge           -> must NOT double-count
  drive ~2.5 km out and come back   -> must count load #2
  park inside                       -> done: exactly 2 auto loads

Timestamps assume ~34 mph so the 60 s cooldown behaves like real life when
the emulator plays the route at 1x speed.

Usage:
  python3 make_route.py                          # default zone 43.6, -96.5
  python3 make_route.py 44.1234 -97.5678         # your real pit
"""
import math
import sys
from datetime import datetime, timedelta, timezone

lat0 = float(sys.argv[1]) if len(sys.argv) > 2 else 43.60000
lng0 = float(sys.argv[2]) if len(sys.argv) > 2 else -96.50000

SPEED = 15.0          # m/s, ~34 mph
STEP = 75.0           # metres between track points
M_PER_DEG_LAT = 111_320.0
m_per_deg_lng = M_PER_DEG_LAT * math.cos(math.radians(lat0))

points = []           # (north_m, east_m, hold_seconds)

def leg(n_from, e_from, n_to, e_to):
    dist = math.hypot(n_to - n_from, e_to - e_from)
    steps = max(1, int(dist / STEP))
    for i in range(1, steps + 1):
        points.append((n_from + (n_to - n_from) * i / steps,
                       e_from + (e_to - e_from) * i / steps, 0))

points.append((0, 0, 20))            # parked at the pit
leg(0, 0, 3000, 0)                   # north, well clear
points.append((3000, 0, 10))         # turn around
leg(3000, 0, 0, 0)                   # back in -> LOAD 1
points.append((0, 0, 15))
leg(0, 0, 120, 0)                    # edge wobble, inside re-arm band
leg(120, 0, 0, 0)
leg(0, 0, 170, 0)
leg(170, 0, 0, 0)                    # still only 1 load
points.append((0, 0, 10))
leg(0, 0, 0, 2500)                   # east this time
points.append((0, 2500, 10))
leg(0, 2500, 0, 0)                   # back in -> LOAD 2
points.append((0, 0, 20))            # parked

t = datetime(2026, 8, 1, 12, 0, 0, tzinfo=timezone.utc)
out = ['<?xml version="1.0" encoding="UTF-8"?>',
       '<gpx version="1.1" creator="silage-loads-test" '
       'xmlns="http://www.topografix.com/GPX/1/1">',
       '  <trk><name>Dump zone test drive</name><trkseg>']
prev = None
for n, e, hold in points:
    if prev is not None:
        t += timedelta(seconds=math.hypot(n - prev[0], e - prev[1]) / SPEED)
    lat = lat0 + n / M_PER_DEG_LAT
    lng = lng0 + e / m_per_deg_lng
    out.append(f'    <trkpt lat="{lat:.6f}" lon="{lng:.6f}">'
               f'<time>{t.strftime("%Y-%m-%dT%H:%M:%SZ")}</time></trkpt>')
    if hold:
        t += timedelta(seconds=hold)
        out.append(f'    <trkpt lat="{lat:.6f}" lon="{lng:.6f}">'
                   f'<time>{t.strftime("%Y-%m-%dT%H:%M:%SZ")}</time></trkpt>')
    prev = (n, e)
out += ['  </trkseg></trk>', '</gpx>']
print("\n".join(out))
