// 자동가계부 가족 공유 서버 (Cloudflare Workers + D1, 파일 하나, 외부 라이브러리 없음)
//
// 앱이 [가족 공유 시작하기]를 누르면 '가족 방'을 하나 만들고, 그 뒤 결제 내역을 한 건씩 올린다.
// 가족은 보기 링크(/v/<보기 토큰>)를 열면 앱 없이도 웹에서 바로 본다.
//
//   POST   /api/households          가족 방 만들기 → { writeKey, viewToken }
//   DELETE /api/households          가족 방과 모든 내역 삭제 (쓰기 키)
//   POST   /api/entries             내역 올리기/고치기/지우기 (쓰기 키)
//   GET    /api/entries?from=&to=   그 기간 내역 (쓰기 키 또는 ?v=보기 토큰)
//   GET    /v/<보기 토큰>?m=YYYY-MM  가족 가계부 웹 화면
//   GET    /                        회사(GRAFT) 첫 화면
//   GET    /health                  서버 동작 확인
//   GET    /app                     앱 소개·다운로드 페이지 (카톡으로 보내는 주소)
//   GET    /privacy                 개인정보처리방침 (플레이스토어 등록용, #delete 는 데이터 삭제 안내)
//
// 쓰기 키는 SHA-256 해시로만 저장한다. 보기 토큰은 링크에 들어가는 무작위 문자열이다.
// D1 데이터베이스를 변수 이름 DB 로 연결해야 한다 (표는 처음 요청 때 자동으로 만든다).

const MAX_ENTRIES_PER_REQUEST = 500;
const MAX_TEXT = 200;

export default {
  async fetch(request, env) {
    try {
      await ensureSchema(env.DB);
      return await route(request, env.DB);
    } catch (e) {
      return json({ ok: false, error: String(e && e.message || e) }, 500);
    }
  },
};

