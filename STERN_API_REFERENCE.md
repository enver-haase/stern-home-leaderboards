# Stern Pinball Insider Connected — API Reference

Discovered 2026-02-12 by probing both API domains exhaustively.

## Domains

| Domain | Purpose |
|--------|---------|
| `insider.sternpinball.com` | Web frontend (Next.js) and authentication |
| `cms.prd.sternpinball.io` | CMS/content API (Django REST Framework) |
| `api.prd.sternpinball.io` | Same backend as CMS — identical responses |

Both `cms.prd` and `api.prd` serve identical data on all endpoints.

## Authentication

```
POST https://insider.sternpinball.com/login
Header: Next-Action: <performLogin action ID>
Body: ["email","password"]
```

The `Next-Action` header is a content-addressed Next.js Server Action ID that changes on every Stern frontend redeploy. Discover the current value by GETting `/login`, downloading the `/_next/static/chunks/*.js` files it references, and grepping for `createServerReference)("<hash>",...,"performLogin")`. `SternAuthService` does this dynamically and caches the result; on auth failure the cache is invalidated and the next attempt re-resolves it.

Returns cookies:
- `spb-insider-token` — Bearer access token
- `spb-insider-refresh-token` — Refresh token
- `spb-insider-user-id` — User ID
- `spb-insider-profile-id` — Profile ID

Token expires after ~30 minutes. All authenticated endpoints require:
```
Authorization: Bearer <spb-insider-token>
Location: {"country":"DE","continent":"EU"}
```

## V1 Portal API

Base: `https://cms.prd.sternpinball.io/api/v1/portal`

### Public endpoints (no auth required)

| Endpoint | Description |
|----------|-------------|
| `GET /avatars/` | 55 avatar objects with S3-hosted image URLs |
| `GET /background_colors/` | 7 color definitions (RGB + hex) |
| `GET /game_titles/` | All 47 game titles with logos, gradients, backgrounds |
| `GET /stern_global_leaderboards/` | 102 global leaderboards (paginated) |
| `GET /nearby_leaderboards/` | 2048 location-based leaderboards (paginated) |
| `GET /user_leaderboards/` | Custom leaderboards (empty without auth) |

### Authenticated endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /user_registered_machines/?group_type=home` | Registered home machines. `group_type=business` returns machines assigned to a business/location; other values return 406. A machine only ever appears under one group type — moving it to a business location (e.g. via the app's "transfer" flow) removes it from `home` entirely. |
| `GET /game_machines/{id}/` | Machine detail: `online`, `last_played`, `code_version`, `game_model`, `last_seven_day_tech_alerts[]`. Trailing slash required (without → 301). |
| `GET /game_machines/` | 500 error — listing all machines is not supported |
| `GET /game_machine_high_scores/?machine_id={id}` | High scores for a specific machine. Requires `machine_id` param. |
| `GET /public_users/` | Public user profiles |
| `GET /user_profiles/` | User profile data |
| `GET /operator_registry/` | Operator registration data |
| `GET /game_achievement_icons/` | Achievement icon assets |
| `GET /game_locations/` | Physical machine locations |
| `GET /game_achievements/` | Achievement definitions |
| `GET /user_leaderboard_scores/` | Individual score entries (requires params) |
| `GET /events/` | Dead endpoint. Exists in Django routing (no 404) but backend never responds. AWS ELB returns 504 Gateway Time-out after 60s. Tested with game activity — no events delivered. |

### Key response fields

**Machine list** (`/user_registered_machines/?group_type=home`):
`id`, `archived`, `online`, `last_played`, `model.title` (name, logos, gradients, backgrounds), `address.location_id`, `last_seven_day_tech_alerts[]`

**Machine detail** (`/game_machines/{id}/`):
`pk`, `online`, `last_played`, `code_version`, `game_model.model_type_name`, `last_seven_day_tech_alerts[]`

**High scores** (`/game_machine_high_scores/?machine_id={id}`):
Array of `{id, score, user: {username, name, initials}}`

## V2 Portal API

Base: `https://api.prd.sternpinball.io/api/v2/portal`

| Endpoint | Description |
|----------|-------------|
| `GET /user_detail/` | User profile with avatar, background color, initials, `following[]` list of connected players |

**This is the only V2 endpoint.** All other paths return 404.

### Key response fields

**User detail** (`/user_detail/`):
`user.profile.initials`, `user.profile.avatar_url`, `user.profile.background_color_hex`, `user.profile.following[]` (each with `initials`, `avatar_url`, `background_color_hex`)

## V3 Portal API

Does not exist. All paths return 404.

## Online Status Reliability

The `online` boolean from both `/user_registered_machines/` and `/game_machines/{id}/` is **unreliable**. Machines that have been physically powered off for 18–42 hours still report `online=true`. Stern's backend does not detect disconnections promptly (or at all).

The `last_played` timestamp is the most reliable signal for inferring actual connectivity.

## Publication Lag

Stern publishes what happened on a cabinet **long after the game ended**, and the delay differs per
kind of data. Measured on machine 12856 (Godzilla) for a single session on 2026-09-02:

| Data | Game | Visible in the API | Delay |
|------|------|--------------------|-------|
| High score (ROC, 2,164,918,720) | 2026-09-02, last play 18:12:33Z | between 21:46:00Z and 21:51:07Z | ~3 h 35 min |
| Achievement ("Xilien Battles") from the same session | 2026-09-02 | credited 2026-09-05 21:59:15Z | ~3 days |

Bracketed by our own 5-minute poll: the 21:46:00Z fetch returned the table without the row, the
21:51:07Z fetch returned it. So the lag is entirely on Stern's side — `last_played` was accurate
all along, and a score missing right after a game is expected, not a bug.

`last_activity` is **not** a "someone played" signal. On 2026-09-05 four of the five machines
reported `last_activity` within nine seconds of each other (21:57:11Z–21:57:20Z) — a backend sync
touching every machine at once, not four simultaneous games.

## Web App Routes

| Route | Description |
|-------|-------------|
| `/login` | Login page |
| `/register` | Multi-step registration |
| `/dashboard` | Main user dashboard (auth required) |
| `/account` | Account/subscription management |
| `/pricing` | Subscription tiers ($0 free / $79.99–$99.99/yr All-Access) |
| `/reset-password` | Password recovery |

## Mobile App Bridge

The web app communicates with the native Flutter shell via:
`app_set_tokens`, `app_log_out`, `app_on_show_qr_code`, `app_on_hide_qr_code`,
`app_share_content`, `app_get_version`, `app_get_current_position`,
`app_get_network_ssid`, `app_can_try_get_network_ssid`, `app_get_available_network_ssids`
