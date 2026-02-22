package com.example.anticheatautoban.discord;

import com.example.anticheatautoban.AntiCheatAutoBan;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Sends Discord embeds via webhook.
 *
 * Hardcoded URL is the primary source. config.yml URL overrides it if valid.
 * All HTTP is async — never blocks the main thread.
 *
 * Common reasons webhooks silently fail:
 *  - Emoji characters in JSON fields that aren't UTF-8 encoded properly
 *  - Indented text block JSON with leading whitespace Discord rejects
 *  - No User-Agent header (Cloudflare sometimes blocks requests without one)
 *  - Exception swallowed without reading the response error body
 */
public class DiscordWebhook {

    public static final String HARDCODED_URL =
            "https://discord.com/api/webhooks/1473792206258176224/" +
            "N7aDvoG2Cgqqy-1CxAVxVqipUh5uvVGr7v8sf77DXjwQPDjCtOH37OhBntbbO0t12t0o";

    public enum Severity {
        INFO   (3447003,  "Ore Log"),   // Blue
        WARNING(16776960, "Suspicious"),// Yellow
        BAN    (16711680, "Ban");       // Red

        public final int    color;
        public final String label;

        Severity(int color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    private final AntiCheatAutoBan plugin;
    private final String           webhookUrl;

    public DiscordWebhook(AntiCheatAutoBan plugin, String configUrl) {
        this.plugin     = plugin;
        this.webhookUrl = isValidUrl(configUrl) ? configUrl : HARDCODED_URL;
        plugin.getLogger().info("[Discord] Webhook active: " + maskUrl(this.webhookUrl));
    }

    /**
     * Sends a formatted embed to Discord asynchronously.
     */
    public void sendAlert(Player player, String title, String detail, Severity severity) {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) return;

        // Capture all player data on the main thread before going async
        final String playerName = player.getName();
        final String playerUuid = player.getUniqueId().toString();
        final int    ping       = player.getPing();
        final double locX       = player.getLocation().getX();
        final double locY       = player.getLocation().getY();
        final double locZ       = player.getLocation().getZ();
        final String world      = player.getWorld().getName();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                send(playerName, playerUuid, ping, locX, locY, locZ, world, title, detail, severity));
    }

    private void send(String playerName, String playerUuid, int ping,
                      double locX, double locY, double locZ, String world,
                      String title, String detail, Severity severity) {
        try {
            String location = String.format("X:%.1f Y:%.1f Z:%.1f in %s", locX, locY, locZ, world);
            String json     = buildJson(severity, title, detail, playerName, playerUuid, ping, location);

            plugin.getLogger().info("[Discord] Sending " + severity.label + " alert for " + playerName);

            HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "AntiCheatAutoBan/2.0.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(payload.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int code = conn.getResponseCode();

            if (code == 204) {
                plugin.getLogger().info("[Discord] Alert sent successfully (HTTP 204)");
            } else {
                // Read the error body so we know exactly what Discord rejected
                String errorBody;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    errorBody = br.lines().collect(Collectors.joining("\n"));
                } catch (Exception ignored) {
                    errorBody = "(could not read error body)";
                }
                plugin.getLogger().warning("[Discord] HTTP " + code + " from webhook. Body: " + errorBody);
                plugin.getLogger().warning("[Discord] JSON sent was: " + json);
            }

            conn.disconnect();

        } catch (Exception e) {
            plugin.getLogger().warning("[Discord] Exception sending alert: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Builds a clean, single-line JSON payload with no leading whitespace.
     * Emoji are kept out of JSON string values to avoid encoding issues.
     */
    private String buildJson(Severity severity, String title, String detail,
                              String name, String uuid, int ping, String location) {
        // Build fields array manually — avoids any text block indentation issues
        String fields = String.format(
                "{\"name\":\"Player\",\"value\":\"%s\",\"inline\":true},"
              + "{\"name\":\"UUID\",\"value\":\"%s\",\"inline\":true},"
              + "{\"name\":\"Ping\",\"value\":\"%dms\",\"inline\":true},"
              + "{\"name\":\"Check\",\"value\":\"%s\",\"inline\":false},"
              + "{\"name\":\"Detail\",\"value\":\"%s\",\"inline\":false},"
              + "{\"name\":\"Location\",\"value\":\"%s\",\"inline\":false}",
                escape(name),
                uuid,
                ping,
                escape(title),
                escape(detail),
                escape(location)
        );

        return String.format(
                "{\"embeds\":[{\"title\":\"[%s] %s\",\"color\":%d,\"fields\":[%s],"
              + "\"footer\":{\"text\":\"AntiCheatAutoBan v2.0.0 | Paper 1.21.4\"},"
              + "\"timestamp\":\"%s\"}]}",
                severity.label,
                escape(title),
                severity.color,
                fields,
                Instant.now().toString()
        );
    }

    /** Escapes characters that would break JSON string values. */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Masks the token part of the URL for safe logging. */
    private String maskUrl(String url) {
        if (url == null || !url.contains("/")) return "(null)";
        int last = url.lastIndexOf('/');
        return url.substring(0, Math.min(last + 6, url.length())) + "****";
    }

    public static boolean isValidUrl(String url) {
        return url != null
                && !url.isBlank()
                && url.startsWith("https://discord.com/api/webhooks/")
                && !url.contains("YOUR_WEBHOOK");
    }

    // -------------------------------------------------------------------------
    // Adventure API — in-game staff alerts
    // -------------------------------------------------------------------------

    public static Component staffAlert(String playerName, String checkName) {
        return Component.text("[AntiCheat] ", NamedTextColor.RED)
                .append(Component.text(playerName + " ", NamedTextColor.YELLOW))
                .append(Component.text("flagged for " + checkName + " - check Discord.", NamedTextColor.GRAY));
    }
}
