#!/usr/bin/env python3
"""coin-signal: 볼린저 밴드 기반 단기(4시간봉)·중기(일봉) 매수 타점.

  points.py now SOL                         단기+중기 현재 타점 (바이낸스 SOLUSDT)
  points.py now BTC --tf mid                중기만
  points.py scan --tf mid                   목록 전체에서 지금 신호가 난 코인
  points.py manage SOL --entry 96 --stop 92 들고 있는 포지션: 보유 유지인지 정리 신호인지
  points.py backtest BTC --tf mid           셋업별 과거 결과만

신호가 나면 진입·손절과 두 가지 청산(고정 2R, 중심선 이탈 추적)을, 이 코인의 과거 결과와
여러 코인 합산 검증 결과(evidence.json, lab.py 로 만듦)와 함께 낸다. 매수(롱)만. 주문은 내지 않는다.
"""
import json
import argparse
import os
import statistics
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.realpath(__file__))), "lib"))
import crt  # noqa: E402

# 단기 = 4시간봉, 중기 = 일봉. 1시간봉은 30개 코인 검증에서 수수료 후 마이너스라 뺐다 (README).
# max_hold: 고정 목표의 최대 보유 봉수, trail_hold: 추적 청산의 최대 보유 봉수.
TF = {
    "short": {"name": "단기", "interval": "4h", "range": "1y", "scan_range": "6mo", "label": "4시간봉",
              "max_hold": 60, "trail_hold": 180, "max_risk_pct": 8.0},
    "mid": {"name": "중기", "interval": "1d", "range": "1460d", "scan_range": "2y", "label": "일봉",
            "max_hold": 20, "trail_hold": 60, "max_risk_pct": 15.0},
}
EXIT_KO = {"fixed2r": "고정 2R", "trail": "추적(중심선 이탈 시 정리)", "target": "목표=중심선"}
EVIDENCE = os.path.join(os.path.dirname(os.path.realpath(__file__)), "evidence.json")

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


CHASE_PCT = 30   # 진입가가 이만큼(손절폭 대비 %) 넘게 오르면 손절폭이 커져 추격 상한으로 본다


def entry_zone(bars, ind, i, tr, market="crypto", quote="USDT"):
    """스퀴즈 돌파의 진입 범위.
    30개 코인 검증(lab.py entry)에서 돌파 봉 종가에 바로 사는 쪽이 되돌림 지정가보다 신호당 성적이 좋았다
    (되돌림을 기다리면 가장 크게 오르는 신호를 놓친다). 그래서 종가가 1순위, 범위 중간까지가 허용 하단,
    손절폭이 CHASE_PCT% 넘게 커지는 가격이 추격 상한이다."""
    level = ind[i - 1]["bb"]["up"]
    close = bars[i]["c"]
    stop = tr["stop"]
    low = (level + close) / 2
    chase = close + (close - stop) * CHASE_PCT / 100
    return {"level": crt.round_down(level, market, quote), "low": crt.round_down(low, market, quote),
            "best": close, "chase": crt.round_near(chase, market, quote), "stop": stop}


def zone_text(z, market="crypto"):
    ps = lambda x: crt.price_str(x, market)  # noqa: E731
    return ("진입 범위 %s ~ %s (1순위: 종가 %s 부근에서 바로), 추격 상한 %s, 첫 손절 %s. "
            "돌파 기준선 %s 아래로 되돌리면 신호가 약해진 것" % (
                ps(z["low"]), ps(z["best"]), ps(z["best"]), ps(z["chase"]), ps(z["stop"]), ps(z["level"])))


def modes_for(setup):
    """셋업별로 보는 청산 방식. 하단 복귀는 목표가 중심선 하나뿐이다."""
    return ["target"] if setup == "lower_reentry" else ["fixed2r", "trail"]


