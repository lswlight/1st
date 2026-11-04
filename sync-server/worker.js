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
  if (path === '/' && method === 'GET') return text('자동가계부 가족 공유 서버가 동작 중입니다.');

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
function html(s, status = 200) {
  return new Response(s, { status, headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' } });
}
