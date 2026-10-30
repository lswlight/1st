#!/usr/bin/env python3
"""kr-timemachine: 계획을 1만 번 다시 돌려 보고, 같은 규칙의 과거 실제 결과를 센다.

  tm.py run                       krt/plan.json, 10,000회, 시드 19
  tm.py run --replays 20000 --seed 7 --write
  tm.py run --symbol 005930.KS --date 2026-10-29 --entry 245500 --stop 241500 --target 249500 --entry-time 09:25

최근 60일 5분봉의 같은 시간대 봉을 무작위로 이어 붙인 시뮬레이션이다. 예측이 아니다.
"""
import argparse
import os
import random
import statistics
import sys
from datetime import timedelta

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.realpath(__file__))), "lib"))
import krt  # noqa: E402


def ratios(session):
    """각 봉을 직전 봉 종가 대비 비율 (시가, 고가, 저가, 종가)로. 첫 봉은 None."""
    out = [None]
    for prev, b in zip(session, session[1:]):
        pc = prev["c"]
        out.append((b["o"] / pc, b["h"] / pc, b["l"] / pc, b["c"] / pc))
    return out


def walk(price, bars_iter, stop, target):
    """봉을 차례로 보며 손절을 먼저, 그다음 목표를 확인. 둘 다 닿은 봉은 손절로 본다."""
    for o, h, l, c in bars_iter:
        if l <= stop or o <= stop:
            return "stop", stop
        if h >= target:
            return "target", target
        price = c
    return "neither", price


def replay(pool, first, last, entry, stop, target, n, seed, window=2):
    """first..last 슬롯을 n번 다시 만든다. pool: 세션별 비율 리스트."""
    rng = random.Random(seed)
    sessions = list(pool)
    nslots = len(sessions[0])
    res = {"target": 0, "stop": 0, "neither": 0, "green": 0}
    finals, rs = [], []
    risk = entry - stop
    for _ in range(n):
        price = entry
        outcome = None
        for s in range(first, last + 1):
            lo, hi = max(1, s - window), min(nslots - 1, s + window)
            r = sessions[rng.randrange(len(sessions))][rng.randint(lo, hi)]
            o, h, l, c = price * r[0], price * r[1], price * r[2], price * r[3]
            if l <= stop or o <= stop:
                outcome, price = "stop", stop
                break
            if h >= target:
                outcome, price = "target", target
                break
            price = c
        outcome = outcome or "neither"
        res[outcome] += 1
        if outcome == "target" or (outcome == "neither" and price > entry):
            res["green"] += 1
        finals.append(price)
        rs.append(1.0 * (target - entry) / risk if outcome == "target" else
                  -1.0 if outcome == "stop" else (price - entry) / risk)
    finals.sort()
    pct = lambda q: finals[min(len(finals) - 1, int(q * len(finals)))]  # noqa: E731
    return {"n": n, "seed": seed, "target": res["target"] / n * 100, "stop": res["stop"] / n * 100,
            "neither": res["neither"] / n * 100, "green": res["green"] / n * 100,
            "median": statistics.median(finals), "p5": pct(0.05), "p95": pct(0.95),
            "avg_r": statistics.fmean(rs), "bars": last - first + 1}


def stop_for(rule, entry, or_hi, or_lo, plan, closes=None, cfg=None):
    if rule == "or-mid":
        return (or_hi + or_lo) / 2
    if rule == "or-low":
        return or_lo
    if rule.startswith("pct:"):
        return entry * (1 - float(rule[4:]) / 100)
    if rule == "bb-mid" and closes:
        bb = krt.bollinger(closes, cfg["bb_period"], cfg["bb_k"])
        return bb["mid"] if bb else None
    # 직접 지정한 가격은 진입가 대비 같은 비율로 옮긴다.
    return entry * (plan["stop"] / plan["entry"])


