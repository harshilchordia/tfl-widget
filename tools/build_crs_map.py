#!/usr/bin/env python3
"""
Builds app/src/main/assets/tiploc_crs.json: rail station TIPLOC -> National Rail CRS code.

TfL identifies rail stations as 910G<TIPLOC> (e.g. 910GSTFD), but National Rail's live
departure API needs the 3-letter CRS code (SRA). Neither source links the two, so stations
are matched by location (and name) between:
  - DfT NaPTAN rail stations (TIPLOC + coordinates)
  - davwheat/uk-railway-stations (CRS + coordinates)

Run from the repo root: python3 tools/build_crs_map.py
"""
import csv, io, json, math, re, urllib.request

NAPTAN = "https://naptan.api.dft.gov.uk/v1/access-nodes?atcoAreaCodes=910&dataFormat=csv"
STATIONS = "https://raw.githubusercontent.com/davwheat/uk-railway-stations/main/stations.json"
OUT = "app/src/main/assets/tiploc_crs.json"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "tfl-widget-build"})
    return urllib.request.urlopen(req, timeout=120).read().decode("utf-8-sig")


def norm(name):
    name = re.sub(r"\b(rail station|station|london)\b", "", name.lower())
    return re.sub(r"[^a-z]", "", name)


def dist(lat1, lon1, lat2, lon2):
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return 6371000 * math.hypot(x, y)


stations = json.loads(fetch(STATIONS))
by_name = {}
for s in stations:
    by_name.setdefault(norm(s["stationName"]), []).append(s)

rows = [r for r in csv.DictReader(io.StringIO(fetch(NAPTAN)))
        if r["StopType"] == "RLY" and r["Status"] == "active" and r["ATCOCode"].startswith("9100")]

mapping, unmatched = {}, []
for r in rows:
    tiploc, name = r["ATCOCode"][4:], norm(r["CommonName"])
    try:
        lat, lon = float(r["Latitude"]), float(r["Longitude"])
    except ValueError:  # no coordinates: only accept an unambiguous name match
        if len(by_name.get(name, [])) == 1:
            mapping[tiploc] = by_name[name][0]["crsCode"]
        else:
            unmatched.append(r["CommonName"])
        continue
    nearest = sorted(stations, key=lambda s: dist(lat, lon, s["lat"], s["long"]))[:3]
    pick = next((s for s in nearest
                 if norm(s["stationName"]) == name and dist(lat, lon, s["lat"], s["long"]) < 1500), None)
    if pick is None and dist(lat, lon, nearest[0]["lat"], nearest[0]["long"]) < 250:
        pick = nearest[0]
    if pick:
        mapping[tiploc] = pick["crsCode"]
    else:
        unmatched.append(r["CommonName"])

with open(OUT, "w") as f:
    json.dump(dict(sorted(mapping.items())), f, separators=(",", ":"))
print(f"{len(mapping)} stations mapped, {len(unmatched)} unmatched -> {OUT}")
