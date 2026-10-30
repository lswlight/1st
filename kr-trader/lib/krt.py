"""kr-trader 공통 모듈: 국내 주식 시장 정의, Yahoo 차트 데이터, 지표, 호가 단위, 상태 파일.

표준 라이브러리만 쓴다. 모든 시각은 한국 시간(KST)이다.
"""
import gzip
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
BAR = timedelta(minutes=5)
UA = "Mozilla/5.0 (kr-trader)"
DATA_DIR = os.environ.get("KRT_DIR", "krt")
HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.dirname(HERE)

# Yahoo 5분봉 기준 세션. Yahoo가 09:00~14:55 봉(72개)만 주므로 15:00 이후 체결은 데이터에 없다.
# (함수들이 market 인자를 받지만 값은 늘 "krx" 다.)
MARKETS = {
    "krx": {"name": "국내 주식", "open": "09:00", "bars": 72,
            "last_entry": "14:30", "flat_by": "14:50"},
}

DEFAULT_CONFIG = {
    "budget_krw": 5000000,
    "max_loss_per_trade_krw": 50000,
    "cash_buffer_pct": 10,
    "max_position_pct": 75,
    "min_order_krw": 5000,
    "min_reward_risk": 1.0,
    "or_minutes": 15,
    "bb_period": 20,
    "bb_k": 2.0,
    "krx_commission_pct": 0.015,
    "krx_sell_tax_pct": 0.20,
    "krx_flat_by": "14:50",
    "krx_last_entry": "14:30",
}


class KrtError(Exception):
    pass


# ------------------------------------------------------------------ 시각

def now_kst(pinned=None):
    """--now "2026-10-29 10:15" (KST)를 받으면 그 시각, 아니면 현재 시각."""
    if pinned:
        return parse_kst(pinned)
    return datetime.now(KST)


def parse_kst(text):
    text = text.strip()
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(text, fmt).replace(tzinfo=KST)
        except ValueError:
            pass
    raise KrtError("시각 형식은 \"YYYY-MM-DD HH:MM\" (한국 시간)입니다: %s" % text)


def hm(text):
    h, m = text.split(":")
    return int(h), int(m)


def at_time(day, text):
    h, m = hm(text)
    return datetime(day.year, day.month, day.day, h, m, tzinfo=KST)


def fmt_time(t):
    return t.strftime("%H:%M")


# ------------------------------------------------------------------ 종목

def market_of(symbol):
    return "krx"


def quote_of(symbol):
    return "KRW"


def universe_file(market="krx"):
    return "universe-krx.txt"


def load_universe(market, filename=None):
    """종목 목록 파일: 한 줄에 SYMBOL|한글 이름|영문 이름."""
    path = os.path.join(ROOT, "kr-scanner", filename or universe_file(market))
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|")
            rows.append({"symbol": parts[0], "name": parts[1] if len(parts) > 1 else "",
                         "yahoo_name": parts[2] if len(parts) > 2 else ""})
    return rows


def names():
    out = {}
    for fn in ("universe-krx.txt",):
        try:
            for r in load_universe(None, fn):
                out[r["symbol"]] = r["name"] or r["yahoo_name"]
        except OSError:
            pass
    return out


def normalize(symbol):
    """005930 -> 005930.KS (목록에서 코스피/코스닥 확인). 접미사가 있으면 그대로."""
    s = symbol.strip().upper()
    if not s:
        raise KrtError("종목이 비어 있습니다")
    if "." in s:
        return s
    if s.isdigit() and len(s) == 6:
        for r in load_universe("krx"):
            if r["symbol"].split(".")[0] == s:
                return r["symbol"]
        return s
    return s


# ------------------------------------------------------------------ 데이터

def range_days(rng):
    return {"1d": 1, "5d": 5, "1mo": 31, "3mo": 92, "6mo": 183, "1y": 366, "2y": 731}.get(rng) or int(rng.rstrip("d"))


