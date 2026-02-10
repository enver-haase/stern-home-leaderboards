# Stern Home Leaderboard

A web application for displaying pinball machine high scores from your Stern Pinball home network. Connects to Stern's cloud API to show machine data, high scores, player avatars, and tech alerts in a dark-themed pinball arcade UI.

Built as a single **Vaadin 25 Flow** (Java) application on Spring Boot 4 — no Docker, no separate frontend/backend containers. Just run it.

## Features

- **High Scores Display** — Top 5 scores per machine with player initials, avatar circles, and formatted scores
- **Machine Cards** — Game-specific background images, logos, gradient borders from Stern's title data
- **Fullscreen Mode** — Click any game logo for a dedicated single-machine view (ESC to exit)
- **Live Updates** — Server Push via WebSocket keeps all connected browsers in sync
- **New Score Detection** — Toast notification + confetti celebration when a new high score appears
- **Auto-Scroll** — Smooth scrolling through machines, pauses on user interaction
- **Status Indicators** — Green/red dots showing machine online/offline status
- **Tech Alerts** — Warning popup with Stern's 7-day tech alert data
- **Responsive** — Adapts to desktop, tablet, and mobile

## Prerequisites

- Java 21+
- A [Stern Insider](https://insider.sternpinball.com/) account with registered machines

## Quick Start

```bash
STERN_USERNAME="your@email.com" STERN_PASSWORD="yourpassword" ./mvnw spring-boot:run
```

Open http://localhost:8080 — that's it.

## Configuration

All settings can be overridden via environment variables:

| Environment Variable | Default | Description |
|---|---|---|
| `STERN_USERNAME` | *(required)* | Stern Insider account email |
| `STERN_PASSWORD` | *(required)* | Stern Insider account password |
| `DEFAULT_COUNTRY` | `DE` | Country code for location header |
| `DEFAULT_CONTINENT` | `EU` | Continent code for location header |
| `DEFAULT_STATE` | *(empty)* | State code (US users) |
| `DEFAULT_STATE_NAME` | *(empty)* | State name (US users) |
| `DATA_REFRESH_INTERVAL_MINUTES` | `60` | How often to poll Stern's API |
| `GRID_COLUMNS` | `1` | Number of columns in the machine grid |
| `DISABLE_AUTOSCROLL` | `false` | Disable automatic scrolling |
| `PORT` | `8080` | HTTP server port |

## Production Build

```bash
./mvnw package -Pproduction
java -jar target/stern-home-leaderboard-1.0-SNAPSHOT.jar
```

The production build bundles all frontend resources via Vite and runs without a dev server.

## Architecture

Single Spring Boot 4 + Vaadin 25 Flow application:

- **`stern/service/`** — Stern API authentication (`SternAuthService`), data fetching (`SternApiClient`), and in-memory caching with scheduled refresh (`LeaderboardDataService`)
- **`stern/domain/`** — Jackson-deserializable records matching Stern's JSON API
- **`ui/`** — Server-side Vaadin views (`LeaderboardView`, `FullscreenView`) and components (`MachineCard`, `HighScoresTable`, `StatusDot`, `TechAlertsPopup`)
- **`ui/broadcast/`** — Push broadcaster to update all connected UIs when data changes
- **`styles.css`** — Dark theme with Lumo overrides, responsive breakpoints, animations

The app talks only to Stern's cloud servers — not to physical machines directly.

## Credits

Inspired by [brombomb/stern-home-leaderboard](https://github.com/brombomb/stern-home-leaderboard) (Node.js + React). This is a full rewrite in Java/Vaadin.

## License

GPLv3 — see [LICENSE.md](LICENSE.md)
