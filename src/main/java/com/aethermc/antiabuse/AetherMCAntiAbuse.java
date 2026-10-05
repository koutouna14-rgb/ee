package com.aethermc.antiabuse;

import com.aethermc.antiabuse.listeners.AbuseListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;

/**
 * AetherMCAntiAbuse
 *
 * Staff-accountability plugin. Watches for items that are spawned via
 * Creative-mode inventory and then moved into persistent storage, another
 * player's inventory, or dropped, within a configurable time window.
 * Flags are logged locally and optionally sent to a private staff webhook.
 *
 * This plugin only observes actions taken by operators/staff on this server's
 * own Bukkit inventory events. It does not inspect, fingerprint, or report on
 * anything installed on a player's client.
 */
public class AetherMCAntiAbuse extends JavaPlugin {

    private static AetherMCAntiAbuse instance;
    private TaintTracker taintTracker;
    private WebhookSender webhookSender;
    private java.util.logging.Logger fileLogger;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.taintTracker = new TaintTracker(this);
        this.webhookSender = new WebhookSender(this);

        if (getConfig().getBoolean("logging.log-to-file", true)) {
            setupFileLogger();
        }

        getServer().getPluginManager().registerEvents(new AbuseListener(this), this);

        getLogger().info("AetherMCAntiAbuse enabled. Watching Creative-mode item injection.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AetherMCAntiAbuse disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aethermc.antiabuse.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§eUsage: /antiabuse <reload|status>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage("§aAetherMCAntiAbuse config reloaded.");
            }
            case "status" -> {
                sender.sendMessage("§eTracked tainted items: §f" + taintTracker.size());
                sender.sendMessage("§eWebhook enabled: §f" + getConfig().getBoolean("webhook.enabled", true));
            }
            default -> sender.sendMessage("§eUsage: /antiabuse <reload|status>");
        }
        return true;
    }

    private void setupFileLogger() {
        try {
            File logDir = new File(getDataFolder(), "logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            String fileName = "abuse-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".log";
            FileHandler handler = new FileHandler(new File(logDir, fileName).getAbsolutePath(), true);
            handler.setFormatter(new SimpleFormatter());
            fileLogger = java.util.logging.Logger.getLogger("AetherMCAntiAbuseFile");
            fileLogger.addHandler(handler);
            fileLogger.setUseParentHandlers(false);
        } catch (IOException e) {
            getLogger().warning("Could not set up file logger: " + e.getMessage());
        }
    }

    public void logAbuseEvent(String message) {
        if (getConfig().getBoolean("logging.log-to-console", true)) {
            getLogger().warning(message);
        }
        if (fileLogger != null) {
            fileLogger.info(message);
        }
    }

    public static AetherMCAntiAbuse getInstance() {
        return instance;
    }

    public TaintTracker getTaintTracker() {
        return taintTracker;
    }

    public WebhookSender getWebhookSender() {
        return webhookSender;
    }
}
