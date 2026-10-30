#!/usr/bin/env python3
"""coin-signal 알람: 스퀴즈 돌파가 나오면 진입 범위와 함께 알린다.

  alert.py check                 바이낸스 상위 30개, 일봉·4시간봉, 새 알람만
  alert.py check --tf mid        일봉만
  alert.py check --symbols BTC,SOL --all    이미 알린 것도 다시 보기

알람 두 가지:
  확정    마지막 완성 봉에서 스퀴즈 돌파 (봉 마감 직후 한 번)
  진행 중 아직 안 끝난 봉에서 가격이 상단 위, 거래량도 속도상 1.5배 이상 (미확정, 마감까지 유지돼야 신호)

같은 알람은 한 번만 낸다 (crt/alerts.json). 새 알람이 있으면 종료 코드 0, 없으면 1.
일봉은 09:00 KST, 4시간봉은 01·05·09·13·17·21시에 마감한다. 주문은 내지 않는다.
"""
import argparse
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "lib"))
import crt  # noqa: E402

_spec = importlib.util.spec_from_file_location("coin_points", os.path.join(HERE, "points.py"))
pts = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pts)

FETCH = {"mid": ("1d", "1y"), "short": ("4h", "3mo")}
STATE = "alerts.json"


def split_live(bars, interval, now):
    """(완성된 봉들, 진행 중인 봉 또는 None, 진행 비율 0~1)."""
    bars = [b for b in bars if b["t"] <= now and b["t"].minute == 0 and b["t"].second == 0]
    done = crt.completed(bars, interval, now)
    live = bars[-1] if len(bars) > len(done) else None
    frac = None
    if live:
        frac = (now - live["t"]) / crt.INTERVALS[interval]
        frac = min(max(frac, 0.01), 1.0)
    return done, live, frac


def check_symbol(sym, tf, f, cfg, now, ev):
    """이 코인·시간축의 알람 목록."""
    interval, rng = FETCH[tf]
    spec = pts.TF[tf]
    market, quote = crt.market_of(sym), crt.quote_of(sym)
    done, live, frac = split_live(f.chart(sym, rng, interval)["bars"], interval, now)
    if len(done) < 140:
        return []
    out = []
    ind = pts.indicators(done, cfg["bb_period"], cfg["bb_k"])
    i = len(done) - 1
    grade = lambda mode: pts.grade(pts.evidence_for(ev, tf, "squeeze_break", mode))  # noqa: E731
    for setup, ref, t1 in pts.setups_at(done, ind, i):
        if setup != "squeeze_break":
            continue
        tr = pts.make_trade(done, i, setup, ref, t1, market, quote, max_risk_pct=spec["max_risk_pct"])
        if not tr:
            continue
        z = pts.entry_zone(done, ind, i, tr, market, quote)
        out.append({"kind": "확정", "symbol": sym, "tf": tf, "bar": done[i]["t"].isoformat(),
                    "price": done[i]["c"], "zone": z, "t2": tr["t2"], "mid": ind[i]["bb"]["mid"],
                    "volx": done[i]["v"] / ind[i]["vavg"] if ind[i]["vavg"] else None,
                    "grade_2r": grade("fixed2r"), "grade_trail": grade("trail")})
    if live and frac:
        # 진행 중인 봉: 거래량은 지금까지의 속도로 봉 전체를 추정해 같은 규칙을 적용한다
        proj = dict(live, v=live["v"] / frac)
        bars2 = done + [proj]
        ind2 = pts.indicators(bars2, cfg["bb_period"], cfg["bb_k"])
        j = len(bars2) - 1
        for setup, ref, t1 in pts.setups_at(bars2, ind2, j):
            if setup != "squeeze_break":
                continue
            tr = pts.make_trade(bars2, j, setup, ref, t1, market, quote, max_risk_pct=spec["max_risk_pct"])
            if not tr:
                continue
            z = pts.entry_zone(bars2, ind2, j, tr, market, quote)
            out.append({"kind": "진행 중", "symbol": sym, "tf": tf, "bar": live["t"].isoformat(),
                        "price": live["c"], "zone": z, "t2": tr["t2"], "mid": ind2[j]["bb"]["mid"],
                        "volx": proj["v"] / ind2[j]["vavg"] if ind2[j]["vavg"] else None,
                        "closes_at": (live["t"] + crt.INTERVALS[interval]).isoformat(),
                        "progress": frac, "grade_2r": grade("fixed2r"), "grade_trail": grade("trail")})
    return out


