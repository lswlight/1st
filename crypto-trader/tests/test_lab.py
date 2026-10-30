"""coin-signal lab.py 테스트: 청산 규칙, BTC 추세 판정, 성적 집계."""
import importlib.util
import os
import sys
import unittest
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "lib"))
import crt  # noqa: E402

spec = importlib.util.spec_from_file_location("coin_lab", os.path.join(ROOT, "coin-signal", "lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)

T0 = datetime(2026, 1, 1, 9, tzinfo=crt.KST)


def bar(i, o, h, l, c):
    return {"t": T0 + timedelta(days=i), "o": o, "h": h, "l": l, "c": c, "v": 1.0}


def ind_mid(mids):
    return [{"bb": {"mid": m} if m is not None else None} for m in mids]


TR = {"setup": "squeeze_break", "entry": 100.0, "stop": 95.0, "risk": 5.0, "target": 110.0}


class TestExit(unittest.TestCase):
    def test_fixed2r_target_and_stop_first(self):
        bars = [bar(0, 100, 100, 100, 100), bar(1, 100, 104, 99, 103), bar(2, 103, 111, 102, 108)]
        self.assertEqual(lab.exit_trade(bars, ind_mid([None] * 3), 0, TR, "fixed2r", 20, 60), ("target", 2.0, 2))
        bars[2] = bar(2, 103, 111, 94, 108)          # 같은 봉에서 손절과 목표를 둘 다 건드림 -> 손절
        self.assertEqual(lab.exit_trade(bars, ind_mid([None] * 3), 0, TR, "fixed2r", 20, 60), ("stop", -1.0, 2))

    def test_fixed3r_needs_more(self):
        bars = [bar(0, 100, 100, 100, 100), bar(1, 100, 111, 99, 110), bar(2, 110, 116, 109, 115)]
        out = lab.exit_trade(bars, ind_mid([None] * 3), 0, TR, "fixed3r", 20, 60)
        self.assertEqual(out, ("target", 3.0, 2))

    def test_time_exit(self):
        bars = [bar(0, 100, 100, 100, 100)] + [bar(i, 101, 102, 100, 101) for i in range(1, 6)]
        out = lab.exit_trade(bars, ind_mid([None] * 6), 0, TR, "fixed2r", 3, 60)
        self.assertEqual(out, ("time", 0.2, 3))

    def test_trail_rides_until_close_below_mid(self):
        bars = [bar(0, 100, 100, 100, 100), bar(1, 100, 112, 99, 111), bar(2, 111, 125, 110, 124),
                bar(3, 124, 125, 117, 118)]
        mids = [None, 100.0, 105.0, 119.0]             # 3번 봉 종가 118 < 중심 119 -> 정리
        out = lab.exit_trade(bars, ind_mid(mids), 0, TR, "trail", 20, 60)
        self.assertEqual(out[0], "trail")
        self.assertAlmostEqual(out[1], 3.6)          # (118 - 100) / 5
        self.assertEqual(out[2], 3)

    def test_lower_reentry_uses_mid_target(self):
        tr = dict(TR, setup="lower_reentry", target=104.0)
        bars = [bar(0, 100, 100, 100, 100), bar(1, 100, 105, 99, 104)]
        out = lab.exit_trade(bars, ind_mid([None] * 2), 0, tr, "trail", 20, 60)
        self.assertEqual(out, ("target", 0.8, 1))


class TestRegime(unittest.TestCase):
    def test_uses_only_completed_day(self):
        regime = [(T0 + timedelta(days=1), True), (T0 + timedelta(days=2), False)]
        self.assertIsNone(lab.regime_at(regime, T0 + timedelta(hours=12)))       # 첫 일봉 완성 전
        self.assertTrue(lab.regime_at(regime, T0 + timedelta(days=1)))           # 완성 시각 그때부터
        self.assertTrue(lab.regime_at(regime, T0 + timedelta(days=1, hours=23)))
        self.assertFalse(lab.regime_at(regime, T0 + timedelta(days=2, hours=1)))


class TestSummary(unittest.TestCase):
    def test_stats(self):
        tr = [{"rnet": 2.0, "exit_t": T0 + timedelta(days=1), "bars": 2},
              {"rnet": -1.0, "exit_t": T0 + timedelta(days=2), "bars": 1},
              {"rnet": -1.0, "exit_t": T0 + timedelta(days=3), "bars": 1},
              {"rnet": 3.0, "exit_t": T0 + timedelta(days=4), "bars": 4}]
        st = lab.summarize(tr)
        self.assertEqual(st["n"], 4)
        self.assertEqual(st["win"], 50.0)
        self.assertEqual(st["total"], 3.0)
        self.assertEqual(st["pf"], 2.5)
        self.assertEqual(st["mdd"], 2.0)             # +2 -> 0 (최고 2에서 2 하락)
        self.assertIsNone(lab.summarize([]))


if __name__ == "__main__":
    unittest.main()
