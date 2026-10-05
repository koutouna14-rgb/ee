package com.aethermc.antiabuse;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks items that were spawned into a player's inventory via a Creative-mode
 * inventory click (i.e. pulled from the creative item palette, not crafted,
 * looted, or traded for). Each tainted item is tagged with a unique ID in its
 * PersistentDataContainer and recorded with metadata (who spawned it, when).
 *
 * This is plugin-agnostic by design: it does not listen for any specific
 * admin/inventory-viewer plugin's custom events. It only tags the item itself,
 * so ANY future plugin that moves that same ItemStack will be caught the next
 * time AbuseListener diffs an inventory, because the tag travels with the item.
 */
public class TaintTracker {

    private static final String TAINT_KEY = "aethermc_tainted_id";

    private final AetherMCAntiAbuse plugin;
    private final NamespacedKey taintKey;
    private final Map<String, TaintRecord> records = new ConcurrentHashMap<>();

    public TaintTracker(AetherMCAntiAbuse plugin) {
        this.plugin = plugin;
        this.taintKey = new NamespacedKey(plugin, TAINT_KEY);
    }

    /** Tags an ItemStack as tainted (creative-spawned) and records who spawned it. */
    public void tagAsTainted(ItemStack item, UUID spawnedBy) {
        if (item == null || item.getType().isAir()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String id = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(taintKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);

        records.put(id, new TaintRecord(spawnedBy, item.getType().name(), item.getAmount(), System.currentTimeMillis()));
    }

    /** Returns the taint ID of an item, or null if it isn't tainted / is expired. */
    public TaintRecord getActiveTaint(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String id = meta.getPersistentDataContainer().get(taintKey, PersistentDataType.STRING);
        if (id == null) return null;

        TaintRecord record = records.get(id);
        if (record == null) return null;

        long windowMs = plugin.getConfig().getLong("tracking.taint-window-seconds", 300) * 1000L;
        if (System.currentTimeMillis() - record.spawnTime() > windowMs) {
            records.remove(id);
            return null;
        }
        return record;
    }

    public int size() {
        return records.size();
    }

    public record TaintRecord(UUID spawnedBy, String material, int amount, long spawnTime) {}
}
