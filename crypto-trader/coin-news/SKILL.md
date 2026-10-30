---
name: coin-news
description: 코인 뉴스와 재료를 정리한다. CoinGecko·CoinMarketCap 뉴스, 거래소 상장·상장폐지·유의 지정, 해킹, 규제, 거시 일정(FOMC·CPI)을 보고 오늘 등락과 나란히 보여 주며 차트 셋업에 영향을 줄 재료를 짚는다. 사용자가 "코인 뉴스", "왜 올랐어", "왜 빠졌어", "재료", "비트코인 뉴스", "상장", "상폐", "유의 종목" 등을 말하거나 coin-scanner·coin-signal 결과에서 코인을 고른 뒤에 쓴다. 국내 주식은 kr-news.
---

# coin-news

차트가 좋아 보여도 상장폐지 공지나 해킹 소식 하나로 급락할 수 있다. 셋업이 거래가 되기 전에
그런 재료를 먼저 읽는다. 이 스킬은 코드가 없다. 연결된 도구를 쓴다.

## 도구 (ToolSearch 로 불러온다)

| 대상 | 도구 |
| --- | --- |
| 뉴스 | CoinGecko `get-crypto-news`, CoinMarketCap `get_crypto_latest_news` |
| 시세·등락 | CoinGecko `get-coin-markets`, Crypto.com `get_ticker`, 또는 `coin-plan entry` 의 현재가 |
| 서사·테마 | CoinMarketCap `trending_crypto_narratives`, CoinGecko `get-trending`, `get-categories` |
| 거시 일정 | CoinMarketCap `get_upcoming_macro_events` |
| 업비트 유의 | `https://api.upbit.com/v1/market/all?isDetails=true` 의 `market_event` (warning, caution) |

연결이 끊긴 도구는 그렇다고 말하고, 지어내지 않는다.

## 규칙

- 거래소 공지(상장·상장폐지·유의)와 프로젝트 공식 발표가 기사보다 우선한다.
- 중요 재료를 먼저: 상장폐지·유의 지정, 해킹·출금 중단, 규제·소송, 토큰 언락, 대형 상장, ETF·거시 이벤트.
- 3% 이상 움직인 코인은 재료가 있든 없든 말한다. 없으면 "확인된 재료 없음".
- 기사 본문을 못 읽었으면 요약만 봤다고 말한다. 예측하지 않는다. 뉴스를 근거로 매수·매도를 권하지 않는다.

## 순서

1. 코인을 정한다 (사용자 지정, 또는 `crt/watchlist.json` 의 hits).
2. 최근 24시간 뉴스와 등락, 업비트 유의 여부를 본다.
3. 코인별로 짧게: 무슨 일, 출처, 오늘 등락, 셋업에 미치는 영향 한 줄.
4. 그림을 바꾸는 재료가 있으면 `/coin-plan` 전에 먼저 말한다.
