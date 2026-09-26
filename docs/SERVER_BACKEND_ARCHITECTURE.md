# Server backend design

## Boundary and runtime

Android phone and Wear OS clients use HTTPS API routes. A private Node.js process handles requests, auto-applies numbered SQLite migrations, stores durable records in SQLite WAL mode, and keeps article/transcript objects in a local directory. A TLS reverse proxy provides the public origin. Single process per SQLite database. Database and object directory must be backed up together; the encryption master key is backed up separately.

| Capability | Data and control |
| --- | --- |
| Mobile accounts | Email/password and optional verified Google ID Token, revocable Bearer sessions, email verification and reset through SMTP. Mobile registration can be toggled by the admin. |
| Sole web administrator | `zeromostia@gmail.com`, provisioned from a private temporary password at first server startup; web cookie sessions, same-origin CSRF check, forced password change and audit log. No web registration, no public admin recovery, no secondary admin promotion. |
| Users and memberships | Admin edits display name, active/disabled status, FREE/PRO membership and monthly managed AI quota. Disabling a user revokes sessions. Only the current month is overridden; future months take plan defaults. |
| AI providers | Provider/model records in SQLite, up to 10 named encrypted API-key slots, health checks and provider selection. Do not expose keys in read APIs or audit records. |
| Content and sync | Existing authenticated API routes use SQLite and a local filesystem object adapter. Podcast audio remains a remote URL. |
| Billing | Existing Google Play server verification may be enabled with a service account. RTDN push remains optional and requires authenticated Pub/Sub. An admin membership override is manual, and billing refresh can supersede it. |
| Mail | SMTP settings are encrypted with AES-256-GCM using `WRISTBRIEF_MASTER_KEY`; email delivery is unavailable until saved. |
| Transcript jobs | New requests explicitly report unavailable until a durable audio-processing worker is implemented. |

## First deployment

1. Configure private data directory, generated 32-byte base64 master key, a temporary 16+ character admin password, and HTTPS public origin.
2. Start the server behind a TLS reverse proxy. Open `/admin/login` and sign in using the fixed admin email and temporary password.
3. Follow the mandatory password change screen, sign in again, then remove the temporary password from the process environment.
4. Configure SMTP, provider key, model and optional Google integrations. Test mobile registration and verify the email message before inviting users.
5. Back up database, objects and master key. Monitor health and SMTP/provider failures.

## Design constraints

- Web administration is limited to one identity. User management must never turn a mobile account into an admin or remove the sole admin.
- Treat the initial password as a deployment secret; never print it in logs or store it in the repository. Reject startup without one when no admin exists.
- Protect mutating admin routes with session, origin and CSRF checks; store only password hashes and token hashes. Never include SMTP/provider secrets in HTML, API reads or audit logs.
- All new durable records use SQLite migrations; avoid in-memory-only state for billing, identity, quotas and jobs. Prevent misleading success responses when background processing is absent.
- Preserve existing mobile API contracts when moving infrastructure. Keep Google integration optional at startup so email-only operation remains possible.
