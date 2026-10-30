#!/usr/bin/env python3
"""coin-signal 검증실: 볼린저 셋업을 코인 여러 개에 합쳐서 설정별로 비교한다.

  lab.py run                                   기본: 1d·4h·1h × 세 셋업 × 세 청산 × BTC 필터 유무
  lab.py run --tf 1d --setups squeeze_break --exits fixed2r,trail
  lab.py run --refresh                         저장해 둔 데이터를 버리고 새로 받기

받은 데이터는 crt/cache/ 에 저장해 12시간 동안 다시 쓴다.
결과는 앞 60% 기간(규칙을 고르는 구간)과 뒤 40% 기간(처음 보는 구간)을 나눠 보여 준다.
뒤 구간에서도 플러스여야 의미가 있다.
"""
import argparse
import bisect
import gzip
import json
import os
import statistics
import sys
import time
import importlib.util
from datetime import datetime

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "lib"))
import crt  # noqa: E402

_spec = importlib.util.spec_from_file_location("coin_points", os.path.join(HERE, "points.py"))
pts = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pts)

# 시간축별 데이터 기간, 보유 한도(고정 목표 / 추적 청산), 손절폭 상한
TFS = {
    "1d": {"range": "1460d", "hold": 20, "trail_hold": 60, "max_risk": 15.0, "label": "일봉 4년"},
    "4h": {"range": "730d", "hold": 60, "trail_hold": 180, "max_risk": 8.0, "label": "4시간봉 2년"},
    "1h": {"range": "365d", "hold": 48, "trail_hold": 144, "max_risk": 5.0, "label": "1시간봉 1년"},
}
EXITS = {
    "fixed2r": "고정 2R",
    "fixed3r": "고정 3R",
    "trail": "추적(종가가 중심선 아래면 정리)",
}
FILTERS = {"none": "필터 없음", "btc": "BTC 일봉 중심선 위일 때만"}


# ------------------------------------------------------------------ 데이터 (캐시)

def cached_bars(f, symbol, tf, refresh=False, max_age=12 * 3600):
    path = crt.path(os.path.join("cache", "%s-%s.json.gz" % (symbol, tf)))
    if not refresh and os.path.isfile(path) and time.time() - os.path.getmtime(path) < max_age:
        with gzip.open(path, "rt", encoding="utf-8") as fh:
            rows = json.load(fh)
        return [{"t": datetime.fromisoformat(r[0]), "o": r[1], "h": r[2], "l": r[3], "c": r[4], "v": r[5]}
                for r in rows]
    bars = f.chart(symbol, TFS[tf]["range"], tf)["bars"]
    bars = crt.completed(bars, tf, crt.now_kst())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.open(path, "wt", encoding="utf-8") as fh:
        json.dump([[b["t"].isoformat(), b["o"], b["h"], b["l"], b["c"], b["v"]] for b in bars], fh)
    return bars


def btc_regime(bars, ind):
    """[(그 일봉이 완성되는 시각, 종가가 중심선 위인가)] 시간순."""
    out = []
    for b, x in zip(bars, ind):
        if x["bb"]:
            out.append((b["t"] + crt.INTERVALS["1d"], b["c"] > x["bb"]["mid"]))
    return out


def regime_at(regime, t):
    """t 시점에 이미 완성된 마지막 BTC 일봉의 상태. 모르면 None."""
    keys = [r[0] for r in regime]
    i = bisect.bisect_right(keys, t) - 1
    return regime[i][1] if i >= 0 else None


# ------------------------------------------------------------------ 청산

