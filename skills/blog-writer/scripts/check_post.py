#!/usr/bin/env python3
"""블로그 본문(마크다운) SEO·문체 검사. 파이썬 표준 라이브러리만 사용.

사용 예:
  python3 check_post.py post.md --keyword "볼린저 밴드" --sub "볼린저 밴드 매매법" --meta "메타 설명"

키워드 밀도 = (메인 키워드 등장 횟수 x 키워드 글자 수) / 본문 글자 수 x 100
(글자 수는 모두 공백 제외, 마크다운 기호 제외)
"""
import argparse
import re
import sys

FORMAL = re.compile(r"(입니다|합니다|습니다|습니까|십시오)")
HYPE = ["무조건", "100%", "충격", "역대급", "절대 실패", "누구나 부자", "대박"]


def strip_md(text):
    text = re.sub(r"!\[([^\]]*)\]\([^)]*\)", "", text)  # 이미지는 본문 길이에서 제외
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"^#{1,6}\s*", "", text, flags=re.M)
    text = re.sub(r"^>\s*", "", text, flags=re.M)
    text = re.sub(r"[*_`|]", "", text)
    text = re.sub(r"^-{3,}$", "", text, flags=re.M)
    return text


def nospace_len(s):
    return len(re.sub(r"\s", "", s))


def count(text, kw):
    return len(re.findall(re.escape(kw).replace(r"\ ", r"\s*"), text))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--keyword", required=True, help="메인 키워드")
    ap.add_argument("--sub", action="append", default=[], help="서브 키워드 (여러 번 지정 가능)")
    ap.add_argument("--meta", default=None, help="메타 설명")
    a = ap.parse_args()

    raw = open(a.file, encoding="utf-8").read()
    lines = raw.splitlines()
    kw = a.keyword

    title = next((l[2:].strip() for l in lines if l.startswith("# ")), "")
    h2_idx = [i for i, l in enumerate(lines) if l.startswith("## ")]
    h2s = [lines[i][3:].strip() for i in h2_idx]
    alts = re.findall(r"!\[([^\]]*)\]", raw)

    # 첫 문단: 제목 다음 첫 번째 비어 있지 않은 문단
    body_after_title = raw.split("\n", 1)[1] if raw.startswith("# ") else raw
    paras = [p.strip() for p in re.split(r"\n\s*\n", body_after_title) if p.strip()]
    first_para = paras[0] if paras else ""

    # 각 H2 다음 첫 비어 있지 않은 줄이 '> 핵심' 으로 시작하는지
    missing_summary = []
    for i in h2_idx:
        nxt = next((l.strip() for l in lines[i + 1:] if l.strip()), "")
        if not nxt.startswith(">"):
            missing_summary.append(lines[i][3:].strip())

    plain = strip_md(raw)
    body_len = nospace_len(plain)
    body_len_sp = len(re.sub(r"\n+", "", plain))
    kw_n = count(plain, kw)
    density = kw_n * nospace_len(kw) / body_len * 100 if body_len else 0
    formal = FORMAL.findall(plain)
    hype = [h for h in HYPE if h in plain]
    all_kws = [kw] + a.sub

    checks = [
        ("본문 길이 (공백 제외 1,500~2,500자)", 1500 <= body_len <= 2500,
         f"{body_len}자 (공백 포함 약 {body_len_sp}자)"),
        ("H2 개수 (4~6개)", 4 <= len(h2s) <= 6, f"{len(h2s)}개"),
        ("제목에 메인 키워드", count(title, kw) > 0, title or "(H1 없음)"),
        ("첫 문단에 메인 키워드", count(first_para, kw) > 0, first_para[:40] + "…"),
        ("H2에 타겟 키워드", any(count(h, k) > 0 for h in h2s for k in all_kws),
         f"{sum(any(count(h, k) for k in all_kws) for h in h2s)}/{len(h2s)}개 포함"),
        ("키워드 밀도 (1~2%)", 1.0 <= density <= 2.0, f"{density:.2f}% ('{kw}' {kw_n}회)"),
        ("H2 첫 줄 핵심 요약", not missing_summary,
         "모두 있음" if not missing_summary else "누락: " + ", ".join(missing_summary)),
        ("이미지 alt에 키워드", bool(alts) and all(any(count(t, k) for k in all_kws) for t in alts),
         f"이미지 {len(alts)}개"),
        ("'~입니다' 체 없음", not formal, "0회" if not formal else f"{len(formal)}회"),
        ("과장 표현 없음", not hype, "없음" if not hype else ", ".join(hype)),
    ]
    if a.meta is not None:
        checks.append(("메타 설명 (155자 이내)", len(a.meta) <= 155 and count(a.meta, kw) > 0,
                       f"{len(a.meta)}자, 키워드 {'포함' if count(a.meta, kw) else '없음'}"))

    for k in a.sub:
        print(f"[정보] 서브 키워드 '{k}': {count(plain, k)}회")
    ok_all = True
    for name, ok, detail in checks:
        ok_all &= ok
        print(f"{'✅' if ok else '❌'} {name}: {detail}")
    if formal:
        for m in sorted({l.strip() for l in plain.splitlines() if FORMAL.search(l)})[:5]:
            print(f"    · {m[:60]}")
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