def history(sessions_by_day, plan, cfg, last_slot):
    """같은 규칙을 과거 세션마다 실제로 적용한 결과."""
    market = plan["market"]
    setup = plan.get("setup", "orb")
    if setup == "level":
        return None
    n_or = int(plan.get("or_minutes") or 15) // 5
    out = {"fired": 0, "target": 0, "stop": 0, "neither": 0, "r": []}
    closes_all = []
    for day in sorted(sessions_by_day):
        bs = sessions_by_day[day]
        hi = max(b["h"] for b in bs[:n_or])
        lo = min(b["l"] for b in bs[:n_or])
        entry_i = None
        for i in range(n_or, last_slot):
            b = bs[i]
            if setup == "orb" and b["c"] > hi:
                entry_i = i
                break
            if setup == "bb":
                cl = closes_all + [x["c"] for x in bs[:i + 1]]
                if len(cl) > cfg["bb_period"] + 1:
                    cur = krt.bollinger(cl, cfg["bb_period"], cfg["bb_k"])
                    prev = krt.bollinger(cl[:-1], cfg["bb_period"], cfg["bb_k"])
                    if b["c"] > cur["up"] and cl[-2] <= prev["up"]:
                        entry_i = i
                        break
        if entry_i is not None:
            entry = bs[entry_i]["c"]
            cl = closes_all + [x["c"] for x in bs[:entry_i + 1]]
            stop = stop_for(plan["stop_rule"], entry, hi, lo, plan, cl, cfg)
            if stop is not None and stop < entry:
                risk = entry - stop
                target = entry + plan["target_r"] * risk
                rest = [(b["o"], b["h"], b["l"], b["c"]) for b in bs[entry_i + 1:last_slot]]
                outcome, price = walk(entry, rest, stop, target)
                out["fired"] += 1
                out[outcome] += 1
                out["r"].append(plan["target_r"] if outcome == "target" else -1.0 if outcome == "stop"
                                else (price - entry) / risk)
        closes_all.extend(b["c"] for b in bs)
    out["days"] = len(sessions_by_day)
    out["avg_r"] = statistics.fmean(out["r"]) if out["r"] else None
    return out


def verdict(mc, target_r):
    hit = mc["target"] + mc["stop"]
    if hit <= 0:
        return "판단 불가: 목표에도 손절에도 닿은 경우가 없습니다."
    share = mc["target"] / hit * 100
    need = 100 / (1 + target_r)
    d = share - need
    if abs(d) < 5:
        head = "동전 던지기에 가까움" if abs(target_r - 1) < 1e-9 else "본전 근처"
    elif abs(d) < 15:
        head = "목표 쪽으로 약간 기욺" if d > 0 else "손절 쪽으로 약간 기욺"
    else:
        head = "목표 쪽으로 강하게 기욺" if d > 0 else "손절 쪽으로 강하게 기욺"
    return "%s: 익절 먼저 %.1f%% 대 손절 먼저 %.1f%% (한쪽에 닿은 경우 중 익절 %.0f%%, 본전 기준 %.0f%%)." % (
        head, mc["target"], mc["stop"], share, need)


