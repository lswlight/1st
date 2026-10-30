# kr-trader: 국내 주식·코인 데이트레이딩 분석 스킬

미국 주식용 [day-trader-skill](https://github.com/Jakeschincariol/day-trader-skill)(MIT)의 흐름을
국내 주식(코스피·코스닥)과 코인에 맞게 새로 만든 Claude 스킬 5개입니다.
**분석 전용**이라 주문 기능은 없습니다. 볼린저 밴드 셋업을 기본으로 넣었습니다.

표준 라이브러리 파이썬만 쓰고(파이썬 3.9 이상), API 키가 필요 없습니다. 데이터는 국내 주식은 Yahoo 무료 차트, 코인은 바이낸스 시세 API(기본)와 업비트 공개 API(원화·김치 프리미엄)입니다.

## 스킬 5개

| 명령 | 하는 일 |
| --- | --- |
| `/kr-scanner` | 장 전 일봉 셋업(볼린저 스퀴즈 돌파·스퀴즈 진행·하단 복귀, 20일 신고가, 20일선 회복, 과매도), 장중 개장 15분 고점 돌파. 국내 145개·코인(바이낸스 USDT 거래대금 상위 30개, 업비트 원화 목록도 있음) |
| `/kr-news` | 국내는 OpenDART 공시, 코인은 CoinGecko·CoinMarketCap 뉴스를 등락과 함께 정리 (연결된 도구 사용, 코드 없음) |
| `/kr-plan` | 진입 신호 확인(범위 돌파·5분봉 볼린저 상단 돌파·가격 레벨), 손절·목표(호가 단위), 원화 계좌 기준 수량(수수료·거래세 포함, USDT는 원화 환산) |
| `/kr-timemachine` | 계획을 최근 60일 5분봉으로 1만 번 재현 + 같은 규칙의 과거 실제 결과 |
| `/kr-signal` | 볼린저 단기(1시간봉)·중기(일봉) 매수 타점: 스퀴즈 돌파·중심선 눌림·하단 복귀, 진입·손절·목표와 그 종목의 같은 신호 과거 성적(비용 후 R) |

한 번의 거래 흐름:

```
데이트레이딩(5분봉): kr-scanner -> kr-news -> kr-plan entry -> kr-plan exit -> kr-timemachine -> kr-plan size
단기·중기(1시간봉·일봉): kr-signal now/scan -> kr-news -> kr-plan exit --entry ... -> kr-plan size
```

## 폴더 구조

```
kr-trader/
├── lib/krt.py                  # 공통: 시장 정의, Yahoo·바이낸스·업비트 데이터, 볼린저·RSI, 호가 단위
├── kr-scanner/                 # scan.py, 종목 목록(universe-*.txt), 후보(candidates-*.txt)
├── kr-plan/plan.py             # entry / exit / size / show
├── kr-timemachine/tm.py        # run
├── kr-signal/points.py         # now / scan / backtest (단기·중기 타점)
├── kr-news/SKILL.md            # 연결된 공시·뉴스 도구 사용법
├── config.example.json         # 설정 예시 (krt/config.json 으로 복사)
└── tests/                      # 오프라인 테스트 + 실제 Yahoo 데이터 조각
```

이 저장소에서 Claude Code를 열면 `.claude/skills/kr-*` 링크로 바로 `/kr-scanner` 처럼 쓸 수 있습니다.
결과와 개인 설정은 현재 폴더의 `krt/` 에 저장되고, `krt/` 는 git에 올라가지 않습니다.

## 빠른 사용 예

```bash
python3 kr-trader/kr-scanner/scan.py daily                          # 국내 장 전 스캔
python3 kr-trader/kr-scanner/scan.py orb                            # 국내 09:20 이후
python3 kr-trader/kr-scanner/scan.py daily --market crypto          # 코인
python3 kr-trader/kr-plan/plan.py entry 005930 --write              # 진입 신호
python3 kr-trader/kr-plan/plan.py exit --stop or-mid --target 1R --write
python3 kr-trader/kr-timemachine/tm.py run
python3 kr-trader/kr-plan/plan.py size --budget 5000000 --max-loss 50000
python3 kr-trader/kr-signal/points.py now SOL                       # 단기+중기 타점
python3 kr-trader/kr-signal/points.py scan --market krx --tf mid    # 국내 중기 타점 스캔
```

```bash
python3 -m unittest discover -s kr-trader/tests -v
```

## 알아둘 점

- **데이터원:** 국내 주식은 Yahoo 무료 데이터라 실시간이 아니고 비공식입니다. 코인은 바이낸스 현물 USDT 실제 체결가가 기본이고(`BTCUSDT`), `BTC-KRW` 처럼 쓰면 업비트 원화 체결가를 씁니다. 업비트는 요청당 약 1초라 느립니다.
- **국내 5분봉 시간대:** 09:00~14:55 봉만 있어서 기본 청산 시한을 14:50으로 둡니다.
- **접미사 확인:** Yahoo는 접미사가 틀린 종목(`005930.KQ` 등)에도 엉뚱한 값을 줍니다. 그래서 거래소 코드로 확인해 걸러냅니다.
- **코인의 하루:** 업비트 일봉과 같이 09:00 KST에 시작합니다. 개장 15분은 09:00~09:15입니다.
- **세율과 수수료:** 기본값은 증권거래세 0.20%, 수수료 0.015%, 코인 수수료 0.05%입니다. 실제 값은 증권사와 시기마다 다르니 `krt/config.json` 에서 맞추세요.
- **휴장일:** 따로 달력을 넣지 않았습니다. 데이터가 없는 날은 "데이터 없음"으로 알려줍니다.
- **막힌 데이터원:** KRX와 네이버 API는 이 클라우드 환경의 네트워크에서 막혀 있습니다. 바이낸스 본 API(`api.binance.com`)는 서버 위치를 막아(451) 시세 전용 주소 `data-api.binance.vision` 을 씁니다. 이 주소와 `api.upbit.com` 이 네트워크 허용 목록에 있어야 합니다.
- **조언 아님:** 모든 결과는 규칙 확인일 뿐 투자 조언이 아닙니다. 데이트레이딩은 대부분의 사람에게 손실을 안깁니다.
