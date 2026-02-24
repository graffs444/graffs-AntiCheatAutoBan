package com.example.anticheatautoban;

import com.example.anticheatautoban.checks.FlyCheck;
import com.example.anticheatautoban.checks.FreecamCheck;
import com.example.anticheatautoban.checks.SpeedCheck;
import com.example.anticheatautoban.checks.XRayCheck;
import com.example.anticheatautoban.data.PlayerData;
import com.example.anticheatautoban.discord.DiscordWebhook;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author graffs444
 */
public class AntiCheatAutoBan extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> playerDataMap = new HashMap<>();

    private FlyCheck       flyCheck;
    private SpeedCheck     speedCheck;
    private FreecamCheck   freecamCheck;
    private XRayCheck      xrayCheck;
    private DiscordWebhook webhook;

    // Config-loaded values
    private int  flyBanThreshold;
    private int  speedBanThreshold;
    private int  freecamBanThreshold;
    private int  xrayMinY;
    private int  xraySuspiciousStreak;
    private int  xrayBurstThreshold;
    private long decayIntervalTicks;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        String configUrl = getConfig().getString("discord.webhook-url", "");
        webhook      = new DiscordWebhook(this, configUrl);
        flyCheck     = new FlyCheck(this, webhook);
        speedCheck   = new SpeedCheck(this, webhook);
        freecamCheck = new FreecamCheck(this, webhook);
        xrayCheck    = new XRayCheck(this, webhook);

        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () ->
                playerDataMap.values().forEach(d -> {
                    d.decayFlyViolations();
                    d.decaySpeedViolations();
                    d.decayFreecamViolations();
                    d.decayXrayViolations();
                }),
                decayIntervalTicks, decayIntervalTicks);

        boolean webhookSet = DiscordWebhook.isValidUrl(configUrl);
        getLogger().info("AntiCheatAutoBan v2.0.0 enabled (Paper 1.21.4)");
        getLogger().info("  Checks : Fly | Speed | Freecam | XRay/OreMine");
        getLogger().info("  Discord: " + (webhookSet ? "configured" : "NOT SET — add webhook URL to config.yml"));

        if (webhookSet) {
            getServer().getScheduler().runTaskAsynchronously(this, this::sendStartupPing);
        }
    }

    private void sendStartupPing() {
        try {
            String configUrl = getConfig().getString("discord.webhook-url", "");
            if (!DiscordWebhook.isValidUrl(configUrl)) return;

            String json = "{\"embeds\":[{\"title\":\"AntiCheatAutoBan Online\","
                    + "\"color\":65280,"
                    + "\"description\":\"Plugin v2.0.0 loaded on Paper 1.21.4. All checks active.\","
                    + "\"footer\":{\"text\":\"AntiCheatAutoBan v2.0.0\"},"
                    + "\"timestamp\":\"" + java.time.Instant.now() + "\"}]}";

            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    java.net.URI.create(configUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "AntiCheatAutoBan/2.0.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            getLogger().info("[Discord] Startup ping: HTTP " + code + (code == 204 ? " (success)" : " (check webhook URL)"));
            conn.disconnect();
        } catch (Exception e) {
            getLogger().warning("[Discord] Startup ping failed: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("AntiCheatAutoBan disabled.");
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        playerDataMap.put(event.getPlayer().getUniqueId(), new PlayerData());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerDataMap.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Grants a teleport grace period for any legitimate cause of teleportation.
     *
     * Covered causes:
     *   CHORUS_FRUIT       — eating chorus fruit
     *   ENDER_PEARL        — ender pearl throw
     *   SPECTATOR          — spectator mode click-through
     *   PLUGIN             — catches ALL plugin-issued teleports:
     *                        /tpa, /tpr, /rtp, /back, /home, /spawn, /warp,
     *                        and any other plugin that calls player.teleport()
     *
     * Riptide tridents are NOT a TeleportCause — they use velocity, not teleport.
     * They produce a large PlayerMoveEvent jump instead, which is caught by the
     * grace period we set on RIPTIDE detection via the player's item-in-hand check
     * in onPlayerMove below.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        TeleportCause cause = event.getCause();
        switch (cause) {
            case ENDER_PEARL:
            case CHORUS_FRUIT:
            case SPECTATOR:
            case PLUGIN:
            case COMMAND:
                PlayerData data = getOrCreate(event.getPlayer());
                data.setTeleportGrace();
                // Also reset freecam stationary state so the arrival location
                // becomes the new "last known position" cleanly
                data.setWasStationary(false);
                data.setLastKnownLocation(event.getTo() != null ? event.getTo().clone() : null);
                data.setLastMoveTime(System.currentTimeMillis());
                break;
            default:
                break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        PlayerData data = getOrCreate(player);

        // Tick down teleport grace counter on every move event
        data.tickTeleportGrace();

        // Detect riptide: player is in rain/water, holding a riptide trident,
        // and has just launched (large vertical+horizontal velocity burst).
        // Riptide uses velocity not teleport, so we grant grace here.
        if (!data.isInTeleportGrace()) {
            checkRiptideGrace(player, data, event.getFrom(), event.getTo());
        }

        freecamCheck.check(player, data, event.getFrom(), event.getTo());

        if (!blockPositionChanged(event)) return;

        flyCheck.check(player, data);
        speedCheck.check(player, data, event.getFrom(), event.getTo());
    }

    /**
     * Grants teleport grace when a riptide launch is detected.
     * Riptide produces a large velocity burst — typically 2+ blocks per tick —
     * while the player is in water or rain and holding a riptide trident.
     */
    private void checkRiptideGrace(Player player, PlayerData data, org.bukkit.Location from, org.bukkit.Location to) {
        org.bukkit.util.Vector vel = player.getVelocity();
        double speed = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ() + vel.getY() * vel.getY());

        // Fast velocity burst while in water or rain — likely riptide
        if (speed > 1.5 && (player.getLocation().getBlock().isLiquid()
                || player.getWorld().hasStorm())) {
            // Check if holding a riptide trident
            org.bukkit.inventory.ItemStack main = player.getInventory().getItemInMainHand();
            org.bukkit.inventory.ItemStack off  = player.getInventory().getItemInOffHand();
            if (hasRiptide(main) || hasRiptide(off)) {
                data.setTeleportGrace();
                data.setWasStationary(false);
            }
        }
    }

    private boolean hasRiptide(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.TRIDENT) return false;
        if (!item.hasItemMeta()) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta)
                && meta.hasEnchant(org.bukkit.enchantments.Enchantment.RIPTIDE)) return true;
        return meta.hasEnchant(org.bukkit.enchantments.Enchantment.RIPTIDE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        xrayCheck.onBlockBreak(event, getOrCreate(player));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void autoBan(Player player, String reason) {
        UUID id = player.getUniqueId();
        PlayerData data = playerDataMap.get(id);
        if (data != null) {
            data.resetFlyViolations();
            data.resetSpeedViolations();
            data.resetFreecamViolations();
        }

        webhook.sendAlert(player, "Player Banned", reason, DiscordWebhook.Severity.BAN);

        String name = player.getName();
        getLogger().warning("[AC] Banning %s for: %s".formatted(name, reason));
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban %s %s".formatted(name, reason)));
    }

    public int getFlyBanThreshold()        { return flyBanThreshold; }
    public int getSpeedBanThreshold()      { return speedBanThreshold; }
    public int getFreecamBanThreshold()    { return freecamBanThreshold; }
    public int getXrayMinY()               { return xrayMinY; }
    public int getXraySuspiciousStreak()   { return xraySuspiciousStreak; }
    public int getXrayBurstThreshold()     { return xrayBurstThreshold; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isExempt(Player player) {
        return switch (player.getGameMode()) {
            case CREATIVE, SPECTATOR -> true;
            default -> player.isFlying()
                    || player.isInsideVehicle()
                    || player.hasPermission("anticheat.bypass")
                    || player.isGliding()
                    || player.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                    || player.hasPotionEffect(PotionEffectType.LEVITATION)
                    || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        };
    }

    private boolean blockPositionChanged(PlayerMoveEvent event) {
        return event.getFrom().getBlockX() != event.getTo().getBlockX()
            || event.getFrom().getBlockY() != event.getTo().getBlockY()
            || event.getFrom().getBlockZ() != event.getTo().getBlockZ();
    }

    private PlayerData getOrCreate(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), k -> new PlayerData());
    }

    private void loadConfigValues() {
        flyBanThreshold      = getConfig().getInt("thresholds.fly-ban",    10);
        speedBanThreshold    = getConfig().getInt("thresholds.speed-ban",   8);
        freecamBanThreshold  = getConfig().getInt("thresholds.freecam-ban", 6);
        xrayMinY             = getConfig().getInt("xray.min-y-level",       0);
        xraySuspiciousStreak = getConfig().getInt("xray.suspicious-ore-streak", 5);
        xrayBurstThreshold   = getConfig().getInt("xray.burst-threshold",   4);
        decayIntervalTicks   = getConfig().getLong("decay-interval-ticks", 100L);
    }
}
