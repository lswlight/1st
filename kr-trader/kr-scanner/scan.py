#!/usr/bin/env python3
"""kr-scanner: 국내 주식·코인 차트 스캐너 (표준 라이브러리만 사용).

  scan.py daily  --market krx|crypto   장 전: 일봉 셋업 (볼린저 스퀴즈 돌파 등)
  scan.py orb    --market krx|crypto   장중: 개장 15분 고점 돌파
  scan.py universe --market krx --check   종목 목록 검증 (Yahoo에 있는지, 코스피/코스닥 구분)
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.realpath(__file__))), "lib"))
import krt  # noqa: E402

SETUP_KO = {
    "bb_squeeze_break": "볼린저 스퀴즈 후 상단 돌파",
    "bb_squeeze": "볼린저 스퀴즈 진행 중",
    "bb_lower_reentry": "볼린저 하단 이탈 후 복귀",
    "breakout_20d": "20일 종가 신고가",
    "reclaim_ma20": "20일선(밴드 중심) 회복",
    "oversold": "과매도 (RSI 30 미만)",
}


def pick_symbols(args, market):
    if args.symbols:
        return [{"symbol": krt.normalize(s), "name": krt.names().get(krt.normalize(s), "")}
                for s in args.symbols.replace(",", " ").split()]
    if args.watchlist:
        wl = krt.load_json(args.watchlist, {}) or {}
        nm = krt.names()
        return [{"symbol": h["symbol"], "name": nm.get(h["symbol"], "")}
                for h in wl.get("hits", []) if krt.market_of(h["symbol"]) == market]
    rows = krt.load_universe(market)
    return rows[:args.limit] if args.limit else rows


# ------------------------------------------------------------------ daily

def daily_setups(closes, n=20, k=2.0, squeeze_pct=20, lookback=120):
    """일봉 종가로 셋업 판정. 마지막 값이 직전 완성 봉."""
    out = []
    if len(closes) < max(n + 2, 30):
        return out, None
    bbs = krt.bb_series(closes, n, k)
    last, prev = bbs[-1], bbs[-2]
    widths = [b["width"] for b in bbs[-lookback:] if b]
    rank = sum(w <= last["width"] for w in widths) / len(widths) * 100
    prev_rank = sum(w <= prev["width"] for w in widths) / len(widths) * 100
    c, pc = closes[-1], closes[-2]
    if prev_rank <= squeeze_pct and c > last["up"]:
        out.append("bb_squeeze_break")
    elif rank <= squeeze_pct / 2:
        out.append("bb_squeeze")
    if pc < prev["lo"] and c > last["lo"]:
        out.append("bb_lower_reentry")
    if c > max(closes[-21:-1]):
        out.append("breakout_20d")
    ma_prev = krt.sma(closes[:-1], n)
    if ma_prev and pc < ma_prev and c > last["mid"]:
        out.append("reclaim_ma20")
    r = krt.rsi(closes)
    if r is not None and r < 30:
        out.append("oversold")
    info = {"pctb": round(last["pctb"], 3), "width": round(last["width"], 2),
            "width_rank": round(rank), "rsi": round(r, 1) if r is not None else None,
            "bb_up": last["up"], "bb_mid": last["mid"], "bb_lo": last["lo"]}
    return out, info


def cmd_daily(args):
    market = args.market
    cfg = krt.config(args.config)
    now = krt.now_kst(args.now)
    f = krt.Fetcher(args.offline, args.pause)
    syms = pick_symbols(args, market)
    min_value = args.min_value if args.min_value is not None else (1e9 if market == "krx" else 0)
    hits, failed, short, liquid, scanned = [], [], [], 0, 0
    asof = None
    for i, row in enumerate(syms):
        s = row["symbol"]
        try:
            data = f.chart(s, "6mo" if market == "crypto" else "1y", "1d")
        except krt.KrtError as e:
            failed.append(s)
            continue
        bars = data["bars"]
        # 오늘 일봉은 장이 끝나기 전엔 미완성이라 뺀다 (국내 15:30, 코인 다음날 09:00).
        if bars:
            t = bars[-1]["t"]
            day = krt.session_day(now, market)
            done = now >= krt.at_time(now.date(), "15:30") if market == "krx" else False
            if krt.session_day(t, market) == day and not done:
                bars = bars[:-1]
        if len(bars) < 30:
            short.append(s)
            continue
        last = bars[-1]
        value = krt.bar_value(last, market)
        if last["c"] < args.min_price or value < min_value:
            continue
        liquid += 1
        closes = [b["c"] for b in bars]
        setups, info = daily_setups(closes, cfg["bb_period"], cfg["bb_k"])
        scanned += 1
        asof = max(asof or last["t"], last["t"])
        if setups:
            hits.append(dict(symbol=s, name=row.get("name") or row.get("yahoo_name", ""),
                             setups=setups, close=last["c"], date=str(krt.session_day(last["t"], market)),
                             value=value, **info))
        if not args.quiet and (i + 1) % 25 == 0:
            print("  %d/%d 종목 확인..." % (i + 1, len(syms)), file=sys.stderr)
    hits.sort(key=lambda h: -h["value"])
    counts = {}
    for h in hits:
        for st in h["setups"]:
            counts[st] = counts.get(st, 0) + 1
    report = {"mode": "daily", "market": market, "as_of": now.isoformat(),
              "closes_through": str(krt.session_day(asof, market)) if asof else None,
              "universe": len(syms), "fetched": len(syms) - len(failed), "failed": failed, "short": short,
              "liquid": liquid, "scanned": scanned, "counts": counts, "hits": hits}
    if args.json:
        print(krt.json.dumps(report, ensure_ascii=False, indent=2, default=str))
    else:
        print_daily(report, args.show)
    if not args.no_write and hits:
        krt.save_json(args.out, report)
        if not args.json:
            print("저장: %s (셋업 %d개)" % (krt.path(args.out) if os.sep not in args.out else args.out, len(hits)))
    return 0 if hits else 1


def print_daily(r, show):
    mk = krt.MARKETS[r["market"]]["name"]
    print("%s 일봉 스캔: %s 종가까지 (%s 일봉)" % (mk, r["closes_through"], krt.source_label(r["market"])))
    if r["market"] == "crypto" and krt.crypto_source() == "binance":
        print("가격은 바이낸스 USDT, 일봉은 09:00 KST(00:00 UTC) 기준.")
    print("목록 %d개 중 %d개 수신, 유동성 통과 %d개, 스캔 %d개." % (
        r["universe"], r["fetched"], r["liquid"], r["scanned"]))
    if r["failed"]:
        print("받지 못한 종목 %d개: %s" % (len(r["failed"]), ", ".join(r["failed"][:10])))
    if r.get("short"):
        print("기록이 30일 미만이라 뺀 종목 %d개 (신규 상장 등): %s" % (len(r["short"]), ", ".join(r["short"][:10])))
    if not r["hits"]:
        print("조건에 맞는 차트가 없습니다.")
        return
    print("조건에 맞은 차트 %d개: %s" % (len(r["hits"]), ", ".join(
        "%s %d" % (SETUP_KO[k], v) for k, v in sorted(r["counts"].items(), key=lambda x: -x[1]))))
    print("거래대금 순:")
    for h in r["hits"][:show]:
        print("  %-11s %-12s %12s  %%B %5.2f  밴드폭 %5.1f%% (하위 %3d%%)  RSI %5s  %s" % (
            h["symbol"], (h["name"] or "")[:10], krt.price_str(h["close"], r["market"]),
            h["pctb"], h["width"], h["width_rank"], h["rsi"],
            ", ".join(SETUP_KO[s] for s in h["setups"])))
    if len(r["hits"]) > show:
        print("  ... 외 %d개 (--show N)" % (len(r["hits"]) - show))
    print(krt.DISCLAIMER)


# ------------------------------------------------------------------ orb

def cmd_orb(args):
    market = args.market
    cfg = krt.config(args.config)
    now = krt.now_kst(args.now)
    day = krt.session_day(now, market)
    open_t = krt.session_open(day, market)
    or_min = args.or_minutes or cfg["or_minutes"]
    if market == "krx" and now.weekday() >= 5:
        krt.die("주말이라 국내 장이 열리지 않습니다. 장 전 스캔은 daily를 쓰세요.")
    if now < open_t + krt.timedelta(minutes=or_min + 5):
        krt.die("아직 이릅니다: 개장 %d분 범위와 그 다음 5분봉이 끝나는 %s 이후에 돌리세요." % (
            or_min, krt.fmt_time(open_t + krt.timedelta(minutes=or_min + 5))))
    until = None
    if args.at:
        until = krt.at_time(open_t.date(), args.at)
        if until < open_t:
            until += krt.timedelta(days=1)
    elif market == "krx":
        last = krt.at_time(day, cfg["krx_last_entry"])
        if now > last:
            until = last
    f = krt.Fetcher(args.offline, args.pause)
    syms = pick_symbols(args, market)
    hits, failed, usable = [], [], 0
    for i, row in enumerate(syms):
        s = row["symbol"]
        try:
            data = f.chart(s, "1d" if market == "crypto" else "5d", "5m")
        except krt.KrtError:
            failed.append(s)
            continue
        bars = krt.five_minute_bars(data["bars"], market, until or now)
        r = krt.orb_check(bars, market, day, now, or_min, until)
        if r["status"] in ("no_data", "forming", "gap"):
            failed.append(s)
            continue
        if r["last"] < args.min_price:
            continue
        usable += 1
        if r["status"] == "triggered":
            hits.append(dict(symbol=s, name=row.get("name") or row.get("yahoo_name", ""), **r))
        if not args.quiet and (i + 1) % 25 == 0:
            print("  %d/%d 종목 확인..." % (i + 1, len(syms)), file=sys.stderr)
    hits.sort(key=lambda h: -h["value"])
    report = {"mode": "orb", "market": market, "date": str(day), "as_of": now.isoformat(),
              "cutoff": krt.fmt_time(until or now), "or_minutes": or_min,
              "universe": len(syms), "usable": usable, "failed": failed, "hits": hits}
    if args.json:
        print(krt.json.dumps(report, ensure_ascii=False, indent=2, default=str))
    else:
        mk = krt.MARKETS[market]["name"]
        print("%s 개장 %d분 범위 돌파 스캔: %s %s 기준 (5분봉, %s)" % (mk, or_min, day, report["cutoff"], krt.source_label(market)))
        print("목록 %d개 중 쓸 수 있는 차트 %d개 (데이터 부족·누락 %d개)." % (len(syms), usable, len(failed)))
        print("범위 고점 위에서 5분봉 종가가 마감한 차트 %d개, 거래대금 순:" % len(hits))
        for h in hits[:args.show]:
            print("  %s  %-11s %-12s %12s  범위 %s~%s  +%.2f%%  거래대금 %s" % (
                h["at"], h["symbol"], (h["name"] or "")[:10], krt.price_str(h["price"], market),
                krt.price_str(h["or_low"], market), krt.price_str(h["or_high"], market),
                h["pct_over"], krt.money(h["value"], krt.quote_of(h["symbol"]))))
        if len(hits) > args.show:
            print("  ... 외 %d개 (--show N)" % (len(hits) - args.show))
        print("늦게 돌파한 차트일수록 지금 가격에 가깝습니다. " + krt.DISCLAIMER)
    if not args.no_write and hits:
        krt.save_json(args.out, report)
    return 0 if hits else 1


# ------------------------------------------------------------------ universe

def binance_universe(args):
    """바이낸스 현물 USDT 마켓 중 24시간 거래대금 상위 N개 (스테이블·레버리지 토큰 제외). 한글 이름은 업비트에서."""
    f = krt.Fetcher(None, args.pause)
    tick = f._get(krt.BINANCE + "/ticker/24hr?type=MINI", "USDT", "바이낸스")
    ko = {}
    try:
        for m in f._get(krt.UPBIT + "/market/all", "KRW", "업비트"):
            if m["market"].startswith("KRW-"):
                ko[m["market"][4:]] = m.get("korean_name", "")
    except krt.KrtError:
        pass
    rows = []
    for t in tick:
        sym = t["symbol"]
        if not sym.endswith("USDT"):
            continue
        base = sym[:-4]
        if base in krt.STABLES or base.endswith(("UP", "DOWN", "BULL", "BEAR")) and len(base) > 4:
            continue
        rows.append((float(t.get("quoteVolume") or 0), sym, base, float(t.get("lastPrice") or 0)))
    rows.sort(reverse=True)
    out, stocks = [], []
    for _, sym, base, last in rows:
        if len(out) >= args.top:
            break
        if is_tokenized_stock(f, base, last):
            stocks.append(base)
            continue
        out.append("%s|%s|%s" % (sym, ko.get(base, ""), base))
    dest = args.dest or os.path.join(os.path.dirname(os.path.realpath(__file__)), "universe-crypto.txt")
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write("# SYMBOL|한글 이름(업비트)|티커  (바이낸스 현물 USDT 24시간 거래대금 상위 %d개, 스테이블 제외)\n" % args.top)
        fh.write("\n".join(out) + "\n")
    print("바이낸스 USDT 마켓 %d개 중 거래대금 상위 %d개 저장: %s" % (len(rows), len(out), dest))
    if stocks:
        print("토큰화 주식이라 뺀 종목: %s" % ", ".join(stocks))
    return 0


def is_tokenized_stock(f, base, last):
    """바이낸스 토큰화 주식(GOOGLB, INTCB 등)인지: 끝의 B를 뗀 티커가 Yahoo에서 미국 주식·ETF이고
    가격이 40% 안으로 비슷하면 (토큰 가격은 장외 시간에 벌어진다) 주식으로 본다 (BNB 처럼 B로 끝나는 코인은 가격이 달라 남는다)."""
    if not base.endswith("B") or len(base) < 3 or last <= 0:
        return False
    try:
        d = f._yahoo(base[:-1], "5d", "1d")
    except krt.KrtError:
        return False
    m = d["meta"]
    px = m.get("regularMarketPrice") or (d["bars"][-1]["c"] if d["bars"] else 0)
    return m.get("instrumentType") in ("EQUITY", "ETF") and px and abs(last / px - 1) < 0.4


def upbit_universe(args):
    """업비트 원화 마켓 중 24시간 거래대금 상위 N개 (유의 종목 제외)."""
    f = krt.Fetcher(None, args.pause, "upbit")
    markets = f._get(krt.UPBIT + "/market/all?isDetails=true", "KRW", "업비트")
    krw = {m["market"]: m for m in markets if m["market"].startswith("KRW-")}
    # 투자유의 종목과 스테이블코인(차트 분석 의미 없음)은 뺀다.
    warn = {k for k, m in krw.items() if (m.get("market_event") or {}).get("warning")
            or (m.get("market_warning") or "NONE") != "NONE"}
    stable = {"KRW-USDT", "KRW-USDC", "KRW-USDS", "KRW-USDE", "KRW-DAI", "KRW-PYUSD"}
    keys = sorted(krw)
    tick = []
    for i in range(0, len(keys), 100):
        tick += f._get(krt.UPBIT + "/ticker?markets=" + ",".join(keys[i:i + 100]), "KRW", "업비트")
    tick.sort(key=lambda t: -t.get("acc_trade_price_24h", 0))
    out = []
    for t in tick:
        if t["market"] in warn or t["market"] in stable:
            continue
        m = krw[t["market"]]
        out.append("%s-KRW|%s|%s" % (t["market"][4:], m.get("korean_name", ""), m.get("english_name", "")))
        if len(out) >= args.top:
            break
    dest = args.dest or os.path.join(os.path.dirname(os.path.realpath(__file__)), "universe-upbit.txt")
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write("# SYMBOL|한글 이름|영문 이름  (업비트 원화 마켓 24시간 거래대금 상위 %d개, 투자유의·스테이블코인 제외)\n" % args.top)
        fh.write("\n".join(out) + "\n")
    print("업비트 원화 마켓 %d개 중 거래대금 상위 %d개 저장 (투자유의 %d개·스테이블코인 제외): %s" % (
        len(krw), len(out), len(warn), dest))
    return 0


def cmd_universe(args):
    market = args.market
    if args.check and market == "crypto" and not args.source:
        src = args.exchange or krt.crypto_source()
        if src == "binance":
            return binance_universe(args)
        if src == "upbit":
            return upbit_universe(args)
    if not args.check:
        rows = krt.load_universe(market)
        for r in rows:
            print("%s|%s|%s" % (r["symbol"], r["name"], r["yahoo_name"]))
        print("%d개" % len(rows), file=sys.stderr)
        return 0
    src = args.source or os.path.join(os.path.dirname(os.path.realpath(__file__)),
                                      "candidates-%s.txt" % market)
    f = krt.Fetcher(None, args.pause)
    out, bad = [], []
    with open(src, encoding="utf-8") as fh:
        cands = [l.strip().split("|") for l in fh if l.strip() and not l.startswith("#")]
    seen = set()
    for parts in cands:
        code, name = parts[0].strip(), (parts[1].strip() if len(parts) > 1 else "")
        tries = [code] if ("." in code or code.endswith("-KRW")) else (
            [code + ".KS", code + ".KQ"] if market == "krx" else [code + "-KRW"])
        for s in tries:
            try:
                d = f.chart(s, "5d", "1d")
            except krt.KrtError:
                continue
            m = d["meta"]
            # Yahoo는 접미사가 틀려도 엉뚱한 데이터를 준다 (005930.KQ 도 값이 나옴).
            # 거래소 코드가 접미사와 맞을 때만 받는다: KSC = 코스피(.KS), KOE = 코스닥(.KQ).
            exch = {".KS": "KSC", ".KQ": "KOE"}.get(s[-3:])
            if exch and m.get("exchangeName") != exch:
                continue
            if d["bars"]:
                if s not in seen:
                    seen.add(s)
                    out.append("%s|%s|%s" % (s, name, m.get("longName") or m.get("shortName") or ""))
                break
        else:
            bad.append(code)
    dest = args.dest or os.path.join(os.path.dirname(os.path.realpath(__file__)),
                                     "universe-%s.txt" % market)
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write("# SYMBOL|한글 이름|Yahoo 이름  (scan.py universe --check 로 생성)\n")
        fh.write("\n".join(out) + "\n")
    print("%d개 확인, 저장: %s" % (len(out), dest))
    if bad:
        print("Yahoo에서 찾지 못함 %d개: %s" % (len(bad), ", ".join(bad)))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="국내 주식·코인 차트 스캐너 (kr-scanner)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("daily", "orb"):
        p = sub.add_parser(name)
        p.add_argument("--market", choices=("krx", "crypto"), default="krx")
        g = p.add_mutually_exclusive_group()
        g.add_argument("--symbols", help="005930,000660 또는 BTC,ETH")
        g.add_argument("--watchlist", help="krt/watchlist.json 같은 파일")
        p.add_argument("--limit", type=int)
        p.add_argument("--min-price", type=float, default=0)
        p.add_argument("--now", help="\"2026-10-29 10:15\" (한국 시간)")
        p.add_argument("--offline", help="저장된 Yahoo JSON 폴더")
        p.add_argument("--config", default="config.json")
        p.add_argument("--out", default="watchlist.json")
        p.add_argument("--no-write", action="store_true")
        p.add_argument("--pause", type=float, default=0.2)
        p.add_argument("--show", type=int, default=20)
        p.add_argument("--quiet", action="store_true")
        p.add_argument("--json", action="store_true")
        if name == "daily":
            p.add_argument("--min-value", type=float, help="최소 거래대금 (국내 기본 10억 원)")
        else:
            p.add_argument("--or-minutes", type=int)
            p.add_argument("--at", help="HH:MM 이 시각까지만 본다")
    p = sub.add_parser("universe")
    p.add_argument("--market", choices=("krx", "crypto"), default="krx")
    p.add_argument("--check", action="store_true", help="후보 목록을 Yahoo로 검증해 universe 파일을 다시 만든다")
    p.add_argument("--source")
    p.add_argument("--dest")
    p.add_argument("--top", type=int, default=30, help="코인: 거래대금 상위 N개 (기본 30)")
    p.add_argument("--exchange", choices=("binance", "upbit"), help="코인 목록을 만들 거래소 (기본: crypto_source)")
    p.add_argument("--pause", type=float, default=0.2)
    args = ap.parse_args(argv)
    try:
        return {"daily": cmd_daily, "orb": cmd_orb, "universe": cmd_universe}[args.cmd](args)
    except krt.KrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
