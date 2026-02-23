package com.example.anticheatautoban.checks;

import com.example.anticheatautoban.AntiCheatAutoBan;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Detects speed hacking by measuring horizontal displacement per move event,
 * accounting for sprinting and Speed potion amplifier so legitimate players
 * are never falsely flagged.
 *
 * @author graffs444
 *
 * Exemptions:
 *   - Elytra gliding (isGliding()) — covers ALL boost sources:
 *       firework rockets (any type/power), wind charges, dispensers, etc.
 *     Elytra speed is uncapped by design in vanilla Minecraft and cannot
 *     be reliably checked with a simple distance threshold.
 *
 * Speed calculations (blocks per PlayerMoveEvent tick, approximate):
 *   Walking          ~0.22 b/t  → capped at 0.60 with buffer
 *   Sprinting        ~0.29 b/t  → capped at 0.80 with buffer
 *   Speed I sprint   ~0.37 b/t  → capped at 1.05 with buffer
 *   Speed II sprint  ~0.44 b/t  → capped at 1.25 with buffer
 *   Speed III+       scales up  → 0.20 added per extra level
 *
 * A 30% leniency buffer is applied on top of each threshold to absorb
 * minor server-side lag without producing false positives.
 */
public class SpeedCheck {

    private static final double BASE_WALK_MAX   = 0.60;
    private static final double BASE_SPRINT_MAX = 0.80;
    private static final double SPEED_PER_LEVEL = 0.20;
    private static final double LENIENCY_MULT   = 1.30;

    private final AntiCheatAutoBan plugin;
    private final DiscordWebhook   webhook;

    public SpeedCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void check(Player player, PlayerData data, Location from, Location to) {
        // Elytra gliding — isGliding() is true for ALL boost sources:
        // firework rockets of any type/power, wind charges, dispensers, etc.
        // Elytra flight speed is uncapped in vanilla so we skip it entirely.
        if (player.isGliding()) return;

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
        double base       = player.isSprinting() ? BASE_SPRINT_MAX : BASE_WALK_MAX;
        double potionBonus = getSpeedLevel(player) * SPEED_PER_LEVEL;
        return (base + potionBonus) * LENIENCY_MULT;
    }

    /**
     * Returns the Speed potion amplifier.
     * 0 = no potion, 1 = Speed I, 2 = Speed II, etc.
     * (PotionEffect amplifier is 0-indexed, so we add 1 for the actual level.)
     */
    private int getSpeedLevel(Player player) {
        PotionEffect effect = player.getPotionEffect(PotionEffectType.SPEED);
        if (effect == null) return 0;
        return effect.getAmplifier() + 1;
    }
}
