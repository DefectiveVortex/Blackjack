package com.vortex.blackjack.gui;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.table.BlackjackTable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chest menu for picking a bet: one chip per quick-bet amount, plus "custom amount" (typed in
 * chat) and close. A chip sets the bet to that amount, the same as the quick-bet buttons in chat.
 *
 * Clicks are identified by raw slot and the event's inventory only. InventoryView became an
 * interface in 1.21, so calling methods on it from this jar would break on 1.20 servers.
 */
public class BetMenu implements Listener {
    private static final int SIZE = 27;
    private static final int INFO_SLOT = 4;
    private static final int CUSTOM_SLOT = 21;
    private static final int CLOSE_SLOT = 23;
    private static final long CUSTOM_BET_WINDOW_MS = 30_000;

    private final BlackjackPlugin plugin;
    private final Map<UUID, Long> awaitingCustomBet = new ConcurrentHashMap<>();
    private final DecimalFormat moneyFormat = new DecimalFormat("0.##");

    /** Marks our inventories and remembers which slot holds which chip. */
    private static final class Holder implements InventoryHolder {
        private final Map<Integer, Integer> chips = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public BetMenu(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        ConfigManager cfg = plugin.getConfigManager();
        BlackjackTable table = plugin.getTableManager().getPlayerTable(player);
        if (table == null) {
            player.sendMessage(cfg.getMessage("not-at-table"));
            return;
        }
        if (table.isBettingLocked()) {
            player.sendMessage(cfg.getMessage("betting-locked"));
            return;
        }

        int minBet = table.getSettings().getMinBet(cfg);
        int maxBet = table.getSettings().getMaxBet(cfg);
        BigDecimal balance = plugin.getEconomyProvider().getBalance(player.getUniqueId());
        Integer currentBet = plugin.getPlayerBets().get(player);

        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, cfg.getMessage("bet-menu-title"));
        holder.inventory = inventory;

        inventory.setItem(INFO_SLOT, item(Material.GOLD_INGOT, cfg.getMessage("bet-menu-info-name"), List.of(
            cfg.formatMessage("bet-menu-info-balance", "balance", cfg.formatCurrency(moneyFormat.format(balance))),
            cfg.formatMessage("bet-menu-info-bet", "bet",
                currentBet == null || currentBet <= 0 ? "-" : cfg.formatCurrency(currentBet)),
            cfg.formatMessage("bet-menu-info-limits",
                "min_bet", cfg.formatCurrency(minBet), "max_bet", cfg.formatCurrency(maxBet)))));

        // Chips: the quick-bet amounts from config.yml, smallest first, centred in the middle row
        TreeSet<Integer> amounts = new TreeSet<>();
        amounts.addAll(cfg.getSmallBets());
        amounts.addAll(cfg.getMediumBets());
        amounts.addAll(cfg.getLargeBets());
        List<Integer> shown = new ArrayList<>(amounts).subList(0, Math.min(9, amounts.size()));
        int slot = 9 + (9 - shown.size()) / 2;
        for (int amount : shown) {
            String problem = null;
            if (amount < minBet) {
                problem = cfg.getMessage("bet-menu-chip-too-low");
            } else if (amount > maxBet) {
                problem = cfg.getMessage("bet-menu-chip-too-high");
            } else if (balance.compareTo(BigDecimal.valueOf(amount)) < 0) {
                problem = cfg.getMessage("bet-menu-chip-unaffordable");
            }

            String name = cfg.getBetColorByAmount(amount) + cfg.formatCurrency(amount);
            if (problem == null) {
                inventory.setItem(slot, item(chipMaterial(amount), name,
                    List.of(cfg.formatMessage("bet-menu-chip-lore", "amount", cfg.formatCurrency(amount)))));
                holder.chips.put(slot, amount);
            } else {
                inventory.setItem(slot, item(Material.GRAY_DYE, name, List.of(problem)));
            }
            slot++;
        }

        inventory.setItem(CUSTOM_SLOT, item(Material.NAME_TAG, cfg.getMessage("bet-menu-custom-name"),
            List.of(cfg.getMessage("bet-menu-custom-lore"))));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, cfg.getMessage("bet-menu-close"), List.of()));

        player.openInventory(inventory);
    }

    /** Chip colour follows the same tiers as the bet colours in chat. */
    private static Material chipMaterial(int amount) {
        if (amount >= 5000) return Material.MAGENTA_DYE;
        if (amount >= 1000) return Material.RED_DYE;
        if (amount >= 100) return Material.YELLOW_DYE;
        return Material.LIME_DYE;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true); // nothing in the menu can be taken or moved in
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }

        int slot = event.getRawSlot();
        Integer amount = holder.chips.get(slot);
        if (amount != null) {
            player.closeInventory();
            plugin.placeBet(player, amount);
        } else if (slot == CUSTOM_SLOT) {
            player.closeInventory();
            awaitingCustomBet.put(player.getUniqueId(), System.currentTimeMillis());
            player.sendMessage(plugin.getConfigManager().getMessage("bet-custom-prompt"));
        } else if (slot == CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** "Custom amount": the next chat line within 30 seconds is the bet (or the cancel word). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Long since = awaitingCustomBet.remove(player.getUniqueId());
        if (since == null || System.currentTimeMillis() - since > CUSTOM_BET_WINDOW_MS) {
            return;
        }
        event.setCancelled(true);
        String text = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ConfigManager cfg = plugin.getConfigManager();
            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase(cfg.getMessage("bet-custom-cancel-word"))) {
                player.sendMessage(cfg.getMessage("bet-custom-cancelled"));
                return;
            }
            try {
                plugin.placeBet(player, Integer.parseInt(text.replace(",", "").replace("$", "")));
            } catch (NumberFormatException e) {
                player.sendMessage(cfg.getMessage("invalid-amount"));
            }
        });
    }

    public void forget(Player player) {
        awaitingCustomBet.remove(player.getUniqueId());
    }
}