def cmd_run(args):
    cfg = krt.config(args.config)
    if args.entry is not None:
        sym = krt.normalize(args.symbol)
        market = krt.market_of(sym)
        plan = {"symbol": sym, "market": market, "date": args.date, "setup": args.setup,
                "entry": args.entry, "stop": args.stop, "target": args.target,
                "target_r": round((args.target - args.entry) / (args.entry - args.stop), 4),
                "stop_rule": args.stop_rule or "price:%s" % args.stop, "or_minutes": cfg["or_minutes"],
                "entry_time": args.entry_time}
        t = krt.parse_kst(args.date + " " + args.entry_time)
        plan["entry_bar"] = krt.slot(t, market) - 1
        plan["hold_until"] = krt.at_time(t.date(), args.until or cfg["krx_flat_by"]).isoformat()
    else:
        plan = krt.load_json(args.input)
        if not plan:
            krt.die("계획(%s)이 없습니다. 먼저 kr-plan 으로 plan.py exit --write 를 돌리세요." % args.input)
    market = plan["market"]
    if plan["stop"] >= plan["entry"] or plan["target"] <= plan["entry"]:
        krt.die("계획이 맞지 않습니다: 매수는 손절 < 진입 < 목표여야 합니다.")
    if plan.get("entry_bar") is None:
        krt.die("진입 시각(봉)이 계획에 없습니다. --entry-time HH:MM 을 주세요.")
    day = krt.parse_kst(plan["date"] + " 00:00").date()
    f = krt.Fetcher(args.offline)
    data = f.chart(plan["symbol"], "60d", "5m")
    bars = krt.five_minute_bars(data["bars"], market, krt.session_open(day, market))
    pool_days = krt.full_sessions(bars, market)
    if len(pool_days) < 20:
        krt.die("과거 전체 세션이 %d개뿐입니다 (20개 이상 필요)." % len(pool_days))
    nslots = krt.MARKETS[market]["bars"]
    first = plan["entry_bar"] + 1
    hold = krt.parse_kst(plan["hold_until"][:16].replace("T", " "))
    last = min(krt.slot(hold, market) - 1, nslots - 1)
    if args.until:
        last = min(krt.slot(krt.at_time(hold.date(), args.until), market) - 1, nslots - 1)
    if last < first:
        krt.die("진입 이후 청산 시한까지 남은 5분봉이 없습니다.")
    pool = [ratios(bs) for bs in pool_days.values()]
    mc = replay(pool, first, last, plan["entry"], plan["stop"], plan["target"], args.replays, args.seed, args.window)
    hist = history(pool_days, plan, cfg, last + 1)
    days = sorted(pool_days)
    m = market
    ps = lambda x: krt.price_str(x, m)  # noqa: E731
    end_t = krt.session_open(day, market) + krt.BAR * (last + 1)
    start_t = krt.session_open(day, market) + krt.BAR * first
    print("%s %s 매수, %s: 진입 %s (%s), 손절 %s, 목표 %s (%gR, %s 위험 %s)" % (
        plan["symbol"], krt.names().get(plan["symbol"], ""), plan["date"], ps(plan["entry"]),
        plan.get("entry_time") or krt.fmt_time(start_t), ps(plan["stop"]), ps(plan["target"]),
        plan["target_r"], "주당", ps(plan["entry"] - plan["stop"])))
    print("%s부터 %s까지 남은 5분봉 %d개를 %s번 다시 만들었습니다 (시드 %d)." % (
        krt.fmt_time(start_t), krt.fmt_time(end_t), mc["bars"], "{:,}".format(mc["n"]), mc["seed"]))
    print("각 봉은 이 종목의 최근 전체 세션 %d개(%s ~ %s) 중 같은 시간대(앞뒤 %d봉) 실제 봉에서 골랐습니다." % (
        len(days), days[0], days[-1], args.window))
    print()
    print("  익절 먼저   %5.1f%%" % mc["target"])
    print("  손절 먼저   %5.1f%%" % mc["stop"])
    print("  둘 다 아님  %5.1f%%   %s까지 보유 중" % (mc["neither"], krt.fmt_time(end_t)))
    print("  플러스      %5.1f%%   익절, 또는 %s에 진입가 위" % (mc["green"], krt.fmt_time(end_t)))
    print()
    print("  %s 가격: 중앙값 %s, 90%%가 %s ~ %s 사이." % (krt.fmt_time(end_t), ps(mc["median"]), ps(mc["p5"]), ps(mc["p95"])))
    print("  평균 R: %+.3f (익절 +%gR, 손절 -1R, 수수료·세금 전)" % (mc["avg_r"], plan["target_r"]))
    print()
    if hist is None:
        print("과거 기록: 가격 레벨 셋업은 과거 세션에 같은 기준을 적용할 수 없어 생략합니다.")
    else:
        avg = "%+.3f" % hist["avg_r"] if hist["avg_r"] is not None else "없음"
        print("과거 기록: 같은 규칙을 실제 %d개 세션에 적용하니 %d번 신호가 났고, 익절 %d, 손절 %d, 둘 다 아님 %d. 평균 R %s." % (
            hist["days"], hist["fired"], hist["target"], hist["stop"], hist["neither"], avg))
    print()
    print(verdict(mc, plan["target_r"]))
    if mc["neither"] > 40:
        print("%.1f%%는 청산 시한까지 어느 쪽에도 닿지 않았습니다. 그 경우는 시한의 가격이 결과를 정합니다." % mc["neither"])
    if hist and hist["avg_r"] is not None and (hist["avg_r"] > 0) != (mc["avg_r"] > 0):
        print("시뮬레이션과 과거 실제 기록의 방향이 엇갈립니다.")
    print("최근 60일 5분봉으로 만든 시뮬레이션이지 예측이 아닙니다. 실적·공시·뉴스는 그 60일에 없던 움직임을 만들 수 있습니다. 같은 시드면 같은 숫자가 나옵니다.")
    if args.write:
        krt.save_json(args.write, {"plan": plan, "mc": mc, "history": hist,
                                   "verdict": verdict(mc, plan["target_r"])})
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="계획 1만 번 다시 돌리기 (kr-timemachine)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run")
    p.add_argument("--input", default="plan.json")
    p.add_argument("--replays", type=int, default=10000)
    p.add_argument("--seed", type=int, default=19)
    p.add_argument("--window", type=int, default=2)
    p.add_argument("--until", help="HH:MM 이 시각에 시뮬레이션 종료")
    p.add_argument("--symbol")
    p.add_argument("--date")
    p.add_argument("--entry", type=float)
    p.add_argument("--stop", type=float)
    p.add_argument("--target", type=float)
    p.add_argument("--entry-time")
    p.add_argument("--setup", choices=("orb", "bb", "level"), default="orb")
    p.add_argument("--stop-rule")
    p.add_argument("--offline")
    p.add_argument("--config", default="config.json")
    p.add_argument("--write", nargs="?", const="timemachine.json")
    args = ap.parse_args(argv)
    try:
        return cmd_run(args)
    except krt.KrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
