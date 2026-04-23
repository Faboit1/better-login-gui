# BetterLogin

A production-ready **Velocity proxy + Paper bridge** authentication plugin for hybrid (online/offline-mode) Minecraft server networks.

## Design overview

```
Client → [Velocity Proxy : BetterLogin-velocity.jar]
               │
               ├─ Premium player  →  auto-authenticated  →  Main server
               │                                              │
               └─ Cracked player  →  Main server  ←──────────┘
                                        │
                              [Paper : BetterLoginBridge.jar]
                                        │
                              Freeze player, show sign-editor dialog
                                        │
                              AUTH_ATTEMPT ──► Velocity ──► validate
                                        │
                              AUTH_RESULT ◄── Velocity
                                        │
                              Unfreeze / re-prompt / kick
```

**Velocity plugin** handles:
- Premium vs cracked detection (`player.isOnlineMode()`)
- Auth-state machine per player (in-memory + SQLite session tokens)
- BCrypt password hashing
- Routing (main server / optional limbo server)
- Plugin-message bridge protocol
- Post-auth command dispatch

**Paper bridge plugin** handles:
- Receiving bridge messages from Velocity
- Freezing unauthenticated players (movement, chat, inventory, etc.)
- Showing the sign-editor dialog for password entry (vanilla Minecraft UI)
- Forwarding submitted passwords to Velocity for validation
- Executing post-auth commands

> **Upgrade path for MC 1.21.5 vanilla dialog screen**
> Once the Paper/PacketEvents API for the MC 1.21.5 `CustomDialogScreen` stabilises, the
> `SignEditorDialogHandler` class can be replaced by a `VanillaDialogHandler` that sends
> the real dialog packet. The `DialogHandler` interface ensures the rest of the code
> requires no changes.

---

## Requirements

| Component | Version |
|-----------|---------|
| Java | 21+ |
| Velocity proxy | 3.3.0+ |
| Paper / Purpur | 1.21.4+ |
| PacketEvents | 2.7.0 (bundled in paper plugin JAR) |

---

## Building

### GitHub Actions (recommended)
Every push to `main` / `master` triggers an automated build.
Download the compiled JARs from the **Actions → Build → Artifacts** section.

### Local build
```bash
git clone https://github.com/Faboit1/better-login-gui.git
cd better-login-gui
./gradlew build
```
Artifacts:
- `velocity-plugin/build/libs/velocity-plugin-1.0.0.jar`
- `paper-plugin/build/libs/paper-plugin-1.0.0.jar`

---

## Installation

1. **Velocity proxy**
   - Drop `velocity-plugin-*.jar` into `plugins/`.
   - Edit `plugins/better-login/config.yml` (created on first run).
   - In `velocity.toml`, set `online-mode = false` if you support cracked players.
     Premium/cracked detection works automatically regardless.

2. **Paper main server**
   - Drop `paper-plugin-*.jar` into `plugins/`.
   - In `server.properties`, set `online-mode = false`
     (the proxy handles authentication).
   - Enable Velocity forwarding in `paper-global.yml`:
     ```yaml
     proxies:
       velocity:
         enabled: true
         online-mode: true        # set to match your Velocity online-mode
         secret: "your-secret"
     ```

3. **Velocity forwarding**
   In `velocity.toml`:
   ```toml
   player-info-forwarding-mode = "modern"
   ```

---

## Configuration

### Velocity plugin (`plugins/better-login/config.yml`)

```yaml
servers:
  main: "main"      # backend server name as in velocity.toml
  limbo: ""         # optional limbo server for unauthenticated players

session:
  enabled: true
  max-age-seconds: 86400   # 24 h session lifetime for cracked players

security:
  max-login-attempts: 5
  auth-timeout-seconds: 60
  min-password-length: 6

messages:
  login-prompt:       "&aPlease log in using the dialog on your screen."
  register-prompt:    "&aWelcome! Please choose a password using the dialog."
  login-success:      "&aLogged in successfully. Welcome back!"
  register-success:   "&aAccount created successfully. Welcome!"
  login-failed:       "&cWrong password. Please try again."
  already-registered: "&cYou already have an account. Please log in instead."
  password-too-short: "&cPassword must be at least {min} characters long."
  kicked:             "&cToo many failed attempts. You have been disconnected."
  timeout:            "&cAuthentication timed out. Please reconnect."

commands:
  on-login:
    # Executed on the Paper server after successful login. Placeholders: {player}, {uuid}
    # - "lp user {player} parent set vip"
  on-register:
    # - "give {player} diamond 1"
```

### Paper bridge (`plugins/BetterLoginBridge/config.yml`)

```yaml
messages:
  welcome: "&aWelcome, {player}!"
  login-prompt: "&eLine 1: Enter your password, then click Done."
  register-prompt: "&eLine 1: Choose a password, then click Done."
```

---

## Auth flow (detailed)

```
1. Player connects to Velocity.
2. LoginEvent → check isOnlineMode():
     true  → AuthState = PREMIUM (auto-auth, no password needed)
     false → check SQLite for existing record:
               missing            → AuthState = PENDING_DIALOG (register)
               present + valid session → AuthState = SESSION_VALID (auto-auth)
               present + expired  → AuthState = PENDING_DIALOG (login)

3. PlayerChooseInitialServerEvent → route all players to main server
   (or limbo, if configured).

4. ServerConnectedEvent:
     PREMIUM / SESSION_VALID → send AUTH_SUCCESS plugin message to Paper
                                → Paper shows welcome message, runs on-login commands
     PENDING_DIALOG          → send AUTH_REQUIRED plugin message to Paper
                                → Paper freezes player, opens sign-editor dialog
                                → Timeout scheduler starts

5. Player types password in sign editor → clicks Done.

6. Paper → Velocity: AUTH_ATTEMPT message with password (plain-text over
   the internal plugin-messaging channel, never leaves the server process).

7. Velocity:
     Register: BCrypt hash + store in SQLite → AUTH_RESULT:SUCCESS
     Login:    BCrypt verify                 → AUTH_RESULT:SUCCESS / FAIL
     Too many failures → kick

8. Paper on AUTH_RESULT:
     SUCCESS → remove from pendingAuth set, unfreeze, run on-login/on-register commands
     FAIL    → re-open dialog (with 1 s delay)

9. Timeout fires → kick player if still pending.
```

---

## Limitations & version notes

| Item | Note |
|------|------|
| Vanilla dialog screen | Requires MC 1.21.5+. This build uses the sign-editor GUI (compatible with all versions). See the upgrade path note above. |
| Password transmission | Plain-text over the internal Velocity ↔ Paper plugin-message channel. This channel is local-machine only and never crosses the public network. |
| SQLite concurrency | Auth DB is on the Velocity machine. For multi-proxy setups, replace `SQLiteStorage` with a MySQL/MariaDB implementation implementing `AuthStorage`. |
| Session security | Session tokens are UUID v4 random strings stored in SQLite. They are not bearer tokens sent to the client; they are purely server-side expiry markers. |
| Limbo support | Set `servers.limbo` to a lightweight server (e.g. NanoLimbo) to isolate unauthenticated players from the main world entirely. |