let schemaReady = false;
async function ensureSchema(db) {
  if (schemaReady) return;
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS households (
      id TEXT PRIMARY KEY, write_hash TEXT NOT NULL UNIQUE, view_token TEXT NOT NULL UNIQUE, created INTEGER NOT NULL)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS entries (
      household TEXT NOT NULL, uid TEXT NOT NULL, owner TEXT NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL,
      memo TEXT NOT NULL, card TEXT NOT NULL, time INTEGER NOT NULL, updated INTEGER NOT NULL,
      PRIMARY KEY (household, uid))`),
    db.prepare(`CREATE INDEX IF NOT EXISTS idx_entries_time ON entries (household, time)`),
  ]);
  schemaReady = true;
}

async function route(request, db) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  if (method === 'OPTIONS') return new Response(null, { headers: cors() });
  if (path === '/health' && method === 'GET') return text('자동가계부 가족 공유 서버가 동작 중입니다.');
  if (path === '/' && method === 'GET') return html(homePage(), 200, true);

  if (path === '/api/households' && method === 'POST') {
    const id = randomToken(12), writeKey = randomToken(32), viewToken = randomToken(22);
    await db.prepare('INSERT INTO households (id, write_hash, view_token, created) VALUES (?, ?, ?, ?)')
      .bind(id, await sha256(writeKey), viewToken, Date.now()).run();
    return json({ ok: true, writeKey, viewToken });
  }

  if (path === '/api/households' && method === 'DELETE') {
    const h = await byWriteKey(db, request);
    if (!h) return json({ ok: false, error: '쓰기 키가 맞지 않아요' }, 401);
    await db.batch([
      db.prepare('DELETE FROM entries WHERE household = ?').bind(h.id),
      db.prepare('DELETE FROM households WHERE id = ?').bind(h.id),
    ]);
    return json({ ok: true });
  }

  if (path === '/api/entries' && method === 'POST') {
    const h = await byWriteKey(db, request);
    if (!h) return json({ ok: false, error: '쓰기 키가 맞지 않아요' }, 401);
    const body = await request.json();
    const list = Array.isArray(body.entries) ? body.entries : [];
    if (list.length > MAX_ENTRIES_PER_REQUEST) return json({ ok: false, error: '한 번에 너무 많아요' }, 413);
    const now = Date.now();
    const stmts = list.filter(e => e && typeof e.uid === 'string' && e.uid).map(e => e.deleted
      ? db.prepare('DELETE FROM entries WHERE household = ? AND uid = ?').bind(h.id, e.uid.slice(0, 64))
      : db.prepare(`INSERT INTO entries (household, uid, owner, kind, amount, memo, card, time, updated)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (household, uid) DO UPDATE SET owner = excluded.owner, kind = excluded.kind,
                    amount = excluded.amount, memo = excluded.memo, card = excluded.card, time = excluded.time, updated = excluded.updated`)
          .bind(h.id, e.uid.slice(0, 64), clip(e.owner || '가족'), e.kind === 'INCOME' ? 'INCOME' : 'EXPENSE',
            Math.round(Number(e.amount) || 0), clip(e.memo), clip(e.card), Math.round(Number(e.time) || 0), now));
    if (stmts.length) await db.batch(stmts);
    return json({ ok: true, count: stmts.length });
  }

  if (path === '/api/entries' && method === 'GET') {
    const h = url.searchParams.get('v') ? await byViewToken(db, url.searchParams.get('v')) : await byWriteKey(db, request);
    if (!h) return json({ ok: false, error: '공유 정보가 맞지 않아요' }, 401);
    const from = Number(url.searchParams.get('from') || 0), to = Number(url.searchParams.get('to') || 9e15);
    return json({ ok: true, entries: await entries(db, h.id, from, to) });
  }

  if ((path === '/app' || path === '/app/') && method === 'GET') return html(appPage(), 200, true);
  if ((path === '/privacy' || path === '/privacy/') && method === 'GET') return html(privacyPage(), 200, true);

  const view = path.match(/^\/v\/([A-Za-z0-9_-]{10,64})$/);
  if (view && method === 'GET') {
    const h = await byViewToken(db, view[1]);
    if (!h) return html('<p style="font:18px sans-serif;padding:24px">링크가 올바르지 않거나 공유가 꺼졌어요.</p>', 404);
    return html(await page(db, h, view[1], url.searchParams.get('m')));
  }

  return json({ ok: false, error: '없는 주소예요' }, 404);
}

// ---------------------------------------------------------------- 저장소

async function byWriteKey(db, request) {
  const auth = request.headers.get('Authorization') || '';
  const key = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!key) return null;
  return db.prepare('SELECT id, view_token FROM households WHERE write_hash = ?').bind(await sha256(key)).first();
}

async function byViewToken(db, token) {
  return db.prepare('SELECT id, view_token FROM households WHERE view_token = ?').bind(token).first();
}

async function entries(db, household, from, to) {
  const r = await db.prepare(`SELECT uid, owner, kind, amount, memo, card, time FROM entries
                              WHERE household = ? AND time >= ? AND time < ? ORDER BY time DESC`)
    .bind(household, from, to).all();
  return r.results || [];
}

// ---------------------------------------------------------------- 웹 화면 (파스텔 라이프)

const TZ = 'Asia/Seoul';

function kst(t) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: TZ, year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', weekday: 'short',
  }).formatToParts(new Date(t)).map(x => [x.type, x.value]));
  return { y: Number(p.year), m: Number(p.month), d: Number(p.day), hm: p.hour + ':' + p.minute,
           wd: ['일', '월', '화', '수', '목', '금', '토'][['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].indexOf(p.weekday)] };
}

function won(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '원'; }
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

function icon(x) {
  const m = x.memo.toLowerCase();
  const has = (...k) => k.some(w => m.includes(w.toLowerCase()));
  if (x.amount < 0) return '↩️';
  if (x.memo.startsWith('카드 혜택')) return '🎁';
  if (x.kind === 'INCOME') return '💰';
  if (x.memo.includes('추정)')) return '🌐';
  if (has('카페', '커피', '스타벅스', '투썸', '이디야', '메가커피', '빽다방', '컴포즈')) return '☕';
  if (has('주유', '오일', '칼텍스', '에너지')) return '⛽';
  if (has('관리비', 'apt', '아파트')) return '🏠';
  if (has('의원', '병원', '약국', '치과')) return '🏥';
  if (has('넷플릭스', 'netflix', 'youtube', '유튜브', '멜론', '디즈니', '티빙')) return '🎬';
  if (has('마트', '편의점', 'gs25', '쿠팡', '다이소', '코스트코')) return '🛒';
  if (has('식당', '김밥', '돈까스', '치킨', '피자', '버거', '배달', '국밥', '낙지')) return '🍽';
  return '💳';
}

async function page(db, h, token, mParam) {
  const now = kst(Date.now());
  const ym = /^\d{4}-\d{2}$/.test(mParam || '') ? mParam : `${now.y}-${String(now.m).padStart(2, '0')}`;
  const y = Number(ym.slice(0, 4)), m = Number(ym.slice(5, 7));
  const from = Date.UTC(y, m - 1, 1) - 9 * 3600e3, to = Date.UTC(y, m, 1) - 9 * 3600e3;
  const list = await entries(db, h.id, from, to);
  const pad = n => String(n).padStart(2, '0');
  const prev = m === 1 ? `${y - 1}-12` : `${y}-${pad(m - 1)}`;
  const next = m === 12 ? `${y + 1}-01` : `${y}-${pad(m + 1)}`;
  const base = `/v/${token}?m=`;

  let inc = 0, exp = 0;
  const owners = {}, cards = {};
  for (const x of list) {
    const o = owners[x.owner] || (owners[x.owner] = { inc: 0, exp: 0 });
    if (x.kind === 'INCOME') { inc += x.amount; o.inc += x.amount; } else { exp += x.amount; o.exp += x.amount; }
    const benefit = x.kind === 'INCOME' && x.memo.startsWith('카드 혜택');
    if (x.kind === 'EXPENSE' || benefit) {
      const c = x.card || '카드 정보 없음';
      cards[c] = (cards[c] || 0) + (x.kind === 'EXPENSE' ? x.amount : -x.amount);
    }
  }
  const left = inc - exp;
  const ownerHtml = Object.keys(owners).map(k =>
    `<div class="row"><b>${esc(k)}</b><span><i class="g">+${won(owners[k].inc)}</i> · <i>-${won(owners[k].exp)}</i></span></div>`).join('');
  const cardHtml = Object.keys(cards).sort((a, b) => cards[b] - cards[a]).map(k =>
    `<div class="row"><span>💳 ${esc(k)}</span><b class="c">${won(cards[k])}</b></div>`).join('') || '<p class="sub">카드 사용 내역이 없어요.</p>';

  let listHtml = '', last = '';
  for (const x of list) {
    const t = kst(x.time);
    const day = `${t.m}월 ${t.d}일 (${t.wd})`;
    if (day !== last) { listHtml += `<h3>${day}</h3>`; last = day; }
    const sign = x.kind === 'INCOME' ? '+' : (x.amount < 0 ? '취소 ' : '-');
    const cls = x.kind === 'INCOME' ? 'g' : (x.amount < 0 ? 'sub' : '');
    listHtml += `<div class="it"><div class="ic">${icon(x)}</div><div class="m"><div class="n">${esc(x.memo)}</div>` +
      `<div class="sub">${[t.hm, esc(x.owner), esc(x.card)].filter(Boolean).join(' · ')}</div></div><b class="${cls}">${sign}${won(x.amount)}</b></div>`;
  }
  if (!listHtml) listHtml = '<p class="sub" style="text-align:center;padding:24px 0">이번 달 내역이 없어요.</p>';
  const head = left >= 0 ? `${m}월은 <em>${won(left)}</em><br>남았어요 🌿` : `${m}월은 <em class="c">${won(left)}</em><br>더 썼어요 🍂`;

  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-title" content="가족 가계부">
<meta name="theme-color" content="#FBF7F0"><meta name="robots" content="noindex">
<title>가족 가계부</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR",sans-serif;background:#FBF7F0;color:#2D2A26}
.w{max-width:560px;margin:0 auto;padding:18px 18px 40px}
.top{display:flex;justify-content:space-between;align-items:center;color:#9C9283;font-size:13px}
.nav{display:flex;gap:6px;align-items:center}.nav a{background:#EFE7DA;color:#2D2A26;text-decoration:none;padding:7px 12px;border-radius:99px;font-weight:700}
.nav b{color:#2D2A26;font-size:14px;padding:0 4px}
.big{font-size:24px;font-weight:800;line-height:1.35;margin:10px 0 14px}.big em{font-style:normal;color:#2F9E6E}em.c{color:#E0705C}
.tiles{display:flex;gap:10px}.tile{flex:1;border-radius:20px;padding:12px}.tile span{font-size:12px;color:#6F665A}.tile b{display:block;font-size:16px;margin-top:4px}
.t1{background:#DFF3E8}.t2{background:#FFE5DF}
.card{background:#fff;border-radius:22px;padding:6px 16px;margin-top:14px}
.row{display:flex;justify-content:space-between;gap:8px;padding:9px 0;font-size:15px;border-bottom:1px solid #F4EEE4}.row:last-child{border:0}.row i{font-style:normal}
h2{font-size:16px;margin:20px 4px 0}h3{font-size:12.5px;color:#9C9283;margin:14px 0 2px}
.it{display:flex;align-items:center;gap:12px;padding:9px 0}.ic{width:40px;height:40px;border-radius:14px;background:#F3EEE6;display:grid;place-items:center;font-size:18px;flex:none}
.m{flex:1;min-width:0}.n{font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.sub{color:#9C9283;font-size:12px;margin-top:2px}.g{color:#2F9E6E}.c{color:#E0705C}
.re{display:block;text-align:center;background:#2F9E6E;color:#fff;text-decoration:none;padding:13px;border-radius:99px;font-weight:700;margin-top:16px}
.foot{color:#B3A897;font-size:11.5px;text-align:center;margin-top:18px}
</style></head><body><div class="w">
<div class="top"><span>가족 가계부</span><div class="nav"><a href="${base}${prev}">‹</a><b>${y}년 ${m}월</b><a href="${base}${next}">›</a></div></div>
<div class="big">${head}</div>
<div class="tiles"><div class="tile t1"><span>수입</span><b>${won(inc)}</b></div><div class="tile t2"><span>지출</span><b>${won(exp)}</b></div></div>
${ownerHtml ? `<h2>사람별</h2><div class="card">${ownerHtml}</div>` : ''}
<h2>카드별 실제 지출</h2><div class="card">${cardHtml}</div>
<h2>내역</h2><div class="card">${listHtml}</div>
<a class="re" href="${base}${ym}">새로고침</a>
<p class="foot">${now.m}/${now.d} ${now.hm} 기준 · 자동가계부<br>아이폰: 공유 버튼 → 홈 화면에 추가 하면 앱처럼 열 수 있어요</p>
</div></body></html>`;
}