def exit_trade(bars, ind, i, tr, mode, hold, trail_hold):
    """(결과, R, 청산 봉 번호). 다음 봉부터 손절을 먼저 본다 (한 봉이 손절과 목표를 둘 다 건드리면 손절).
    fixed2r/fixed3r: 고정 목표, target: 계획의 목표(하단 복귀의 중심선), trail: 종가가 중심선 아래면 그 종가에 정리."""
    entry, stop, risk = tr["entry"], tr["stop"], tr["risk"]
    if mode != "trail" or tr["setup"] == "lower_reentry":
        if tr["setup"] == "lower_reentry" or mode == "target":
            target = tr["target"]
        else:
            target = entry + (3 if mode == "fixed3r" else 2) * risk
        r_t = (target - entry) / risk
        for j in range(i + 1, min(len(bars), i + 1 + hold)):
            b = bars[j]
            if b["l"] <= stop or b["o"] <= stop:
                return "stop", -1.0, j
            if b["h"] >= target:
                return "target", r_t, j
        j = min(len(bars) - 1, i + hold)
        return ("time", (bars[j]["c"] - entry) / risk, j) if j > i else ("open", 0.0, i)
    for j in range(i + 1, min(len(bars), i + 1 + trail_hold)):
        b = bars[j]
        if b["l"] <= stop or b["o"] <= stop:
            return "stop", -1.0, j
        mid = ind[j]["bb"]["mid"] if ind[j]["bb"] else None
        if mid and b["c"] < mid:
            return "trail", (b["c"] - entry) / risk, j
    j = min(len(bars) - 1, i + trail_hold)
    return ("time", (bars[j]["c"] - entry) / risk, j) if j > i else ("open", 0.0, i)


def walk(bars, i, trade, max_hold):
    """고정 목표(trade["target"]) 청산. exit_trade 의 target 방식과 같다."""
    return exit_trade(bars, None, i, dict(trade, setup=trade.get("setup", "x")), "target", max_hold, max_hold)


def backtest(bars, ind, market, quote, max_hold, cost_pct, max_risk_pct=100.0, trail_hold=None):
    """셋업·청산 방식별로 과거 신호를 모두 따라가 본다: stats[셋업][방식].
    한 거래가 끝나기 전 같은 셋업·방식의 새 신호는 건너뛴다."""
    trail_hold = trail_hold or max_hold * 3
    stats = {k: {m: {"n": 0, "target": 0, "stop": 0, "time": 0, "trail": 0, "r": [], "rnet": []}
                 for m in modes_for(k)} for k in SETUP_KO}
    busy = {(k, m): -1 for k in SETUP_KO for m in modes_for(k)}
    for i in range(60, len(bars) - 1):
        for setup, ref, t1 in setups_at(bars, ind, i):
            tr = None
            for mode in modes_for(setup):
                if i <= busy[(setup, mode)]:
                    continue
                tr = tr or make_trade(bars, i, setup, ref, t1, market, quote, max_risk_pct=max_risk_pct)
                if not tr:
                    break
                outcome, r, j = exit_trade(bars, ind, i, tr, mode, max_hold, trail_hold)
                if outcome == "open":
                    continue
                busy[(setup, mode)] = j
                st = stats[setup][mode]
                st["n"] += 1
                st[outcome] += 1
                st["r"].append(r)
                st["rnet"].append(r - cost_pct * tr["entry"] / tr["risk"])
    for per in stats.values():
        for st in per.values():
            st["avg_r"] = statistics.fmean(st["r"]) if st["r"] else None
            st["avg_rnet"] = statistics.fmean(st["rnet"]) if st["rnet"] else None
    return stats


