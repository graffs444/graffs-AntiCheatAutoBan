package com.example.anticheatautoban.checks;

import com.example.anticheatautoban.AntiCheatAutoBan;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Freecam detection using two heuristics:
 *
 * @author graffs444
 *
 * 1. POSITION SNAP — After being stationary for 8+ seconds, the player suddenly
 *    moves 5+ blocks. Real freecam does this when the module closes.
 *
 * 2. PITCH LOCK — Camera pitch stuck at ±89.5° while stationary.
 *
 * Both heuristics are suppressed during the teleport grace period, which covers:
 *   - Riptide trident launch
 *   - Ender pearl throw landing
 *   - All plugin TPs: /tpa, /tpr, /rtp, /back, /home, /spawn, etc.
 * Without this, any teleport looks like a "position snap" and would false-flag.
 */
public class FreecamCheck {

    private static final long   STATIONARY_THRESHOLD_MS = 8_000L;
    private static final double SNAP_DISTANCE            = 5.0;
    private static final float  PITCH_LOCK_THRESHOLD     = 89.5f;

    private final AntiCheatAutoBan plugin;
    private final DiscordWebhook   webhook;

    public FreecamCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void check(Player player, PlayerData data, Location from, Location to) {
        long now           = System.currentTimeMillis();
        boolean movedBlock = blockPositionChanged(from, to);

        if (movedBlock) {
            // Check for snap after stationary period —
            // but ONLY when NOT in a teleport grace window.
            // Riptide, ender pearls, and all plugin /tp commands produce
            // exactly this pattern and must be ignored.
            if (data.wasStationary()
                    && data.getLastKnownLocation() != null
                    && !data.isInTeleportGrace()) {

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
                // Skip pitch-lock check during grace period too
                if (!data.isInTeleportGrace()) {
                    float pitch = player.getLocation().getPitch();
                    if (Math.abs(pitch) >= PITCH_LOCK_THRESHOLD) {
                        flag(player, data,
                             "Pitch locked at %.1f° while stationary for %ds"
                                 .formatted(pitch, stationaryMs / 1000));
                    }
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
