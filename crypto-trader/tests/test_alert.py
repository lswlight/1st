"""alert.py 테스트: 진행 중 봉 분리, 진입 범위, 같은 알람 한 번만."""
import contextlib
import importlib.util
import io
import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, os.path.join(ROOT, "lib"))
import crt  # noqa: E402

spec = importlib.util.spec_from_file_location("coin_alert", os.path.join(ROOT, "coin-signal", "alert.py"))
alert = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alert)
pts = alert.pts


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        code = alert.main(argv)
    return code, out.getvalue()


class TestLive(unittest.TestCase):
    def test_split_live(self):
        t0 = datetime(2026, 10, 30, 1, tzinfo=crt.KST)
        bars = [{"t": t0 - timedelta(hours=4)}, {"t": t0}]
        done, live, frac = alert.split_live(bars, "4h", t0 + timedelta(hours=1))
        self.assertEqual(len(done), 1)
        self.assertIs(live, bars[1])
        self.assertAlmostEqual(frac, 0.25)
        done, live, frac = alert.split_live(bars, "4h", t0 + timedelta(hours=4))
        self.assertEqual((len(done), live), (2, None))


class TestZone(unittest.TestCase):
    def test_entry_zone(self):
        bars = [{"c": 100.0}] * 5 + [{"c": 110.0}]
        ind = [{"bb": {"up": 104.0}}] * 6
        z = pts.entry_zone(bars, ind, 5, {"stop": 100.0}, "crypto", "USDT")
        self.assertEqual(z["level"], 104.0)
        self.assertEqual(z["low"], 107.0)               # (104 + 110) / 2
        self.assertEqual(z["best"], 110.0)
        self.assertEqual(z["chase"], 113.0)             # 110 + (110 - 100) x 30%
        self.assertIn("1순위", pts.zone_text(z))


class TestDedupe(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.old = crt.DATA_DIR
        crt.DATA_DIR = os.path.join(self.tmp, "crt")
        self.orig = alert.check_symbol
        z = {"level": 1.0, "low": 1.05, "best": 1.1, "chase": 1.13, "stop": 1.0}
        alert.check_symbol = lambda s, tf, f, cfg, now, ev: [{
            "kind": "확정", "symbol": s, "tf": tf, "bar": "2026-10-29T09:00:00+09:00", "price": 1.1, "zone": z,
            "t2": 1.3, "mid": 1.0, "volx": 2.0, "grade_2r": "튼튼함 (x)", "grade_trail": "튼튼함 (y)"}]

    def tearDown(self):
        alert.check_symbol = self.orig
        crt.DATA_DIR = self.old
        shutil.rmtree(self.tmp)

    def test_once(self):
        code, out = run(["check", "--symbols", "TRX", "--tf", "mid", "--now", "2026-10-30 09:05"])
        self.assertEqual(code, 0)
        self.assertIn("[확정] TRXUSDT", out)
        self.assertIn("진입 범위 1.05 ~ 1.1", out)
        code, out = run(["check", "--symbols", "TRX", "--tf", "mid", "--now", "2026-10-30 10:05"])
        self.assertEqual(code, 1)
        self.assertIn("새 알람 없음", out)
        code, out = run(["check", "--symbols", "TRX", "--tf", "mid", "--now", "2026-10-30 10:10", "--all"])
        self.assertEqual(code, 0)


class TestOffline(unittest.TestCase):
    def test_runs_on_fixture(self):
        f = crt.Fetcher(FIX)
        out = alert.check_symbol("BTCUSDT", "short", f, crt.config(), crt.parse_kst("2026-10-30 08:30"),
                                 pts.load_evidence())
        self.assertIsInstance(out, list)
        for a in out:
            self.assertIn(a["kind"], ("확정", "진행 중"))
            self.assertLessEqual(a["zone"]["low"], a["zone"]["best"])


if __name__ == "__main__":
    unittest.main()
