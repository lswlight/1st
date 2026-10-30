"""crypto-trader 공통 모듈: 코인 차트 데이터(바이낸스 기본, 업비트 원화), 지표, 호가 단위, 상태 파일.

표준 라이브러리만 쓴다. 모든 시각은 한국 시간(KST)이다.
코인의 하루는 바이낸스·업비트 일봉처럼 09:00 KST(00:00 UTC)에 시작한다.
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
UA = "Mozilla/5.0 (crypto-trader)"
DATA_DIR = os.environ.get("CRT_DIR", "crt")
HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.dirname(HERE)

# 코인 세션: 09:00 KST 시작, 5분봉 288개. (함수들이 market 인자를 받지만 값은 늘 "crypto" 다.)
MARKETS = {
    "crypto": {"name": "코인", "open": "09:00", "bars": 288},
}

DEFAULT_CONFIG = {
    "crypto_source": "binance",
    "budget_krw": 5000000,
    "max_loss_per_trade_krw": 50000,
    "cash_buffer_pct": 10,
    "max_position_pct": 75,
    "min_order_krw": 5000,
    "min_reward_risk": 1.0,
    "or_minutes": 15,
    "bb_period": 20,
    "bb_k": 2.0,
    "crypto_fee_pct": 0.05,
    "binance_fee_pct": 0.10,
    "usdkrw": None,
    "crypto_hold_hours": 6,
}


class CrtError(Exception):
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
    raise CrtError("시각 형식은 \"YYYY-MM-DD HH:MM\" (한국 시간)입니다: %s" % text)


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
    return "crypto"


def quote_of(symbol):
    """가격 통화: 바이낸스 심볼(BTCUSDT)은 USDT, 나머지는 원화."""
    return "USDT" if symbol.upper().endswith("USDT") else "KRW"


def universe_file(market="crypto"):
    return "universe-crypto.txt" if crypto_source() == "binance" else "universe-upbit.txt"


def load_universe(market, filename=None):
    """종목 목록 파일: 한 줄에 SYMBOL|한글 이름|영문 이름."""
    path = os.path.join(ROOT, "coin-scanner", filename or universe_file(market))
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
    for fn in ("universe-crypto.txt", "universe-upbit.txt"):
        try:
            for r in load_universe(None, fn):
                out[r["symbol"]] = r["name"] or r["yahoo_name"]
        except OSError:
            pass
    return out


def normalize(symbol):
    """btc -> BTCUSDT (바이낸스, 기본) 또는 BTC-KRW (crypto_source 가 upbit 일 때). 형식이 있으면 그대로."""
    s = symbol.strip().upper()
    if not s:
        raise CrtError("종목이 비어 있습니다")
    if s.endswith("-KRW") or (s.endswith("USDT") and len(s) > 4):
        return s
    if s.isalnum():
        return s + ("USDT" if crypto_source() == "binance" else "-KRW")
    return s


# ------------------------------------------------------------------ 데이터

UPBIT = "https://api.upbit.com/v1"
BINANCE = "https://data-api.binance.vision/api/v3"   # 시세 전용 공개 주소 (api.binance.com 은 서버 위치 차단)
STABLES = {"USDT", "USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "USDE", "USDS", "PYUSD", "EUR", "EURI",
           "AEUR", "XUSD", "USD1", "BFUSD", "RLUSD", "U", "PAXG", "WBTC", "WBETH", "BNSOL"}


def crypto_source():
    """코인 기본 데이터원: binance(기본), upbit, yahoo. 환경 변수 CRT_CRYPTO_SOURCE 나 config 의 crypto_source.
    심볼이 정해져 있으면 심볼 형식이 우선한다: BTCUSDT 는 바이낸스, BTC-KRW 는 업비트."""
    src = os.environ.get("CRT_CRYPTO_SOURCE") or (load_json("config.json", {}) or {}).get("crypto_source")
    return (src or "binance").lower()


def upbit_market(symbol):
    """BTC-KRW -> KRW-BTC."""
    base = symbol.upper().rsplit("-", 1)[0]
    return "KRW-" + base


def range_days(rng):
    return {"1d": 1, "5d": 5, "1mo": 31, "3mo": 92, "6mo": 183, "1y": 366, "2y": 731}.get(rng) or int(rng.rstrip("d"))


class Fetcher(object):
    """코인 차트. BTCUSDT 는 바이낸스, BTC-KRW 는 업비트 (crypto_source 가 yahoo 면 Yahoo 원화 환산).
    --offline 이면 저장된 파일만 읽는다."""

    def __init__(self, offline=None, pause=0.2, source=None):
        self.offline = offline
        self.pause = pause
        self.source = source
        self._last = 0.0

    def _wait(self, pause=None):
        wait = (self.pause if pause is None else pause) - (time.time() - self._last)
        if wait > 0:
            time.sleep(wait)
        self._last = time.time()

    def chart(self, symbol, rng, interval):
        if self.offline:
            return self._offline(symbol, interval)
        if quote_of(symbol) == "USDT":
            return self._binance(symbol, rng, interval)
        if (self.source or crypto_source()) != "yahoo":
            return self._upbit(symbol, rng, interval)
        return self._yahoo(symbol, rng, interval)

    def _binance(self, symbol, rng, interval):
        """바이낸스 현물 캔들. 한 번에 1,000개 (5분봉 60일이면 18번)."""
        step = {"5m": BAR, "1h": timedelta(hours=1), "1d": timedelta(days=1)}.get(interval)
        if not step:
            raise CrtError("바이낸스는 5m, 1h, 1d 만 씁니다")
        end_ms = int(time.time() * 1000)
        start_ms = end_ms - int(timedelta(days=range_days(rng)).total_seconds() * 1000)
        rows, cur = [], start_ms
        while cur < end_ms:
            url = "%s/klines?symbol=%s&interval=%s&limit=1000&startTime=%d" % (BINANCE, symbol.upper(), interval, cur)
            page = self._get(url, symbol, "바이낸스")
            if not page:
                break
            rows.extend(page)
            nxt = page[-1][0] + int(step.total_seconds() * 1000)
            if nxt <= cur or len(page) < 1000:
                break
            cur = nxt
        if not rows:
            raise CrtError("%s: 바이낸스 캔들이 비어 있습니다" % symbol)
        return binance_result(rows, symbol)

    def _get(self, url, symbol, what):
        for attempt in range(3):
            self._wait(0.12)
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=25) as r:
                    return json.load(r)
            except urllib.error.HTTPError as e:
                if e.code == 429 and attempt < 2:
                    time.sleep(1.0 + attempt)
                    continue
                if e.code in (400, 404):
                    raise CrtError("%s: %s에 없는 종목입니다" % (symbol, what))
                if e.code == 451:
                    raise CrtError("%s: %s가 이 서버 위치를 차단했습니다 (451)" % (symbol, what))
                raise CrtError("%s: %s 응답 오류 %s" % (symbol, what, e.code))
            except (urllib.error.URLError, OSError, ValueError) as e:
                raise CrtError("%s: %s 데이터를 받지 못했습니다 (%s)" % (symbol, what, e))
        raise CrtError("%s: %s 요청 한도 초과" % (symbol, what))

    def _upbit(self, symbol, rng, interval):
        """업비트 캔들. 한 번에 200개라 기간만큼 나눠 받는다 (5분봉 60일이면 약 87번)."""
        mk = upbit_market(symbol)
        if interval == "1d":
            path, step = "days", timedelta(days=1)
        elif interval == "1h":
            path, step = "minutes/60", timedelta(hours=1)
        elif interval == "5m":
            path, step = "minutes/5", BAR
        else:
            raise CrtError("업비트는 5m, 1h, 1d 만 지원합니다")
        need = int(timedelta(days=range_days(rng)) / step) + 1
        rows, to = [], None
        while len(rows) < need:
            url = "%s/candles/%s?market=%s&count=200" % (UPBIT, path, mk)
            if to:
                url += "&to=" + to
            page = self._get(url, symbol, "업비트")
            if not page:
                break
            rows.extend(page)
            to = page[-1]["candle_date_time_utc"] + "Z"
            if len(page) < 200:
                break
        if not rows:
            raise CrtError("%s: 업비트 캔들이 비어 있습니다" % symbol)
        return upbit_result(rows, symbol, interval)

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
                        raise CrtError("%s: Yahoo에 없는 종목입니다" % symbol)
                    break
                except (urllib.error.URLError, OSError, ValueError) as e:
                    last_err = e
                    break
        raise CrtError("%s: 데이터를 받지 못했습니다 (%s)" % (symbol, last_err))

    def _offline(self, symbol, interval):
        base = self.offline
        cands = [base] if os.path.isfile(base) else [
            os.path.join(base, "%s-%s.json%s" % (symbol, interval, ext)) for ext in (".gz", "")]
        if not os.path.isfile(base):
            cands = [os.path.join(base, "%s-%s.upbit.json%s" % (symbol, interval, ext)) for ext in (".gz", "")] + cands
        for path in cands:
            if os.path.isfile(path):
                opener = gzip.open if path.endswith(".gz") else open
                with opener(path, "rt", encoding="utf-8") as fh:
                    data = json.load(fh)
                if isinstance(data, list) and data and isinstance(data[0], list):
                    return binance_result(data, symbol)
                if isinstance(data, list):
                    return upbit_result(data, symbol, interval)
                return chart_result(data, symbol)
        raise CrtError("%s: 오프라인 파일이 없습니다 (%s)" % (symbol, base))


def upbit_result(rows, symbol, interval):
    """업비트 캔들 목록 -> {"meta", "bars"}. 거래가 없어 빠진 5분봉은 직전 종가로 채운다 (거래량 0)."""
    seen, bars = set(), []
    for r in rows:
        t = datetime.strptime(r["candle_date_time_kst"][:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=KST)
        if t in seen:
            continue
        seen.add(t)
        bars.append({"t": t, "o": float(r["opening_price"]), "h": float(r["high_price"]),
                     "l": float(r["low_price"]), "c": float(r["trade_price"]),
                     "v": float(r.get("candle_acc_trade_volume") or 0),
                     "val": float(r.get("candle_acc_trade_price") or 0)})
    bars.sort(key=lambda b: b["t"])
    filled = 0
    if interval == "5m" and bars:
        out = [bars[0]]
        for b in bars[1:]:
            t = out[-1]["t"] + BAR
            while t < b["t"]:
                pc = out[-1]["c"]
                out.append({"t": t, "o": pc, "h": pc, "l": pc, "c": pc, "v": 0.0, "val": 0.0, "filled": True})
                filled += 1
                t += BAR
            out.append(b)
        bars = out
    return {"meta": {"exchangeName": "UPBIT", "source": "upbit", "market": upbit_market(symbol),
                     "filled": filled}, "bars": bars}


def binance_result(rows, symbol):
    """바이낸스 klines -> {"meta", "bars"}. val 은 USDT 거래대금."""
    seen, bars = set(), []
    for r in rows:
        t = datetime.fromtimestamp(r[0] / 1000, KST)
        if t in seen:
            continue
        seen.add(t)
        bars.append({"t": t, "o": float(r[1]), "h": float(r[2]), "l": float(r[3]), "c": float(r[4]),
                     "v": float(r[5]), "val": float(r[7])})
    bars.sort(key=lambda b: b["t"])
    return {"meta": {"exchangeName": "BINANCE", "source": "binance", "currency": "USDT"}, "bars": bars}


def usdkrw(fetcher=None, cfg=None):
    """원/달러(USDT). 설정값 -> 업비트 KRW-USDT 체결가(한국에서 실제로 USDT를 사는 값) -> Yahoo KRW=X."""
    if cfg and cfg.get("usdkrw"):
        return float(cfg["usdkrw"]), "설정값"
    f = fetcher or Fetcher()
    try:
        t = f._get(UPBIT + "/ticker?markets=KRW-USDT", "USDT", "업비트")
        return float(t[0]["trade_price"]), "업비트 USDT"
    except (CrtError, KeyError, IndexError, ValueError):
        pass
    try:
        d = f._yahoo("KRW=X", "5d", "1d")
        return float(d["bars"][-1]["c"]), "Yahoo 환율"
    except (CrtError, IndexError):
        raise CrtError("원/달러 환율을 받지 못했습니다. crt/config.json 에 \"usdkrw\": 1400 처럼 넣어 주세요")


def kimchi(symbol, price_usdt, fetcher, fx=None):
    """같은 코인의 업비트 원화가와 바이낸스 환산가 차이(%). 업비트에 없으면 None."""
    base = symbol.upper()[:-4]
    try:
        t = fetcher._get(UPBIT + "/ticker?markets=KRW-" + base, base, "업비트")
        krw = float(t[0]["trade_price"])
        fx = fx or usdkrw(fetcher)[0]
        return {"upbit_krw": krw, "fx": fx, "premium_pct": (krw / (price_usdt * fx) - 1) * 100}
    except (CrtError, KeyError, IndexError, ValueError, ZeroDivisionError):
        return None


def bar_value(b, market="crypto"):
    """봉의 거래대금. 바이낸스는 USDT, 업비트는 원화 그대로, Yahoo 코인은 거래량이 이미 원화."""
    if "val" in b:
        return b["val"]
    return b["v"]


def resolve(symbol, fetcher=None):
    return normalize(symbol)


def chart_result(data, symbol):
    res = (data.get("chart") or {}).get("result")
    if not res:
        raise CrtError("%s: 차트 데이터가 비어 있습니다" % symbol)
    r = res[0]
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

def session_day(t, market="crypto"):
    """그 봉이 속한 세션(하루)의 날짜. 09:00 KST에 하루가 바뀐다."""
    return (t - timedelta(hours=9)).date()


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
        out.append(b)
    return out


def sessions(bars, market):
    """{날짜: [봉...]} (시간순)."""
    out = {}
    for b in bars:
        out.setdefault(session_day(b["t"], market), []).append(b)
    return out


def full_sessions(bars, market):
    """모든 슬롯이 다 있는 세션만: {날짜: [5분봉 288개]}."""
    n = MARKETS[market]["bars"]
    out = {}
    for day, bs in sessions(bars, market).items():
        if len(bs) == n and all(slot(b["t"], market) == i for i, b in enumerate(bs)):
            out[day] = bs
    return out


# ------------------------------------------------------------------ 09:00 범위 돌파

def orb_check(bars, market, day, now, or_minutes=15, until=None):
    """하루(09:00 KST 시작)의 첫 범위 돌파. bars는 완성된 5분봉 (여러 날 섞여도 됨)."""
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

def tick_size(price, market="crypto", quote="KRW"):
    """호가 단위 근사치 (거래소·종목마다 다르다): 원화는 가격 자릿수 기준, USDT 는 가격의 약 0.0001% 단위."""
    if quote == "USDT":
        if price <= 0:
            return 1e-8
        return max(10 ** (math.floor(math.log10(price)) - 6), 1e-8)
    if price >= 1000000:
        return 1000
    if price >= 100000:
        return 50
    if price >= 10000:
        return 10
    if price >= 1000:
        return 1
    if price >= 100:
        return 0.1
    if price >= 1:
        return 0.001
    return 0.00001


def _snap(price, market, fn, quote="KRW"):
    t = tick_size(price, market, quote)
    v = fn(price / t - 1e-9 if fn is math.ceil else price / t + 1e-9) * t
    return round(v, 10)


def round_down(price, market="crypto", quote="KRW"):
    return _snap(price, market, math.floor, quote)


def round_up(price, market="crypto", quote="KRW"):
    return _snap(price, market, math.ceil, quote)


def round_near(price, market="crypto", quote="KRW"):
    t = tick_size(price, market, quote)
    return round(math.floor(price / t + 0.5) * t, 10)


def won(x):
    """원화 표기: 1,234원 (1원 미만은 소수 둘째 자리)."""
    if abs(x) >= 100 or float(x).is_integer():
        return "{:,.0f}원".format(x)
    return "{:,.2f}원".format(x)


def price_str(x, market="crypto"):
    """원화·USDT 모두 의미 있는 자리까지 (끝의 0은 지움)."""
    a = abs(x)
    if a >= 1:
        s = "{:,.2f}".format(x) if a >= 100 else "{:,.4f}".format(x)
    elif a >= 0.01:
        s = "{:.5f}".format(x)
    else:
        s = "{:.8f}".format(x)
    return s.rstrip("0").rstrip(".") if "." in s else s


def money(x, quote="KRW"):
    """거래대금 표기: 원화는 만/억/조, USDT 는 $와 K/M/B."""
    if quote == "USDT":
        a = abs(x)
        for unit, div in (("B", 1e9), ("M", 1e6), ("K", 1e3)):
            if a >= div:
                return "$%.1f%s" % (x / div, unit)
        return "$%.0f" % x
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


def source_label(market="crypto", symbol=None):
    if symbol:
        return "바이낸스" if quote_of(symbol) == "USDT" else ("업비트" if crypto_source() != "yahoo" else "Yahoo 지연 시세")
    return {"binance": "바이낸스", "upbit": "업비트"}.get(crypto_source(), "Yahoo 지연 시세")


INTERVALS = {"5m": BAR, "1h": timedelta(hours=1), "1d": timedelta(days=1)}


def completed(bars, interval, now, market="crypto"):
    """진행 중인 마지막 봉을 뺀다. 일봉은 다음 09:00 KST가 지나야 완성."""
    if not bars:
        return bars
    last = bars[-1]
    if interval == "1d":
        done = session_day(last["t"]) < session_day(now)
    else:
        done = last["t"] + INTERVALS[interval] <= now
    return bars if done else bars[:-1]


DISCLAIMER = "규칙에 맞는지 확인한 결과일 뿐, 매수·매도 추천이 아닙니다. 데이터는 바이낸스·업비트 공개 시세입니다."
