#!/usr/bin/env python3
"""Пересчёт мировых индексов в рубли по дневным курсам.

Источник курсов — ЦБ РФ, официальные котировки. Если ЦБ с раннера
недоступен, берутся котировки Yahoo; какой источник сработал, печатается в
лог, потому что это меняет смысл цифр: официальный курс и внебиржевая
котировка после 2022 года расходятся.
"""
import csv
import io
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}


def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def cbr_rates(char_code, date_from, date_to):
    """{дата: рублей за единицу валюты} по официальным курсам ЦБ."""
    daily = ET.fromstring(get("https://www.cbr.ru/scripts/XML_daily.asp").decode("cp1251"))
    ids = {v.findtext("CharCode"): v.get("ID") for v in daily.findall("Valute")}
    code = ids.get(char_code)
    if not code:
        raise KeyError(f"ЦБ не котирует {char_code}")
    # ЦБ ждёт дату в виде дд/мм/гггг, а на вход приходит ISO.
    def ru(day):
        year, month, date = day.split("-")
        return f"{date}/{month}/{year}"

    url = (
        "https://www.cbr.ru/scripts/XML_dynamic.asp"
        f"?date_req1={ru(date_from)}&date_req2={ru(date_to)}&VAL_NM_RQ={code}"
    )
    root = ET.fromstring(get(url).decode("cp1251"))
    out = {}
    for rec in root.findall("Record"):
        day, month, year = rec.get("Date").split(".")
        nominal = float(rec.findtext("Nominal").replace(",", "."))
        value = float(rec.findtext("Value").replace(",", "."))
        out[f"{year}-{month}-{day}"] = value / nominal
    if not out:
        raise ValueError(f"ЦБ вернул пустой ряд по {char_code}")
    return out


def yahoo_series(symbol):
    import json

    data = json.loads(
        get(f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=15y&interval=1d")
    )
    result = data["chart"]["result"][0]
    closes = result["indicators"]["quote"][0]["close"]
    import datetime

    out = {}
    for ts, close in zip(result["timestamp"], closes):
        if close:
            day = datetime.datetime.utcfromtimestamp(ts).strftime("%Y-%m-%d")
            out[day] = float(close)
    if not out:
        raise ValueError(f"Yahoo вернул пустой ряд по {symbol}")
    return out


def yahoo_rates(char_code):
    """{дата: рублей за единицу валюты} через доллар."""
    usd_rub = yahoo_series("RUB%3DX")  # рублей за доллар
    if char_code == "USD":
        return usd_rub
    direct = {"EUR": ("EURUSD%3DX", False), "AUD": ("AUDUSD%3DX", False)}
    inverse = {
        "CHF": ("USDCHF%3DX", True),
        "KRW": ("USDKRW%3DX", True),
        "BRL": ("USDBRL%3DX", True),
        "MXN": ("USDMXN%3DX", True),
    }
    symbol, invert = (direct | inverse)[char_code]
    leg = yahoo_series(symbol)
    out = {}
    for day, usd_per_rub in usd_rub.items():
        local = leg.get(day)
        if not local:
            continue
        per_usd = 1.0 / local if invert else local
        out[day] = per_usd * usd_per_rub
    return out


def rates_for(char_code, date_from, date_to):
    try:
        return cbr_rates(char_code, date_from, date_to), "ЦБ"
    except Exception as error:  # ЦБ может не отвечать зарубежному раннеру
        print(f"  {char_code}: ЦБ недоступен ({error}) — берём Yahoo", flush=True)
        return yahoo_rates(char_code), "Yahoo"


def carry_forward(rates, day):
    """Курс на дату, а если её нет (выходной, праздник) — последний до неё."""
    if day in rates:
        return rates[day]
    earlier = [d for d in rates if d <= day]
    return rates[max(earlier)] if earlier else None


def convert(src, dst, rates):
    rows = list(csv.reader(io.StringIO(open(src, encoding="utf-8").read())))
    header, body = rows[0], rows[1:]
    written = 0
    with open(dst, "w", encoding="utf-8", newline="") as handle:
        out = csv.writer(handle)
        out.writerow(header)
        for row in body:
            if len(row) < 6:
                continue
            day = row[5][:10]
            rate = carry_forward(rates, day)
            if not rate:
                continue
            out.writerow([f"{float(row[i]) * rate:.6f}" for i in range(4)] + [row[4], row[5]])
            written += 1
    return written


def main():
    source_dir, target_dir, date_from, date_to = sys.argv[1:5]
    os.makedirs(target_dir, exist_ok=True)
    # Валюта каждого индекса. Рублёвые инструменты переносятся как есть.
    currency = {
        "mir-indeks__IBEX35": "EUR",
        "mir-indeks__CAC40": "EUR",
        "mir-indeks__AEX": "EUR",
        "mir-indeks__SMI": "CHF",
        "mir-indeks__ASX200": "AUD",
        "mir-indeks__BOVESPA": "BRL",
        "mir-indeks__IPC_MEXICO": "MXN",
        "mir-indeks__KOSPI": "KRW",
    }
    cache = {}
    for name in sorted(os.listdir(source_dir)):
        if not name.endswith(".csv"):
            continue
        stem = name[:-4]
        src = os.path.join(source_dir, name)
        dst = os.path.join(target_dir, name)
        code = currency.get(stem)
        if not code:
            open(dst, "w", encoding="utf-8").write(open(src, encoding="utf-8").read())
            print(f"  {stem}: уже в рублях")
            continue
        if code not in cache:
            cache[code] = rates_for(code, date_from, date_to)
        rates, origin = cache[code]
        written = convert(src, dst, rates)
        print(f"  {stem}: {code}->RUB по курсам {origin}, {written} свечей")


if __name__ == "__main__":
    main()
