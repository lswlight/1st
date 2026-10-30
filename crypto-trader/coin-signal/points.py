#!/usr/bin/env python3
"""coin-signal: 볼린저 밴드 기반 단기(1시간봉)·중기(일봉) 매수 타점.

  points.py now SOL                    단기+중기 현재 타점 (바이낸스 SOLUSDT)
  points.py now 005930 --tf mid        중기만
  points.py scan --tf short             목록 전체에서 지금 신호가 난 종목
  points.py backtest 005930 --tf mid   셋업별 과거 결과만

신호가 나면 진입·손절·목표가와, 이 종목에서 같은 신호가 과거에 어떻게 끝났는지를 함께 낸다.
매수(롱)만. 주문은 내지 않는다.
"""
import argparse
import os
import statistics
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.realpath(__file__))), "lib"))
import crt  # noqa: E402

# 단기 = 1시간봉 (보유 최대 48봉, 약 2일), 중기 = 일봉 (보유 최대 20봉, 약 한 달)
TF = {
    "short": {"name": "단기", "interval": "1h", "range": "6mo", "scan_range": "3mo", "label": "1시간봉",
              "max_hold": 48, "max_risk_pct": 5.0},
    "mid": {"name": "중기", "interval": "1d", "range": "2y", "scan_range": "2y", "label": "일봉",
            "max_hold": 20, "max_risk_pct": 15.0},
}

SETUP_KO = {
    "squeeze_break": "스퀴즈 돌파",
    "mid_pullback": "중심선 눌림",
    "lower_reentry": "하단 복귀",
}
SETUP_HOW = {
    "squeeze_break": "밴드폭이 최근 120봉 하위 20%로 좁아진 뒤 종가가 상단 위로 (거래량 1.5배 이상)",
    "mid_pullback": "상승 추세(60선·중심선 상승)에서 중심선까지 눌렸다가 양봉으로 중심선 위 마감",
    "lower_reentry": "종가가 하단 밖에 있다가 다시 하단 안으로 양봉 마감",
}


# ------------------------------------------------------------------ 지표 계산

def indicators(bars, n=20, k=2.0, lookback=120):
    """봉마다 볼린저(중심·상단·하단·밴드폭·%B), 밴드폭 순위, 60선, RSI, 거래량 평균."""
    c = [b["c"] for b in bars]
    out = []
    widths = []
    for i in range(len(bars)):
        bb = crt.bollinger(c[:i + 1], n, k)
        if bb:
            widths.append(bb["width"])
        w = widths[-lookback:]
        rank = (sum(x <= bb["width"] for x in w) / len(w) * 100) if bb and w else None
        ma60 = crt.sma(c[:i + 1], 60)
        vols = [x["v"] for x in bars[max(0, i - 20):i]]
        out.append({"bb": bb, "rank": rank, "ma60": ma60,
                    "vavg": statistics.fmean(vols) if vols else 0.0})
    # RSI 는 한 번에 계산 (Wilder)
    rsis = [None] * len(c)
    if len(c) > 15:
        n_ = 14
        gains = sum(max(c[j] - c[j - 1], 0) for j in range(1, n_ + 1)) / n_
        losses = sum(max(c[j - 1] - c[j], 0) for j in range(1, n_ + 1)) / n_
        for j in range(n_ + 1, len(c)):
            d = c[j] - c[j - 1]
            gains = (gains * (n_ - 1) + max(d, 0)) / n_
            losses = (losses * (n_ - 1) + max(-d, 0)) / n_
            rsis[j] = 100.0 if losses == 0 else 100 - 100 / (1 + gains / losses)
    for j, r in enumerate(rsis):
        out[j]["rsi"] = r
    return out


def trend(bars, ind, i):
    """추세 판정: 상승 / 하락 / 중립. 종가와 중심선 위치 + 중심선 기울기(5봉)."""
    bb, pb = ind[i]["bb"], ind[i - 5]["bb"] if i >= 5 else None
    if not bb or not pb:
        return "판단 불가"
    up = bb["mid"] > pb["mid"]
    c = bars[i]["c"]
    if c > bb["mid"] and up:
        return "상승"
    if c < bb["mid"] and not up:
        return "하락"
    return "중립"


# ------------------------------------------------------------------ 셋업

