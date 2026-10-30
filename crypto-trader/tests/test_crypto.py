"""crypto-trader 테스트. 네트워크 없이 tests/fixtures 의 실제 바이낸스·Yahoo 데이터로 돈다.

  python3 -m unittest discover -s crypto-trader/tests -v
"""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, os.path.join(ROOT, "lib"))
import crt  # noqa: E402


def load(rel, name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, rel))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


scan = load("coin-scanner/scan.py", "coin_scan")
plan = load("coin-plan/plan.py", "coin_plan")
tm = load("coin-timemachine/tm.py", "coin_tm")

BTC_NOW = "2026-10-28 20:00"


def run(fn, argv):
    out = io.StringIO()
    code = None
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        try:
            code = fn(argv)
        except SystemExit as e:
            code = e.code
    return code, out.getvalue()


class TempDir(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.old = crt.DATA_DIR
        crt.DATA_DIR = os.path.join(self.tmp, "crt")
        self.old_src = os.environ.pop("CRT_CRYPTO_SOURCE", None)

    def tearDown(self):
        crt.DATA_DIR = self.old
        if self.old_src is not None:
            os.environ["CRT_CRYPTO_SOURCE"] = self.old_src
        shutil.rmtree(self.tmp)


class TestSymbols(TempDir):
    def test_normalize_by_source(self):
        os.environ["CRT_CRYPTO_SOURCE"] = "binance"
        self.assertEqual(crt.normalize("btc"), "BTCUSDT")
        os.environ["CRT_CRYPTO_SOURCE"] = "upbit"
        self.assertEqual(crt.normalize("btc"), "BTC-KRW")
        del os.environ["CRT_CRYPTO_SOURCE"]
        self.assertEqual(crt.normalize("btc"), "BTCUSDT")          # 기본은 바이낸스
        self.assertEqual(crt.normalize("ethusdt"), "ETHUSDT")
        self.assertEqual(crt.normalize("sol-krw"), "SOL-KRW")
        self.assertEqual(crt.quote_of("BTCUSDT"), "USDT")
        self.assertEqual(crt.quote_of("BTC-KRW"), "KRW")
        self.assertEqual(crt.upbit_market("BTC-KRW"), "KRW-BTC")


class TestFormat(unittest.TestCase):
    def test_ticks(self):
        self.assertEqual(crt.tick_size(77941.28, "crypto", "USDT"), 0.01)
        self.assertEqual(crt.round_down(80824.894, "crypto", "USDT"), 80824.89)
        self.assertEqual(crt.round_near(80824.895, "crypto", "USDT"), 80824.9)
        self.assertEqual(crt.tick_size(105000000, "crypto", "KRW"), 1000)
        self.assertEqual(crt.round_down(129_740, "crypto", "KRW"), 129_700)

    def test_text(self):
        self.assertEqual(crt.price_str(77941.28), "77,941.28")
        self.assertEqual(crt.price_str(1.2436), "1.2436")
        self.assertEqual(crt.price_str(129500), "129,500")
        self.assertEqual(crt.price_str(0.00001234), "0.00001234")
        self.assertEqual(crt.money(114.8e6, "USDT"), "$114.8M")
        self.assertEqual(crt.money(1.9e11, "KRW"), "1900억원")
        self.assertEqual(crt.won(50000), "50,000원")


class TestIndicators(unittest.TestCase):
    def test_bollinger_and_rsi(self):
        bb = crt.bollinger([100.0] * 19 + [120.0], 20, 2)
        self.assertAlmostEqual(bb["mid"], 101.0)
        self.assertGreater(bb["pctb"], 1)
        up = [float(i) for i in range(1, 40)]
        self.assertEqual(crt.rsi(up), 100.0)
        self.assertLess(crt.rsi(list(reversed(up))), 1)

    def test_daily_setups(self):
        closes = [100 + (8 if i % 2 else -8) for i in range(100)]
        closes += [100 + (0.3 if i % 2 else -0.3) for i in range(25)] + [103]
        setups, info = scan.daily_setups(closes)
        self.assertIn("bb_squeeze_break", setups)
        closes = [100 + (0.5 if i % 2 else -0.5) for i in range(40)] + [96, 99.9]
        self.assertIn("bb_lower_reentry", scan.daily_setups(closes)[0])


class TestParsers(unittest.TestCase):
    def test_binance(self):
        rows = [[1793332800000 + 300000, "2", "3", "1", "2.5", "10", 0, "25", 5],
                [1793332800000, "1", "2", "0.5", "2", "4", 0, "8", 3],
                [1793332800000, "1", "2", "0.5", "2", "4", 0, "8", 3]]
        bars = crt.binance_result(rows, "BTCUSDT")["bars"]
        self.assertEqual(len(bars), 2)
        self.assertEqual((bars[1]["o"], bars[1]["h"], bars[1]["l"], bars[1]["c"]), (2.0, 3.0, 1.0, 2.5))
        self.assertEqual(crt.bar_value(bars[1]), 25.0)

    def test_upbit_fill_gap(self):
        def r(kst, o, h, l, c, v, val):
            return {"candle_date_time_kst": kst, "opening_price": o, "high_price": h, "low_price": l,
                    "trade_price": c, "candle_acc_trade_volume": v, "candle_acc_trade_price": val}
        rows = [r("2026-10-30T09:10:00", 101, 103, 100, 102, 2.0, 204.0),
                r("2026-10-30T09:00:00", 100, 101, 99, 100.5, 1.0, 100.5),
                r("2026-10-30T09:00:00", 100, 101, 99, 100.5, 1.0, 100.5)]
        res = crt.upbit_result(rows, "BTC-KRW", "5m")
        bars = res["bars"]
        self.assertEqual([b["t"].strftime("%H:%M") for b in bars], ["09:00", "09:05", "09:10"])
        self.assertTrue(bars[1]["filled"])
        self.assertEqual(bars[1]["c"], 100.5)
        self.assertEqual(res["meta"]["filled"], 1)


class TestSessions(unittest.TestCase):
    def test_day_starts_0900(self):
        t = crt.parse_kst("2026-10-29 08:55")
        self.assertEqual(str(crt.session_day(t)), "2026-10-28")
        self.assertEqual(crt.slot(t, "crypto"), 287)
        self.assertEqual(crt.slot(crt.parse_kst("2026-10-29 09:00"), "crypto"), 0)

    def test_full_sessions(self):
        bars = crt.Fetcher(FIX).chart("BTCUSDT", "60d", "5m")["bars"]
        full = crt.full_sessions(crt.five_minute_bars(bars, "crypto", crt.parse_kst(BTC_NOW)), "crypto")
        self.assertGreaterEqual(len(full), 30)
        self.assertTrue(all(len(bs) == 288 for bs in full.values()))


class TestUniverse(unittest.TestCase):
    def test_files(self):
        coins = {r["symbol"] for r in crt.load_universe(None, "universe-crypto.txt")}
        self.assertIn("BTCUSDT", coins)
        self.assertTrue(all(s.endswith("USDT") for s in coins))
        self.assertFalse({"GOOGLBUSDT", "INTCBUSDT", "USDCUSDT"} & coins)
        up = {r["symbol"] for r in crt.load_universe(None, "universe-upbit.txt")}
        self.assertTrue(all(s.endswith("-KRW") for s in up))
        self.assertNotIn("USDT-KRW", up)

    def test_tokenized_stock_heuristic(self):
        class Fake(object):
            def _yahoo(self, sym, rng, iv):
                meta = {"INTC": {"instrumentType": "EQUITY", "regularMarketPrice": 92.0},
                        "BN": {"instrumentType": "EQUITY", "regularMarketPrice": 39.5}}
                if sym not in meta:
                    raise crt.CrtError("없음")
                return {"meta": meta[sym], "bars": []}
        f = Fake()
        self.assertTrue(scan.is_tokenized_stock(f, "INTCB", 71.2))
        self.assertFalse(scan.is_tokenized_stock(f, "BNB", 703.0))
        self.assertFalse(scan.is_tokenized_stock(f, "SOL", 95.0))


class TestScanner(TempDir):
    def test_orb_binance(self):
        code, out = run(scan.main, ["orb", "--symbols", "BTC", "--offline", FIX, "--now", BTC_NOW, "--json"])
        self.assertEqual(code, 0, out)
        h = json.loads(out)["hits"][0]
        self.assertEqual((h["symbol"], h["at"], h["price"]), ("BTCUSDT", "10:40", 80918.0))
        self.assertEqual((h["or_high"], h["or_low"]), (80909.78, 80740.0))

    def test_orb_too_early(self):
        code, out = run(scan.main, ["orb", "--symbols", "BTC", "--offline", FIX, "--now", "2026-10-28 09:10"])
        self.assertEqual(code, 1)
        self.assertIn("아직 이릅니다", out)

    def test_daily_drops_unfinished_day(self):
        code, out = run(scan.main, ["daily", "--symbols", "BTC", "--offline", FIX, "--now", "2026-10-30 13:00",
                                    "--json"])
        r = json.loads(out)
        self.assertEqual(r["fetched"], 1)
        self.assertEqual(r["closes_through"], "2026-10-29")
        code, out = run(scan.main, ["daily", "--symbols", "BTC-KRW,XRP-KRW", "--offline", FIX,
                                    "--now", "2026-10-29 16:30", "--json"])
        self.assertEqual(json.loads(out)["closes_through"], "2026-10-28")


class TestPlan(TempDir):
    def test_entry_exit_size(self):
        code, out = run(plan.main, ["entry", "BTC", "--offline", FIX, "--now", BTC_NOW, "--write"])
        self.assertEqual(code, 0, out)
        e = crt.load_json("entry.json")
        self.assertEqual((e["symbol"], e["entry_time"], e["entry"]), ("BTCUSDT", "10:40", 80918.0))
        p = plan.make_plan(e, "or-mid", "1R", crt.config())
        self.assertEqual(p["stop"], 80824.89)
        self.assertEqual(p["flat_by"], "16:40")              # 진입 후 6시간
        r = plan.size_plan(p, crt.config(), 5000000, 50000, fx=1350.0)
        self.assertTrue(0 < r["qty"] < 1)
        self.assertEqual(r["qty"], round(r["qty"], 6))
        self.assertAlmostEqual(r["amount"], r["amount_quote"] * 1350.0, places=4)
        self.assertLessEqual(r["amount"], 5000000 * 0.75 + 1e-6)
        loss = (80918.0 - 80824.89) * 1350 + (80918.0 + 80824.89) * 1350 * 0.001
        self.assertAlmostEqual(r["loss_per_unit"], loss, places=3)

    def test_refuses_bad_stop(self):
        e = {"symbol": "BTCUSDT", "market": "crypto", "date": "2026-10-28", "entry": 80918.0,
             "entry_time": "10:40", "or_high": 80909.78, "or_low": 80740.0}
        with self.assertRaises(crt.CrtError):
            plan.make_plan(e, "price:81000", "1R", crt.config())     # 손절이 진입가 위
        with self.assertRaises(crt.CrtError):
            plan.make_plan(e, "price:80900", "1R", crt.config())     # 0.02%: 너무 좁음


class TestTimeMachine(TempDir):
    def make_plan(self):
        run(plan.main, ["entry", "BTC", "--offline", FIX, "--now", BTC_NOW, "--write"])
        run(plan.main, ["exit", "--target", "1R", "--write"])

    def test_deterministic_and_consistent(self):
        self.make_plan()
        _, a = run(tm.main, ["run", "--offline", FIX, "--replays", "2000"])
        code, b = run(tm.main, ["run", "--offline", FIX, "--replays", "2000", "--write"])
        self.assertEqual(code, 0, b)
        self.assertEqual(a, b)
        self.assertIn("10:40부터 16:40까지", a)
        self.assertIn("1개당 위험", a)
        r = crt.load_json("timemachine.json")
        mc, h = r["mc"], r["history"]
        self.assertAlmostEqual(mc["target"] + mc["stop"] + mc["neither"], 100.0, places=6)
        self.assertEqual(h["fired"], h["target"] + h["stop"] + h["neither"])

    def test_verdict_bands(self):
        self.assertTrue(tm.verdict({"target": 30.0, "stop": 30.0}, 1).startswith("동전"))
        self.assertTrue(tm.verdict({"target": 24.1, "stop": 50.5}, 2).startswith("본전"))
        self.assertIn("강하게", tm.verdict({"target": 60.0, "stop": 20.0}, 1))


class TestRepo(unittest.TestCase):
    def test_skills_have_frontmatter(self):
        for d in ("coin-scanner", "coin-plan", "coin-timemachine", "coin-news", "coin-signal"):
            with open(os.path.join(ROOT, d, "SKILL.md"), encoding="utf-8") as fh:
                head = fh.read(400)
            self.assertTrue(head.startswith("---\nname: %s\n" % d), d)

    def test_stdlib_only_and_no_stock_code(self):
        allowed = set(sys.stdlib_module_names) | {"crt"}
        for rel in ("lib/crt.py", "coin-scanner/scan.py", "coin-plan/plan.py", "coin-timemachine/tm.py",
                    "coin-signal/points.py"):
            with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
                text = fh.read()
            for line in text.splitlines():
                line = line.strip()
                if line.startswith(("import ", "from ")):
                    self.assertIn(line.split()[1].split(".")[0], allowed, rel)
            self.assertNotIn("krx", text, rel)


if __name__ == "__main__":
    unittest.main()