// ---------------------------------------------------------------- 소개 · 개인정보처리방침

const APP_VERSION = '2.6';
const APK_URL = 'https://github.com/lswlight/1st/releases/download/moneybook-latest/moneybook.apk';
const IMG = 'https://raw.githubusercontent.com/lswlight/1st/40687aad10bc002e3649185d854fe9916907e48f/expense-app/docs/';
const CONTACT = 'graft.korea@gmail.com';
const POLICY_DATE = '2026년 11월 4일';

const PAGE_CSS = `*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR",sans-serif;background:#FBF7F0;color:#2D2A26;line-height:1.6;word-break:keep-all}
.w{max-width:640px;margin:0 auto;padding:22px 18px 48px}
a{color:#2F9E6E}
h1{font-size:28px;line-height:1.3;margin:8px 0 10px}h2{font-size:19px;margin:30px 0 10px}h3{font-size:15.5px;margin:16px 0 6px}
p,li{font-size:15px;color:#4A443C}ul,ol{padding-left:20px}li{margin:4px 0}
.sub{color:#9C9283;font-size:13px}
.card{background:#fff;border-radius:22px;padding:16px 18px;margin-top:12px}
.btn{display:block;text-align:center;background:#2F9E6E;color:#fff;text-decoration:none;padding:15px;border-radius:99px;font-weight:800;font-size:17px;margin-top:16px}
.btn2{background:#EFE7DA;color:#2D2A26}
.tag{display:inline-block;background:#DFF3E8;color:#2F9E6E;font-weight:700;font-size:12.5px;padding:4px 10px;border-radius:99px}
.feat{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:12px}
.feat div{background:#fff;border-radius:18px;padding:12px 14px;font-size:13.5px;color:#6F665A}.feat b{display:block;font-size:15px;color:#2D2A26;margin-bottom:2px}
.shots{display:flex;gap:12px;overflow-x:auto;padding:4px 2px 10px;scroll-snap-type:x mandatory}
.shots figure{flex:none;width:200px;scroll-snap-align:start}.shots img{width:100%;border-radius:18px;box-shadow:0 2px 10px rgba(0,0,0,.08);display:block}
.shots figcaption{font-size:12.5px;color:#6F665A;text-align:center;margin-top:6px}
.guide img{width:100%;border-radius:16px;margin-top:10px;display:block}
table{width:100%;border-collapse:collapse;font-size:14px;margin-top:8px}th,td{border-bottom:1px solid #F0E9DD;padding:8px 6px;text-align:left;vertical-align:top}th{background:#F6F0E6;font-weight:700}
.foot{color:#B3A897;font-size:12px;text-align:center;margin-top:34px}.foot a{color:#9C9283}`;

