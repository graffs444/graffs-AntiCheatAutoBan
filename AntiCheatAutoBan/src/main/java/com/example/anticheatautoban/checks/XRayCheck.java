package com.example.anticheatautoban.checks;

import com.example.anticheatautoban.AntiCheatAutoBan;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Deque;
import java.util.Set;

/**
 * Ore logging + XRay behavioral detection for Paper 1.21.4.
 *
 * @author graffs444
 *
 * Every ore break sends a Discord INFO log.
 * Suspicious rare-ore patterns send a Discord WARNING log.
 *
 * XRay NEVER kicks, bans, or takes any action against the player.
 * It is purely a logging and flagging system for staff to review manually.
 */
public class XRayCheck {

    private static final Set<Material> ALL_ORES = Set.of(
            Material.COAL_ORE,              Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE,              Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE,            Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE,              Material.DEEPSLATE_GOLD_ORE,
            Material.NETHER_GOLD_ORE,
            Material.REDSTONE_ORE,          Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE,             Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE,           Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,           Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );

    private static final Set<Material> RARE_ORES = Set.of(
            Material.DIAMOND_ORE,           Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,           Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS,
            Material.GOLD_ORE,              Material.DEEPSLATE_GOLD_ORE,
            Material.NETHER_GOLD_ORE
    );

    private static final long BURST_WINDOW_MS = 30_000L;

    // Webhook URL hardcoded in DiscordWebhook.HARDCODED_URL as fallback
    private final AntiCheatAutoBan plugin;
    private final DiscordWebhook   webhook;

    public XRayCheck(AntiCheatAutoBan plugin, DiscordWebhook webhook) {
        this.plugin  = plugin;
        this.webhook = webhook;
    }

    public void onBlockBreak(BlockBreakEvent event, PlayerData data) {
        Player   player = event.getPlayer();
        Material broken = event.getBlock().getType();
        Location loc    = event.getBlock().getLocation();

        if (!ALL_ORES.contains(broken)) return;

        String label = oreLabel(broken);

        // --- Log every ore mine to Discord (INFO, no action) ---
        String mineDetail = "Mined %s at X:%.0f Y:%.0f Z:%.0f in %s"
                .formatted(label, loc.getX(), loc.getY(), loc.getZ(), player.getWorld().getName());

        plugin.getLogger().info("[AC][Ore] %s | %s".formatted(player.getName(), mineDetail));
        webhook.sendAlert(player, "Ore Mined", mineDetail, DiscordWebhook.Severity.INFO);

        // --- XRay pattern detection — flag to Discord only, no punishment ---
        if (RARE_ORES.contains(broken) && loc.getY() <= plugin.getXrayMinY()) {
            checkPattern(player, data, label, loc);
        } else if (!RARE_ORES.contains(broken)) {
            data.resetConsecutiveRareOres();
        }
    }

    private void checkPattern(Player player, PlayerData data, String label, Location loc) {
        long now = System.currentTimeMillis();

        data.incrementConsecutiveRareOres();
        int streak = data.getConsecutiveRareOres();

        Deque<Long> recent = data.getRecentRareOreTimes();
        recent.addLast(now);
        while (!recent.isEmpty() && (now - recent.peekFirst()) > BURST_WINDOW_MS) {
            recent.pollFirst();
        }
        int burst = recent.size();

        boolean suspicious = streak >= plugin.getXraySuspiciousStreak()
                          || burst  >= plugin.getXrayBurstThreshold();

        if (!suspicious) return;

        // ---------------------------------------------------------------
        // LOG ONLY — no kick, no ban, no in-game message to the player.
        // Staff should review this Discord alert and decide manually.
        // ---------------------------------------------------------------
        String detail = "%s | streak: %d consecutive | %d rare ores in 30s | Y:%.0f | FLAG ONLY - no action taken"
                .formatted(label, streak, burst, loc.getY());

        plugin.getLogger().warning("[AC][XRay] %s | %s".formatted(player.getName(), detail));
        webhook.sendAlert(player, "Possible XRay (Flag Only)", detail, DiscordWebhook.Severity.WARNING);
    }

    private static String oreLabel(Material mat) {
        return switch (mat) {
            case COAL_ORE, DEEPSLATE_COAL_ORE         -> "Coal Ore";
            case IRON_ORE, DEEPSLATE_IRON_ORE         -> "Iron Ore";
            case COPPER_ORE, DEEPSLATE_COPPER_ORE     -> "Copper Ore";
            case GOLD_ORE, DEEPSLATE_GOLD_ORE         -> "Gold Ore";
            case NETHER_GOLD_ORE                      -> "Nether Gold Ore";
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> "Redstone Ore";
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE       -> "Lapis Ore";
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE   -> "Diamond Ore";
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE   -> "Emerald Ore";
            case NETHER_QUARTZ_ORE                    -> "Nether Quartz Ore";
            case ANCIENT_DEBRIS                       -> "Ancient Debris";
            default                                   -> mat.name();
        };
    }
}