class Fetcher(object):
    """Yahoo 차트 API. --offline 이면 저장된 파일만 읽는다."""

    def __init__(self, offline=None, pause=0.2):
        self.offline = offline
        self.pause = pause
        self._last = 0.0

    def _wait(self, pause=None):
        wait = (self.pause if pause is None else pause) - (time.time() - self._last)
        if wait > 0:
            time.sleep(wait)
        self._last = time.time()

    def chart(self, symbol, rng, interval):
        if self.offline:
            return self._offline(symbol, interval)
        return self._yahoo(symbol, rng, interval)

    def _yahoo(self, symbol, rng, interval):
        q = "range=%s&interval=%s&includePrePost=false" % (rng, interval)
        last_err = None
        for host in ("query2", "query1"):
            url = "https://%s.finance.yahoo.com/v8/finance/chart/%s?%s" % (host, symbol, q)
            for attempt in range(2):
                self._wait()
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": UA})
                    with urllib.request.urlopen(req, timeout=25) as r:
                        data = json.load(r)
                    return chart_result(data, symbol)
                except urllib.error.HTTPError as e:
                    last_err = e
                    if e.code == 429 and attempt == 0:
                        time.sleep(5)
                        continue
                    if e.code == 404:
                        raise KrtError("%s: Yahoo에 없는 종목입니다" % symbol)
                    break
                except (urllib.error.URLError, OSError, ValueError) as e:
                    last_err = e
                    break
        raise KrtError("%s: 데이터를 받지 못했습니다 (%s)" % (symbol, last_err))

    def _offline(self, symbol, interval):
        base = self.offline
        cands = [base] if os.path.isfile(base) else [
            os.path.join(base, "%s-%s.json%s" % (symbol, interval, ext)) for ext in (".gz", "")]
        for path in cands:
            if os.path.isfile(path):
                opener = gzip.open if path.endswith(".gz") else open
                with opener(path, "rt", encoding="utf-8") as fh:
                    return chart_result(json.load(fh), symbol)
        raise KrtError("%s: 오프라인 파일이 없습니다 (%s)" % (symbol, base))


def bar_value(b, market="krx"):
    """봉의 거래대금(원) = 종가 x 거래량."""
    return b["c"] * b["v"]


EXCHANGES = {".KS": "KSC", ".KQ": "KOE"}


def resolve(symbol, fetcher):
    """6자리 코드만 받으면 코스피(.KS)와 코스닥(.KQ)을 차례로 확인해 맞는 쪽을 고른다."""
    s = normalize(symbol)
    if not (s.isdigit() and len(s) == 6):
        return s
    for suf in (".KS", ".KQ"):
        try:
            fetcher.chart(s + suf, "5d", "1d")
            return s + suf
        except KrtError:
            pass
    raise KrtError("%s: 코스피·코스닥 어디에서도 찾지 못했습니다" % s)


def chart_result(data, symbol):
    res = (data.get("chart") or {}).get("result")
    if not res:
        raise KrtError("%s: 차트 데이터가 비어 있습니다" % symbol)
    r = res[0]
    # Yahoo는 접미사가 틀린 국내 종목에도 엉뚱한 데이터를 준다. 거래소 코드로 걸러낸다.
    exch = EXCHANGES.get(symbol[-3:].upper())
    got = (r.get("meta") or {}).get("exchangeName")
    if exch and got and got != exch:
        raise KrtError("%s: 시장 구분이 맞지 않습니다 (Yahoo 거래소 %s)" % (symbol, got))
    ts = r.get("timestamp") or []
    q = (r.get("indicators") or {}).get("quote") or [{}]
    q = q[0]
    bars = []
    for i, t in enumerate(ts):
        try:
            o, h, l, c, v = (q["open"][i], q["high"][i], q["low"][i], q["close"][i], q["volume"][i])
        except (KeyError, IndexError):
            continue
        if c is None or o is None or h is None or l is None:
            continue
        bars.append({"t": datetime.fromtimestamp(t, KST), "o": o, "h": h, "l": l, "c": c, "v": v or 0})
    return {"meta": r.get("meta") or {}, "bars": bars}


