#!/usr/bin/env python3
"""Непрерывный ряд фьючерса Мосбиржи из квартальных (или месячных) контрактов.

Биржа отдаёт историю по каждому контракту отдельно, а контракт живёт квартал.
Ряд склеивается так: пока до последнего дня торгов контракта больше пяти
торговых дней, берётся он; затем — следующий. В точке перехода прошлая часть
ряда умножается на отношение цен нового и старого контракта, чтобы разрыв
между ними не выглядел движением рынка (обратная корректировка).
"""
import csv
import json
import sys
import urllib.request
from datetime import date

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}
MONTHS = "FGHJKMNQUVXZ"  # январь … декабрь
ROLL_DAYS_BEFORE_EXPIRY = 5


def get_json(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def contract_history(code):
    """[(дата, open, high, low, close, volume)] по одному контракту."""
    rows = []
    start = 0
    while True:
        url = (
            "https://iss.moex.com/iss/history/engines/futures/markets/forts/"
            f"securities/{code}.json?iss.only=history&start={start}"
        )
        data = get_json(url)["history"]
        cols = data["columns"]
        page = data["data"]
        if not page:
            break
        idx = {name: cols.index(name) for name in ("TRADEDATE", "OPEN", "HIGH", "LOW", "CLOSE", "VOLUME")}
        for row in page:
            close = row[idx["CLOSE"]]
            if close is None or close <= 0:
                continue
            rows.append((
                row[idx["TRADEDATE"]],
                row[idx["OPEN"]] or close,
                row[idx["HIGH"]] or close,
                row[idx["LOW"]] or close,
                close,
                row[idx["VOLUME"]] or 0,
            ))
        start += len(page)
        if len(page) < 100:
            break
    return rows


def codes(root, months, year_from, year_to):
    for year in range(year_from, year_to + 1):
        for m in months:
            yield f"{root}{m}{year % 10}", year, MONTHS.index(m) + 1


def splice(root, months, year_from, year_to):
    contracts = []
    for code, year, month in codes(root, months, year_from, year_to):
        # Однозначный код года — десятилетие берём из запрошенного диапазона.
        history = contract_history(code)
        if len(history) < 20:
            continue
        # Контракт мог получить тот же код десятилетием раньше: оставляем
        # только строки того года, к которому он относится.
        history = [h for h in history if abs(int(h[0][:4]) - year) <= 1]
        if len(history) < 20:
            continue
        contracts.append((history[-1][0], history))
    contracts.sort()
    if not contracts:
        return []

    series = []
    current = None
    for expiry, history in contracts:
        by_date = {h[0]: h for h in history}
        dates = sorted(by_date)
        roll_at = dates[-ROLL_DAYS_BEFORE_EXPIRY - 1] if len(dates) > ROLL_DAYS_BEFORE_EXPIRY else dates[-1]
        if current is None:
            current = (by_date, dates, roll_at)
            continue
        cur_by_date, cur_dates, cur_roll = current
        for d in cur_dates:
            if d > cur_roll:
                break
            if series and d <= series[-1][0]:
                continue
            series.append(cur_by_date[d])
        # Переход: всё прошлое приводится к цене нового контракта в день
        # перехода, чтобы разрыв между контрактами не выглядел движением.
        if cur_roll in by_date and cur_roll in cur_by_date:
            ratio = by_date[cur_roll][4] / cur_by_date[cur_roll][4]
            series = [(d, o * ratio, h * ratio, l * ratio, c * ratio, v) for (d, o, h, l, c, v) in series]
        current = (by_date, dates, roll_at)
    cur_by_date, cur_dates, _ = current
    for d in cur_dates:
        if series and d <= series[-1][0]:
            continue
        series.append(cur_by_date[d])
    return series


def main():
    out_dir, year_from, year_to = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    # Корень контракта, его месяцы и имя файла.
    roots = [
        ("Si", "HMUZ", "Si_USDRUB"),
        ("RI", "HMUZ", "RI_RTS"),
        ("MX", "HMUZ", "MX_IMOEX"),
        ("SR", "HMUZ", "SR_SBER"),
        ("GZ", "HMUZ", "GZ_GAZP"),
        ("GD", "HMUZ", "GD_ZOLOTO"),
        ("BR", MONTHS, "BR_BRENT"),
        ("NG", MONTHS, "NG_GAZ"),
    ]
    for root, months, name in roots:
        try:
            series = splice(root, months, year_from, year_to)
        except Exception as error:
            print(f"  {root}: не удалось ({error})", flush=True)
            continue
        if len(series) < 400:
            print(f"  {root}: только {len(series)} дней — пропуск", flush=True)
            continue
        path = f"{out_dir}/fyuchers__{name}.csv"
        with open(path, "w", newline="", encoding="utf-8") as handle:
            w = csv.writer(handle)
            w.writerow(["open", "high", "low", "close", "volume", "begin"])
            for d, o, h, l, c, v in series:
                w.writerow([f"{o:.6f}", f"{h:.6f}", f"{l:.6f}", f"{c:.6f}", int(v), f"{d} 00:00:00"])
        print(f"  {root} -> {path.split('/')[-1]}: {len(series)} дней, с {series[0][0]}", flush=True)


if __name__ == "__main__":
    main()
