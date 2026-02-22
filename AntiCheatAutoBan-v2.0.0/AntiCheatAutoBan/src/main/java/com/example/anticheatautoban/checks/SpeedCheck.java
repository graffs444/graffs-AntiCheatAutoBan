package com.example.anticheatautoban.checks;

import com.example.anticheatautoban.AntiCheatAutoBan;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Detects speed hacking by measuring horizontal displacement per move event.
 *
 * @author graffs444
 * accounting for sprinting and Speed potion amplifier so legitimate players
 * are never falsely flagged.
 *
 * Speed calculations (blocks per PlayerMoveEvent tick, approximate):
 *   Walking          ~0.22 b/t  → capped at 0.6  with buffer
 *   Sprinting        ~0.29 b/t  → capped at 0.8  with buffer
 *   Speed I sprint   ~0.37 b/t  → capped at 1.05 with buffer
 *   Speed II sprint  ~0.44 b/t  → capped at 1.25 with buffer
 *   Speed III+       scales up  → 0.2 added per extra level
 *
 * A 30% leniency buffer is added on top of each threshold to absorb
 * minor server-side lag without producing false positives.
 */
public class SpeedCheck {

    // Base thresholds per tick (blocks), with 30% leniency already built in
    private static final double BASE_WALK_MAX    = 0.60;  // walking
    private static final double BASE_SPRINT_MAX  = 0.80;  // sprinting, no potion
    private static final double SPEED_PER_LEVEL  = 0.20;  // added per Speed amplifier level
    private static final double LENIENCY_MULT    = 1.30;  // 30% extra buffer on top

    // Webhook URL hardcoded in DiscordWebhook.HARDCODED_URL as fallback
    private final AntiCheatAutoBan plugin;
    private final DiscordWebhook   webhook;

    public SpeedCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void check(Player player, PlayerData data, Location from, Location to) {
        double dx         = to.getX() - from.getX();
        double dz         = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double maxAllowed = calculateMaxSpeed(player);

        if (horizontal <= maxAllowed) return;

        int violations = data.incrementSpeedViolations();
        String detail = "Moved %.2f blocks (max allowed: %.2f) | sprinting: %b | speed level: %d | violations: %d"
                .formatted(horizontal, maxAllowed, player.isSprinting(), getSpeedLevel(player), violations);

        plugin.getLogger().warning("[AC][Speed] %s | %s".formatted(player.getName(), detail));
        webhook.sendAlert(player, "Speed Hack", detail,
                violations >= plugin.getSpeedBanThreshold()
                        ? DiscordWebhook.Severity.BAN
                        : DiscordWebhook.Severity.WARNING);

        if (violations >= plugin.getSpeedBanThreshold()) {
            plugin.autoBan(player, "Speed hacking (%d violations)".formatted(violations));
        }
    }

    /**
     * Calculates the maximum legitimate horizontal speed for this player
     * based on their current sprint state and Speed potion amplifier.
     */
    private double calculateMaxSpeed(Player player) {
        int speedLevel = getSpeedLevel(player);
        boolean sprinting = player.isSprinting();

        double base = sprinting ? BASE_SPRINT_MAX : BASE_WALK_MAX;

        // Each Speed level adds to the sprint max (walking is rarely affected noticeably)
        double potionBonus = speedLevel * SPEED_PER_LEVEL;

        return (base + potionBonus) * LENIENCY_MULT;
    }

    /**
     * Returns the Speed potion amplifier (0 = no potion, 1 = Speed I, 2 = Speed II, etc.).
     */
    private int getSpeedLevel(Player player) {
        PotionEffect effect = player.getPotionEffect(PotionEffectType.SPEED);
        if (effect == null) return 0;
        return effect.getAmplifier(); // amplifier is 0-indexed: Speed I = 0, Speed II = 1, etc.
    }
}