def message(a):
    spec = pts.TF[a["tf"]]
    z = a["zone"]
    ps = lambda x: crt.price_str(x)  # noqa: E731
    name = crt.names().get(a["symbol"], "")
    head = "[%s] %s %s · %s 스퀴즈 돌파" % (a["kind"], a["symbol"], name, spec["label"])
    if a["kind"] == "진행 중":
        head += " (봉 %d%% 진행, %s 마감까지 유지돼야 확정)" % (a["progress"] * 100, a["closes_at"][11:16])
    lines = [head,
             "  현재 %s, 거래량 %.1f배%s" % (ps(a["price"]), a["volx"] or 0,
                                         " (속도 추정)" if a["kind"] == "진행 중" else ""),
             "  진입 범위 %s ~ %s (1순위: 종가 부근 바로), 추격 상한 %s" % (ps(z["low"]), ps(z["best"]), ps(z["chase"])),
             "  첫 손절 %s (-%.1f%%) | 청산 A: 2R 목표 %s | 청산 B: %s 종가가 중심선(지금 %s) 아래면 정리" % (
                 ps(z["stop"]), (z["best"] - z["stop"]) / z["best"] * 100, ps(a["t2"]), spec["label"], ps(a["mid"])),
             "  검증: 2R %s / 추적 %s" % (a["grade_2r"].split(" (")[0], a["grade_trail"].split(" (")[0])]
    return "\n".join(lines)


def cmd_check(args):
    cfg = crt.config(args.config)
    f = crt.Fetcher(args.offline)
    now = crt.now_kst(args.now)
    ev = pts.load_evidence()
    syms = [crt.normalize(s) for s in args.symbols.split(",")] if args.symbols else \
        [r["symbol"] for r in crt.load_universe(None)]
    tfs = ["mid", "short"] if args.tf == "both" else [args.tf]
    state = crt.load_json(STATE, {}) or {}
    seen = state.setdefault("seen", {})
    found, failed = [], []
    for s in syms:
        for tf in tfs:
            try:
                found += check_symbol(s, tf, f, cfg, now, ev)
            except crt.CrtError:
                failed.append("%s(%s)" % (s, tf))
    new = []
    for a in found:
        key = "%s|%s|%s|%s" % (a["symbol"], a["tf"], a["bar"], a["kind"])
        confirmed_key = "%s|%s|%s|확정" % (a["symbol"], a["tf"], a["bar"])
        if args.all or (key not in seen and confirmed_key not in seen):
            new.append(a)
        seen[key] = now.isoformat()
    # 오래된 기록 정리 (30일)
    cutoff = (now - crt.timedelta(days=30)).isoformat()
    state["seen"] = {k: v for k, v in seen.items() if v >= cutoff}
    state["last_check"] = now.isoformat()
    if not args.dry_run:
        crt.save_json(STATE, state)
    print("스퀴즈 돌파 알람 확인: %s, 코인 %d개 × %s, %s" % (
        now.strftime("%Y-%m-%d %H:%M"), len(syms), "·".join(pts.TF[t]["label"] for t in tfs),
        crt.source_label()))
    if failed:
        print("데이터 실패: %s" % ", ".join(failed[:8]))
    if not new:
        print("새 알람 없음." + (" (진행 중·확정 신호 %d개는 이미 알림)" % len(found) if found else ""))
    for a in sorted(new, key=lambda x: (x["kind"] != "확정", x["tf"] != "mid")):
        print()
        print(message(a))
    if new:
        print()
        print("진행 중 알람은 미확정입니다. 봉 마감 때 상단 아래로 돌아오면 신호가 아닙니다. 규칙 확인일 뿐 매수 권유가 아닙니다.")
    if args.json:
        crt.save_json(args.json, {"checked": now.isoformat(), "new": new})
    return 0 if new else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description="스퀴즈 돌파 알람 (coin-signal)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("check")
    p.add_argument("--tf", choices=("mid", "short", "both"), default="both")
    p.add_argument("--symbols")
    p.add_argument("--all", action="store_true", help="이미 알린 것도 다시 보여 준다")
    p.add_argument("--dry-run", action="store_true", help="알림 기록을 남기지 않는다")
    p.add_argument("--now")
    p.add_argument("--offline")
    p.add_argument("--config", default="config.json")
    p.add_argument("--json", nargs="?", const="alerts-new.json")
    args = ap.parse_args(argv)
    try:
        return cmd_check(args)
    except crt.CrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
