#!/usr/bin/env python3
"""Внутридневные свечи фьючерса: каждый контракт берётся только в те дни,
когда он был основным (от перехода на него до перехода на следующий).
Склейка цен не нужна — внутридневная позиция закрывается к концу дня.

Аргументы: каталог, корень (Si), интервал (10 или 60), год начала, год конца.
"""
import csv
import importlib.util
import os
import sys

spec = importlib.util.spec_from_file_location(
    "splice", os.path.join(os.path.dirname(__file__), "moex-futures-splice.py"))
splice = importlib.util.module_from_spec(spec)
spec.loader.exec_module(splice)


def candles(code, interval, day_from, day_to):
    rows, start = [], 0
    while True:
        url = ("https://iss.moex.com/iss/engines/futures/markets/forts/securities/"
               f"{code}/candles.json?interval={interval}&from={day_from}&till={day_to}&start={start}")
        data = splice.get_json(url)["candles"]
        page = data["data"]
        if not page:
            break
        cols = data["columns"]
        i = {c: cols.index(c) for c in ("open", "close", "high", "low", "volume", "begin")}
        rows += [(r[i["open"]], r[i["high"]], r[i["low"]], r[i["close"]], r[i["volume"]], r[i["begin"]]) for r in page]
        start += len(page)
        if len(page) < 500:
            break
    return rows


def main():
    out, root, interval, y0, y1 = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
    contracts = []
    for code, year, _ in splice.codes(root, "HMUZ", y0 - 1, y1):
        try:
            loaded = splice.load_contract(code, year)
        except Exception as error:  # noqa: BLE001
            print(f"  {code}: не скачался ({error})", flush=True)
            continue
        if loaded:
            dates = [h[0] for h in loaded[1]]
            roll = dates[-splice.ROLL_DAYS_BEFORE_EXPIRY - 1] if len(dates) > 6 else dates[-1]
            contracts.append((roll, code))
    contracts.sort()
    rows, prev_roll = [], None
    for roll, code in contracts:
        if prev_roll and roll[:4] >= str(y0):
            part = candles(code, interval, prev_roll, roll)
            part = [r for r in part if prev_roll < r[5][:10] <= roll]
            rows += part
            print(f"  {code}: {len(part)} свечей {prev_roll}..{roll}", flush=True)
        prev_roll = roll
    rows.sort(key=lambda r: r[5])
    path = f"{out}/{root}__{interval}m.csv"
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["open", "high", "low", "close", "volume", "begin"])
        w.writerows(rows)
    print(f"{root} {interval}m -> {len(rows)} свечей", flush=True)


if __name__ == "__main__":
    main()
