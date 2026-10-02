// 유튜브 영상 분석 MCP 커넥터 (Cloudflare Workers)
// claude.ai 의 "사용자 지정 커넥터"로 등록하면 채팅(모바일 포함)에서
// analyze_youtube_video 도구로 Gemini 영상 분석을 호출할 수 있다.
//
// 필요한 비밀값(Cloudflare 대시보드 → Worker → Settings → Variables and Secrets):
//   GEMINI_API_KEY : Google AI Studio 에서 받은 키
//   ACCESS_TOKEN   : 아무도 못 맞힐 긴 문자열. 커넥터 주소 /mcp/<ACCESS_TOKEN> 에 들어간다.
// 선택: GEMINI_MODEL (기본 gemini-flash-latest)

const API_ROOT = "https://generativelanguage.googleapis.com/v1beta/models";
const FALLBACK_MODELS = ["gemini-3.5-flash", "gemini-3.8-flash", "gemini-flash-lite-latest"];
const SERVER_INFO = { name: "youtube-analyzer", version: "1.0.0" };

const PROMPT = `너는 유튜브 영상 편집자이자 콘텐츠 분석가다. 이 영상을 처음부터 끝까지 직접 보고 듣고,
다른 사람이 영상을 보지 않고도 똑같이 재현할 수 있을 만큼 구체적으로 기록하라.
한국어로 작성하고, 보이지 않거나 들리지 않는 것은 지어내지 말고 "확인 불가"라고 써라.

## 1. 기본 정보
- 추정 제목/주제, 길이, 화면비(가로/세로), 형식(쇼츠/롱폼), 출연자 수와 특징, 언어

## 2. 첫 15초 정밀 기록 (1초 단위)
| 시간 | 화면 | 음성(원문 그대로) | 화면 텍스트/자막 | 소리(BGM/효과음) |

## 3. 장면별 분석 (컷이 바뀔 때마다 한 행)
| 시작–끝 | 화면(구도, 피사체, 배경, 색감) | 카메라(샷 크기, 움직임) | 음성(원문 요약, 핵심 문장은 원문) | 화면 텍스트/자막 | 편집(전환, 줌, 효과, B롤) | 소리 |

## 4. 편집 스타일 요약
- 총 컷 수와 평균 컷 길이(초), 자막 디자인(위치/색/크기/강조 방식), 반복되는 편집 패턴, BGM 분위기 변화 지점

## 5. 대본 전문
- 들리는 말을 시간 표시와 함께 원문 그대로 받아적어라. 너무 길면 핵심 구간 위주로 쓰고 생략 구간을 표시하라.

## 6. 구성 구간
- 훅 / 문제제기 / 전개 / 반전·클라이맥스 / 결론 / CTA 로 나누고 각 구간의 시간 범위를 적어라.
`;

const TOOL = {
  name: "analyze_youtube_video",
  description:
    "Gemini가 유튜브 영상을 직접 보고 들어서 장면별 분석 원자료(첫 15초 1초 단위 기록, 장면표, 편집 스타일, 대본 전문, 구성 구간)를 한국어로 돌려준다. " +
    "유튜브 링크 분석·벤치마킹 요청이 오면 유튜브 페이지를 직접 열지 말고 이 도구를 쓴다. 특정 구간 질문이면 start/end 와 focus 를 넣는다. " +
    "보통 30초~2분 걸린다. 공개/일부공개 영상만 가능.",
  inputSchema: {
    type: "object",
    properties: {
      url: { type: "string", description: "유튜브 URL (watch, shorts, youtu.be 모두 가능)" },
      start: { type: "string", description: "분석 시작 지점, 예: 30s (선택)" },
      end: { type: "string", description: "분석 끝 지점, 예: 40s (선택)" },
      focus: { type: "string", description: "특정 구간/관점에 대한 추가 질문 (선택)" },
      low_res: { type: "boolean", description: "10분 넘는 긴 영상이면 true (선택)" },
    },
    required: ["url"],
  },
};

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname.replace(/\/+$/, "");
    if (!env.ACCESS_TOKEN || path !== `/mcp/${env.ACCESS_TOKEN}`) {
      return new Response("Not found", { status: 404 });
    }
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405, headers: { Allow: "POST" } });
    }

    let msg;
    try {
      msg = await request.json();
    } catch {
      return json(rpcError(null, -32700, "Parse error"));
    }
    if (Array.isArray(msg)) {
      const out = (await Promise.all(msg.map((m) => handle(m, env)))).filter(Boolean);
      return out.length ? json(out) : new Response(null, { status: 202 });
    }
    const out = await handle(msg, env);
    return out ? json(out) : new Response(null, { status: 202 });
  },
};

