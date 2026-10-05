package com.aethermc.antiabuse;

import org.bukkit.Bukkit;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends abuse-flag alerts to a private staff Discord webhook.
 * Intended audience: server admins reviewing their own staff team's actions.
 */
public class WebhookSender {

    private final AetherMCAntiAbuse plugin;

    public WebhookSender(AetherMCAntiAbuse plugin) {
        this.plugin = plugin;
    }

    public void sendAlert(String staffName, String action, String material, int amount, String destination, long secondsSinceSpawn) {
        if (!plugin.getConfig().getBoolean("webhook.enabled", true)) return;

        String url = plugin.getConfig().getString("webhook.url", "");
        if (url == null || url.isBlank() || url.contains("REPLACE")) {
            plugin.getLogger().warning("Webhook URL not configured; skipping alert send.");
            return;
        }

        String username = plugin.getConfig().getString("webhook.username", "AetherMC AntiAbuse");

        String json = buildPayload(username, staffName, action, material, amount, destination, secondsSinceSpawn);

        // Fire off-thread so we never block the main server thread on network IO
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> postJson(url, json));
    }

    private String buildPayload(String username, String staffName, String action, String material,
                                 int amount, String destination, long secondsSinceSpawn) {
        String description = String.format(
                "**Staff:** %s\\n**Action:** %s\\n**Item:** %dx %s\\n**Destination:** %s\\n**Time since Creative spawn:** %ds",
                escape(staffName), escape(action), amount, escape(material), escape(destination), secondsSinceSpawn
        );

        return "{"
                + "\"username\":\"" + escape(username) + "\","
                + "\"embeds\":[{"
                + "\"title\":\"\\uD83D\\uDEA8 Possible Staff Item Abuse\","
                + "\"description\":\"" + description + "\","
                + "\"color\":15158332"
                + "}]"
                + "}";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void postJson(String urlStr, String json) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code >= 300) {
                plugin.getLogger().warning("Webhook responded with HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send webhook alert: " + e.getMessage());
        }
    }
}