def setups_at(bars, ind, i):
    """i번째 (완성된) 봉에서 난 신호 목록: [(셋업, 손절 기준가, 1차 목표)]."""
    if i < 60 or not ind[i]["bb"] or not ind[i - 1]["bb"]:
        return []
    b, p = bars[i], bars[i - 1]
    bb, pbb = ind[i]["bb"], ind[i - 1]["bb"]
    out = []
    bull = b["c"] > b["o"]
    # 1) 스퀴즈 돌파
    vol_ok = ind[i]["vavg"] <= 0 or b["v"] >= 1.5 * ind[i]["vavg"]
    if ind[i - 1]["rank"] is not None and ind[i - 1]["rank"] <= 20 and b["c"] > bb["up"] \
            and p["c"] <= pbb["up"] and vol_ok:
        out.append(("squeeze_break", bb["mid"], None))
    # 2) 중심선 눌림 (상승 추세)
    ma60, ma60p = ind[i]["ma60"], ind[i - 5]["ma60"]
    mid5 = ind[i - 5]["bb"]
    if ma60 and ma60p and mid5 and ma60 > ma60p and b["c"] > ma60 and bb["mid"] > mid5["mid"] \
            and bb["pctb"] <= 0.8:
        touched = min(x["l"] for x in bars[i - 2:i + 1]) <= bb["mid"] * 1.003
        walked = any(ind[j]["bb"] and ind[j]["bb"]["pctb"] >= 0.8 for j in range(i - 10, i - 2))
        if touched and walked and b["c"] > bb["mid"] and bull and b["c"] > p["c"]:
            low = min(x["l"] for x in bars[i - 2:i + 1])
            out.append(("mid_pullback", min(low, bb["mid"]), bb["up"]))
    # 3) 하단 복귀
    if p["c"] < pbb["lo"] and b["c"] > bb["lo"] and bull:
        low = min(x["l"] for x in bars[i - 3:i + 1])
        out.append(("lower_reentry", low, bb["mid"]))
    return out


def divergence(bars, ind, i, span=(5, 30)):
    """하단 복귀 시 RSI 상승 다이버전스: 최근 저점은 더 낮은데 RSI 저점은 더 높음."""
    if ind[i]["rsi"] is None:
        return False
    lo_now = min(range(i - 4, i + 1), key=lambda j: bars[j]["l"])
    rng = range(max(0, i - span[1]), i - span[0])
    if not rng:
        return False
    lo_prev = min(rng, key=lambda j: bars[j]["l"])
    r1, r0 = ind[lo_now]["rsi"], ind[lo_prev]["rsi"]
    return bool(r1 and r0 and bars[lo_now]["l"] < bars[lo_prev]["l"] and r1 > r0)


def make_trade(bars, i, setup, stop_ref, t1, market, quote, r_target=2.0, max_risk_pct=100.0):
    """진입=신호 봉 종가, 손절=기준가를 호가 단위로 내림, 목표=1차(밴드) 또는 2R.
    손절폭이 0.2% 미만(흔들림에 걸림)이거나 max_risk_pct 초과(너무 멂)면 신호로 치지 않는다."""
    entry = bars[i]["c"]
    stop = crt.round_down(stop_ref, market, quote)
    if stop >= entry:
        return None
    risk = entry - stop
    if risk / entry < 0.002 or risk / entry * 100 > max_risk_pct:
        return None
    t2 = crt.round_near(entry + r_target * risk, market, quote)
    first = crt.round_near(t1, market, quote) if t1 and t1 > entry else None
    # 하단 복귀는 중심선이 목표, 나머지는 2R
    target = first if setup == "lower_reentry" and first else t2
    return {"setup": setup, "entry": entry, "stop": stop, "risk": risk, "target": target,
            "t1": first, "t2": t2, "r": (target - entry) / risk}


def walk(bars, i, trade, max_hold):
    """신호 다음 봉부터: 손절 먼저 확인, 그다음 목표. 최대 보유 봉수 지나면 종가로 정리."""
    for j in range(i + 1, min(len(bars), i + 1 + max_hold)):
        b = bars[j]
        if b["l"] <= trade["stop"] or b["o"] <= trade["stop"]:
            return "stop", -1.0, j
        if b["h"] >= trade["target"]:
            return "target", trade["r"], j
    j = min(len(bars) - 1, i + max_hold)
    if j <= i:
        return "open", 0.0, i
    return "time", (bars[j]["c"] - trade["entry"]) / trade["risk"], j