function shell(title, desc, body) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#FBF7F0">
<title>${title}</title><meta name="description" content="${desc}">
<meta property="og:title" content="${title}"><meta property="og:description" content="${desc}">
<meta property="og:image" content="${IMG}screens/screen1-main-v2.png"><meta property="og:type" content="website">
<style>${PAGE_CSS}</style></head><body><div class="w">${body}
<p class="foot"><a href="/">GRAFT</a> · 자동가계부 · 문의 <a href="mailto:${CONTACT}">${CONTACT}</a><br><a href="/app">앱 소개</a> · <a href="/privacy">개인정보처리방침</a></p>
</div></body></html>`;
}

function homePage() {
  return shell('GRAFT — 생활을 편하게 만드는 작은 앱',
    'GRAFT 는 일상의 번거로운 일을 자동으로 정리해 주는 모바일 앱을 만듭니다.',
    `<span class="tag">GRAFT</span>
<h1>생활을 편하게 만드는<br>작은 앱을 만듭니다</h1>
<p>GRAFT 는 매일 반복되는 번거로운 일을 자동으로 정리해 주는 모바일 앱을 만드는 팀입니다. 회원가입 없이 바로 쓰고, 내 정보는 내 휴대폰에 두는 앱을 지향합니다.</p>

<h2>앱</h2>
<div class="card">
<h3>📒 자동가계부</h3>
<p>카드 결제 알림을 읽어 지출을 자동으로 기록하고, 달력·그래프·카드별로 정리해 주는 안드로이드 가계부. 가족과 링크 하나로 함께 볼 수 있어요.</p>
<a class="btn" href="/app">자동가계부 자세히 보기</a>
</div>
<div class="card">
<h3>🗂️ 앨범 박스 <span class="sub">준비 중</span></h3>
<p>갤러리 사진을 찍은 장소와 출처별로 자동으로 묶어 보여 주는 안드로이드 앱.</p>
</div>

