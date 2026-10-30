---
name: coin-plan
description: 코인 매수 계획 도구. 5분봉으로 진입 신호(09:00~09:15 KST 범위 돌파, 5분봉 볼린저 상단 돌파, 가격 레벨 돌파)를 확인하고, 진입 전에 손절가(범위 중간·범위 저가·볼린저 중심선·퍼센트·직접 지정)와 목표가(1R·2R·직접 지정)를 정한 뒤, 원화 계좌와 1회 최대 손실에 맞춰 소수점 수량을 계산한다. 바이낸스 USDT 가격은 업비트 USDT 원화가로 환산하고 수수료를 반영하며 김치 프리미엄도 보여 준다. 주문은 내지 않는다. 사용자가 "코인 진입 시점", "손절 어디", "목표가", "손익비", "비트코인 몇 개", "얼마나 사", "500만 원 계좌", "최대 손실 5만 원", "김치 프리미엄" 등을 말하면 쓴다. 국내 주식은 kr-plan.
---

# coin-plan

거래가 어떻게 끝날지를 시작 전에 정하는 도구다. 손절은 셋업이 실패한 자리,
목표는 가져갈 몫이다. 둘 다 진입 전에 정해 적어 둔다. 손절은 계획이지 보장이
아니다. 갭이나 급변 때는 더 불리하게 체결된다.

```bash
python3 "<이 폴더>/plan.py" entry BTC --write                    # 09:00 범위 돌파 확인 (바이낸스 BTCUSDT) -> crt/entry.json
python3 "<이 폴더>/plan.py" entry SOL --setup bb --write         # 5분봉 볼린저 상단 돌파
python3 "<이 폴더>/plan.py" entry ETH --setup level --level 2300 --write
python3 "<이 폴더>/plan.py" entry BTC-KRW --write                # 코인: 업비트 원화
python3 "<이 폴더>/plan.py" exit --stop or-mid --target 1R --write   # -> crt/plan.json
python3 "<이 폴더>/plan.py" exit --symbol SOL --entry 96.02 --or-high 95.78 --or-low 95.11 --entry-time 12:10
python3 "<이 폴더>/plan.py" size --budget 5000000 --max-loss 50000 --write   # -> crt/order.json (제안서)
python3 "<이 폴더>/plan.py" show
```

## 진입 신호 (매수만)

| 셋업 | 신호 |
| --- | --- |
| `orb` | 개장 15분 범위 고가 위에서 5분봉 종가가 처음 마감 |
| `bb` | 5분봉 종가가 볼린저(20, 2σ) 상단을 처음 넘음 (직전 봉은 상단 아래), 09:15 이후 |
| `level` | 5분봉 종가가 지정한 가격 위에서 마감 (`--level`) |

상태는 대기 / 신호. 신호가 나면 그 봉의 종가가 진입가다.

## 손절과 목표

| 손절 | 위치 |
| --- | --- |
| `or-mid` | 개장 범위 중간 (orb 기본값) |
| `or-low` | 개장 범위 저가 (넓음, 수량이 줄어듦) |
| `bb-mid` | 신호 봉의 5분봉 볼린저 중심선 (bb 기본값) |
| `pct:X` | 진입가 대비 -X% |
| `price:X` | 직접 지정 |

- 손절은 **호가 단위로 내림**, 목표는 진입가 + R x 손절폭을 반올림한다. 호가 단위는 근사치다 (USDT 는 가격의 약 0.0001%).
- 거부: 손절이 진입가 이상, 목표가 진입가 이하, 손절폭이 2틱 또는 0.1% 미만.
- 왕복 비용이 손절폭의 25% 이상이면 경고한다 (코인처럼 범위가 좁을 때 흔하다).
- 청산 시한: 진입 후 6시간 (`crypto_hold_hours`).

## 수량 (원화)

가장 작은 한도가 수량을 정한다.

| 한도 | 계산 |
| --- | --- |
| 최대 손실 | 1회 최대 손실 ÷ (손절폭 + 매수 비용 + 매도 비용) |
| 종목당 상한 | 계좌 x 75% ÷ 진입가 |
| 현금 | 계좌 x 90% ÷ (진입가 + 매수 비용) |

- 수수료 기본값: 바이낸스 0.10%, 업비트 0.05% (매수·매도 각각).
- 바이낸스 USDT 가격은 원/USDT 환율로 원화로 바꿔 계산한다: `usdkrw` 설정값 → 업비트 KRW-USDT 체결가 → Yahoo 환율 순.
  USDT 수량은 소수점 6자리(근사), 원화 코인은 8자리.
  수수료는 등급·BNB 할인에 따라 다르니 `crt/config.json` 에서 고친다.
- 최소 주문 5,000원.
- 거부: 0주, 최소 주문 미만, 손익비(비용 전)가 `min_reward_risk` 미만.
- 비용 포함 손익비도 보여 준다. 1R 목표는 비용을 빼면 1보다 작다.

## 설정 (`crt/config.json`, 없으면 기본값)

`budget_krw`, `max_loss_per_trade_krw`, `cash_buffer_pct`, `max_position_pct`, `min_order_krw`,
`min_reward_risk`, `or_minutes`, `bb_period`, `bb_k`,
`crypto_fee_pct`, `binance_fee_pct`, `usdkrw`, `crypto_source`, `crypto_hold_hours`. 예시는 `config.example.json`.
계좌 금액과 최대 손실은 사용자가 말한 값만 쓴다. 추측하지 않는다.

## 순서

1. `/coin-scanner` 결과 중 사용자가 고른 종목으로 `entry --write`. 대기면 대기라고 말한다.
2. 신호가 나면 `exit --write`. 진입, 손절과 그 규칙, 목표와 그 규칙, 위험, 손익비, 청산 시한을 읽어 준다.
3. `/coin-timemachine` 으로 확률을 본 뒤 `size --write`. 네 줄 한도, 결정한 한도, 수량, 금액, 손절 시 손실을 말한다.
4. 손절은 보장이 아니라고 한 번 말한다. 주문은 사용자가 거래소 앱에서 직접 판단한다.

## 하지 않는 것

주문을 내지 않는다. 손절을 넓혀서 계획을 통과시키지 않는다. 공매도·신용·레버리지
계획을 세우지 않는다. 수익을 약속하거나 예측하지 않는다. 투자 조언이 아니다.