def backtest(bars, ind, market, quote, max_hold, cost_pct, max_risk_pct=100.0):
    """셋업별로 과거 신호를 모두 따라가 본다. 한 거래가 끝나기 전 같은 셋업의 새 신호는 건너뛴다."""
    stats = {k: {"n": 0, "target": 0, "stop": 0, "time": 0, "r": [], "rnet": []} for k in SETUP_KO}
    busy = {k: -1 for k in SETUP_KO}
    for i in range(60, len(bars) - 1):
        for setup, ref, t1 in setups_at(bars, ind, i):
            if i <= busy[setup]:
                continue
            tr = make_trade(bars, i, setup, ref, t1, market, quote, max_risk_pct=max_risk_pct)
            if not tr:
                continue
            outcome, r, j = walk(bars, i, tr, max_hold)
            if outcome == "open":
                continue
            busy[setup] = j
            st = stats[setup]
            st["n"] += 1
            st[outcome] += 1
            st["r"].append(r)
            st["rnet"].append(r - cost_pct * tr["entry"] / tr["risk"])
    for st in stats.values():
        st["avg_r"] = statistics.fmean(st["r"]) if st["r"] else None
        st["avg_rnet"] = statistics.fmean(st["rnet"]) if st["rnet"] else None
    return stats


def roundtrip_cost(symbol, market, cfg):
    fee = cfg["binance_fee_pct"] if crt.quote_of(symbol) == "USDT" else cfg["crypto_fee_pct"]
    return 2 * fee / 100


def watch_items(bars, ind, i, market):
    """신호는 아직 아니지만 지켜볼 상태와 그 방아쇠 가격."""
    bb = ind[i]["bb"]
    if not bb:
        return []
    out = []
    c = bars[i]["c"]
    ps = lambda x: crt.price_str(x, market)  # noqa: E731
    if ind[i]["rank"] is not None and ind[i]["rank"] <= 10:
        out.append(("squeeze_break", "스퀴즈 진행 중 (밴드폭 하위 %d%%): 종가가 상단 %s 위로, 거래량 1.5배 이상이면 신호"
                    % (ind[i]["rank"], ps(bb["up"]))))
    if c < bb["lo"]:
        out.append(("lower_reentry", "하단 이탈 중: 양봉으로 하단 %s 위 마감하면 신호 (밴드를 타고 더 내려갈 수도 있음)"
                    % ps(bb["lo"])))
    ma60, ma60p = ind[i]["ma60"], ind[i - 5]["ma60"] if i >= 5 else None
    if ma60 and ma60p and ma60 > ma60p and c > ma60 and 0.3 <= bb["pctb"] <= 0.65:
        out.append(("mid_pullback", "상승 추세 속 눌림 구간: 중심선 %s 부근에서 양봉 반등 마감하면 신호" % ps(bb["mid"])))
    return out


# ------------------------------------------------------------------ 분석 한 번

def analyze(symbol, tf, f, cfg, now, scan=False):
    spec = TF[tf]
    market = crt.market_of(symbol)
    quote = crt.quote_of(symbol)
    data = f.chart(symbol, spec["scan_range"] if scan else spec["range"], spec["interval"])
    bars = data["bars"]
    if spec["interval"] == "1h":
        bars = [b for b in bars if b["t"].minute == 0 and b["t"].second == 0]
    bars = [b for b in bars if b["t"] <= now]
    bars = crt.completed(bars, spec["interval"], now, market)
    if len(bars) < 80:
        raise crt.CrtError("%s: %s이 %d개뿐입니다 (80개 이상 필요)" % (symbol, spec["label"], len(bars)))
    ind = indicators(bars, cfg["bb_period"], cfg["bb_k"])
    i = len(bars) - 1
    cost = roundtrip_cost(symbol, market, cfg)
    stats = backtest(bars, ind, market, quote, spec["max_hold"], cost, spec["max_risk_pct"])
    sigs = []
    for setup, ref, t1 in setups_at(bars, ind, i):
        tr = make_trade(bars, i, setup, ref, t1, market, quote, max_risk_pct=spec["max_risk_pct"])
        if tr:
            tr["divergence"] = setup == "lower_reentry" and divergence(bars, ind, i)
            sigs.append(tr)
    bb = ind[i]["bb"]
    return {"symbol": symbol, "tf": tf, "market": market, "quote": quote, "bar_time": bars[i]["t"],
            "close": bars[i]["c"], "trend": trend(bars, ind, i), "pctb": bb["pctb"], "width": bb["width"],
            "rank": ind[i]["rank"], "rsi": ind[i]["rsi"], "bb": bb, "ma60": ind[i]["ma60"],
            "signals": sigs, "watch": watch_items(bars, ind, i, market), "stats": stats,
            "bars": len(bars), "first": bars[0]["t"], "cost": cost}


