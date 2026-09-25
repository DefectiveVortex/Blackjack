package com.vortex.blackjack.table;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.config.ConfigManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Physical controls: right-click a chair or the table to sit down, right-click the table while
 * seated to open the bet menu, and sneak to stand up and leave.
 */
public class TableInteractListener implements Listener {
    private final BlackjackPlugin plugin;
    private final TableManager tableManager;

    public TableInteractListener(BlackjackPlugin plugin, TableManager tableManager) {
        this.plugin = plugin;
        this.tableManager = tableManager;
    }

    // LOW so the click is claimed before sit plugins (GSit sits players on stairs) see it
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isClickToJoin() || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return; // sneak-click still places blocks against the table, for building around it
        }

        TableManager.TableBlock hit = tableManager.findTableBlock(event.getClickedBlock());
        if (hit == null) {
            return;
        }
        event.setCancelled(true);

        BlackjackTable current = tableManager.getPlayerTable(player);
        if (current == hit.table()) {
            if (hit.seat() < 0) {
                plugin.getBetMenu().open(player);
            }
            return;
        }
        if (current != null) {
            player.sendMessage(cfg.getMessage("already-at-table"));
            return;
        }
        if (!player.hasPermission("blackjack.play")) {
            player.sendMessage(cfg.getMessage("no-permission"));
            return;
        }
        hit.table().addPlayer(player, hit.seat());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !plugin.getConfigManager().shouldSeatPlayers()) {
            return;
        }
        Player player = event.getPlayer();
        BlackjackTable table = tableManager.getPlayerTable(player);
        if (table != null && table.confirmSneakLeave(player)) {
            table.removePlayer(player);
        }
    }

    /**
     * A player saved while riding a seat (server crash) would log back in on a recreated armor
     * stand. Get them off it.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Entity vehicle = event.getPlayer().getVehicle();
        if (vehicle != null && vehicle.getScoreboardTags().contains(BlackjackTable.SEAT_TAG)) {
            vehicle.eject();
            vehicle.remove();
        }
    }
}