<h2>회사 정보</h2>
<div class="card">
<p>상호: GRAFT<br>대표: SANGWOO LEE<br>이메일: <a href="mailto:${CONTACT}">${CONTACT}</a></p>
</div>`);
}

function appPage() {
  const shot = (f, c) => `<figure><img src="${IMG}screens/${f}" alt="${c}" loading="lazy"><figcaption>${c}</figcaption></figure>`;
  return shell('자동가계부 — 결제하면 알아서 기록되는 가계부',
    '카드 결제 알림을 읽어 지출을 자동으로 기록하고, 가족과 링크 하나로 함께 보는 안드로이드 가계부 앱',
    `<span class="tag">안드로이드 앱 · 버전 ${APP_VERSION}</span>
<h1>📒 결제하면<br>알아서 기록되는 가계부</h1>
<p>카드를 쓰면 휴대폰에 오는 결제 알림(문자·카카오톡·카드사·은행·페이 앱)을 읽어 지출을 자동으로 적어 줘요. 회원가입 없이 바로 시작해요.</p>
<a class="btn" href="${APK_URL}">⬇️ 앱 내려받기 (APK)</a>
<p class="sub" style="text-align:center;margin-top:8px">안드로이드 8.0 이상 · 아이폰 가족은 앱 없이 <a href="#family">가족 보기 링크</a>로 봐요</p>

<div class="feat">
<div><b>🤖 자동 기록</b>결제 알림이 오면 사용처·금액·카드를 바로 기록</div>
<div><b>📅 달력·그래프</b>날짜별·월별로 얼마나 썼는지 한눈에</div>
<div><b>💳 카드별 지출</b>결제 − 취소 − 혜택 = 실제 쓴 돈</div>
<div><b>👨‍👩‍👧 가족 가계부</b>버튼 한 번, 링크로 가족과 함께 보기</div>
<div><b>💱 해외 결제</b>환율과 수수료(기본 2%)를 반영해 원화로</div>
<div><b>🚫 똑똑한 구분</b>계좌이체·적립·광고는 빼고 결제만</div>
</div>

