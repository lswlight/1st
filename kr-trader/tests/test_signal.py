"""kr-signal 테스트. 합성 봉으로 셋업 규칙을, 저장된 바이낸스·Yahoo 데이터로 전체 흐름을 확인한다.

  python3 -m unittest discover -s kr-trader/tests -v
"""
import contextlib
import importlib.util
import io
import os
import sys
import unittest
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, os.path.join(ROOT, "lib"))
import krt  # noqa: E402

spec = importlib.util.spec_from_file_location("kr_points", os.path.join(ROOT, "kr-signal", "points.py"))
pts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pts)


def make_bars(closes, vols=None, start=datetime(2026, 1, 1, 9, tzinfo=krt.KST), step=timedelta(days=1),
              wick=0.002):
    bars = []
    prev = closes[0]
    for i, c in enumerate(closes):
        o = prev
        bars.append({"t": start + step * i, "o": o, "h": max(o, c) * (1 + wick), "l": min(o, c) * (1 - wick),
                     "c": c, "v": (vols[i] if vols else 1000.0)})
        prev = c
    return bars


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        try:
            code = pts.main(argv)
        except SystemExit as e:
            code = e.code
    return code, out.getvalue()


class TestSetups(unittest.TestCase):
    def last_setups(self, bars):
        ind = pts.indicators(bars)
        return [s[0] for s in pts.setups_at(bars, ind, len(bars) - 1)], ind

    def test_squeeze_break_needs_volume(self):
        closes = [100 + (6 if i % 2 else -6) for i in range(100)]
        closes += [100 + (0.2 if i % 2 else -0.2) for i in range(30)]
        closes.append(102.5)
        vols = [1000.0] * (len(closes) - 1)
        names, _ = self.last_setups(make_bars(closes, vols + [3000.0]))
        self.assertIn("squeeze_break", names)
        names, _ = self.last_setups(make_bars(closes, vols + [1200.0]))   # 거래량 1.2배: 신호 아님
        self.assertNotIn("squeeze_break", names)

    def test_lower_reentry(self):
        closes = [100 + (0.5 if i % 2 else -0.5) for i in range(80)] + [96.0, 99.7]
        names, _ = self.last_setups(make_bars(closes))
        self.assertIn("lower_reentry", names)

    def test_mid_pullback(self):
        # 꾸준한 상승 -> 상단을 타다가 -> 중심선까지 눌림 -> 양봉으로 중심선 위 마감
        closes = [100 + i * 0.5 for i in range(90)]
        closes += [closes[-1] + 1.5 * k for k in range(1, 6)]
        top = closes[-1]
        closes += [top - 1.5, top - 3.0, top - 4.5, top - 5.5]
        bars = make_bars(closes)
        ind = pts.indicators(bars)
        mid = ind[-1]["bb"]["mid"]
        # 마지막 봉: 저가가 중심선을 건드리고 종가는 중심선 위, 직전 종가보다 높은 양봉
        c = max(mid * 1.01, bars[-1]["c"] + 0.5)
        bars.append({"t": bars[-1]["t"] + timedelta(days=1), "o": mid * 0.999, "h": c * 1.001,
                     "l": mid * 0.998, "c": c, "v": 1000.0})
        names, _ = self.last_setups(bars)
        self.assertIn("mid_pullback", names)

    def test_no_signal_on_flat(self):
        names, _ = self.last_setups(make_bars([100.0 + (0.1 if i % 2 else 0) for i in range(120)]))
        self.assertEqual(names, [])


