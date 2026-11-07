# vgi-starter-api

The public demo REST API that the starter templates' `fetch_items` examples call:
**https://vgi-starter-api.query-farm.services/v1**. It's a Cloudflare Worker with no
dependencies and no storage.

| Endpoint | Returns |
|---|---|
| `GET /v1/items?page=N&page_size=M` | `{"items":[{id,name,category,price}], "next_page": N+1 or null}` over 25 fixed items |
| `GET /v1/auth/items?...` | the same, but requires `Authorization: Bearer demo-token` (otherwise 401) |
| `GET /health` | `{"status":"ok"}` |

**`/v1` is frozen.** Copies of the templates made years ago still call it, so never
change its data or behaviour. Put anything new under `/v2`.

It answers exactly like the tests' offline mock (`shared/test/mock_api/server.py`).
The tests keep using the mock: they need failure injection (429 on every page) and
must run offline. The hosted API is for examples, the README and vgi-lint, which
executes examples.

Each client IP gets 300 requests a minute; beyond that it returns 429 with
`Retry-After`, which `fetch_items` honours.

```bash
bun install
bunx tsc --noEmit                 # type-check
bunx wrangler dev                 # local, then: python3 parity.py http://127.0.0.1:8787
bunx wrangler deploy              # Query.Farm account, zone query-farm.services
python3 parity.py                 # compare the deployed API with the mock (26 checks)
```