# ------------------------------------------------------------------ 세션

def session_day(t, market="krx"):
    """그 봉이 속한 세션의 날짜."""
    return t.date()


def session_open(day, market):
    return at_time(day, MARKETS[market]["open"])


def slot(t, market):
    return int((t - session_open(session_day(t, market), market)) / BAR)


def five_minute_bars(bars, market, now):
    """5분 격자 위의 완성된 봉만 남긴다 (진행 중인 봉, 격자 밖 틱 제외)."""
    out = []
    for b in bars:
        t = b["t"]
        if t.second or t.minute % 5:
            continue
        if t + BAR > now:
            continue
        if market == "krx":
            s = slot(t, market)
            if s < 0 or s >= MARKETS["krx"]["bars"]:
                continue
        out.append(b)
    return out


def sessions(bars, market):
    """{날짜: [봉...]} (시간순)."""
    out = {}
    for b in bars:
        out.setdefault(session_day(b["t"], market), []).append(b)
    return out


def full_sessions(bars, market):
    """모든 슬롯이 다 있는 세션만: {날짜: [5분봉 72개]}."""
    n = MARKETS[market]["bars"]
    out = {}
    for day, bs in sessions(bars, market).items():
        if len(bs) == n and all(slot(b["t"], market) == i for i, b in enumerate(bs)):
            out[day] = bs
    return out


# ------------------------------------------------------------------ 개장 범위 돌파

def orb_check(bars, market, day, now, or_minutes=15, until=None):
    """한 세션의 개장 범위 돌파. bars는 완성된 5분봉 (여러 날 섞여도 됨)."""
    bs = [b for b in bars if session_day(b["t"], market) == day]
    n_or = or_minutes // 5
    if not bs or slot(bs[0]["t"], market) != 0:
        return {"status": "no_data"}
    rng = bs[:n_or]
    if len(rng) < n_or or any(slot(b["t"], market) != i for i, b in enumerate(rng)):
        return {"status": "forming"}
    hi = max(b["h"] for b in rng)
    lo = min(b["l"] for b in rng)
    res = {"status": "waiting", "or_high": hi, "or_low": lo, "last": bs[-1]["c"],
           "value": sum(bar_value(b, market) for b in bs), "volume": sum(b["v"] for b in bs)}
    for i, b in enumerate(bs[n_or:], start=n_or):
        if slot(b["t"], market) != i:
            res["status"] = "gap"
            return res
        end = b["t"] + BAR
        if until and end > until:
            break
        if b["c"] > hi:
            res.update(status="triggered", price=b["c"], at=fmt_time(end), bar=i,
                       bar_start=fmt_time(b["t"]), pct_over=round((b["c"] / hi - 1) * 100, 2),
                       value_at=sum(bar_value(x, market) for x in bs[:i + 1]))
            return res
    return res



# ------------------------------------------------------------------ 지표

def bollinger(closes, n=20, k=2.0):
    """마지막 n개 종가로 (중심, 상단, 하단, 밴드폭%, %B). 모집단 표준편차."""
    if len(closes) < n:
        return None
    w = closes[-n:]
    mid = statistics.fmean(w)
    sd = statistics.pstdev(w)
    up, lo = mid + k * sd, mid - k * sd
    width = (up - lo) / mid * 100 if mid else 0.0
    pctb = (closes[-1] - lo) / (up - lo) if up != lo else 0.5
    return {"mid": mid, "up": up, "lo": lo, "width": width, "pctb": pctb}


def bb_series(closes, n=20, k=2.0):
    return [bollinger(closes[:i + 1], n, k) for i in range(len(closes))]