def exit_trade(bars, ind, i, tr, mode, hold, trail_hold):
    """(결과, R, 청산 봉 번호). 손절은 늘 먼저 본다 (한 봉이 손절과 목표를 둘 다 건드리면 손절)."""
    entry, stop, risk = tr["entry"], tr["stop"], tr["risk"]
    if tr["setup"] == "lower_reentry" or mode.startswith("fixed"):
        target = tr["target"] if tr["setup"] == "lower_reentry" else entry + (2 if mode == "fixed2r" else 3) * risk
        r_t = (target - entry) / risk
        for j in range(i + 1, min(len(bars), i + 1 + hold)):
            b = bars[j]
            if b["l"] <= stop or b["o"] <= stop:
                return "stop", -1.0, j
            if b["h"] >= target:
                return "target", r_t, j
        j = min(len(bars) - 1, i + hold)
        return ("time", (bars[j]["c"] - entry) / risk, j) if j > i else ("open", 0.0, i)
    # 추적: 처음 손절은 그대로, 종가가 볼린저 중심선 아래로 마감하면 그 종가에 정리
    for j in range(i + 1, min(len(bars), i + 1 + trail_hold)):
        b = bars[j]
        if b["l"] <= stop or b["o"] <= stop:
            return "stop", -1.0, j
        mid = ind[j]["bb"]["mid"] if ind[j]["bb"] else None
        if mid and b["c"] < mid:
            return "trail", (b["c"] - entry) / risk, j
    j = min(len(bars) - 1, i + trail_hold)
    return ("time", (bars[j]["c"] - entry) / risk, j) if j > i else ("open", 0.0, i)


# ------------------------------------------------------------------ 집계

def summarize(trades):
    if not trades:
        return None
    rn = [t["rnet"] for t in trades]
    wins = [x for x in rn if x > 0]
    loss = [x for x in rn if x <= 0]
    eq = peak = dd = 0.0
    for t in sorted(trades, key=lambda x: x["exit_t"]):
        eq += t["rnet"]
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    return {"n": len(rn), "win": len(wins) / len(rn) * 100, "avg": statistics.fmean(rn),
            "median": statistics.median(rn), "total": sum(rn),
            "pf": (sum(wins) / -sum(loss)) if loss and sum(loss) < 0 else float("inf"),
            "mdd": dd, "hold": statistics.fmean(t["bars"] for t in trades)}


def collect(symbols, tf, setups, exits, filters, f, refresh, cost_of, log):
    spec = TFS[tf]
    btc_bars = cached_bars(f, "BTCUSDT", "1d", refresh)
    regime = btc_regime(btc_bars, pts.indicators(btc_bars))
    out = {}
    done = 0
    for s in symbols:
        try:
            bars = cached_bars(f, s, tf, refresh)
        except crt.CrtError as e:
            log("  %s 건너뜀: %s" % (s, e))
            continue
        if len(bars) < 200:
            continue
        ind = pts.indicators(bars)
        cost = cost_of(s)
        for i in range(60, len(bars) - 1):
            for setup, ref, t1 in pts.setups_at(bars, ind, i):
                if setup not in setups:
                    continue
                tr = pts.make_trade(bars, i, setup, ref, t1, "crypto", crt.quote_of(s), max_risk_pct=spec["max_risk"])
                if not tr:
                    continue
                sig_close = bars[i]["t"] + crt.INTERVALS[tf]
                up = regime_at(regime, sig_close)
                for mode in exits:
                    if setup == "lower_reentry" and mode != exits[0]:
                        continue      # 하단 복귀는 목표가 중심선 하나뿐이라 한 번만 센다
                    outcome, r, j = exit_trade(bars, ind, i, tr, mode, spec["hold"], spec["trail_hold"])
                    if outcome == "open":
                        continue
                    for flt in filters:
                        if flt == "btc" and up is not True:
                            continue
                        key = (setup, "목표=중심선" if setup == "lower_reentry" else mode, flt)
                        lst = out.setdefault(key, [])
                        # 같은 코인·같은 설정에서 앞 거래가 끝나기 전 신호는 건너뛴다
                        if lst and lst[-1]["sym"] == s and lst[-1]["exit_i"] >= i:
                            continue
                        lst.append({"sym": s, "entry_t": bars[i]["t"], "exit_t": bars[j]["t"], "exit_i": j,
                                    "r": r, "rnet": r - cost * tr["entry"] / tr["risk"], "bars": j - i,
                                    "outcome": outcome})
        done += 1
        if done % 10 == 0:
            log("  %s: %d/%d 코인" % (tf, done, len(symbols)))
    return out


