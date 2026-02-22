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

        // Use config URL if valid, otherwise the hardcoded URL in DiscordWebhook.HARDCODED_URL is used
        String configUrl = getConfig().getString("discord.webhook-url", "");
        webhook      = new DiscordWebhook(this, configUrl);
        flyCheck     = new FlyCheck(this, webhook);
        speedCheck   = new SpeedCheck(this, webhook);
        freecamCheck = new FreecamCheck(this, webhook);
        xrayCheck    = new XRayCheck(this, webhook);

        getServer().getPluginManager().registerEvents(this, this);

        // Periodically decay violation counts to forgive transient lag spikes
        Bukkit.getScheduler().runTaskTimer(this, () ->
                playerDataMap.values().forEach(d -> {
                    d.decayFlyViolations();
                    d.decaySpeedViolations();
                    d.decayFreecamViolations();
                    d.decayXrayViolations();
                }),
                decayIntervalTicks, decayIntervalTicks);

        String webhookSource = DiscordWebhook.isValidUrl(configUrl)
                ? "config.yml URL"
                : "hardcoded URL (config.yml not set)";

        getLogger().info("AntiCheatAutoBan v2.0.0 enabled (Paper 1.21.4)");
        getLogger().info("  Checks : Fly | Speed | Freecam | XRay/OreMine");
        getLogger().info("  Discord: " + webhookSource);

        // Fire a test ping to Discord on startup so you can confirm it's working
        getServer().getScheduler().runTaskAsynchronously(this, () -> sendStartupPing());
    }

    private void sendStartupPing() {
        try {
            String json = "{\"embeds\":[{\"title\":\"AntiCheatAutoBan Online\","
                    + "\"color\":65280,"
                    + "\"description\":\"Plugin v2.0.0 loaded on Paper 1.21.4. All checks active.\","
                    + "\"footer\":{\"text\":\"AntiCheatAutoBan v2.0.0\"},"
                    + "\"timestamp\":\"" + java.time.Instant.now() + "\"}]}";

            String url = com.example.anticheatautoban.discord.DiscordWebhook.HARDCODED_URL;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    java.net.URI.create(url).toURL().openConnection();
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        PlayerData data = getOrCreate(player);

        // Freecam runs on every event — it monitors stationary players too
        freecamCheck.check(player, data, event.getFrom(), event.getTo());

        // Fly and speed only need to fire when the block position actually changes
        // (PlayerMoveEvent fires on head rotation too — this filters that out)
        if (!blockPositionChanged(event)) return;

        flyCheck.check(player, data);
        speedCheck.check(player, data, event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        xrayCheck.onBlockBreak(event, getOrCreate(player));
    }

    // -------------------------------------------------------------------------
    // Public API (used by check classes)
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

    // Config getters
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
            // NOTE: Sprinting and Speed potion are NOT exempted here.
            // SpeedCheck dynamically raises the allowed threshold based on
            // player.isSprinting() and the Speed potion amplifier level,
            // so legitimate sprinting/Speed I/II players are never flagged.
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
