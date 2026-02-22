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
 * WHY THE OLD CHECK FLAGGED JUMPING:
 * A normal jump produces Y velocity ~0.42 and getFallDistance() = 0 at launch,
 * which passed all the old conditions and triggered a false flag every jump.
 *
 * HOW THIS CHECK WORKS:
 * We count how many consecutive move events the player has been airborne with
 * upward velocity. A real jump peaks at roughly 6-8 ticks of upward movement
 * before gravity pulls the Y velocity negative. A fly hacker sustains positive
 * Y velocity far beyond that.
 *
 * Thresholds:
 *   UPWARD_VELOCITY_MIN  (0.08) — below this we ignore tiny bobbing/slopes
 *   MAX_JUMP_TICKS       (12)   — normal jump + generous lag buffer
 *   Flag fires only when the player exceeds MAX_JUMP_TICKS of continuous
 *   upward airborne movement, which no legitimate jump can do.
 *
 * Counter resets the moment the player:
 *   - touches the ground (isOnGround())
 *   - starts falling (Y velocity goes negative)
 *   - enters water/lava (which resets fall mechanics)
 */
public class FlyCheck {

    // Minimum upward Y velocity to count as "actively flying upward"
    private static final double UPWARD_VELOCITY_MIN = 0.08;

    // A normal jump sustains upward velocity for ~6-8 ticks.
    // We set the threshold to 12 to absorb lag spikes and still catch hackers.
    private static final int MAX_JUMP_TICKS = 12;

    // Webhook URL hardcoded in DiscordWebhook.HARDCODED_URL as fallback
    private final AntiCheatAutoBan plugin;
    private final DiscordWebhook   webhook;

    public FlyCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void check(Player player, PlayerData data) {
        Vector vel = player.getVelocity();

        // Player landed or started falling — reset the counter and exit
        if (player.isOnGround() || vel.getY() <= 0) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // Player is in liquid — reset, water/lava have their own movement rules
        if (player.getLocation().getBlock().isLiquid()
                || player.getLocation().clone().subtract(0, 0.5, 0).getBlock().isLiquid()) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // Only count ticks where the player is meaningfully moving upward
        if (vel.getY() < UPWARD_VELOCITY_MIN) {
            data.resetAirborneUpwardTicks();
            return;
        }

        // Player is airborne and moving upward — increment the tick counter
        int ticks = data.incrementAirborneUpwardTicks();

        // Only flag if they've been rising longer than any legitimate jump allows
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
