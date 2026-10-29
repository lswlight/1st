"""kr-trader 테스트. 네트워크 없이 tests/fixtures 의 실제 Yahoo 데이터로 돈다.

  python3 -m unittest discover -s kr-trader/tests -v
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
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, os.path.join(ROOT, "lib"))
import krt  # noqa: E402


def load(rel, name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, rel))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


scan = load("kr-scanner/scan.py", "kr_scan")
plan = load("kr-plan/plan.py", "kr_plan")
tm = load("kr-timemachine/tm.py", "kr_tm")

KRX_NOW = "2026-10-29 16:30"
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
        self.old = krt.DATA_DIR
        krt.DATA_DIR = os.path.join(self.tmp, "krt")

    def tearDown(self):
        krt.DATA_DIR = self.old
        shutil.rmtree(self.tmp)


class TestTicks(unittest.TestCase):
    def test_krx_tick_table(self):
        cases = [(1999, 1), (2000, 5), (4999, 5), (5000, 10), (19990, 10), (20000, 50),
                 (49950, 50), (50000, 100), (199900, 100), (200000, 500), (499500, 500),
                 (500000, 1000), (1526000, 1000)]
        for price, tick in cases:
            self.assertEqual(krt.tick_size(price, "krx"), tick, price)

    def test_rounding(self):
        self.assertEqual(krt.round_down(241750, "krx"), 241500)
        self.assertEqual(krt.round_down(241500, "krx"), 241500)
        self.assertEqual(krt.round_up(241501, "krx"), 242000)
        self.assertEqual(krt.round_near(249750, "krx"), 250000)
        self.assertEqual(krt.round_near(249740, "krx"), 249500)
        self.assertEqual(krt.round_down(12345, "krx"), 12340)

    def test_money_format(self):
        self.assertEqual(krt.won(50000), "50,000원")
        self.assertEqual(krt.man(5.82e12), "5.8조")
        self.assertEqual(krt.man(1.9e11), "1900억")


class TestIndicators(unittest.TestCase):
    def test_bollinger_flat_then_jump(self):
        closes = [100.0] * 19 + [120.0]
        bb = krt.bollinger(closes, 20, 2)
        self.assertAlmostEqual(bb["mid"], 101.0)
        self.assertGreater(bb["pctb"], 1)
        self.assertGreater(bb["width"], 0)

    def test_rsi_bounds(self):
        up = [float(i) for i in range(1, 40)]
        self.assertEqual(krt.rsi(up), 100.0)
        down = list(reversed(up))
        self.assertLess(krt.rsi(down), 1)

    def test_squeeze_break_setup(self):
        # 오래 넓게 흔들리다가 좁게 수렴한 뒤 상단 돌파
        closes = []
        for i in range(100):
            closes.append(100 + (8 if i % 2 else -8))
        closes += [100 + (0.3 if i % 2 else -0.3) for i in range(25)]
        closes.append(103)
        setups, info = scan.daily_setups(closes)
        self.assertIn("bb_squeeze_break", setups)
        self.assertIn("breakout_20d", setups)
        self.assertGreater(info["pctb"], 1)

    def test_lower_reentry_setup(self):
        closes = [100 + (0.5 if i % 2 else -0.5) for i in range(40)] + [96, 99.9]
        setups, _ = scan.daily_setups(closes)
        self.assertIn("bb_lower_reentry", setups)


class TestExchangeGuard(unittest.TestCase):
    def test_wrong_suffix_rejected(self):
        data = {"chart": {"result": [{"meta": {"exchangeName": "KOE"}, "timestamp": [],
                                      "indicators": {"quote": [{}]}}]}}
        with self.assertRaises(krt.KrtError):
            krt.chart_result(data, "005930.KS")
        krt.chart_result(data, "247540.KQ")

    def test_normalize(self):
        self.assertEqual(krt.normalize("005930"), "005930.KS")
        self.assertEqual(krt.normalize("247540"), "247540.KQ")
        self.assertEqual(krt.normalize("btc"), "BTC-KRW")
        self.assertEqual(krt.market_of("BTC-KRW"), "crypto")
        self.assertEqual(krt.market_of("005930.KS"), "krx")


class TestUniverse(unittest.TestCase):
    def test_files(self):
        krx = krt.load_universe("krx")
        self.assertGreater(len(krx), 100)
        self.assertTrue(all(r["symbol"][-3:] in (".KS", ".KQ") for r in krx))
        syms = {r["symbol"] for r in krx}
        self.assertIn("120110.KS", syms)  # 코오롱인더
        self.assertIn("298050.KS", syms)  # HS효성첨단소재
        self.assertEqual(len(syms), len(krx))
        coins = krt.load_universe("crypto")
        self.assertTrue(all(r["symbol"].endswith("-KRW") for r in coins))


class TestSessions(unittest.TestCase):
    def test_krx_slots(self):
        f = krt.Fetcher(FIX)
        bars = f.chart("005930.KS", "60d", "5m")["bars"]
        bars = krt.five_minute_bars(bars, "krx", krt.parse_kst(KRX_NOW))
        full = krt.full_sessions(bars, "krx")
        self.assertGreaterEqual(len(full), 50)
        for day, bs in full.items():
            self.assertEqual(len(bs), 72)
            self.assertEqual(bs[0]["t"].strftime("%H:%M"), "09:00")
            self.assertEqual(bs[-1]["t"].strftime("%H:%M"), "14:55")

    def test_crypto_day_starts_0900(self):
        t = krt.parse_kst("2026-10-29 08:55")
        self.assertEqual(str(krt.session_day(t, "crypto")), "2026-10-28")
        self.assertEqual(krt.slot(t, "crypto"), 287)
        self.assertEqual(krt.slot(krt.parse_kst("2026-10-29 09:00"), "crypto"), 0)


class TestScanner(TempDir):
    def test_orb_samsung(self):
        code, out = run(scan.main, ["orb", "--symbols", "005930", "--offline", FIX, "--now", KRX_NOW, "--json"])
        self.assertEqual(code, 0, out)
        r = json.loads(out)
        h = r["hits"][0]
        self.assertEqual((h["symbol"], h["at"], h["price"]), ("005930.KS", "09:25", 245500.0))
        self.assertEqual((h["or_high"], h["or_low"]), (244500.0, 239000.0))
        self.assertEqual(r["cutoff"], "14:30")

    def test_orb_too_early(self):
        code, out = run(scan.main, ["orb", "--symbols", "005930", "--offline", FIX,
                                    "--now", "2026-10-29 09:10"])
        self.assertEqual(code, 1)
        self.assertIn("아직 이릅니다", out)

    def test_daily_krx(self):
        code, out = run(scan.main, ["daily", "--symbols", "005930,000660,000270,120110",
                                    "--offline", FIX, "--now", KRX_NOW, "--json", "--min-value", "0"])
        r = json.loads(out)
        self.assertEqual(r["fetched"], 4)
        self.assertEqual(r["closes_through"], "2026-10-29")
        for h in r["hits"]:
            self.assertTrue(h["setups"])
            self.assertIn("pctb", h)

    def test_daily_crypto_drops_unfinished_day(self):
        code, out = run(scan.main, ["daily", "--market", "crypto", "--symbols", "BTC,XRP",
                                    "--offline", FIX, "--now", "2026-10-29 16:30", "--json"])
        r = json.loads(out)
        self.assertEqual(r["closes_through"], "2026-10-28")


class TestPlan(TempDir):
    def entry_exit(self):
        code, out = run(plan.main, ["entry", "005930", "--offline", FIX, "--now", KRX_NOW, "--write"])
        self.assertEqual(code, 0, out)
        code, out = run(plan.main, ["exit", "--stop", "or-mid", "--target", "1R", "--write"])
        self.assertEqual(code, 0, out)
        return krt.load_json("plan.json")

    def test_exit_plan(self):
        p = self.entry_exit()
        self.assertEqual(p["entry"], 245500)
        self.assertEqual(p["stop"], 241500)      # (244500 + 239000) / 2 = 241750 -> 500원 단위 내림
        self.assertEqual(p["target"], 249500)
        self.assertEqual(p["risk"], 4000)
        self.assertEqual(p["flat_by"], "14:50")
        self.assertEqual(p["entry_bar"], 4)

    def test_refuses_bad_stop(self):
        e = {"symbol": "005930.KS", "market": "krx", "date": "2026-10-29", "entry": 245500,
             "entry_time": "09:25", "or_high": 244500, "or_low": 239000}
        with self.assertRaises(krt.KrtError):
            plan.make_plan(e, "price:246000", "1R", krt.config())
        with self.assertRaises(krt.KrtError):
            plan.make_plan(e, "price:245000", "1R", krt.config())  # 1틱(500원) 차이: 너무 좁음

    def test_size_with_costs(self):
        p = self.entry_exit()
        r = plan.size_plan(p, krt.config(), 5000000, 50000)
        # 주당 손실 = 4000 + 245500*0.00015 + 241500*0.00215 = 4000 + 36.825 + 519.225 = 4556.05
        self.assertAlmostEqual(r["loss_per_unit"], 4556.05, places=2)
        self.assertEqual(r["qty"], 10)
        self.assertEqual(r["binding"], "최대 손실")
        self.assertLess(r["net_rr"], 1)
        self.assertIsNone(r["refused"])

    def test_size_refuses_tiny_budget(self):
        p = self.entry_exit()
        r = plan.size_plan(p, krt.config(), 200000, 50000)
        self.assertIsNotNone(r["refused"])

    def test_crypto_fractional(self):
        code, out = run(plan.main, ["entry", "BTC", "--offline", FIX, "--now", BTC_NOW, "--write"])
        self.assertEqual(code, 0, out)
        e = krt.load_json("entry.json")
        self.assertEqual(e["entry_time"], "09:25")
        p = plan.make_plan(e, "or-mid", "1R", krt.config())
        r = plan.size_plan(p, krt.config(), 5000000, 50000)
        self.assertGreater(r["qty"], 0)
        self.assertLess(r["qty"], 1)
        self.assertEqual(r["qty"], round(r["qty"], 8))


class TestTimeMachine(TempDir):
    def make_plan(self):
        run(plan.main, ["entry", "005930", "--offline", FIX, "--now", KRX_NOW, "--write"])
        run(plan.main, ["exit", "--stop", "or-mid", "--target", "1R", "--write"])

    def test_deterministic(self):
        self.make_plan()
        _, a = run(tm.main, ["run", "--offline", FIX, "--replays", "3000"])
        _, b = run(tm.main, ["run", "--offline", FIX, "--replays", "3000"])
        self.assertEqual(a, b)
        self.assertIn("시드 19", a)

    def test_odds_add_up(self):
        self.make_plan()
        code, out = run(tm.main, ["run", "--offline", FIX, "--replays", "2000", "--write"])
        self.assertEqual(code, 0, out)
        r = krt.load_json("timemachine.json")
        mc = r["mc"]
        self.assertAlmostEqual(mc["target"] + mc["stop"] + mc["neither"], 100.0, places=6)
        self.assertEqual(mc["bars"], 65)            # 09:25 ~ 14:50
        h = r["history"]
        self.assertEqual(h["fired"], h["target"] + h["stop"] + h["neither"])
        self.assertGreater(h["days"], 40)

    def test_verdict_bands(self):
        mc = {"target": 30.0, "stop": 30.0}
        self.assertTrue(tm.verdict(mc, 1).startswith("동전"))
        mc = {"target": 24.1, "stop": 50.5}
        self.assertTrue(tm.verdict(mc, 2).startswith("본전"))
        mc = {"target": 60.0, "stop": 20.0}
        self.assertIn("강하게", tm.verdict(mc, 1))

    def test_crypto(self):
        run(plan.main, ["entry", "BTC", "--offline", FIX, "--now", BTC_NOW, "--write"])
        run(plan.main, ["exit", "--target", "1R", "--write"])
        code, out = run(tm.main, ["run", "--offline", FIX, "--replays", "2000"])
        self.assertEqual(code, 0, out)
        self.assertIn("09:25부터 15:25까지", out)


class TestRepo(unittest.TestCase):
    def test_skills_have_frontmatter(self):
        for d in ("kr-scanner", "kr-plan", "kr-timemachine", "kr-news"):
            with open(os.path.join(ROOT, d, "SKILL.md"), encoding="utf-8") as fh:
                head = fh.read(400)
            self.assertTrue(head.startswith("---\nname: %s\n" % d), d)
            self.assertIn("description:", head)

    def test_stdlib_only(self):
        allowed = set(sys.stdlib_module_names) | {"krt"}
        for rel in ("lib/krt.py", "kr-scanner/scan.py", "kr-plan/plan.py", "kr-timemachine/tm.py"):
            with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if line.startswith("import ") or line.startswith("from "):
                        mod = line.split()[1].split(".")[0]
                        self.assertIn(mod, allowed, "%s: %s" % (rel, line))


if __name__ == "__main__":
    unittest.main()
