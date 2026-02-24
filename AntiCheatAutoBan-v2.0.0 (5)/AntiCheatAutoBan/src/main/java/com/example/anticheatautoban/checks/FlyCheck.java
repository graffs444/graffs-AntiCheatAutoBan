package com.example.anticheatautoban.checks;

import com.example.anticheatautoban.AntiCheatAutoBan;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Detects fly hacking using a sustained airborne tick counter.
 *
 * @author graffs444
 *
 * HOW THIS CHECK WORKS:
 * We count how many consecutive move events the player has been airborne with
 * upward velocity. A real jump peaks at roughly 6-8 ticks of upward movement
 * before gravity pulls the Y velocity negative. A fly hacker sustains positive
 * Y velocity far beyond that. Flag fires only past MAX_JUMP_TICKS (12).
 *
 * Exemptions (counter reset, no flag):
 *   - Teleport grace period — covers riptide trident launches, ender pearl
 *     landings, and ALL plugin TPs (/tpa, /tpr, /rtp, /back, /home, etc.)
 *     Riptide in particular launches the player with a large upward velocity
 *     burst that would otherwise trip the tick counter immediately.
 *   - On ground / falling (Y vel <= 0)
 *   - In water or lava
 *   - Velocity below UPWARD_VELOCITY_MIN (0.08)
 */
public class FlyCheck {

    private static final double UPWARD_VELOCITY_MIN = 0.08;
    private static final int    MAX_JUMP_TICKS      = 12;

    private final AntiCheatAutoBan plugin;
    private final DiscordWebhook   webhook;

    public FlyCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void check(Player player, PlayerData data) {
        Vector vel = player.getVelocity();

        // Teleport grace — riptide, ender pearls, and all plugin TPs (/tpa, /tpr, /rtp, etc.)
        // produce large upward velocity bursts that would trip the tick counter.
        // Reset and skip until grace expires.
        if (data.isInTeleportGrace()) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // Landed or falling — reset counter
        if (player.isOnGround() || vel.getY() <= 0) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // In liquid — water/lava have their own movement rules
        if (player.getLocation().getBlock().isLiquid()
                || player.getLocation().clone().subtract(0, 0.5, 0).getBlock().isLiquid()) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // Ignore tiny vertical bobbing
        if (vel.getY() < UPWARD_VELOCITY_MIN) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // Airborne and moving upward — count the tick
        int ticks = data.incrementAirborneUpwardTicks();

        if (ticks <= MAX_JUMP_TICKS) return;

        int violations = data.incrementFlyViolations();
        String detail = "Airborne upward ticks: %d (max legit: %d) | Y vel: %.3f | violations: %d"
                .formatted(ticks, MAX_JUMP_TICKS, vel.getY(), violations);

        plugin.getLogger().warning("[AC][Fly] %s | %s".formatted(player.getName(), detail));
        webhook.sendAlert(player, "Fly Hack", detail,
                violations >= plugin.getFlyBanThreshold()
                        ? DiscordWebhook.Severity.BAN
                        : DiscordWebhook.Severity.WARNING);

        if (violations >= plugin.getFlyBanThreshold()) {
            plugin.autoBan(player, "Fly hacking (%d violations)".formatted(violations));
        }
    }
}
