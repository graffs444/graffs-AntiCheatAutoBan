# AntiCheatAutoBan v2.0.0

Paper 1.21.4 anti-cheat plugin with fly, speed, freecam, and XRay detection,
plus Discord webhook logging for every ore mined and every suspicious event.

## Requirements

| Requirement | Version   |
|-------------|-----------|
| Server      | Paper 1.21.4 (not Spigot) |
| Java        | 21+       |
| Maven       | 3.6+ (to build) |

## Checks

| Check    | What it detects                                        | Auto-ban?      |
|----------|--------------------------------------------------------|----------------|
| Fly      | Sustained upward velocity without ground contact       | Yes (threshold)|
| Speed    | Horizontal movement exceeding sprint maximum           | Yes (threshold)|
| Freecam  | Position snap after stationary period, pitch lock      | Yes (threshold)|
| XRay     | Every ore logged; rare ore streak/burst flagged        | No (alert only)|

## Discord Message Types

| Colour | Trigger                              |
|--------|--------------------------------------|
| 🔵 Blue | Every ore mined (info log)           |
| 🟡 Yellow | Suspicious pattern (XRay, warning) |
| 🔴 Red  | Player auto-banned                   |

## Building

```bash
cd AntiCheatAutoBan
mvn clean package
# Output: target/AntiCheatAutoBan-2.0.0.jar
```

> Maven will download the Paper API from repo.papermc.io automatically.
> No extra setup needed.

## Installation

1. Copy `AntiCheatAutoBan-2.0.0.jar` into your Paper server's `plugins/` folder.
2. Start the server once — this generates `plugins/AntiCheatAutoBan/config.yml`.
3. Open `config.yml` and paste in your Discord webhook URL.
4. Restart the server or run `/reload confirm`.

## Discord Webhook Setup

1. In your Discord server: **Channel Settings → Integrations → Webhooks → New Webhook**
2. Copy the webhook URL.
3. In `config.yml`:

```yaml
discord:
  webhook-url: "https://discord.com/api/webhooks/YOUR_ID/YOUR_TOKEN"
  enabled: true
```

> Tip: Use a dedicated `#ore-log` channel — every ore break sends a message,
> which can be noisy in a busy server channel.

## config.yml Reference

```yaml
discord:
  webhook-url: "https://discord.com/api/webhooks/..."
  enabled: true

thresholds:
  fly-ban: 10       # violations before auto-ban
  speed-ban: 8
  freecam-ban: 6

xray:
  min-y-level: 0              # rare ores flagged only at or below this Y
  suspicious-ore-streak: 5    # consecutive rare ores before WARNING
  burst-threshold: 4          # rare ores in 30 seconds before WARNING

decay-interval-ticks: 100     # how often violations decrease (20 ticks = 1s)
```

## Permissions

| Permission           | Default | Description                      |
|----------------------|---------|----------------------------------|
| `anticheat.bypass`   | OP      | Bypasses all checks              |
| `anticheat.alerts`   | OP      | Receives in-game XRay alerts     |

## Notes

- **XRay never auto-bans.** Enable Paper's built-in `engine-mode: 2` anti-xray
  in `config/paper-world-defaults.yml` to also make XRay less effective client-side.
- **`PotionEffectType.JUMP_BOOST`** — this plugin uses the 1.20.5+ registry name.
  It will not compile against older Spigot/Bukkit APIs.
- This plugin uses the **Adventure API** for in-game staff messages — Paper bundles
  Adventure natively, no extra shading required.