def stat_line(st, label):
    if not st["n"]:
        return "%s 과거 같은 신호 없음" % label
    warn = " (표본 적음)" if st["n"] < 10 else ""
    return "%s 과거 %d회: 목표 %d, 손절 %d, 시간 정리 %d, 평균 %+.2fR (비용 후 %+.2fR)%s" % (
        label, st["n"], st["target"], st["stop"], st["time"], st["avg_r"], st["avg_rnet"], warn)


def print_tf(a):
    spec = TF[a["tf"]]
    m = a["market"]
    ps = lambda x: crt.price_str(x, m)  # noqa: E731
    when = a["bar_time"].strftime("%m-%d %H:%M") if a["tf"] == "short" else a["bar_time"].strftime("%Y-%m-%d")
    period = "%s~" % a["first"].strftime("%Y-%m-%d")
    print("[%s · %s] 마지막 완성 봉 %s, 종가 %s" % (spec["name"], spec["label"], when, ps(a["close"])))
    print("  추세 %s | %%B %.2f | 밴드폭 %.1f%% (하위 %d%%) | RSI %s | 중심 %s, 상단 %s, 하단 %s" % (
        a["trend"], a["pctb"], a["width"], a["rank"] or 0, "%.1f" % a["rsi"] if a["rsi"] else "-",
        ps(a["bb"]["mid"]), ps(a["bb"]["up"]), ps(a["bb"]["lo"])))
    if a["signals"]:
        for s in a["signals"]:
            extra = " + RSI 상승 다이버전스" if s.get("divergence") else ""
            t1 = "  (1차 %s)" % ps(s["t1"]) if s["t1"] and s["setup"] != "lower_reentry" else ""
            print("  ★ 신호: %s%s" % (SETUP_KO[s["setup"]], extra))
            print("     진입 %s / 손절 %s (-%.2f%%) / 목표 %s (%.1fR)%s" % (
                ps(s["entry"]), ps(s["stop"]), s["risk"] / s["entry"] * 100, ps(s["target"]), s["r"], t1))
            print("     " + stat_line(a["stats"][s["setup"]], "이 종목 %s" % period))
    else:
        print("  지금 난 신호 없음.")
    for setup, text in a["watch"]:
        print("  · 지켜볼 것: %s" % text)
        print("     " + stat_line(a["stats"][setup], "  %s 신호는 %s" % (SETUP_KO[setup], period)))


def cmd_now(args):
    cfg = crt.config(args.config)
    f = crt.Fetcher(args.offline)
    sym = crt.resolve(args.symbol, f)
    now = crt.now_kst(args.now)
    tfs = ["mid", "short"] if args.tf == "both" else [args.tf]
    print("%s %s · %s · %s 기준" % (sym, crt.names().get(sym, ""), crt.source_label(crt.market_of(sym), sym),
                                  now.strftime("%Y-%m-%d %H:%M")))
    res = []
    for tf in tfs:
        try:
            a = analyze(sym, tf, f, cfg, now)
        except crt.CrtError as e:
            print("[%s] %s" % (TF[tf]["name"], e))
            continue
        res.append(a)
        print_tf(a)
    if len(res) == 2:
        mid, short = res
        if short["signals"] and mid["trend"] == "하락":
            print("※ 단기 신호가 중기 하락 추세와 반대 방향입니다. 짧게 보고 손절을 지키세요.")
        elif short["signals"] and mid["trend"] == "상승":
            print("※ 단기 신호가 중기 상승 추세와 같은 방향입니다.")
    print("평균 R은 '손절 -1R, 목표 +R배'로 셈한 과거 기록이지 예측이 아닙니다. 표본이 적으면 우연일 수 있습니다.")
    print(crt.DISCLAIMER)
    if args.json:
        crt.save_json(args.json, [{k: v for k, v in a.items() if k != "stats"} | {"stats": {
            s: {kk: vv for kk, vv in st.items() if kk not in ("r", "rnet")} for s, st in a["stats"].items()}}
            for a in res])
    return 0 if any(a["signals"] for a in res) else 1