<h2>화면 미리보기</h2>
<div class="shots">
${shot('screen1-main-v2.png', '메인 — 이번 달 요약')}
${shot('screen2-calendar-v2.png', '달력 — 날짜별 지출')}
${shot('screen3-graph-v2.png', '그래프 — 일별·월별')}
${shot('screen4-cards-v2.png', '카드별 실제 지출')}
${shot('screen5-family-v3.png', '가족 공유 시작')}
${shot('screen6-family-web.png', '가족이 보는 웹 화면')}
</div>

<h2>설치 방법</h2>
<div class="card">
<ol>
<li>위의 <b>[앱 내려받기]</b>를 눌러 파일을 받아요.</li>
<li>받은 <b>moneybook.apk</b>를 눌러 설치해요. 플레이스토어 밖에서 받은 앱이라 경고가 나올 수 있어요.
<ul><li>"출처를 알 수 없는 앱" → <b>설정</b> → <b>이 출처 허용</b>을 켜고 돌아와 설치</li>
<li>"안전하지 않은 앱 차단됨" → <b>자세히</b> → <b>무시하고 설치</b></li></ul></li>
<li>앱을 열고 <b>알림 접근 허용</b>을 켜면 끝! 이제 결제하면 자동으로 기록돼요.</li>
</ol>
</div>
<div class="guide">
<img src="${IMG}install-guide-1.png" alt="설치 안내 1" loading="lazy">
<img src="${IMG}install-guide-2-1.png" alt="설치 안내 2" loading="lazy">
<img src="${IMG}install-guide-2-2.png" alt="설치 안내 3" loading="lazy">
<img src="${IMG}install-guide-3.png" alt="설치 안내 4" loading="lazy">
</div>

<h2 id="family">가족 가계부</h2>
<div class="card">
<h3>기록하는 사람 (안드로이드)</h3>
<ol><li>아래쪽 <b>가족</b> 탭 → <b>[가족 공유 시작하기]</b></li>
<li>만들어진 <b>보기 링크</b>를 카카오톡으로 가족에게 보내요.</li>
<li>이후 기록되는 내역은 자동으로 올라가요.</li></ol>
<h3>보는 사람 (아이폰·PC)</h3>
<ul><li>받은 링크를 열면 바로 보여요. 앱 설치·로그인이 필요 없어요.</li>
<li>아이폰은 사파리 공유 버튼 → <b>홈 화면에 추가</b>하면 앱처럼 열려요.</li></ul>
<h3>함께 기록하기 (안드로이드 가족)</h3>
<ul><li>공유 설정의 <b>초대 코드</b>를 받아 [받은 링크·초대 코드로 참여하기]에 붙여 넣어요.</li></ul>
</div>

<h2>자주 묻는 질문</h2>
<div class="card">
<h3>Q. 내 결제 내역이 밖으로 나가나요?</h3>
<p>기본적으로 휴대폰 안에만 저장돼요. 가족 공유를 켠 경우에만 사용처·금액·카드사·시각·이름이 공유 서버에 저장되고, 공유를 끄면 서버에서 모두 지워져요. 자세한 내용은 <a href="/privacy">개인정보처리방침</a>을 확인해 주세요.</p>
<h3>Q. 알림 접근 권한은 왜 필요한가요?</h3>
<p>결제 알림을 읽어 자동으로 기록하기 위해서예요. 결제·금액과 관계없는 알림은 저장하지 않아요.</p>
<h3>Q. 기록이 안 될 때는요?</h3>
<p>앱 설정 → <b>최근 감지 알림</b>에서 알림을 받았는지 확인할 수 있고, <b>문자 붙여넣기</b>로 직접 넣을 수도 있어요. 안 되는 알림은 메일로 알려 주시면 고쳐 드릴게요.</p>
</div>
<a class="btn" href="${APK_URL}">⬇️ 앱 내려받기 (APK)</a>`);
}

function privacyPage() {
  return shell('자동가계부 개인정보처리방침', '자동가계부 앱의 개인정보 처리 항목, 목적, 보관 기간과 삭제 방법 안내',
    `<h1>개인정보처리방침</h1>
