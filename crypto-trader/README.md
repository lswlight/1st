# crypto-trader: 코인 트레이딩 분석 스킬

바이낸스 현물 USDT 가격을 기준으로 코인 차트를 분석하는 Claude 스킬 5개입니다.
업비트 원화 가격은 실제 매수가와 김치 프리미엄을 볼 때 씁니다.
**분석 전용**이라 주문 기능은 없습니다. 볼린저 밴드 셋업이 중심입니다.
국내 주식은 [`kr-trader/`](../kr-trader/README.md)에 따로 있습니다.

표준 라이브러리 파이썬만 쓰고(파이썬 3.9 이상), API 키가 필요 없습니다.

## 스킬 5개

| 명령 | 하는 일 |
| --- | --- |
| `/coin-signal` | 볼린저 단기(4시간봉)·중기(일봉) 매수 타점: 스퀴즈 돌파·중심선 눌림·하단 복귀. 청산 두 가지(고정 2R, 중심선 이탈 추적)의 이 코인 성적과 30개 합산 검증 등급, 보유 관리(`manage`), 스퀴즈 돌파 알람(`alert.py`, 진입 범위 포함), 검증실(`lab.py`) |
| `/coin-scanner` | 일봉 셋업(볼린저 스퀴즈 돌파·스퀴즈 진행·하단 복귀, 20일 신고가, 20일선 회복, 과매도), 09:00~09:15 범위 고점 돌파. 바이낸스 거래대금 상위 30개 |
| `/coin-plan` | 진입 신호 확인, 손절·목표, 원화 계좌 기준 수량 (USDT 가격을 업비트 USDT 원화가로 환산, 수수료 포함), 김치 프리미엄 |
| `/coin-timemachine` | 계획을 최근 60일 5분봉으로 1만 번 재현 + 같은 규칙의 과거 실제 결과 |
| `/coin-news` | CoinGecko·CoinMarketCap 뉴스를 등락과 함께 정리 (연결된 도구 사용, 코드 없음) |

```
단기·중기: coin-signal now/scan -> coin-news -> coin-plan exit --entry ... -> coin-plan size
데이트레이딩(5분봉): coin-scanner orb -> coin-plan entry -> coin-plan exit -> coin-timemachine -> coin-plan size
```

## 폴더 구조

```
crypto-trader/
├── lib/crt.py                  # 공통: 바이낸스·업비트 데이터, 볼린저·RSI, 호가 근사, 환율·김치 프리미엄
├── coin-signal/points.py       # now / scan / manage / backtest (단기·중기 타점)
├── coin-signal/alert.py        # 스퀴즈 돌파 알람 (확정·진행 중, 진입 범위)
├── coin-signal/lab.py          # 여러 코인 합산 검증 -> coin-signal/evidence.json, 진입 방식 비교
├── coin-scanner/               # scan.py, 종목 목록(universe-crypto.txt 바이낸스, universe-upbit.txt 업비트)
├── coin-plan/plan.py           # entry / exit / size / show
├── coin-timemachine/tm.py      # run
├── coin-news/SKILL.md          # 코인 뉴스 도구 사용법
├── config.example.json         # 설정 예시 (crt/config.json 으로 복사)
└── tests/                      # 오프라인 테스트 + 실제 바이낸스·Yahoo 데이터 조각
```

이 저장소에서 Claude Code를 열면 `.claude/skills/coin-*` 링크로 바로 `/coin-signal` 처럼 쓸 수 있습니다.
결과와 개인 설정은 현재 폴더의 `crt/` 에 저장되고, `crt/` 는 git에 올라가지 않습니다.

## 빠른 사용 예

```bash
python3 crypto-trader/coin-signal/points.py now SOL                  # 단기+중기 타점 (SOLUSDT)
python3 crypto-trader/coin-signal/points.py scan --tf mid            # 30개 중기 타점
python3 crypto-trader/coin-signal/points.py manage SOL --entry 96 --stop 92   # 보유 중: 유지/정리 신호
python3 crypto-trader/coin-signal/points.py backtest BTC --tf both   # 셋업·청산별 과거 성적
python3 crypto-trader/coin-signal/alert.py check                     # 스퀴즈 돌파 알람 (새 것만)
python3 crypto-trader/coin-signal/lab.py run --tf 1d,4h --evidence   # 합산 검증 (첫 실행은 데이터 받느라 수 분)
python3 crypto-trader/coin-scanner/scan.py daily                     # 일봉 셋업 스캔
python3 crypto-trader/coin-plan/plan.py exit --symbol SOL --entry 96.02 --or-high 95.78 --or-low 95.11 --entry-time 12:10
python3 crypto-trader/coin-plan/plan.py size --budget 5000000 --max-loss 50000
```

```bash
python3 -m unittest discover -s crypto-trader/tests -v
```

## 지금까지 검증한 것 (2026-10-30, 바이낸스 상위 30개 합산, 수수료 후)

| 셋업 · 청산 | 일봉 4년 | 4시간봉 2년 | 1시간봉 1년 |
| --- | --- | --- | --- |
| 스퀴즈 돌파 · 고정 2R | 195회, 적중 43%, **+0.21R** (뒤 기간 +0.21R) | -0.14R | -0.12R |
| 스퀴즈 돌파 · 추적 | 195회, 적중 35%, **+0.91R** (뒤 기간 +0.99R) | 778회, **+0.30R** | -0.01R |
| 중심선 눌림 · 추적 | +0.53R (ZEC 한 코인 의존) | +0.04R | -0.18R |
| 하단 복귀 | -0.02R | -0.10R | -0.23R |

- 추적 청산은 수익이 큰 몇 건에 몰린다: 일봉 스퀴즈 추적에서 상위 10건을 빼면 평균 +0.05R, 대형 코인 10개만 보면 +0.47R.
- 가장 고르게 통한 것은 **일봉 스퀴즈 돌파 · 고정 2R** (상위 10건을 빼도 +0.12R, 대형 10개만 +0.21R).
- 진입은 돌파 봉 종가에 바로 사는 쪽이 되돌림 지정가보다 신호당 성적이 좋았다 (되돌림을 기다리면 크게 오르는 신호를 놓친다).
- 1시간봉은 모두 수수료에 져서 단기 시간축을 4시간봉으로 바꿨다. BTC 추세 필터는 효과가 일정하지 않았다.
- 지금 거래대금 상위 코인으로만 본 결과라 생존 편향이 있고, 앞으로도 같다는 보장은 없다. 자세한 수치는 `coin-signal/evidence.json`.

## 알아둘 점

- **데이터원:** 기본은 바이낸스 현물 USDT(`BTCUSDT`)입니다. `BTC-KRW` 처럼 쓰면 업비트 원화 체결가를 씁니다.
  바이낸스 본 API(`api.binance.com`)는 서버 위치를 막아(451) 시세 전용 주소 `data-api.binance.vision` 을 씁니다.
  이 주소와 `api.upbit.com` 이 네트워크 허용 목록에 있어야 합니다. 업비트는 요청당 약 1초라 느립니다.
- **하루의 기준:** 09:00 KST(00:00 UTC)에 하루가 바뀝니다 (바이낸스·업비트 일봉과 같음).
- **수수료:** 기본값은 바이낸스 0.10%, 업비트 0.05%입니다. `crt/config.json` 에서 맞추세요.
- **호가·수량 단위:** 근사치입니다. 실제 주문 단위는 거래소 앱 기준으로 맞추세요.
- **조언 아님:** 모든 결과는 규칙 확인일 뿐 투자 조언이 아닙니다.
