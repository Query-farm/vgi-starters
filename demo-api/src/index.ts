/**
 * vgi-starter-api: the public demo REST API behind the VGI starter templates'
 * `fetch_items` table function (https://github.com/Query-farm/vgi-starters).
 *
 * The contract matches the starters' offline test mock (shared/test/mock_api/server.py)
 * exactly, and everything under /v1 is frozen: old copies of the templates keep working.
 *
 *   GET /v1/items?page=N&page_size=M       {"items":[{id,name,category,price}], "next_page": N+1|null}
 *   GET /v1/auth/items?page=N&page_size=M  same, but requires "Authorization: Bearer demo-token"
 *   GET /health                            {"status":"ok"}
 */

export interface Env {
  LIMITER?: { limit(options: { key: string }): Promise<{ success: boolean }> };
}

/** The published demo token. It guards nothing: it exists so the secrets example has something to send. */
const DEMO_TOKEN = "demo-token";

const CATEGORIES = ["hardware", "software", "service"] as const;

/** 25 fixed items, generated exactly as the Python mock does. */
const ITEMS = Array.from({ length: 25 }, (_, k) => {
  const i = k + 1;
  return {
    id: i,
    name: `item-${String(i).padStart(2, "0")}`,
    category: CATEGORIES[i % 3],
    price: Math.round(1.5 * i * 100) / 100,
  };
});

const BASE_HEADERS: Record<string, string> = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization",
  "X-Content-Type-Options": "nosniff",
};

function json(status: number, body: unknown, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...BASE_HEADERS, ...extra } });
}

const INDEX = {
  service: "vgi-starter-api",
  about: "Demo REST API for the VGI starter templates' fetch_items example.",
  source: "https://github.com/Query-farm/vgi-starters",
  endpoints: {
    "/v1/items?page=1&page_size=10": "25 fixed items, paginated",
    "/v1/auth/items?page=1&page_size=10": `the same, requires 'Authorization: Bearer ${DEMO_TOKEN}'`,
    "/health": "liveness",
  },
};

/** Serve one page of items, validating page/page_size like the mock. */
function itemsPage(url: URL): Response {
  const pageRaw = url.searchParams.get("page") ?? "1";
  const sizeRaw = url.searchParams.get("page_size") ?? "10";
  if (!/^-?\d+$/.test(pageRaw) || !/^-?\d+$/.test(sizeRaw)) {
    return json(400, { error: "page and page_size must be integers" });
  }
  const page = Number(pageRaw);
  const pageSize = Number(sizeRaw);
  if (page < 1 || pageSize < 1 || pageSize > 1000) {
    return json(400, { error: "page must be >= 1 and page_size in [1, 1000]" });
  }
  const start = (page - 1) * pageSize;
  const items = ITEMS.slice(start, start + pageSize);
  const nextPage = start + pageSize < ITEMS.length ? page + 1 : null;
  // The data never changes, so caches may keep it.
  return json(200, { items, next_page: nextPage }, { "Cache-Control": "public, max-age=86400" });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: { ...BASE_HEADERS, "Access-Control-Allow-Methods": "GET, OPTIONS" } });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return json(405, { error: "method not allowed" }, { Allow: "GET, HEAD, OPTIONS" });
    }
    if (url.pathname === "/health") return json(200, { status: "ok" });
    if (url.pathname === "/" || url.pathname === "/v1" || url.pathname === "/v1/") return json(200, INDEX);

    const auth = url.pathname === "/v1/auth/items";
    if (url.pathname !== "/v1/items" && !auth) return json(404, { error: "not found" });

    if (env.LIMITER) {
      const key = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const { success } = await env.LIMITER.limit({ key });
      if (!success) return json(429, { error: "slow down" }, { "Retry-After": "10" });
    }
    if (auth && request.headers.get("Authorization") !== `Bearer ${DEMO_TOKEN}`) {
      return json(401, { error: "missing or invalid bearer token" }, { "WWW-Authenticate": "Bearer" });
    }
    return itemsPage(url);
  },
};
