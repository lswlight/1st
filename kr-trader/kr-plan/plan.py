#!/usr/bin/env python3
"""kr-plan: 국내 주식 진입 확인, 손절·목표 계획, 수량 계산 (매수만).

  plan.py entry 005930 --setup orb|bb|level [--level 250000] [--write]
  plan.py exit  [--stop or-mid|or-low|bb-mid|pct:1.5|price:240000] [--target 1R|2R|price:X] [--write]
  plan.py size  [--budget 5000000 --max-loss 50000] [--write]
  plan.py show

주문은 내지 않는다. krt/ 폴더에 entry.json, plan.json, order.json (제안서)만 쓴다.
"""
import argparse
import math
import os
import sys
from datetime import timedelta

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.realpath(__file__))), "lib"))
import krt  # noqa: E402

SETUP_KO = {"orb": "개장 범위 돌파", "bb": "5분봉 볼린저 상단 돌파", "level": "가격 레벨 돌파"}


# ------------------------------------------------------------------ entry

def bb_trigger(bars, market, day, n=20, k=2.0, start_slot=3, until=None):
    """오늘 세션에서 5분봉 종가가 볼린저 상단을 처음 넘어선 봉 (직전 봉은 상단 아래)."""
    closes, out = [], None
    for b in bars:
        closes.append(b["c"])
        if krt.session_day(b["t"], market) != day or len(closes) < n + 1:
            continue
        s = krt.slot(b["t"], market)
        if s < start_slot:
            continue
        end = b["t"] + krt.BAR
        if until and end > until:
            break
        cur = krt.bollinger(closes, n, k)
        prev = krt.bollinger(closes[:-1], n, k)
        if b["c"] > cur["up"] and closes[-2] <= prev["up"]:
            out = {"status": "triggered", "price": b["c"], "at": krt.fmt_time(end), "bar": s,
                   "bar_start": krt.fmt_time(b["t"]), "bb_up": cur["up"], "bb_mid": cur["mid"],
                   "bb_lo": cur["lo"]}
            break
    return out