<p class="sub">자동가계부 (Android 앱 및 가족 공유 웹) · 시행일 ${POLICY_DATE}</p>
<div class="card"><p>GRAFT(이하 '운영자')는 자동가계부 앱(이하 '앱')을 이용하는 분의 개인정보를 소중히 여기며, 「개인정보 보호법」 등 관련 법령을 지킵니다. 앱은 회원가입이 없으며, <b>내역은 기본적으로 이용자의 휴대폰 안에만 저장</b>됩니다. 이용자가 <b>가족 공유</b>를 켠 경우에만 일부 정보가 공유 서버에 저장됩니다.</p></div>

<h2>1. 처리하는 정보와 목적</h2>
<table>
<tr><th>구분</th><th>항목</th><th>목적 · 저장 위치</th></tr>
<tr><td>휴대폰 안 (모든 이용자)</td><td>결제 알림에서 읽은 사용처·금액·카드사·시각, 직접 입력한 수입·지출, 최근 감지 알림 50건(앱 이름·결과·알림 내용 일부), 환율·제외 단어 설정</td><td>가계부 기록·통계 표시, 자동 기록 문제 확인. 이용자의 휴대폰에만 저장되며 운영자에게 전송되지 않음</td></tr>
<tr><td>공유 서버 (가족 공유를 켠 경우)</td><td>표시 이름(예: 남편), 사용처(메모), 금액, 수입·지출 구분, 카드사 이름, 시각, 내역 식별번호, 가족 방 식별정보(쓰기 키의 해시값, 보기 토큰)</td><td>가족이 보기 링크·앱으로 같은 가계부를 보도록 하기 위함</td></tr>
<tr><td>자동 생성 정보</td><td>서버 접속 시 IP 주소, 접속 시각, 브라우저 정보</td><td>서비스 제공 과정에서 서버 운영사(Cloudflare)가 보안·장애 대응을 위해 처리할 수 있음. 운영자는 이를 따로 저장하거나 분석하지 않음</td></tr>
</table>
<p class="sub" style="margin-top:8px">결제 알림 원문, 카드번호, 계좌번호, 연락처, 문자·카카오톡 대화 내용은 서버로 보내지 않습니다. 결제·금액과 관계없는 알림은 저장하지 않습니다.</p>

<h2>2. 앱 권한</h2>
<ul>
<li><b>알림 접근</b>: 결제 알림을 읽어 내역을 자동으로 기록합니다. 이용자가 휴대폰 설정에서 언제든 끌 수 있습니다.</li>
<li><b>인터넷</b>: 해외 결제 환율 조회, 가족 공유를 켠 경우 내역 올리기·받기에 사용합니다.</li>
</ul>

<h2>3. 보관 기간과 파기</h2>
<ul>
<li><b>휴대폰 안의 정보</b>: 이용자가 내역을 지우거나 앱을 삭제하면 함께 지워집니다.</li>
<li><b>공유 서버의 정보</b>: 가족 공유를 켜 둔 동안 보관합니다. 앱의 <b>[가족 공유 끄기]</b>를 누르면 가족 방과 모든 내역이 서버에서 즉시 삭제되며, 복구할 수 없습니다. 앱에서 내역을 지우면 서버에서도 지워집니다.</li>
<li>삭제된 정보는 데이터베이스에서 복구할 수 없는 방식으로 지웁니다.</li>
</ul>

<h2 id="delete">4. 데이터 삭제 요청</h2>
<div class="card">
<ol>
<li><b>앱에서 직접 삭제</b>: 가족 탭 → 공유 설정 → <b>[가족 공유 끄기]</b> → 서버 내역도 삭제를 선택하세요.</li>
<li><b>앱을 이미 지웠거나 휴대폰을 잃어버린 경우</b>: <a href="mailto:${CONTACT}?subject=%EC%9E%90%EB%8F%99%EA%B0%80%EA%B3%84%EB%B6%80%20%EB%8D%B0%EC%9D%B4%ED%84%B0%20%EC%82%AD%EC%A0%9C%20%EC%9A%94%EC%B2%AD">${CONTACT}</a>로 <b>가족 보기 링크</b>를 보내 삭제를 요청해 주세요. 확인 후 지체 없이(늦어도 10일 이내) 해당 가족 방과 모든 내역을 삭제하고 결과를 알려 드립니다.</li>
</ol>
</div>

