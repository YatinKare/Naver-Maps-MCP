# Authentication diagnosis, 19 November 2026

The controlled public browser navigation returned café results while signed out.
No login or verification challenge was shown. There is no evidence from this
navigation that an account is blocked or that public search requires login.

![Actual signed-out NAVER results](evidence/auth-2026-11-19-signed-out-results.png)

The page's `searchIframe` points at `pcmap.place.naver.com/place/list`.
Top-page resource timing entries include HTTP 200 for
`/p/api/search/query-analyze`, `/p/api/search/bubble-keyword-list`, and
`/p/api/login/simple`. They did not include the configured legacy
`/p/api/search/allSearch` endpoint. Cross-origin iframe response bodies were not
available through the preview tool, so this observation alone does not establish
the new response payload or validate a replacement extractor. The parent later
confirmed matching Apollo hydration in the actual result frame and implemented
the capture fix separately from this auth PR.

One actual connected `naver_map_search` call for `서울역 카페`, page 1, returned
`isError=true` with the generic timeout message. The gateway's corresponding
outcome at `2026-11-19T18:07:00.55200444Z` recorded `failure_category=timeout`,
`failure_stage=worker_startup`, and `search_ms=0` (correlation
`7a9ab5d15258776c`). It never reached NAVER. It is neither a successful MCP
search nor evidence of an expired account session. The saturated host was
subsequently observed with load average 24.72 and 70% I/O wait; those timings are
not performance benchmarks.

The auth change adds optional persistence of a legitimate Chrome profile and
specific failure categories when a critical response actually returns HTTP
401/403/429 or the browser displays login/verification evidence. It leaves
unrecognized capture failures as capture failures and does not require login
for public search. These upstream failure categories were not encountered in
the controlled navigation; they are not claimed as live-validated account
recovery.

Validation completed without unit tests:

- `bash gradlew bootJar --no-daemon --max-workers=1` succeeded.
- `GOMAXPROCS=1 go build -p=1 -o ../build/auth/naver-maps-mcp ./cmd/gateway`
  succeeded from `gateway/`.
- `bash -n scripts/naver-session-login.sh` and `git diff --check` passed.
- A Luna High implementation helper created the operator script and recovery
  guide; a separate Luna High reviewer reported no concrete blockers.
- An isolated loopback gateway built from this auth source successfully handled
  real MCP `initialize` and `tools/list` requests and discovered both tools.
  With `NAVER_MAP_SELENIUM_USER_DATA_DIR=relative-profile`, an actual
  `naver_map_search` request started the real Java worker and returned the
  actionable `session_configuration` message through the MCP gateway. See the
  [actual MCP response](evidence/auth-2026-11-19-invalid-profile.json). The path
  was rejected before `new ChromeDriver`, so this check made no NAVER requests.
  The isolated gateway and worker were stopped after the check.

The base auth implementation's built Java JAR SHA-256 is
`b094c8a6fc12815e8f49a81fb8ccdbdfe1629205a0a6f84ce8dc6dcda2ce679b`.
No credentials, account cookies, or profile files were collected or committed.

## Integrated live search validation

The parent built the combined auth, capture, and timing source successfully,
including the resolved-profile accessor and redirected category-list guard.
With browser reuse disabled, actual MCP calls then returned:

| Workload | Places | Page-2 IDs absent from page 1 |
| --- | ---: | ---: |
| Keyword page 1, cold worker | 70 | — |
| Keyword page 1, warm worker | 70 | — |
| Keyword page 2 | 70 | 70 |
| Coordinate page 1 | 70 | — |
| Coordinate page 2 | 70 | 70 |

All five calls returned HTTP 200 and passed the real client's result checks.
The parent's gateway log independently recorded successful tool outcomes.
No upstream 401, 403, 429, or verification challenge was observed. These results
establish the tested public search flow; they do not claim an authenticated
account login or live exercise of an unobserved security challenge.

The [auth-focused copy of the actual integration evidence](evidence/auth-2026-11-19-integrated-mcp.json)
preserves result counts, ID hashes, artifact hashes, and the original report's
source-revision/dirty-state metadata, without making latency claims. The
combined worker's SHA-256 is
`86564551ec14838b63820d3faf23bd2111026f366688167c3c102246efdf0ee9`.
The original report is the parent's
`runs/latency-2026-11-19/repaired-smoke.json` with its corresponding server log.
The production deployment was not changed.

The parent owns the final successful-call screenshots and the capture/performance
PR. This auth PR supplies session persistence and error recovery and does not
independently close issue #68; successful extraction requires the capture fix.
