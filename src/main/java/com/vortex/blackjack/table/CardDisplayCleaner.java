package com.vortex.blackjack.table;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.util.ServerCompat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Finds and removes card displays and seats that no table owns any more.
 *
 * Cards and seats are spawned non-persistent, so a live one never gets written to disk. Anything
 * that comes back when a chunk loads is therefore left over from a crash, an unloaded chunk, or an
 * older version of the plugin (2.3 and earlier saved cards with the chunk and didn't tag them).
 */
public class CardDisplayCleaner implements Listener {
    public static final String CARD_TAG = "blackjack-card";
    private static final String CARD_MODEL_NAMESPACE = "playing_cards";

    private final BlackjackPlugin plugin;
    private final TableManager tableManager;

    public CardDisplayCleaner(BlackjackPlugin plugin, TableManager tableManager) {
        this.plugin = plugin;
        this.tableManager = tableManager;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        int removed = 0;
        for (Entity entity : event.getEntities()) {
            if (isLeftover(entity)) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " leftover card display(s)/seat(s) in chunk "
                + event.getChunk().getX() + "," + event.getChunk().getZ()
                + " (" + event.getChunk().getWorld().getName() + ")");
        }
    }

    /**
     * Remove every card display within {@code radius} blocks that isn't part of a game in progress.
     * Only entities in loaded chunks can be reached; the load listener handles the rest.
     */
    public int removeNear(Location center, double radius) {
        if (center.getWorld() == null) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius,
                CardDisplayCleaner::isTableEntity)) {
            if (!tableManager.isActiveTableEntity(entity)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private boolean isLeftover(Entity entity) {
        return isTableEntity(entity) && !tableManager.isActiveTableEntity(entity);
    }

    /** A card display or a seat stand. */
    public static boolean isTableEntity(Entity entity) {
        return isCardDisplay(entity) || entity.getScoreboardTags().contains(BlackjackTable.SEAT_TAG);
    }

    /**
     * True for displays this plugin spawned: tagged ones (2.4+), or untagged clocks carrying a
     * playing_cards item model (2.3 and earlier).
     */
    public static boolean isCardDisplay(Entity entity) {
        if (!(entity instanceof ItemDisplay display)) {
            return false;
        }
        if (display.getScoreboardTags().contains(CARD_TAG)) {
            return true;
        }
        if (!ServerCompat.ITEM_MODELS) {
            return false; // no server this old could have spawned textured cards
        }

        ItemStack item = display.getItemStack();
        if (item == null || item.getType() != Material.CLOCK || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasItemModel()) {
            return false;
        }
        NamespacedKey model = meta.getItemModel();
        return model != null && CARD_MODEL_NAMESPACE.equals(model.getNamespace())
            && model.getKey().startsWith("card/");
    }
}
