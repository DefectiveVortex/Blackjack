package com.vortex.blackjack.integration;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.model.PlayerStats;
import com.vortex.blackjack.table.BlackjackTable;
import com.vortex.blackjack.util.GenericUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.text.DecimalFormat;

/**
 * PlaceholderAPI expansion for Blackjack plugin
 * Provides comprehensive placeholders for player statistics, table information, and game states
 */
public class BlackjackPlaceholderExpansion extends PlaceholderExpansion {
    
    private final BlackjackPlugin plugin;
    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");
    private final DecimalFormat percentFormat = new DecimalFormat("#.#");
    
    // Cache for stats to avoid reading file too frequently
    private FileConfiguration cachedStatsConfig;
    private long lastStatsLoad = 0;
    private static final long STATS_CACHE_DURATION = 5000; // 5 seconds
    
    public BlackjackPlaceholderExpansion(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public @NotNull String getIdentifier() {
        return "blackjack";
    }
    
    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }
    
    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }
    
    @Override
    public boolean persist() {
        return true; // Our placeholders will persist through reloads
    }
    
    @Override
    public boolean canRegister() {
        return true;
    }
    
    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        
        // Player Statistics Placeholders
        if (params.startsWith("stats_")) {
            return handleStatsPlaceholder(player, params.substring(6));
        }
        
        // Table Information Placeholders
        if (params.startsWith("table_")) {
            return handleTablePlaceholder(player, params.substring(6));
        }
        
        // Game State Placeholders
        if (params.startsWith("game_")) {
            return handleGamePlaceholder(player, params.substring(5));
        }
        
        // Current Bet Placeholders
        if (params.startsWith("bet_")) {
            return handleBetPlaceholder(player, params.substring(4));
        }
        
        // Economy Integration Placeholders
        if (params.startsWith("economy_")) {
            return handleEconomyPlaceholder(player, params.substring(8));
        }
        
        return null; // Placeholder not found
    }
    
    /**
     * Handle player statistics placeholders
     * Examples: %blackjack_stats_hands_won%, %blackjack_stats_win_rate%
     */
    private String handleStatsPlaceholder(Player player, String param) {
        // Load stats from file with caching to improve performance
        FileConfiguration statsConfig = getCachedStatsConfig();
        if (statsConfig == null) {
            return param.equals("has_played") ? "false" : "0";
        }
        
        String path = "players." + player.getUniqueId() + ".";
        
        // If no data exists for this player, return defaults
        if (!statsConfig.contains(path)) {
            return param.equals("has_played") ? "false" : "0";
        }
        
        // Use generic method to load player stats
        PlayerStats stats = GenericUtils.loadPlayerStats(statsConfig, player.getUniqueId());
        
        return switch (param) {
            case "hands_won" -> String.valueOf(stats.getHandsWon());
            case "hands_lost" -> String.valueOf(stats.getHandsLost());
            case "hands_pushed" -> String.valueOf(stats.getHandsPushed());
            case "total_hands" -> String.valueOf(stats.getTotalHands());
            case "blackjacks" -> String.valueOf(stats.getBlackjacks());
            case "busts" -> String.valueOf(stats.getBusts());
            case "current_streak" -> String.valueOf(stats.getCurrentStreak());
            case "best_streak" -> String.valueOf(stats.getBestStreak());
            case "win_rate" -> percentFormat.format(stats.getWinRate());
            case "win_rate_raw" -> decimalFormat.format(stats.getWinRate() / 100);
            case "total_winnings" -> decimalFormat.format(stats.getTotalWinnings());
            case "total_winnings_formatted" -> formatMoney(stats.getTotalWinnings());
            case "has_played" -> stats.getTotalHands() > 0 ? "true" : "false";
            default -> "0";
        };
    }
    
    /**
     * Get cached stats configuration to avoid reading file too frequently
     */
    private FileConfiguration getCachedStatsConfig() {
        long currentTime = System.currentTimeMillis();
        
        // Check if cache is still valid
        if (cachedStatsConfig != null && (currentTime - lastStatsLoad) < STATS_CACHE_DURATION) {
            return cachedStatsConfig;
        }
        
        // Load stats from file
        File statsFile = new File(plugin.getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            return null;
        }
        
        try {
            cachedStatsConfig = YamlConfiguration.loadConfiguration(statsFile);
            lastStatsLoad = currentTime;
            return cachedStatsConfig;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load stats file for PlaceholderAPI: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Handle table information placeholders
     * Examples: %blackjack_table_players%, %blackjack_table_max_players%
     */
    private String handleTablePlaceholder(Player player, String param) {
        BlackjackTable table = plugin.getTableManager().getPlayerTable(player);
        
        return switch (param) {
            case "at_table" -> table != null ? "true" : "false";
            case "id" -> table != null ? String.valueOf(table.getId()) : "";
            case "players" -> table != null ? String.valueOf(table.getPlayerCount()) : "0";
            case "max_players" -> String.valueOf(table != null
                ? table.getSettings().getMaxPlayers(plugin.getConfigManager())
                : plugin.getConfigManager().getMaxPlayers());
            case "seats_available" -> table != null ? String.valueOf(table.getAvailableSeats()) : "0";
            case "is_full" -> table != null ? (table.isFull() ? "true" : "false") : "false";
            case "game_in_progress" -> table != null ? (table.isGameInProgress() ? "true" : "false") : "false";
            case "can_join" -> table == null ? "true" : "false";
            case "location_x" -> table != null ? String.valueOf(table.getLocation().getBlockX()) : "0";
            case "location_y" -> table != null ? String.valueOf(table.getLocation().getBlockY()) : "0";
            case "location_z" -> table != null ? String.valueOf(table.getLocation().getBlockZ()) : "0";
            case "world" -> table != null ? table.getLocation().getWorld().getName() : "";
            default -> "";
        };
    }
    
    /**
     * Handle game state placeholders
     * Examples: %blackjack_game_hand_value%, %blackjack_game_is_turn%
     */
    private String handleGamePlaceholder(Player player, String param) {
        BlackjackTable table = plugin.getTableManager().getPlayerTable(player);
        if (table == null) {
            return "";
        }
        
        return switch (param) {
            case "hand_value" -> table.hasPlayerHand(player) ? String.valueOf(table.getPlayerHandValue(player)) : "0";
            case "hand_cards" -> table.hasPlayerHand(player) ? String.valueOf(table.getPlayerHandSize(player)) : "0";
            case "is_turn" -> table.isPlayerTurn(player) ? "true" : "false";
            case "is_finished" -> table.isPlayerFinished(player) ? "true" : "false";
            case "has_blackjack" -> table.hasPlayerBlackjack(player) ? "true" : "false";
            case "is_busted" -> table.isPlayerBusted(player) ? "true" : "false";
            case "can_double_down" -> table.canPlayerDoubleDown(player) ? "true" : "false";
            case "has_doubled_down" -> table.hasPlayerDoubledDown(player) ? "true" : "false";
            case "dealer_visible_value" -> String.valueOf(table.getDealerVisibleValue());
            case "dealer_card_count" -> String.valueOf(table.getDealerCardCount());
            default -> "";
        };
    }
    
    /**
     * Handle betting placeholders
     * Examples: %blackjack_bet_current%, %blackjack_bet_has_bet%
     */
    private String handleBetPlaceholder(Player player, String param) {
        Integer currentBet = plugin.getPlayerBets().get(player);
        Integer persistentBet = plugin.getPlayerPersistentBets().get(player);
        
        return switch (param) {
            case "current" -> currentBet != null ? String.valueOf(currentBet) : "0";
            case "current_formatted" -> formatMoney(currentBet != null ? currentBet : 0);
            case "has_bet" -> currentBet != null && currentBet > 0 ? "true" : "false";
            case "persistent" -> persistentBet != null ? String.valueOf(persistentBet) : "0";
            case "persistent_formatted" -> formatMoney(persistentBet != null ? persistentBet : 0);
            case "has_persistent" -> persistentBet != null && persistentBet > 0 ? "true" : "false";
            case "min_bet" -> String.valueOf(plugin.getConfigManager().getMinBet());
            case "max_bet" -> String.valueOf(plugin.getConfigManager().getMaxBet());
            case "min_bet_formatted" -> formatMoney(plugin.getConfigManager().getMinBet());
            case "max_bet_formatted" -> formatMoney(plugin.getConfigManager().getMaxBet());
            default -> "0";
        };
    }
    
    /**
     * Handle economy integration placeholders
     * Examples: %blackjack_economy_balance%, %blackjack_economy_can_afford%
     */
    private String handleEconomyPlaceholder(Player player, String param) {
        if (plugin.getEconomyProvider() == null) {
            return "0";
        }
        
        return switch (param) {
            case "balance" -> {
                double balance = plugin.getEconomyProvider().getBalance(player.getUniqueId()).doubleValue();
                yield decimalFormat.format(balance);
            }
            case "balance_formatted" -> {
                double balance = plugin.getEconomyProvider().getBalance(player.getUniqueId()).doubleValue();
                yield formatMoney(balance);
            }
            case "can_afford_min" -> {
                double balance = plugin.getEconomyProvider().getBalance(player.getUniqueId()).doubleValue();
                yield balance >= plugin.getConfigManager().getMinBet() ? "true" : "false";
            }
            case "can_afford_max" -> {
                double balance = plugin.getEconomyProvider().getBalance(player.getUniqueId()).doubleValue();
                yield balance >= plugin.getConfigManager().getMaxBet() ? "true" : "false";
            }
            default -> "0";
        };
    }
    
    /**
     * Format money values with appropriate suffixes
     */
    private String formatMoney(double amount) {
        String number;
        if (amount >= 1_000_000) {
            number = decimalFormat.format(amount / 1_000_000) + "M";
        } else if (amount >= 1_000) {
            number = decimalFormat.format(amount / 1_000) + "K";
        } else {
            number = decimalFormat.format(amount);
        }
        return plugin.getConfigManager().formatCurrency(number);
    }
}
