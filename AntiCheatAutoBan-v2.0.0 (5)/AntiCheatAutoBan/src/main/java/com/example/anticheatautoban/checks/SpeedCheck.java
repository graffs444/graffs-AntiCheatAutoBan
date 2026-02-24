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
 *
 * Exemptions (no flag, no violation):
 *   - Elytra gliding (any boost source — fireworks, wind charges, etc.)
 *   - Teleport grace period — covers:
 *       Riptide trident launch
 *       Ender pearl throw landing
 *       Any plugin /tp command: /tpa, /tpr, /rtp, /back, /home, /spawn, etc.
 *     Grace lasts 20 ticks (1 second) after the teleport, reset by PlayerTeleportEvent.
 *
 * Speed thresholds (blocks per PlayerMoveEvent tick, ~30% leniency included):
 *   Walking          → 0.60
 *   Sprinting        → 0.80
 *   Speed I sprint   → 1.05
 *   Speed II sprint  → 1.25
 *   Speed III+       → +0.20 per extra level
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
        // Elytra — covers fireworks of any kind, wind charges, dispensers, etc.
        if (player.isGliding()) return;

        // Teleport grace — covers riptide, ender pearls, and all plugin TPs
        // (/tpa, /tpr, /rtp, /back, /home, /spawn, etc.)
        if (data.isInTeleportGrace()) return;

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

    private double calculateMaxSpeed(Player player) {
        double base        = player.isSprinting() ? BASE_SPRINT_MAX : BASE_WALK_MAX;
        double potionBonus = getSpeedLevel(player) * SPEED_PER_LEVEL;
        return (base + potionBonus) * LENIENCY_MULT;
    }

    private int getSpeedLevel(Player player) {
        PotionEffect effect = player.getPotionEffect(PotionEffectType.SPEED);
        if (effect == null) return 0;
        return effect.getAmplifier() + 1;
    }
}