<h2>5. 제3자 제공</h2>
<p>운영자는 이용자의 정보를 제3자에게 제공하거나 판매하지 않습니다. 다만 이용자가 직접 보기 링크나 초대 코드를 보낸 사람은 그 가족 가계부를 볼 수 있습니다. 법령에 따라 수사기관 등의 요구가 있는 경우는 예외로 합니다.</p>

<h2>6. 처리 위탁 및 국외 이전</h2>
<table>
<tr><th>받는 곳</th><th>내용</th></tr>
<tr><td>Cloudflare, Inc. (미국)<br><span class="sub">privacy@cloudflare.com</span></td><td>가족 공유 서버 운영(Workers·D1). 이전 항목: 1번 표의 공유 서버 정보. 이전 시점·방법: 가족 공유를 켠 뒤 내역을 저장할 때 암호화된 연결(HTTPS)로 전송. 보관 기간: 3번과 같음. 이전을 원하지 않으면 가족 공유를 켜지 않으면 되며, 이 경우에도 앱의 다른 기능은 모두 쓸 수 있습니다.</td></tr>
<tr><td>환율 정보 제공처 (open.er-api.com, jsdelivr)</td><td>하루 한 번 환율 데이터만 받아 오며 이용자 정보는 보내지 않습니다(통신 과정의 IP 주소 제외).</td></tr>
</table>

<h2>7. 안전성 확보 조치</h2>
<ul>
<li>모든 통신은 암호화된 연결(HTTPS)로 합니다.</li>
<li>내역을 올리고 지우는 쓰기 키는 서버에 해시값으로만 저장합니다.</li>
<li>보기 링크에는 추측하기 어려운 무작위 값이 들어가며, 가족 방마다 내역이 분리됩니다. 링크가 유출되었다면 공유를 끄고 다시 시작해 새 링크를 만들 수 있습니다.</li>
<li>보기 화면은 검색엔진에 노출되지 않도록 설정합니다.</li>
</ul>

<h2>8. 이용자의 권리</h2>
<p>이용자는 언제든 앱에서 자신의 내역을 확인·수정·삭제할 수 있으며, 공유 서버의 정보에 대해 열람·정정·삭제·처리정지를 요청할 수 있습니다. 요청은 아래 연락처로 하시면 지체 없이 처리합니다. 앱은 만 14세 미만 아동을 대상으로 하지 않습니다.</p>

<h2>9. 개인정보 보호책임자</h2>
<div class="card">
<p>상호: GRAFT<br>책임자: SANGWOO LEE (대표)<br>이메일: <a href="mailto:${CONTACT}">${CONTACT}</a></p>
</div>

<h2>10. 권익 침해 구제</h2>
<ul>
<li>개인정보침해신고센터: privacy.kisa.or.kr / 국번 없이 118</li>
<li>개인정보분쟁조정위원회: www.kopico.go.kr / 1833-6972</li>
<li>대검찰청: www.spo.go.kr / 국번 없이 1301</li>
<li>경찰청: ecrm.police.go.kr / 국번 없이 182</li>
</ul>

<h2>11. 방침의 변경</h2>
<p>이 방침이 바뀌면 시행 7일 전부터 이 페이지에 알립니다. 이용자에게 불리한 중요한 변경은 30일 전에 알립니다.</p>
<p class="sub">공고일 · 시행일: ${POLICY_DATE}</p>`);
}

// ---------------------------------------------------------------- 도우미

function clip(s) { return String(s == null ? '' : s).slice(0, MAX_TEXT); }

function randomToken(n) {
  const abc = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  const b = crypto.getRandomValues(new Uint8Array(n));
  return Array.from(b, x => abc[x % abc.length]).join('');
}

async function sha256(s) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return Array.from(new Uint8Array(d), b => b.toString(16).padStart(2, '0')).join('');
}

function cors() {
  return { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS' };
}
function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...cors() } });
}
function text(s) { return new Response(s, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } }); }
function html(s, status = 200, cacheable = false) {
  return new Response(s, { status, headers: { 'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': cacheable ? 'public, max-age=300' : 'no-store', 'Referrer-Policy': 'no-referrer' } });
}
