# Naver authentication recovery

Public map searches may work without signing in. If Naver requires a session,
use a persistent Chrome profile and sign in manually.

Set `NAVER_MAP_SELENIUM_USER_DATA_DIR` to an absolute, private directory in the
worker's environment. When unset, the worker uses a temporary, signed-out profile.
The login helper alone defaults to
`$XDG_DATA_HOME/naver-maps-mcp/chrome-profile`, or
`$HOME/.local/share/naver-maps-mcp/chrome-profile` when `XDG_DATA_HOME` is unset.
The directory must have mode `0700`. For example, set the same value for both
the helper and worker:

```bash
export NAVER_MAP_SELENIUM_USER_DATA_DIR="$HOME/.local/share/naver-maps-mcp/chrome-profile"
```

For systemd, also set that absolute path in the service's private environment
file before restarting the gateway. Run
`scripts/naver-session-login.sh` to open normal installed Google Chrome with this
same profile. Stop the worker first, sign in or complete any requested security
verification in that Chrome window, then close Chrome completely before
restarting the worker.

The login helper does not automate sign-in or CAPTCHA/security checks. Complete
only a genuine Naver security interaction shown by Naver. Never create retry
storms, and do not treat a timeout as proof that an account or session expired.

Common responses:

- HTTP `401 login_required`: sign-in is required.
- HTTP `403 access_denied`: access was denied.
- HTTP `429 rate_limited`: the worker applies a local 60-second cooldown; wait
  before another request.
- `verification_required`: Naver requires a manual security interaction.
- `session_configuration`: check the configured Chrome profile path and
  permissions.

The profile contains private session data, including cookies. Never commit,
share, or upload it. If an agent needs account credentials, provide them through
`request_secret`; do not paste them in chat or commit them to files.

No unit tests are provided for this recovery flow. Run a live MCP smoke check
only after coordinating the cooldown with other users of the worker.
