package com.example.anticheatautoban.checks;

import com.example.anticheatautoban.AntiCheatAutoBan;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Freecam detection using two heuristics.
 *
 * @author graffs444
 *
 * 1. POSITION SNAP — After a player is stationary for 8+ seconds, they suddenly
 *    teleport 5+ blocks. Freecam modules detach the camera from the server-side
 *    entity; when the player closes freecam, the entity "snaps" to where the
 *    camera was.
 *
 * 2. PITCH LOCK — Camera pitch is stuck near ±90° while the player hasn't moved,
 *    a pattern produced by some freecam implementations that freeze the view angle.
 *
 * Note: AFK players with an extreme camera angle will look similar. Tune
 * freecam-ban threshold higher if your server has long AFK periods.
 */
public class FreecamCheck {

    private static final long  STATIONARY_THRESHOLD_MS = 8_000L; // 8 seconds
    private static final double SNAP_DISTANCE           = 5.0;    // blocks
    private static final float  PITCH_LOCK_THRESHOLD    = 89.5f;

    private final AntiCheatAutoBan plugin;
    // Webhook URL hardcoded in DiscordWebhook.HARDCODED_URL as fallback
    private final DiscordWebhook   webhook;

    public FreecamCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void check(Player player, PlayerData data, Location from, Location to) {
        long now             = System.currentTimeMillis();
        boolean movedBlock   = blockPositionChanged(from, to);

        if (movedBlock) {
            // Check for snap after stationary period
            if (data.wasStationary() && data.getLastKnownLocation() != null) {
                double snap = data.getLastKnownLocation().distance(to);
                if (snap > SNAP_DISTANCE) {
                    flag(player, data,
                         "Position snap after stationary period (%.1f blocks)".formatted(snap));
                }
            }
            data.setLastKnownLocation(to.clone());
            data.setLastMoveTime(now);
            data.setWasStationary(false);

        } else {
            long stationaryMs = now - data.getLastMoveTime();
            if (stationaryMs >= STATIONARY_THRESHOLD_MS) {
                data.setWasStationary(true);
                float pitch = player.getLocation().getPitch();
                if (Math.abs(pitch) >= PITCH_LOCK_THRESHOLD) {
                    flag(player, data,
                         "Pitch locked at %.1f° while stationary for %ds"
                             .formatted(pitch, stationaryMs / 1000));
                }
            }
        }
    }

    private void flag(Player player, PlayerData data, String detail) {
        int violations = data.incrementFreecamViolations();
        String full    = detail + " | violations: " + violations;

        plugin.getLogger().warning("[AC][Freecam] %s | %s".formatted(player.getName(), full));
        webhook.sendAlert(player, "Freecam", full,
                violations >= plugin.getFreecamBanThreshold()
                        ? DiscordWebhook.Severity.BAN
                        : DiscordWebhook.Severity.WARNING);

        if (violations >= plugin.getFreecamBanThreshold()) {
            plugin.autoBan(player, "Freecam (%d violations)".formatted(violations));
        }
    }

    private boolean blockPositionChanged(Location from, Location to) {
        return from.getBlockX() != to.getBlockX()
            || from.getBlockY() != to.getBlockY()
            || from.getBlockZ() != to.getBlockZ();
    }
}