async function handle(msg, env) {
  const { id, method, params } = msg || {};
  // id 가 없으면 알림(notification) → 응답하지 않는다
  if (id === undefined || id === null) return null;

  switch (method) {
    case "initialize":
      return rpcResult(id, {
        protocolVersion: params?.protocolVersion || "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: SERVER_INFO,
      });
    case "ping":
      return rpcResult(id, {});
    case "tools/list":
      return rpcResult(id, { tools: [TOOL] });
    case "tools/call":
      if (params?.name !== TOOL.name) return rpcError(id, -32602, `Unknown tool: ${params?.name}`);
      try {
        const text = await analyzeWithFallback(params.arguments || {}, env);
        return rpcResult(id, { content: [{ type: "text", text }] });
      } catch (e) {
        return rpcResult(id, { content: [{ type: "text", text: String(e.message || e) }], isError: true });
      }
    default:
      return rpcError(id, -32601, `Method not found: ${method}`);
  }
}

class GeminiError extends Error {
  constructor(code, message) {
    super(`ERROR ${code}: ${message}`);
    this.code = code;
  }
}

async function analyzeWithFallback(args, env) {
  if (!args.url) throw new Error("url 이 필요합니다.");
  if (!env.GEMINI_API_KEY) throw new Error("서버에 GEMINI_API_KEY 가 설정되지 않았습니다.");

  const first = env.GEMINI_MODEL || "gemini-flash-latest";
  const models = [first, ...FALLBACK_MODELS.filter((m) => m !== first)];
  // 429(분당 한도)로 모두 실패하면 저해상도로 한 번 더
  const resolutions = args.low_res ? [true] : [false, true];
  let lastErr;

  for (const lowRes of resolutions) {
    let hitQuota = false;
    for (const model of models) {
      try {
        const { text, tokens } = await analyze(args, model, lowRes, env.GEMINI_API_KEY);
        return `<!-- source: Gemini API (${model}${lowRes ? ", low-res" : ""}) | url: ${args.url} | tokens: ${tokens} -->\n${text}`;
      } catch (e) {
        lastErr = e;
        if (!(e instanceof GeminiError) || ![404, 429, 500, 503].includes(e.code)) throw e;
        if (e.code === 429) hitQuota = true;
      }
    }
    if (!hitQuota) break;
  }
  const hint =
    lastErr?.code === 429
      ? "\n힌트: Gemini 무료 한도 초과. 1분 뒤 다시 하거나 start/end 로 구간을 줄이세요."
      : lastErr?.code === 503
        ? "\n힌트: Gemini 서버 혼잡. 잠시 뒤 다시 시도하세요."
        : "";
  throw new Error(String(lastErr?.message || lastErr) + hint);
}

async function analyze(args, model, lowRes, key) {
  const videoPart = { file_data: { file_uri: normalizeUrl(args.url) } };
  if (args.start || args.end) {
    videoPart.video_metadata = {};
    if (args.start) videoPart.video_metadata.start_offset = args.start;
    if (args.end) videoPart.video_metadata.end_offset = args.end;
  }
  const prompt = PROMPT + (args.focus ? `\n\n## 7. 사용자 질문\n${args.focus}` : "");
  const body = {
    contents: [{ parts: [videoPart, { text: prompt }] }],
    generationConfig: { temperature: 0.2 },
  };
  if (lowRes) body.generationConfig.mediaResolution = "MEDIA_RESOLUTION_LOW";

  for (let attempt = 0; ; attempt++) {
    const r = await fetch(`${API_ROOT}/${model}:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify(body),
    });
    if (r.ok) {
      const data = await r.json();
      const parts = data?.candidates?.[0]?.content?.parts;
      if (!parts) throw new Error("응답에 결과가 없습니다: " + JSON.stringify(data).slice(0, 1000));
      return {
        text: parts.map((p) => p.text || "").join(""),
        tokens: data.usageMetadata?.totalTokenCount ?? "?",
      };
    }
    const errText = (await r.text()).slice(0, 1000);
    if ([429, 500, 503].includes(r.status) && attempt < 1) {
      await sleep((r.status === 429 ? 30 : 10) * 1000);
      continue;
    }
    throw new GeminiError(r.status, errText);
  }
}

function normalizeUrl(url) {
  const m = url.match(/(?:shorts\/|youtu\.be\/|v=)([A-Za-z0-9_-]{11})/);
  return m ? `https://www.youtube.com/watch?v=${m[1]}` : url;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rpcResult = (id, result) => ({ jsonrpc: "2.0", id, result });
const rpcError = (id, code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
const json = (obj) => new Response(JSON.stringify(obj), { headers: { "Content-Type": "application/json" } });
