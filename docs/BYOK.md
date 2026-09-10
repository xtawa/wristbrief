# WristBrief BYOK contract

WristBrief supports bring-your-own-key (BYOK) summaries through the authenticated Gateway without embedding managed provider secrets in the APK.

## Supported providers

The current allowlisted BYOK provider set is:

- `openrouter`
- `gemini`

The client sends a provider ID and model name, but never an arbitrary upstream URL. The Gateway constructs the provider adapter against fixed provider endpoints, so `/v1/byok/summary` cannot be used as an open proxy.

## Request contract

`POST /v1/byok/summary` requires the normal WristBrief Gateway bearer/session credential plus a per-request provider key in the `X-WristBrief-BYOK-Key` header. The provider key is not accepted in the URL or JSON body.

The JSON body contains only source data plus the selected allowlisted provider/model. BYOK requests use the same structured-summary validation as managed summaries.

## Secret handling

- Managed provider secrets remain server-side and are never shipped in the Android APK.
- A BYOK provider key is supplied per request and is never persisted by the Gateway.
- The phone companion keeps the provider key only in non-saveable Compose memory; it is not written to preferences or the Wear Data Layer.
- The phone clears the provider key when leaving BYOK mode or switching providers.
- Gateway responses and tests must not include provider keys, Authorization headers, or upstream response bodies.
- BYOK requests are not written into the managed summary cache and do not consume managed-AI quota.

## Error semantics

Gateway authentication and provider authentication are intentionally distinct:

- `401 unauthorized`: WristBrief Gateway session/access token was rejected.
- `400 byok_*`: local BYOK configuration is unsupported, missing, or invalid.
- `422 byok_provider_auth_failed`: the selected provider rejected the caller-supplied provider credential (upstream 401/403). The Gateway does not echo the provider key or upstream body.
- `429 quota_exceeded`: server-owned entitlement/quota policy rejected the request.
- `502/503/504`: provider or Gateway infrastructure failure/timeout.

The Android client should map these categories to safe user-facing messages and must not include secret values in exception text or logs.

## Production checklist

Before production release, exercise both providers against non-production keys in an internal test environment, verify invalid/revoked-key behavior, confirm request/edge logs do not capture secret headers or bodies, and keep provider endpoint allowlists fixed in code/configuration rather than accepting client-supplied hosts.