def cmd_entry(args):
    cfg = krt.config(args.config)
    f = krt.Fetcher(args.offline)
    sym = krt.resolve(args.symbol, f)
    market = krt.market_of(sym)
    now = krt.now_kst(args.now)
    day = krt.session_day(now, market)
    open_t = krt.session_open(day, market)
    or_min = cfg["or_minutes"]
    until = None
    if market == "krx":
        if now.weekday() >= 5:
            krt.die("%s: 주말이라 국내 장이 쉽니다." % sym)
        last = krt.at_time(day, cfg["krx_last_entry"])
        if now > last:
            until = last
    if now < open_t + timedelta(minutes=or_min):
        krt.die("%s: 아직 개장 %d분 범위가 만들어지는 중입니다 (%s 이후 다시)." % (
            sym, or_min, krt.fmt_time(open_t + timedelta(minutes=or_min))))
    data = f.chart(sym, "5d", "5m")
    bars = krt.five_minute_bars(data["bars"], market, until or now)
    r = krt.orb_check(bars, market, day, now, or_min, until)
    if r["status"] in ("no_data", "gap"):
        krt.die("%s: 오늘(%s) 5분봉이 없거나 빠진 봉이 있습니다. 휴장일이거나 거래가 적은 종목일 수 있습니다." % (sym, day))
    res = {"symbol": sym, "market": market, "date": str(day), "setup": args.setup,
           "checked": now.isoformat(), "or_minutes": or_min,
           "or_high": r.get("or_high"), "or_low": r.get("or_low"), "last": r.get("last")}
    if args.setup == "orb":
        trig = r if r["status"] == "triggered" else None
        level = r.get("or_high")
    elif args.setup == "bb":
        trig = bb_trigger(bars, market, day, cfg["bb_period"], cfg["bb_k"], or_min // 5, until)
        level = None
    else:
        if args.level is None:
            krt.die("레벨 셋업은 --level 가격이 필요합니다.")
        level = args.level
        trig = None
        for b in bars:
            if krt.session_day(b["t"], market) == day and b["c"] > level:
                s = krt.slot(b["t"], market)
                end = b["t"] + krt.BAR
                trig = {"status": "triggered", "price": b["c"], "at": krt.fmt_time(end), "bar": s,
                        "bar_start": krt.fmt_time(b["t"])}
                break
    if trig:
        res.update(status="triggered", entry=trig["price"], entry_time=trig["at"],
                   entry_bar=trig["bar"], level=level,
                   **{k: trig[k] for k in ("bb_up", "bb_mid", "bb_lo") if k in trig})
    else:
        expired = until is not None
        res.update(status="expired" if expired else "waiting", level=level)
    mk = market
    print("%s %s %s 기준, %s (5분봉, %s)" % (
        sym, krt.names().get(sym, ""), krt.fmt_time(until or now), SETUP_KO[args.setup], krt.source_label(market, sym)))
    if r.get("or_high") is not None:
        print("개장 %d분 범위: 고가 %s, 저가 %s" % (or_min, krt.price_str(r["or_high"], mk), krt.price_str(r["or_low"], mk)))
    if res["status"] == "triggered":
        print("진입 신호: %s에 시작한 5분봉이 %s에 %s로 마감 → 진입가 %s" % (
            trig["bar_start"], res["entry_time"], krt.price_str(res["entry"], mk), krt.price_str(res["entry"], mk)))
    elif res["status"] == "waiting":
        gap = ""
        if level and r.get("last"):
            gap = ", 현재 %s (기준까지 %+.2f%%)" % (krt.price_str(r["last"], mk), (level / r["last"] - 1) * 100)
        print("대기 중: 아직 조건을 만족한 5분봉이 없습니다%s." % gap)
    else:
        print("만료: 신규 진입 마감(%s)까지 신호가 없었습니다." % cfg["krx_last_entry"])
    if args.write:
        p = krt.save_json(args.write, res)
        print("저장: %s" % p)
    print("주문은 내지 않았습니다.")
    return 0 if res["status"] == "triggered" else 1


# ------------------------------------------------------------------ exit

def make_plan(e, stop_rule, target_rule, cfg, now=None):
    market = e["market"]
    quote = "KRW"
    entry = float(e["entry"])
    rule = stop_rule
    if rule == "or-mid":
        if e.get("or_high") is None:
            raise krt.KrtError("개장 범위가 없어 범위 중간 손절을 쓸 수 없습니다")
        raw = (e["or_high"] + e["or_low"]) / 2
        why = "개장 범위 중간 (%s + %s) / 2" % (krt.price_str(e["or_high"], market), krt.price_str(e["or_low"], market))
    elif rule == "or-low":
        raw = e["or_low"]
        why = "개장 범위 저가"
    elif rule == "bb-mid":
        if e.get("bb_mid") is None:
            raise krt.KrtError("볼린저 중심값이 없습니다 (bb 셋업에서만 씁니다)")
        raw = e["bb_mid"]
        why = "5분봉 볼린저 중심선"
    elif rule.startswith("pct:"):
        pct = float(rule[4:])
        raw = entry * (1 - pct / 100)
        why = "진입가 대비 -%g%%" % pct
    elif rule.startswith("price:"):
        raw = float(rule[6:])
        why = "직접 지정"
    else:
        raise krt.KrtError("손절 규칙은 or-mid, or-low, bb-mid, pct:X, price:X 중 하나입니다")
    stop = krt.round_down(raw, market, quote)
    if stop >= entry:
        raise krt.KrtError("손절가 %s가 진입가 %s 이상입니다. 매수 계획에서는 손절이 진입가보다 낮아야 합니다" % (
            krt.price_str(stop, market), krt.price_str(entry, market)))
    risk = entry - stop
    tick = krt.tick_size(entry, market, quote)
    if risk < 2 * tick - 1e-9 or risk / entry < 0.001:
        raise krt.KrtError("손절폭 %s가 너무 좁습니다 (호가 2틱 또는 0.1%% 미만): 정상적인 흔들림에도 손절됩니다" % (
            krt.price_str(risk, market)))
    if target_rule.upper().endswith("R"):
        r_mult = float(target_rule[:-1])
        target = krt.round_near(entry + r_mult * risk, market, quote)
        twhy = "%gR (손절폭의 %g배)" % (r_mult, r_mult)
    elif target_rule.startswith("price:"):
        target = float(target_rule[6:])
        r_mult = (target - entry) / risk
        twhy = "직접 지정"
    else:
        raise krt.KrtError("목표 규칙은 1R, 2R, price:X 형식입니다")
    if target <= entry:
        raise krt.KrtError("목표가가 진입가 이하입니다")
    flat_by = cfg["krx_flat_by"]
    hold_until = krt.at_time(krt.parse_kst(e["date"] + " 00:00").date(), flat_by)
    return {"symbol": e["symbol"], "market": market, "date": e["date"], "setup": e.get("setup", "orb"),
            "entry": entry, "entry_time": e.get("entry_time"), "entry_bar": e.get("entry_bar"),
            "or_high": e.get("or_high"), "or_low": e.get("or_low"), "or_minutes": e.get("or_minutes", 15),
            "stop": stop, "stop_rule": stop_rule, "stop_why": why, "target": target,
            "target_r": round(r_mult, 4), "target_why": twhy, "risk": round(risk, 10), "quote": quote,
            "reward_risk": round((target - entry) / risk, 2), "flat_by": flat_by,
            "hold_until": hold_until.isoformat()}


def cmd_exit(args):
    cfg = krt.config(args.config)
    if args.entry is not None:
        if not args.symbol:
            krt.die("--entry 를 쓰면 --symbol 도 필요합니다.")
        sym = krt.normalize(args.symbol)
        market = krt.market_of(sym)
        now = krt.now_kst(args.now)
        e = {"symbol": sym, "market": market, "date": args.date or str(krt.session_day(now, market)),
             "setup": "orb", "entry": args.entry, "entry_time": args.entry_time,
             "entry_bar": None, "or_high": args.or_high, "or_low": args.or_low, "status": "triggered"}
        if args.entry_time:
            t = krt.parse_kst(e["date"] + " " + args.entry_time)
            e["entry_bar"] = krt.slot(t, market) - 1
    else:
        e = krt.load_json(args.input)
        if not e:
            krt.die("진입 정보(%s)가 없습니다. 먼저 plan.py entry 를 돌리거나 --entry 로 가격을 주세요." % args.input)
        if e.get("status") != "triggered":
            krt.die("진입 신호가 아직 없습니다 (상태: %s). 미리 계획하려면 --entry 가격을 주세요." % e.get("status"))
    stop_rule = args.stop or ("bb-mid" if e.get("setup") == "bb" else "or-mid")
    try:
        p = make_plan(e, stop_rule, args.target, cfg)
    except krt.KrtError as err:
        krt.die("계획을 만들 수 없습니다: %s" % err)
    m = p["market"]
    ps = lambda x: krt.price_str(x, m)  # noqa: E731
    print("청산 계획: %s %s 매수, %s, %s" % (p["symbol"], krt.names().get(p["symbol"], ""), p["date"],
                                       SETUP_KO.get(p["setup"], p["setup"])))
    print("  진입     %s%s" % (ps(p["entry"]), (" (%s)" % p["entry_time"]) if p["entry_time"] else ""))
    print("  손절     %s  %s, 호가 단위로 내림" % (ps(p["stop"]), p["stop_why"]))
    print("  목표     %s  %s" % (ps(p["target"]), p["target_why"]))
    print("  주당 위험 %s (진입가의 %.2f%%)" % (ps(p["risk"]), p["risk"] / p["entry"] * 100))
    print("  손익비   %.2f (수수료·세금 전)" % p["reward_risk"])
    print("  청산 시한 %s, 결과와 관계없이 정리" % p["flat_by"])
    print("  (Yahoo 5분봉이 15:00까지만 있어 기본 청산 시한을 14:50으로 둡니다)")
    cb, cs = costs(m, cfg)
    cost = p["entry"] * cb + p["stop"] * cs
    if cost / p["risk"] >= 0.25:
        print("  주의: 손절 시 왕복 비용(수수료%s)이 손절폭의 %.0f%%입니다. 손절폭이 비용에 비해 좁습니다." % (
            "·세금", cost / p["risk"] * 100))
    if args.write:
        print("저장: %s" % krt.save_json(args.write, p))
    print("손절가는 계획이지 보장이 아닙니다. 갭이나 급변 때는 더 불리하게 체결될 수 있습니다.")
    return 0


# ------------------------------------------------------------------ size

def costs(market, cfg, quote="KRW"):
    """(매수 비용률, 매도 비용률) = (수수료, 수수료 + 매도 시 증권거래세)."""
    c = cfg["krx_commission_pct"] / 100
    return c, c + cfg["krx_sell_tax_pct"] / 100


def size_plan(p, cfg, budget=None, max_loss=None):
    """계좌와 최대 손실(원)으로 정수 주 수량을 정한다."""
    market = p["market"]
    budget = float(budget if budget is not None else cfg["budget_krw"])
    max_loss = float(max_loss if max_loss is not None else cfg["max_loss_per_trade_krw"])
    entry, stop, target = p["entry"], p["stop"], p["target"]
    cb, cs = costs(market, cfg)
    loss_ps = (entry - stop) + entry * cb + stop * cs
    gain_ps = (target - entry) - entry * cb - target * cs
    def qty(x):
        return max(int(math.floor(x + 1e-9)), 0)

    cash = budget * (1 - cfg["cash_buffer_pct"] / 100)
    limits = [
        ("최대 손실", "%s ÷ 주당 손실 %s" % (krt.won(max_loss), krt.won(loss_ps)), qty(max_loss / loss_ps)),
        ("종목당 상한 (%g%%)" % cfg["max_position_pct"],
         "%s ÷ %s" % (krt.won(budget * cfg["max_position_pct"] / 100), krt.won(entry)),
         qty(budget * cfg["max_position_pct"] / 100 / entry)),
        ("현금 (%g%% 남김)" % cfg["cash_buffer_pct"], "%s ÷ %s" % (krt.won(cash), krt.won(entry * (1 + cb))),
         qty(cash / (entry * (1 + cb)))),
    ]
    q = min(l[2] for l in limits)
    binding = [l[0] for l in limits if l[2] == q][0]
    gross_rr = (target - entry) / (entry - stop)
    net_rr = gain_ps / loss_ps if loss_ps > 0 else 0
    res = {"symbol": p["symbol"], "market": market, "qty": q, "limits": limits, "binding": binding,
           "amount": q * entry, "loss_at_stop": q * loss_ps, "gain_at_target": q * gain_ps,
           "loss_per_unit": loss_ps, "gross_rr": round(gross_rr, 2), "net_rr": round(net_rr, 2),
           "budget": budget, "max_loss": max_loss, "refused": None}
    if q <= 0:
        res["refused"] = "1%s도 살 수 없습니다: %s 한도에 걸립니다" % ("주", binding)
    elif q * entry < cfg["min_order_krw"]:
        res["refused"] = "주문 금액 %s이 최소 주문 %s보다 작습니다" % (krt.won(q * entry), krt.won(cfg["min_order_krw"]))
    elif gross_rr + 1e-9 < cfg["min_reward_risk"]:
        res["refused"] = "손익비 %.2f가 최소 %.2f보다 낮습니다" % (gross_rr, cfg["min_reward_risk"])
    return res


def cmd_size(args):
    cfg = krt.config(args.config)
    p = krt.load_json(args.input)
    if not p:
        krt.die("계획(%s)이 없습니다. 먼저 plan.py exit --write 를 돌리세요." % args.input)
    r = size_plan(p, cfg, args.budget, args.max_loss)
    m = p["market"]
    unit = "주"
    print("%s %s 매수 수량 계산: 진입 %s, 손절 %s, 목표 %s" % (
        p["symbol"], krt.names().get(p["symbol"], ""), krt.price_str(p["entry"], m),
        krt.price_str(p["stop"], m), krt.price_str(p["target"], m)))
    cb, cs = costs(m, cfg)
    print("계좌 %s, 1회 최대 손실 %s. 비용: 매수 %.3f%%, 매도 %.3f%%%s" % (
        krt.won(r["budget"]), krt.won(r["max_loss"]), cb * 100, cs * 100,
        " (수수료 + 증권거래세)"))
    print("한도 (가장 작은 값이 수량을 정합니다):")
    for name, how, q in r["limits"]:
        mark = "  <- 결정" if name == r["binding"] else ""
        print("  %-16s %-40s %s%s%s" % (name, how, q, unit, mark))
    if r["refused"]:
        print("주문 제안 없음: %s" % r["refused"])
        return 1
    print("수량: %s%s, 금액 %s" % (r["qty"], unit, krt.won(r["amount"])))
    print("손절 시 예상 손실 %s (비용 포함), 목표 도달 시 손익비 %.2f (비용 포함, 비용 전 %.2f)" % (
        krt.won(r["loss_at_stop"]), r["net_rr"], r["gross_rr"]))
    if r["net_rr"] < cfg["min_reward_risk"]:
        print("참고: 수수료·세금을 빼면 손익비가 %.2f로 내려갑니다." % r["net_rr"])
    if args.write:
        order = {"symbol": p["symbol"], "market": m, "side": "buy", "qty": r["qty"], "type": "market",
                 "stop_loss": p["stop"], "take_profit": p["target"], "flat_by": p["flat_by"],
                 "why": "%s: %s%s, %s 한도 기준, 손절 시 약 %s" % (
                     SETUP_KO.get(p["setup"], p["setup"]), r["qty"], unit, r["binding"],
                     krt.won(r["loss_at_stop"])),
                 "note": "제안서일 뿐 주문이 아닙니다. 증권사·거래소 앱에서 직접 판단해 넣으세요."}
        print("저장: %s" % krt.save_json(args.write, order))
    print("손절 주문은 약속이 아닙니다: 급변하는 장에서는 손절가보다 낮게 체결될 수 있습니다. 주문은 내지 않았습니다.")
    return 0


def cmd_show(args):
    for name in ("entry.json", "plan.json", "order.json"):
        d = krt.load_json(name)
        print("== %s" % krt.path(name))
        print(krt.json.dumps(d, ensure_ascii=False, indent=2) if d else "(없음)")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="진입·청산·수량 계획 (kr-plan)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("entry")
    p.add_argument("symbol")
    p.add_argument("--setup", choices=("orb", "bb", "level"), default="orb")
    p.add_argument("--level", type=float)
    p.add_argument("--now")
    p.add_argument("--offline")
    p.add_argument("--config", default="config.json")
    p.add_argument("--write", nargs="?", const="entry.json")
    p = sub.add_parser("exit")
    p.add_argument("--stop")
    p.add_argument("--target", default="2R")
    p.add_argument("--input", default="entry.json")
    p.add_argument("--symbol")
    p.add_argument("--entry", type=float)
    p.add_argument("--entry-time", help="진입 봉이 마감한 시각 HH:MM")
    p.add_argument("--or-high", type=float)
    p.add_argument("--or-low", type=float)
    p.add_argument("--date")
    p.add_argument("--now")
    p.add_argument("--config", default="config.json")
    p.add_argument("--write", nargs="?", const="plan.json")
    p = sub.add_parser("size")
    p.add_argument("--input", default="plan.json")
    p.add_argument("--budget", type=float, help="계좌 금액 (원)")
    p.add_argument("--max-loss", type=float, help="1회 최대 손실 (원)")
    p.add_argument("--config", default="config.json")
    p.add_argument("--write", nargs="?", const="order.json")
    sub.add_parser("show")
    args = ap.parse_args(argv)
    try:
        return {"entry": cmd_entry, "exit": cmd_exit, "size": cmd_size, "show": cmd_show}[args.cmd](args)
    except krt.KrtError as e:
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