def cmd_scan(args):
    cfg = crt.config(args.config)
    f = crt.Fetcher(args.offline)
    now = crt.now_kst(args.now)
    if args.symbols:
        syms = [crt.normalize(s) for s in args.symbols.replace(",", " ").split()]
    else:
        syms = [r["symbol"] for r in crt.load_universe(args.market)][:args.limit or None]
    hits, watch, failed = [], [], []
    for n, s in enumerate(syms, 1):
        try:
            a = analyze(s, args.tf, f, cfg, now, scan=True)
        except crt.CrtError:
            failed.append(s)
            continue
        for sig in a["signals"]:
            hits.append((a, sig))
        for setup, text in a["watch"]:
            if setup == "squeeze_break":
                watch.append((a, text))
        if n % 20 == 0:
            print("  %d/%d..." % (n, len(syms)), file=sys.stderr)
    spec = TF[args.tf]
    print("%s 타점 스캔 (%s, %s): %d개 중 %d개 분석, %s 기준" % (
        spec["name"], spec["label"], crt.source_label(args.market), len(syms), len(syms) - len(failed),
        now.strftime("%Y-%m-%d %H:%M")))
    if failed:
        print("데이터 부족·실패 %d개: %s" % (len(failed), ", ".join(failed[:8])))
    nm = crt.names()
    if hits:
        def score(x):
            st = x[0]["stats"][x[1]["setup"]]
            return (st["avg_rnet"] if st["avg_rnet"] is not None and st["n"] >= 5 else -9)
        hits.sort(key=score, reverse=True)
        print("지금 신호 %d개 (이 종목 과거 성적 순, 표본 5회 미만은 뒤로):" % len(hits))
        for a, s in hits:
            m = a["market"]
            st = a["stats"][s["setup"]]
            rec = "과거 %d회 평균 %+.2fR(비용 후)" % (st["n"], st["avg_rnet"]) if st["n"] else "과거 기록 없음"
            print("  %-11s %-10s %-8s 추세 %s  진입 %s 손절 %s 목표 %s  | %s" % (
                a["symbol"], (nm.get(a["symbol"], "") or "")[:8], SETUP_KO[s["setup"]], a["trend"],
                crt.price_str(s["entry"], m), crt.price_str(s["stop"], m), crt.price_str(s["target"], m), rec))
    else:
        print("지금 신호가 난 종목은 없습니다.")
    if watch:
        print("스퀴즈 진행 중 (돌파 대기) %d개:" % len(watch))
        for a, text in watch[:args.show]:
            print("  %-11s %-10s %s" % (a["symbol"], (nm.get(a["symbol"], "") or "")[:8], text))
    print(crt.DISCLAIMER)
    return 0 if hits else 1


def cmd_backtest(args):
    cfg = crt.config(args.config)
    f = crt.Fetcher(args.offline)
    sym = crt.resolve(args.symbol, f)
    now = crt.now_kst(args.now)
    tfs = ["mid", "short"] if args.tf == "both" else [args.tf]
    for tf in tfs:
        a = analyze(sym, tf, f, cfg, now)
        spec = TF[tf]
        print("%s %s · %s %s %d개 (%s ~ %s), 최대 보유 %d봉, 왕복 비용 %.2f%%" % (
            sym, crt.names().get(sym, ""), spec["name"], spec["label"], a["bars"],
            a["first"].strftime("%Y-%m-%d"), a["bar_time"].strftime("%Y-%m-%d"), spec["max_hold"], a["cost"] * 100))
        for setup in SETUP_KO:
            print("  %-8s %s" % (SETUP_KO[setup], stat_line(a["stats"][setup], "")))
            print("           규칙: %s" % SETUP_HOW[setup])
    print("과거 기록이지 예측이 아닙니다. 같은 데이터로 규칙을 고르면 과거에만 잘 맞을 수 있습니다.")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="볼린저 기반 단기·중기 타점 (coin-signal)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("now", "backtest"):
        p = sub.add_parser(name)
        p.add_argument("symbol")
        p.add_argument("--tf", choices=("short", "mid", "both"), default="both")
        p.add_argument("--now")
        p.add_argument("--offline")
        p.add_argument("--config", default="config.json")
        if name == "now":
            p.add_argument("--json", nargs="?", const="signal.json")
    p = sub.add_parser("scan")
    p.set_defaults(market="crypto")
    p.add_argument("--tf", choices=("short", "mid"), default="mid")
    p.add_argument("--symbols")
    p.add_argument("--limit", type=int)
    p.add_argument("--show", type=int, default=15)
    p.add_argument("--now")
    p.add_argument("--offline")
    p.add_argument("--config", default="config.json")
    args = ap.parse_args(argv)
    try:
        return {"now": cmd_now, "scan": cmd_scan, "backtest": cmd_backtest}[args.cmd](args)
    except crt.CrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
