package com.aethermc.antiabuse.listeners;

import com.aethermc.antiabuse.AetherMCAntiAbuse;
import com.aethermc.antiabuse.TaintTracker;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Detects: staff member switches to Creative -> pulls an item from the
 * creative palette -> item later ends up in persistent storage (chest,
 * shulker, ender chest, barrel, hopper-fed system), another player's
 * inventory, or is dropped on the ground.
 *
 * Deliberately generic: we never hook a specific plugin's API. We tag items
 * the moment they're pulled from the creative palette (InventoryClickEvent
 * with InventoryType.CREATIVE) and then watch for that tagged item showing up
 * anywhere persistent via the standard Bukkit inventory/container events,
 * which every plugin -- including ones that don't exist yet -- must route
 * through to actually move an item.
 */
public class AbuseListener implements Listener {

    private static final Set<InventoryType> STORAGE_TYPES = Set.of(
            InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.ENDER_CHEST, InventoryType.HOPPER, InventoryType.DROPPER,
            InventoryType.DISPENSER, InventoryType.FURNACE, InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER, InventoryType.BREWING
    );

    private final AetherMCAntiAbuse plugin;

    public AbuseListener(AetherMCAntiAbuse plugin) {
        this.plugin = plugin;
    }

    // --- Tag items the moment they're pulled from the creative palette ---
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreativeClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory().getType() != InventoryType.CREATIVE) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (player.hasPermission("aethermc.antiabuse.exempt")) return;

        ItemStack result = event.getCursor();
        if (result == null || result.getType().isAir()) {
            result = event.getCurrentItem();
        }
        if (result == null || result.getType().isAir()) return;

        if (!qualifiesForFlag(result)) return;

        plugin.getTaintTracker().tagAsTainted(result, player.getUniqueId());
    }

    // --- Catch tainted items moved into any storage-type inventory ---
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryAction action = event.getAction();
        boolean movingOut = action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY;

        if (!movingOut) return;

        // Figure out the destination inventory of this click
        InventoryType destType = event.getView().getTopInventory().getType();
        ItemStack moved = event.getCursor() != null && !event.getCursor().getType().isAir()
                ? event.getCursor() : event.getCurrentItem();

        checkAndFlag(moved, destType.name(), player, "Placed via inventory click (" + destType + ")");

        // Shift-click into another player's inventory (e.g. trading GUIs) also routes here;
        // if the top inventory is a player inventory that isn't theirs, flag it too.
        if (destType == InventoryType.PLAYER
                && event.getView().getTopInventory().getHolder() instanceof Player targetPlayer
                && !targetPlayer.getUniqueId().equals(player.getUniqueId())) {
            checkAndFlag(moved, "Player inventory (" + targetPlayer.getName() + ")", player,
                    "Transferred to another player's inventory");
        }
    }

    // --- Catch hopper/auto-mover transfers of tainted items between containers ---
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        TaintTracker.TaintRecord record = plugin.getTaintTracker().getActiveTaint(item);
        if (record == null) return;

        InventoryHolder destHolder = event.getDestination().getHolder();
        String destDesc = destHolder instanceof Container c
                ? c.getBlock().getType().name() + " @ " + describeLocation(c)
                : event.getDestination().getType().name();

        reportFlag(record, item, destDesc, null, "Auto-transferred via hopper/mover into storage");
    }

    // --- Catch dropping a tainted item on the ground ---
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemDrop().getItemStack();
        TaintTracker.TaintRecord record = plugin.getTaintTracker().getActiveTaint(item);
        if (record == null) return;

        reportFlag(record, item, "Dropped on ground at " + formatLoc(player), player, "Dropped item on the ground");
    }

    // --- Catch another player picking up a dropped tainted item ---
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player pickerUpper)) return;
        ItemStack item = event.getItem().getItemStack();
        TaintTracker.TaintRecord record = plugin.getTaintTracker().getActiveTaint(item);
        if (record == null) return;
        if (record.spawnedBy().equals(pickerUpper.getUniqueId())) return; // picking up their own item isn't a new flag

        reportFlag(record, item, "Picked up by " + pickerUpper.getName(), pickerUpper,
                "Another player picked up a creative-spawned item");
    }

    private void checkAndFlag(ItemStack item, String destinationLabel, Player actor, String actionDesc) {
        TaintTracker.TaintRecord record = plugin.getTaintTracker().getActiveTaint(item);
        if (record == null) return;
        reportFlag(record, item, destinationLabel, actor, actionDesc);
    }

    private void reportFlag(TaintTracker.TaintRecord record, ItemStack item, String destination,
                             Player actor, String actionDesc) {
        long secondsSince = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - record.spawnTime());

        Player spawner = plugin.getServer().getPlayer(record.spawnedBy());
        String spawnerName = spawner != null ? spawner.getName() : record.spawnedBy().toString();

        String message = String.format(
                "[AntiAbuse] %s creative-spawned %dx %s, then it was flagged: %s -> %s (%ds later)",
                spawnerName, record.amount(), record.material(), actionDesc, destination, secondsSince
        );
        plugin.logAbuseEvent(message);

        plugin.getWebhookSender().sendAlert(
                spawnerName,
                actionDesc,
                record.material(),
                item.getAmount(),
                destination,
                secondsSince
        );
    }

    private boolean qualifiesForFlag(ItemStack item) {
        int minAmount = plugin.getConfig().getInt("tracking.min-flag-amount", 1);
        List<String> always = plugin.getConfig().getStringList("tracking.always-flag-materials");
        if (always.contains(item.getType().name())) return true;
        return item.getAmount() >= minAmount;
    }

    private String describeLocation(Container c) {
        var loc = c.getBlock().getLocation();
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private String formatLoc(Player p) {
        var loc = p.getLocation();
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