class TestTrade(unittest.TestCase):
    def test_make_trade_and_caps(self):
        bars = make_bars([100.0] * 70 + [110.0])
        t = pts.make_trade(bars, 70, "squeeze_break", 105.0, None, "krx", "KRW")
        self.assertEqual((t["entry"], t["stop"], t["target"]), (110.0, 105.0, 120.0))   # 2R
        t = pts.make_trade(bars, 70, "squeeze_break", 109.9, None, "krx", "KRW")
        self.assertEqual(t["stop"], 109)         # 1원 단위로 내림 -> 0.9% 라 통과
        big = make_bars([100000.0] * 70 + [100100.0])
        self.assertIsNone(pts.make_trade(big, 70, "squeeze_break", 100000.0, None, "krx", "KRW"))  # 0.1%: 너무 좁음
        self.assertIsNone(pts.make_trade(bars, 70, "squeeze_break", 90.0, None, "krx", "KRW",
                                         max_risk_pct=15))   # 18% 손절: 상한 초과
        t = pts.make_trade(bars, 70, "lower_reentry", 105.0, 115.0, "krx", "KRW")
        self.assertEqual(t["target"], 115.0)    # 하단 복귀는 중심선이 목표
        self.assertAlmostEqual(t["r"], 1.0)

    def test_walk_stop_first(self):
        bars = make_bars([100.0] * 3)
        tr = {"entry": 100.0, "stop": 98.0, "target": 104.0, "risk": 2.0, "r": 2.0}
        bars[1].update(h=105.0, l=97.0)          # 한 봉이 둘 다 건드리면 손절
        self.assertEqual(pts.walk(bars, 0, tr, 10)[:2], ("stop", -1.0))
        bars[1].update(h=105.0, l=99.0)
        self.assertEqual(pts.walk(bars, 0, tr, 10)[:2], ("target", 2.0))

    def test_completed_drops_running_bar(self):
        t0 = datetime(2026, 10, 30, 12, tzinfo=krt.KST)
        bars = [{"t": t0 - timedelta(hours=1)}, {"t": t0}]
        self.assertEqual(len(krt.completed(bars, "1h", t0 + timedelta(minutes=30), "krx")), 1)
        self.assertEqual(len(krt.completed(bars, "1h", t0 + timedelta(hours=1), "krx")), 2)
        k = [{"t": datetime(2026, 10, 30, 9, tzinfo=krt.KST)}]
        self.assertEqual(krt.completed(k, "1d", datetime(2026, 10, 30, 14, tzinfo=krt.KST), "krx"), [])
        self.assertEqual(len(krt.completed(k, "1d", datetime(2026, 10, 30, 16, tzinfo=krt.KST), "krx")), 1)


class TestOffline(unittest.TestCase):
    def test_now_both(self):
        code, out = run(["now", "005930", "--offline", FIX, "--now", "2026-10-29 16:30"])
        self.assertIn("005930.KS 삼성전자", out)
        self.assertIn("[중기 · 일봉] 마지막 완성 봉 2026-10-29", out)       # 15:30 이후라 오늘 일봉 완성
        self.assertIn("[단기 · 1시간봉] 마지막 완성 봉 10-29", out)

    def test_daily_unfinished_before_close(self):
        code, out = run(["now", "005930", "--tf", "mid", "--offline", FIX, "--now", "2026-10-29 14:00"])
        self.assertIn("마지막 완성 봉 2026-10-28", out)

    def test_backtest_counts_consistent(self):
        f = krt.Fetcher(FIX)
        a = pts.analyze("005930.KS", "mid", f, krt.config(), krt.parse_kst("2026-10-29 16:30"))
        for name, st in a["stats"].items():
            self.assertEqual(st["n"], st["target"] + st["stop"] + st["time"], name)
            if st["n"]:
                self.assertLess(st["avg_rnet"], st["avg_r"])       # 비용(0.23%)을 빼면 줄어든다
        self.assertAlmostEqual(a["cost"], 0.0023)

    def test_scan_offline(self):
        code, out = run(["scan", "--tf", "mid", "--symbols", "005930", "--offline", FIX,
                         "--now", "2026-10-29 16:30"])
        self.assertIn("1개 중 1개 분석", out)


if __name__ == "__main__":
    unittest.main()