def load_evidence():
    try:
        with open(EVIDENCE, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def evidence_for(ev, tf, setup, mode):
    """evidence.json 에서 (시간축, 셋업, 청산, 필터 없음) 결과."""
    if not ev:
        return None
    key = {"mid": "1d", "short": "4h"}[tf]
    for r in ev.get("results", []):
        if r["tf"] == key and r["setup"] == setup and r["exit"] == mode and r["filter"] == "none":
            return r
    return None


def grade(r):
    """검증 결과 등급: 튼튼 / 큰 수익 몇 건에 의존 / 마이너스."""
    if not r or not r.get("all"):
        return "검증 자료 없음"
    oos = r.get("out_of_sample") or {}
    if (r["all"]["avg"] > 0 and oos.get("avg", -1) > 0 and (r.get("ex_top10_avg") or -1) > 0
            and (r.get("large_avg") or -1) > 0):
        return "튼튼함 (큰 수익 10건을 빼도, 대형 코인만 봐도 플러스)"
    if r["all"]["avg"] > 0 and oos.get("avg", -1) > 0:
        return "플러스지만 큰 수익 몇 건에 기댐 (모든 신호를 따라야 그 몇 건을 잡는다)"
    return "수수료 후 마이너스 (참고만)"


def evidence_line(r):
    if not r or not r.get("all"):
        return None
    a, o = r["all"], r.get("out_of_sample") or {}
    extra = []
    if r.get("ex_top10_avg") is not None:
        extra.append("상위 10건 빼면 %+.2fR" % r["ex_top10_avg"])
    if r.get("large_avg") is not None:
        extra.append("대형 10개만 %+.2fR" % r["large_avg"])
    return "코인 %d개 합산 %d회, 적중 %.0f%%, 평균 %+.2fR, 뒤 기간 %+.2fR%s" % (
        r.get("coins", 0), a["n"], a["win"], a["avg"], o.get("avg", 0.0),
        (", " + ", ".join(extra)) if extra else "")


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
    if spec["interval"] in ("1h", "4h"):
        bars = [b for b in bars if b["t"].minute == 0 and b["t"].second == 0]
    bars = [b for b in bars if b["t"] <= now]
    bars = crt.completed(bars, spec["interval"], now, market)
    if len(bars) < 80:
        raise crt.CrtError("%s: %s이 %d개뿐입니다 (80개 이상 필요)" % (symbol, spec["label"], len(bars)))
    ind = indicators(bars, cfg["bb_period"], cfg["bb_k"])
    i = len(bars) - 1
    cost = roundtrip_cost(symbol, market, cfg)
    stats = backtest(bars, ind, market, quote, spec["max_hold"], cost, spec["max_risk_pct"], spec["trail_hold"])
    sigs = []
    for setup, ref, t1 in setups_at(bars, ind, i):
        tr = make_trade(bars, i, setup, ref, t1, market, quote, max_risk_pct=spec["max_risk_pct"])
        if tr:
            tr["divergence"] = setup == "lower_reentry" and divergence(bars, ind, i)
            if setup == "squeeze_break":
                tr["zone"] = entry_zone(bars, ind, i, tr, market, quote)
            sigs.append(tr)
    bb = ind[i]["bb"]
    return {"symbol": symbol, "tf": tf, "market": market, "quote": quote, "bar_time": bars[i]["t"],
            "close": bars[i]["c"], "trend": trend(bars, ind, i), "pctb": bb["pctb"], "width": bb["width"],
            "rank": ind[i]["rank"], "rsi": ind[i]["rsi"], "bb": bb, "ma60": ind[i]["ma60"],
            "signals": sigs, "watch": watch_items(bars, ind, i, market), "stats": stats,
            "bars": len(bars), "first": bars[0]["t"], "cost": cost}


def stat_line(st, label):
    if not st["n"]:
        return "%s 같은 신호 없음" % label
    warn = " (표본 적음)" if st["n"] < 10 else ""
    trail = ", 추적 정리 %d" % st["trail"] if st.get("trail") else ""
    return "%s %d회: 목표 %d, 손절 %d%s, 시간 정리 %d, 평균 %+.2fR (수수료 후 %+.2fR)%s" % (
        label, st["n"], st["target"], st["stop"], trail, st["time"], st["avg_r"], st["avg_rnet"], warn)


def best_mode(stats_setup):
    """이 코인 과거 성적이 더 나은 청산 방식 (표본 5회 이상일 때만 비교)."""
    cands = [(st["avg_rnet"], m) for m, st in stats_setup.items() if st["n"] >= 5 and st["avg_rnet"] is not None]
    return max(cands)[1] if cands else None


def print_tf(a, ev=None):
    spec = TF[a["tf"]]
    m = a["market"]
    ps = lambda x: crt.price_str(x, m)  # noqa: E731
    when = a["bar_time"].strftime("%m-%d %H:%M") if a["tf"] == "short" else a["bar_time"].strftime("%Y-%m-%d")
    period = "이 코인 %s~" % a["first"].strftime("%Y-%m-%d")
    print("[%s · %s] 마지막 완성 봉 %s, 종가 %s" % (spec["name"], spec["label"], when, ps(a["close"])))
    print("  추세 %s | %%B %.2f | 밴드폭 %.1f%% (하위 %d%%) | RSI %s | 중심 %s, 상단 %s, 하단 %s" % (
        a["trend"], a["pctb"], a["width"], a["rank"] or 0, "%.1f" % a["rsi"] if a["rsi"] else "-",
        ps(a["bb"]["mid"]), ps(a["bb"]["up"]), ps(a["bb"]["lo"])))
    if a["signals"]:
        for s in a["signals"]:
            extra = " + RSI 상승 다이버전스" if s.get("divergence") else ""
            print("  ★ 신호: %s%s (%s 종가 기준)" % (SETUP_KO[s["setup"]], extra, when))
            print("     진입 %s / 첫 손절 %s (-%.2f%%)" % (ps(s["entry"]), ps(s["stop"]), s["risk"] / s["entry"] * 100))
            if s.get("zone"):
                print("     " + zone_text(s["zone"], m))
            for mode in modes_for(s["setup"]):
                if mode == "trail":
                    plan = "%s 종가가 중심선 아래로 마감하면 정리 (지금 중심선 %s)" % (spec["label"], ps(a["bb"]["mid"]))
                elif mode == "fixed2r":
                    plan = "목표 %s (2R)" % ps(s["t2"])
                else:
                    plan = "목표 %s (중심선, %.1fR)" % (ps(s["target"]), s["r"])
                print("     - 청산 %s: %s" % (EXIT_KO[mode], plan))
                print("       %s" % stat_line(a["stats"][s["setup"]][mode], period))
                r = evidence_for(ev, a["tf"], s["setup"], mode)
                if r:
                    print("       검증: %s → %s" % (evidence_line(r), grade(r)))
    else:
        print("  지금 난 신호 없음.")
    for setup, text in a["watch"]:
        print("  · 지켜볼 것: %s" % text)
        for mode in modes_for(setup):
            r = evidence_for(ev, a["tf"], setup, mode)
            note = (" | 검증 %s" % grade(r).split(" (")[0]) if r else ""
            print("     %s %s%s" % (EXIT_KO[mode], stat_line(a["stats"][setup][mode], period), note))


def cmd_now(args):
    cfg = crt.config(args.config)
    f = crt.Fetcher(args.offline)
    sym = crt.resolve(args.symbol, f)
    now = crt.now_kst(args.now)
    tfs = ["mid", "short"] if args.tf == "both" else [args.tf]
    print("%s %s · %s · %s 기준" % (sym, crt.names().get(sym, ""), crt.source_label(crt.market_of(sym), sym),
                                  now.strftime("%Y-%m-%d %H:%M")))
    btc = btc_trend(f, cfg, now)
    if btc:
        print("BTC 일봉: %s (종가 %s, 중심선 %s) — 참고용, 검증에서 필터 효과는 뚜렷하지 않았다" % btc)
    ev = load_evidence()
    res = []
    for tf in tfs:
        try:
            a = analyze(sym, tf, f, cfg, now)
        except crt.CrtError as e:
            print("[%s] %s" % (TF[tf]["name"], e))
            continue
        res.append(a)
        print_tf(a, ev)
    if len(res) == 2:
        mid, short = res
        if short["signals"] and mid["trend"] == "하락":
            print("※ 단기 신호가 중기 하락 추세와 반대 방향입니다. 짧게 보고 손절을 지키세요.")
        elif short["signals"] and mid["trend"] == "상승":
            print("※ 단기 신호가 중기 상승 추세와 같은 방향입니다.")
    if ev:
        print("검증 자료: %s (lab.py, 지금 거래대금 상위 코인이라 생존 편향 있음)" % ev.get("made", "")[:10])
    print("평균 R은 '손절 -1R'로 셈한 과거 기록이지 예측이 아닙니다. 추적 청산은 적중률이 낮고(약 30%) 가끔 큰 수익으로 메우는 방식입니다.")
    print(crt.DISCLAIMER)
    if args.json:
        crt.save_json(args.json, [{k: v for k, v in a.items() if k != "stats"} | {"stats": {
            s: {m: {kk: vv for kk, vv in st.items() if kk not in ("r", "rnet")} for m, st in per.items()}
            for s, per in a["stats"].items()}} for a in res])
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
        ev = load_evidence()
        order = {"squeeze_break": 0, "mid_pullback": 1, "lower_reentry": 2}
        hits.sort(key=lambda x: (order[x[1]["setup"]], -(x[0]["stats"][x[1]["setup"]][modes_for(x[1]["setup"])[-1]]
                                                          ["avg_rnet"] or -9)))
        print("지금 신호 %d개 (검증이 튼튼한 셋업 순: 스퀴즈 돌파 → 중심선 눌림 → 하단 복귀):" % len(hits))
        for a, s in hits:
            m = a["market"]
            mode = modes_for(s["setup"])[-1]
            st = a["stats"][s["setup"]][mode]
            rec = "이 코인 %s %d회 평균 %+.2fR" % (EXIT_KO[mode].split("(")[0], st["n"], st["avg_rnet"]) \
                if st["n"] else "이 코인 기록 없음"
            g = evidence_for(ev, args.tf, s["setup"], mode)
            print("  %-11s %-10s %-8s 추세 %s  진입 %s 첫 손절 %s  | %s | 검증 %s" % (
                a["symbol"], (nm.get(a["symbol"], "") or "")[:8], SETUP_KO[s["setup"]], a["trend"],
                crt.price_str(s["entry"], m), crt.price_str(s["stop"], m), rec,
                grade(g).split(" (")[0] if g else "자료 없음"))
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
        print("%s %s · %s %s %d개 (%s ~ %s), 최대 보유 고정 %d봉·추적 %d봉, 왕복 수수료 %.2f%%" % (
            sym, crt.names().get(sym, ""), spec["name"], spec["label"], a["bars"],
            a["first"].strftime("%Y-%m-%d"), a["bar_time"].strftime("%Y-%m-%d"), spec["max_hold"],
            spec["trail_hold"], a["cost"] * 100))
        for setup in SETUP_KO:
            print("  %s — 규칙: %s" % (SETUP_KO[setup], SETUP_HOW[setup]))
            for mode in modes_for(setup):
                print("     %-18s %s" % (EXIT_KO[mode], stat_line(a["stats"][setup][mode], "")))
    print("과거 기록이지 예측이 아닙니다. 같은 데이터로 규칙을 고르면 과거에만 잘 맞을 수 있습니다.")
    return 0


def btc_trend(f, cfg, now):
    """BTC 일봉 종가가 중심선 위인지 (참고 정보). 실패하면 None."""
    try:
        bars = crt.completed(f.chart("BTCUSDT", "3mo", "1d")["bars"], "1d", now)
        bars = [b for b in bars if b["t"] <= now]
        bb = crt.bollinger([b["c"] for b in bars], cfg["bb_period"], cfg["bb_k"])
    except crt.CrtError:
        return None
    if not bb:
        return None
    c = bars[-1]["c"]
    return ("중심선 위" if c > bb["mid"] else "중심선 아래", crt.price_str(c), crt.price_str(bb["mid"]))


def cmd_manage(args):
    """들고 있는 포지션: 손절 도달 / 추적 청산 신호(종가가 중심선 아래) / 보유 유지."""
    cfg = crt.config(args.config)
    f = crt.Fetcher(args.offline)
    sym = crt.resolve(args.symbol, f)
    now = crt.now_kst(args.now)
    spec = TF[args.tf]
    bars = f.chart(sym, "3mo" if args.tf == "mid" else "1mo", spec["interval"])["bars"]
    bars = [b for b in bars if b["t"] <= now and b["t"].minute == 0 and b["t"].second == 0]
    bars = crt.completed(bars, spec["interval"], now)
    if len(bars) < cfg["bb_period"]:
        crt.die("%s: %s이 부족합니다" % (sym, spec["label"]))
    bb = crt.bollinger([b["c"] for b in bars], cfg["bb_period"], cfg["bb_k"])
    last = bars[-1]
    m = crt.market_of(sym)
    ps = lambda x: crt.price_str(x, m)  # noqa: E731
    risk = args.entry - args.stop
    if risk <= 0:
        crt.die("손절가가 진입가보다 낮아야 합니다.")
    r_now = (last["c"] - args.entry) / risk
    when = last["t"].strftime("%m-%d %H:%M") if args.tf == "short" else last["t"].strftime("%Y-%m-%d")
    print("%s %s 보유 관리 · %s · 마지막 완성 봉 %s, 종가 %s (진입 %s, 첫 손절 %s, 지금 %+.2fR)" % (
        sym, crt.names().get(sym, ""), spec["label"], when, ps(last["c"]), ps(args.entry), ps(args.stop), r_now))
    print("  볼린저: 중심 %s, 상단 %s, 하단 %s, %%B %.2f" % (ps(bb["mid"]), ps(bb["up"]), ps(bb["lo"]), bb["pctb"]))
    if last["l"] <= args.stop:
        print("  ■ 손절 도달: 마지막 봉 저가 %s가 손절 %s 이하입니다. 계획대로라면 이미 정리됐어야 합니다." % (
            ps(last["l"]), ps(args.stop)))
        code = 2
    elif last["c"] < bb["mid"]:
        print("  ■ 추적 청산 신호: 종가 %s가 중심선 %s 아래로 마감했습니다. 추적 규칙이면 정리할 자리입니다." % (
            ps(last["c"]), ps(bb["mid"])))
        code = 2
    else:
        print("  □ 보유 유지: 종가가 중심선 위입니다. 다음 %s 종가가 중심선(지금 %s) 아래로 마감하면 정리, "
              "그 전에 손절 %s에 닿으면 손절." % (spec["label"], ps(bb["mid"]), ps(args.stop)))
        if bb["pctb"] > 1:
            print("    상단 밖에서 마감 중 (밴드 워킹). 추적 규칙은 이런 구간을 들고 가려는 것이다.")
        code = 0
    if args.target:
        print("  고정 목표 %s: %s" % (ps(args.target), "도달" if last["h"] >= args.target else "아직"))
    print("규칙 확인일 뿐 매도 권유가 아닙니다. 주문은 거래소 앱에서 직접 판단하세요.")
    return code


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
    p = sub.add_parser("manage")
    p.add_argument("symbol")
    p.add_argument("--entry", type=float, required=True)
    p.add_argument("--stop", type=float, required=True)
    p.add_argument("--target", type=float)
    p.add_argument("--tf", choices=("short", "mid"), default="mid")
    p.add_argument("--now")
    p.add_argument("--offline")
    p.add_argument("--config", default="config.json")
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
        return {"now": cmd_now, "scan": cmd_scan, "backtest": cmd_backtest, "manage": cmd_manage}[args.cmd](args)
    except crt.CrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
