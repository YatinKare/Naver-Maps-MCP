# Error issue and CI triage — 2026-11-19

The `YatinKare/map-data-fetcher` GitHub remote redirects to
[`YatinKare/Naver-Maps-MCP`](https://github.com/YatinKare/Naver-Maps-MCP).
This inventory was read from GitHub issues and Actions, rather than inferred
from local logs. No issues were closed during this work.

## Open issue inventory and ownership

| Issue | Evidence and disposition |
| --- | --- |
| [#68: 429 and first-page capture timeout](https://github.com/YatinKare/Naver-Maps-MCP/issues/68) | The report distinguishes an initial worker startup failure (systemd Java PATH), a subsequent ChromeDriver/Chrome mismatch, a first-page response capture timeout, and a later HTTP 429 after repeated diagnostic searches. Those are separate failures. Auth/recovery owns current restriction and session diagnosis; the performance parent owns changes to browser lifecycle in `NaverMapSearchService`. Keep open until real keyword and coordinate calls succeed after recovery and restriction errors are distinguished from capture timeouts. This runner change does not establish recovery. |
| [#63: machine-agnostic integration runner](https://github.com/YatinKare/Naver-Maps-MCP/issues/63) | Reproduced by inspecting hardcoded `/home/yatink` Java paths and `/usr/bin/google-chrome` in `tests/run-script`. This change removes them, discovers executables, validates prerequisites before launching, documents overrides, and adds a preflight-only invocation. It also fixes the build helper's working-directory assumption. Closure should be based on the portability validation below; live NAVER acceptance remains subject to #68. |
| [#52: release performance baseline](https://github.com/YatinKare/Naver-Maps-MCP/issues/52) | Owned by the performance parent, including benchmark scripts and measurements. No latency improvement is claimed by this PR. |
| [#54: bounded queue](https://github.com/YatinKare/Naver-Maps-MCP/issues/54) | Explicitly depends on measurements and real usage after the existing concurrency guard. No evidence currently justifies replacing the busy response in this error-fix scope. Defer to the performance/usage study; keep open. |
| [#48: management CLI](https://github.com/YatinKare/Naver-Maps-MCP/issues/48), [#49: CLI foundation](https://github.com/YatinKare/Naver-Maps-MCP/issues/49), [#50: service controls](https://github.com/YatinKare/Naver-Maps-MCP/issues/50), [#51: install/update/rollback](https://github.com/YatinKare/Naver-Maps-MCP/issues/51) | Feature backlog, not reported error regressions. No closure or implementation in this PR. |

## CI evidence

GitHub lists three historical Actions runs:

- [Main CI/CD run 34825779478](https://github.com/YatinKare/Naver-Maps-MCP/actions/runs/34825779478),
  created 2026-09-14 at commit `a820b4a27323e756d1df594c1aa010d8f5d5dbb9`:
  Java and Go builds succeeded; the failed step is **Copy JAR to server**.
  GitHub's log command returned no log bytes during this inspection, so the
  specific deployment failure cannot be established. Do not infer an SSH,
  credential, network, or source-compilation cause from the failed step alone.
- [v0.1.0 release run 38121996082](https://github.com/YatinKare/Naver-Maps-MCP/actions/runs/38121996082): successful.
- [v0.1.1 release run 38123743548](https://github.com/YatinKare/Naver-Maps-MCP/actions/runs/38123743548): successful.

At the inspected source base (`fa62052`), `.github/workflows/release.yml` is the
current workflow and runs on version tags. The old PM2 deployment workflow is
absent. There is no demonstrated current build failure to repair from the old
run; preserve its history and rely on current release evidence rather than
reintroducing obsolete deployment machinery. Tag-only release CI does not
provide a pull-request check for this change.

## Portability validation

No unit tests were created or run. The developer runner retains its keyword,
coordinate, pagination, lazy startup, idle shutdown, and restart integration
steps. `--check` returns before gateway startup or any NAVER request.

Manual checks against real local installations:

- `bash -n tests/run-script tests/build-baseline`: passed.
- `tests/run-script --check` with existing real gateway/JAR artifacts: passed,
  reporting Java `17.0.20`, Chrome `156.0.8078.47`, and matching ChromeDriver
  `156.0.8078.47`.
- Invocation by absolute path from `/tmp`, with `JAVA_BIN=java` and
  `CHROMEDRIVER_BIN=chromedriver`: passed, resolving commands from `PATH`.
- Invalid explicit Java and ChromeDriver paths: failed immediately with the
  relevant executable name and override guidance, before any gateway startup.

Direct MCP integration against the running local v0.1.1 service also passed:
`initialize` negotiated protocol `2025-11-25`, and `tools/list` returned both
`naver_map_search` and `naver_map_coordinate_search` without a JSON-RPC error.
The raw JSON responses are retained locally in `artifacts/issue63/`. These
calls do not invoke NAVER or establish successful search capture.

These checks establish local dependency discovery and failure behavior. They
do not establish a successful live NAVER search or portability to every Linux
installation. Full live integration is coordinated with auth and performance
work to avoid repeated NAVER load while #68 is under investigation. The parent
explicitly accepted discovery/preflight evidence for this runner fix after its
released-artifact keyword baseline timed out at the client (120100 ms).

A fresh `tests/build-baseline` invocation from `/tmp` reached Gradle startup but
hit the 240-second observation bound during severe shared host memory/IO
pressure. It exited 124; no successful fresh build is claimed. No Gradle
process from this attempt remains. The final helper limits Gradle to one
worker and disables persistent daemons; existing release artifacts were used
for preflight. A visual preview of the saved discovery report also timed out:
“Headless Chrome could not render the page: the page did not finish loading
within 20 seconds.” Raw MCP response evidence remains available; no rendered
proof or successful search is claimed by this PR.