def fmt(st):
    if not st:
        return "  -"
    pf = "∞" if st["pf"] == float("inf") else "%.2f" % st["pf"]
    return "%4d회 적중 %4.1f%% 평균 %+.3fR 합계 %+6.1fR PF %s 최대낙폭 %.1fR" % (
        st["n"], st["win"], st["avg"], st["total"], pf, st["mdd"])


def cmd_run(args):
    cfg = crt.config(args.config)
    f = crt.Fetcher()
    symbols = [s.strip().upper() for s in args.symbols.split(",")] if args.symbols else \
        [r["symbol"] for r in crt.load_universe(None, "universe-crypto.txt")]
    symbols = [crt.normalize(s) for s in symbols]

    def cost_of(sym):
        fee = cfg["binance_fee_pct"] if crt.quote_of(sym) == "USDT" else cfg["crypto_fee_pct"]
        return 2 * fee / 100

    log = (lambda m: print(m, file=sys.stderr)) if not args.quiet else (lambda m: None)
    setups = args.setups.split(",")
    exits = args.exits.split(",")
    filters = args.filters.split(",")
    report = {"made": crt.now_kst().isoformat(), "symbols": symbols, "split": args.split, "results": []}
    print("코인 %d개 합산, 왕복 수수료 반영(바이낸스 %.2f%%), 앞 %d%% / 뒤 %d%% 기간 분리" % (
        len(symbols), 2 * cfg["binance_fee_pct"], args.split * 100, 100 - args.split * 100))
    for tf in args.tf.split(","):
        data = collect(symbols, tf, setups, exits, filters, f, args.refresh, cost_of, log)
        all_t = [t["entry_t"] for lst in data.values() for t in lst]
        if not all_t:
            print("\n[%s] 신호 없음" % TFS[tf]["label"])
            continue
        lo, hi = min(all_t), max(all_t)
        cut = lo + (hi - lo) * args.split
        print("\n[%s] %s ~ %s, 나누는 날 %s" % (TFS[tf]["label"], lo.date(), hi.date(), cut.date()))
        for key in sorted(data, key=lambda k: (k[0], k[1], k[2])):
            lst = data[key]
            st_all = summarize(lst)
            st_is = summarize([t for t in lst if t["entry_t"] < cut])
            st_oos = summarize([t for t in lst if t["entry_t"] >= cut])
            setup, mode, flt = key
            name = "%s · %s · %s" % (pts.SETUP_KO[setup], EXITS.get(mode, mode), FILTERS[flt])
            mark = " ✅" if st_is and st_oos and st_is["avg"] > 0 and st_oos["avg"] > 0 and st_oos["n"] >= 20 else ""
            print("  %s%s" % (name, mark))
            print("     전체 %s" % fmt(st_all))
            print("     앞   %s" % fmt(st_is))
            print("     뒤   %s" % fmt(st_oos))
            report["results"].append({"tf": tf, "setup": setup, "exit": mode, "filter": flt,
                                      "all": st_all, "in_sample": st_is, "out_of_sample": st_oos,
                                      "cut": cut.isoformat()})
    print("\n✅ = 앞·뒤 기간 모두 수수료 후 평균 플러스, 뒤 기간 20회 이상.")
    print("지금 거래대금 상위 코인만 본 결과라 생존 편향이 있습니다. 과거 기록이지 예측이 아닙니다.")
    if args.write:
        p = crt.save_json(args.write, report)
        print("저장: %s" % p)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="볼린저 셋업 검증실 (coin-signal lab)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run")
    p.add_argument("--tf", default="1d,4h,1h")
    p.add_argument("--setups", default="squeeze_break,mid_pullback,lower_reentry")
    p.add_argument("--exits", default="fixed2r,fixed3r,trail")
    p.add_argument("--filters", default="none,btc")
    p.add_argument("--symbols")
    p.add_argument("--split", type=float, default=0.6)
    p.add_argument("--refresh", action="store_true")
    p.add_argument("--quiet", action="store_true")
    p.add_argument("--config", default="config.json")
    p.add_argument("--write", nargs="?", const="lab.json")
    args = ap.parse_args(argv)
    try:
        return cmd_run(args)
    except crt.CrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