def rsi(closes, n=14):
    """Wilder RSI."""
    if len(closes) < n + 1:
        return None
    gains = losses = 0.0
    for i in range(1, n + 1):
        d = closes[i] - closes[i - 1]
        gains += max(d, 0)
        losses += max(-d, 0)
    ag, al = gains / n, losses / n
    for i in range(n + 1, len(closes)):
        d = closes[i] - closes[i - 1]
        ag = (ag * (n - 1) + max(d, 0)) / n
        al = (al * (n - 1) + max(-d, 0)) / n
    if al == 0:
        return 100.0
    return 100 - 100 / (1 + ag / al)


def sma(values, n):
    if len(values) < n:
        return None
    return statistics.fmean(values[-n:])


# ------------------------------------------------------------------ 호가 단위

KRX_TICKS = [(2000, 1), (5000, 5), (20000, 10), (50000, 50),
             (200000, 100), (500000, 500), (float("inf"), 1000)]


def tick_size(price, market="krx", quote="KRW"):
    """2023년 개편된 유가·코스닥 공통 호가 단위."""
    for limit, tick in KRX_TICKS:
        if price < limit:
            return tick


def _snap(price, market, fn, quote="KRW"):
    t = tick_size(price, market, quote)
    v = fn(price / t - 1e-9 if fn is math.ceil else price / t + 1e-9) * t
    return round(v, 10)


def round_down(price, market="krx", quote="KRW"):
    return _snap(price, market, math.floor, quote)


def round_up(price, market="krx", quote="KRW"):
    return _snap(price, market, math.ceil, quote)


def round_near(price, market="krx", quote="KRW"):
    t = tick_size(price, market, quote)
    return round(math.floor(price / t + 0.5) * t, 10)


def won(x):
    """원화 표기: 1,234원 (1원 미만은 소수 둘째 자리)."""
    if abs(x) >= 100 or float(x).is_integer():
        return "{:,.0f}원".format(x)
    return "{:,.2f}원".format(x)


def price_str(x, market="krx"):
    return "{:,.0f}".format(x)


def money(x, quote="KRW"):
    """거래대금 표기: 만/억/조 원."""
    return man(x) + "원"


def man(x):
    """금액을 만 원/억 원 단위로 짧게."""
    if abs(x) >= 1e12:
        return "%.1f조" % (x / 1e12)
    if abs(x) >= 1e8:
        return "%.0f억" % (x / 1e8) if abs(x) >= 1e10 else "%.1f억" % (x / 1e8)
    if abs(x) >= 1e4:
        return "%.0f만" % (x / 1e4)
    return "{:,.0f}".format(x)


# ------------------------------------------------------------------ 상태 파일

def path(name):
    return os.path.join(DATA_DIR, name)


def load_json(name, default=None):
    p = name if os.path.sep in name else path(name)
    try:
        with open(p, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


def save_json(name, data):
    p = name if os.path.sep in name else path(name)
    d = os.path.dirname(p)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(p, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2, default=str)
    return p


def config(path_=None):
    cfg = dict(DEFAULT_CONFIG)
    user = load_json(path_ or "config.json", {}) or {}
    cfg.update({k: v for k, v in user.items() if not k.startswith("_")})
    return cfg


def die(msg, code=1):
    print(msg)
    sys.exit(code)


def source_label(market="krx", symbol=None):
    return "Yahoo 지연 시세"


INTERVALS = {"5m": BAR, "1h": timedelta(hours=1), "1d": timedelta(days=1)}


def completed(bars, interval, now, market="krx"):
    """진행 중인 마지막 봉을 뺀다. 일봉은 15:30 이후에야 완성으로 본다."""
    if not bars:
        return bars
    last = bars[-1]
    if interval == "1d":
        done = last["t"].date() < now.date() or now >= at_time(now.date(), "15:30")
    else:
        done = last["t"] + INTERVALS[interval] <= now
    return bars if done else bars[:-1]


DISCLAIMER = "규칙에 맞는지 확인한 결과일 뿐, 매수·매도 추천이 아닙니다. 데이터는 무료 Yahoo 지연 시세입니다."
