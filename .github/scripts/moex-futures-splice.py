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
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}
MONTHS = "FGHJKMNQUVXZ"  # январь … декабрь
ROLL_DAYS_BEFORE_EXPIRY = 5
RETRIES = 5


def get_json(url):
    """Биржа периодически отвечает 502 или рвёт соединение — повторяем с паузой."""
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as error:  # noqa: BLE001 — любой сетевой сбой лечится повтором
            if attempt == RETRIES - 1:
                raise
            time.sleep(2 ** attempt)


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


def load_contract(code, year):
    """(дата экспирации, история) или None; сетевой сбой — исключение."""
    history = contract_history(code)
    if len(history) < 20:
        return None
    # Однозначный код года: контракт мог носить тот же код десятилетием
    # раньше, оставляем только строки своего года.
    history = [h for h in history if abs(int(h[0][:4]) - year) <= 1]
    if len(history) < 20:
        return None
    return history[-1][0], history


def splice(root, months, year_from, year_to):
    """Склеенный ряд и число контрактов, которые так и не скачались."""
    contracts = []
    failed = 0

    def fetch(item):
        code, year, _ = item
        try:
            return load_contract(code, year)
        except Exception as error:  # noqa: BLE001
            return error

    with ThreadPoolExecutor(max_workers=4) as pool:
        for loaded in pool.map(fetch, list(codes(root, months, year_from, year_to))):
            if isinstance(loaded, Exception):
                failed += 1
            elif loaded is not None:
                contracts.append(loaded)
    contracts.sort()
    if not contracts:
        return [], failed

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
    return series, failed


def main():
    out_dir, year_from, year_to = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    # Группа файла, корень контракта, его месяцы и имя файла.
    # «fyuchers» — шесть контрактов, на которых зацепка была найдена;
    # «fyuchers-oos» — отложенная выборка: контракты, которых система не
    # видела, когда делался вывод. Brent и газ в первом прогоне не скачались,
    # поэтому они честно относятся ко второй группе.
    roots = [
        ("fyuchers", "Si", "HMUZ", "Si_USDRUB"),
        ("fyuchers", "RI", "HMUZ", "RI_RTS"),
        ("fyuchers", "MX", "HMUZ", "MX_IMOEX"),
        ("fyuchers", "SR", "HMUZ", "SR_SBER"),
        ("fyuchers", "GZ", "HMUZ", "GZ_GAZP"),
        ("fyuchers", "GD", "HMUZ", "GD_ZOLOTO"),
        ("fyuchers-oos", "BR", MONTHS, "BR_BRENT"),
        ("fyuchers-oos", "NG", MONTHS, "NG_GAZ"),
        ("fyuchers-oos", "Eu", "HMUZ", "Eu_EURRUB"),
        ("fyuchers-oos", "CR", "HMUZ", "CR_CNYRUB"),
        ("fyuchers-oos", "ED", "HMUZ", "ED_EURUSD"),
        ("fyuchers-oos", "SV", "HMUZ", "SV_SEREBRO"),
        ("fyuchers-oos", "PT", "HMUZ", "PT_PLATINA"),
        ("fyuchers-oos", "PD", "HMUZ", "PD_PALLADIY"),
        ("fyuchers-oos", "LK", "HMUZ", "LK_LKOH"),
        ("fyuchers-oos", "RN", "HMUZ", "RN_ROSN"),
        ("fyuchers-oos", "VB", "HMUZ", "VB_VTBR"),
        ("fyuchers-oos", "NK", "HMUZ", "NK_NVTK"),
        ("fyuchers-oos", "GM", "HMUZ", "GM_GMKN"),
        ("fyuchers-oos", "TT", "HMUZ", "TT_TATN"),
        ("fyuchers-oos", "MG", "HMUZ", "MG_MGNT"),
        ("fyuchers-oos", "CH", "HMUZ", "CH_CHMF"),
        ("fyuchers-oos", "NL", "HMUZ", "NL_NLMK"),
        ("fyuchers-oos", "AL", "HMUZ", "AL_ALRS"),
        ("fyuchers-oos", "MT", "HMUZ", "MT_MTSS"),
        ("fyuchers-oos", "ME", "HMUZ", "ME_MOEX"),
        ("fyuchers-oos", "RT", "HMUZ", "RT_RTKM"),
        ("fyuchers-oos", "PZ", "HMUZ", "PZ_PLZL"),
        ("fyuchers-oos", "PH", "HMUZ", "PH_PHOR"),
        ("fyuchers-oos", "HY", "HMUZ", "HY_HYDR"),
        ("fyuchers-oos", "FS", "HMUZ", "FS_FEES"),
        ("fyuchers-oos", "SN", "HMUZ", "SN_SNGS"),
        ("fyuchers-oos", "SG", "HMUZ", "SG_SNGSP"),
        ("fyuchers-oos", "AF", "HMUZ", "AF_AFLT"),
        ("fyuchers-oos", "SF", "HMUZ", "SF_SPYF"),
        ("fyuchers-oos", "NA", "HMUZ", "NA_NASD"),
    ]
    only = set(sys.argv[4].split(",")) if len(sys.argv) > 4 else None
    for group, root, months, name in roots:
        if only and root not in only:
            continue
        try:
            series, failed = splice(root, months, year_from, year_to)
        except Exception as error:  # noqa: BLE001
            print(f"  {root}: не удалось ({error})", flush=True)
            continue
        note = f", не скачалось контрактов: {failed}" if failed else ""
        if len(series) < 400:
            print(f"  {root}: только {len(series)} дней — пропуск{note}", flush=True)
            continue
        path = f"{out_dir}/{group}__{name}.csv"
        with open(path, "w", newline="", encoding="utf-8") as handle:
            w = csv.writer(handle)
            w.writerow(["open", "high", "low", "close", "volume", "begin"])
            for d, o, h, l, c, v in series:
                w.writerow([f"{o:.6f}", f"{h:.6f}", f"{l:.6f}", f"{c:.6f}", int(v), f"{d} 00:00:00"])
        print(f"  {root} -> {path.split('/')[-1]}: {len(series)} дней, с {series[0][0]}{note}", flush=True)


if __name__ == "__main__":
    main()
