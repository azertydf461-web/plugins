#!/usr/bin/env python3
"""Часовые свечи спота из открытого архива data.binance.vision (помесячные zip).
Аргументы: каталог, пара (BTCUSDT), год начала."""
import csv, io, sys, time, urllib.request, zipfile
from datetime import date, datetime, timezone

out, pair, y0 = sys.argv[1], sys.argv[2], int(sys.argv[3])
rows = []
today = date.today()
for y in range(y0, today.year + 1):
    for m in range(1, 13):
        if (y, m) >= (today.year, today.month):
            break
        url = f"https://data.binance.vision/data/spot/monthly/klines/{pair}/1h/{pair}-1h-{y}-{m:02d}.zip"
        for attempt in range(4):
            try:
                with urllib.request.urlopen(url, timeout=60) as r:
                    z = zipfile.ZipFile(io.BytesIO(r.read()))
                break
            except Exception as e:  # noqa: BLE001
                z = None
                if attempt == 3:
                    print(f"  {pair} {y}-{m:02d}: {e}", flush=True)
                time.sleep(2 ** attempt)
        if z is None:
            continue
        for line in z.read(z.namelist()[0]).decode().splitlines():
            p = line.split(",")
            if not p[0].isdigit():
                continue
            ts = int(p[0])
            ts = ts / 1_000_000 if ts > 10**14 else ts / 1000  # с 2025 года метки в микросекундах
            t = datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            rows.append((p[1], p[2], p[3], p[4], p[5], t))
rows.sort(key=lambda r: r[5])
with open(f"{out}/{pair}__1h.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["open", "high", "low", "close", "volume", "begin"])
    w.writerows(rows)
print(f"{pair} 1h: {len(rows)} свечей, {rows[0][5] if rows else '-'} … {rows[-1][5] if rows else '-'}")
