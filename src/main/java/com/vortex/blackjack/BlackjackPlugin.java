package com.vortex.blackjack;

import com.vortex.blackjack.commands.CommandManager;
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.economy.EconomyProvider;
import com.vortex.blackjack.economy.VaultEconomyProvider;
import com.vortex.blackjack.integration.BlackjackPlaceholderExpansion;
import com.vortex.blackjack.model.PlayerStats;
import com.vortex.blackjack.table.BlackjackTable;
import com.vortex.blackjack.table.TableManager;
import com.vortex.blackjack.table.TableSettings;
import com.vortex.blackjack.util.AsyncUtils;
import com.vortex.blackjack.util.GenericUtils;
import com.vortex.blackjack.util.VersionChecker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main Blackjack plugin class
 */
public class BlackjackPlugin extends JavaPlugin implements Listener {
    
    // Core managers - each handles a specific responsibility
    private ConfigManager configManager;
    private TableManager tableManager;
    private CommandManager commandManager;
    private AsyncUtils asyncUtils;
    private EconomyProvider economyProvider;
    
    // GSit integration
    private boolean gSitEnabled = false;
    
    // PlaceholderAPI integration
    private BlackjackPlaceholderExpansion placeholderExpansion;
    
    // Version checker
    private VersionChecker versionChecker;
    
    // Player data - thread-safe collections
    private final Map<Player, Integer> playerBets = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerPersistentBets = new ConcurrentHashMap<>(); // Keeps bet amount for "Play Again"
    private final Map<Player, Long> lastBetTime = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    
    // Files
    private File statsFile;
    
    @Override
    public void onEnable() {
        // Initialize configuration
        saveDefaultConfig();
        
        // Load messages configuration
        File messagesFile = new File(getDataFolder(), "messages.yml");
        FileConfiguration messagesConfig = null;
        
        if (!messagesFile.exists()) {
            // Try to save the resource if it exists in the JAR
            try {
                saveResource("messages.yml", false);
                messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                getLogger().info("Created messages.yml file");
            } catch (IllegalArgumentException e) {
                // messages.yml doesn't exist in JAR, create a default one
                getLogger().info("Creating default messages.yml file");
                createDefaultMessagesFile(messagesFile);
                messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            }
        } else {
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        }
        
        configManager = new ConfigManager(getConfig(), messagesConfig);
        
        // Initialize core managers
        tableManager = new TableManager(this, configManager);
        commandManager = new CommandManager(this);
        asyncUtils = new AsyncUtils(this);
        versionChecker = new VersionChecker(this);
        
        // Initialize economy provider - Vault with EssentialsX fallback
        economyProvider = initializeEconomyProvider();
        
        if (economyProvider == null) {
            getLogger().severe("No supported economy plugin found! Please install Vault with an economy plugin (like EssentialsX, EconomyAPI, CMI, HexaEcon, etc.)");
            getLogger().severe("Disabling Blackjack...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Check for GSit integration
        if (getServer().getPluginManager().getPlugin("GSit") != null) {
            gSitEnabled = true;
            getLogger().info("GSit found! Players will automatically sit when joining tables.");
        } else {
            getLogger().info("GSit not found. Players will not automatically sit when joining tables.");
        }
        
        // Check for PlaceholderAPI integration
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new BlackjackPlaceholderExpansion(this);
            placeholderExpansion.register();
            getLogger().info("PlaceholderAPI found! Extensive placeholder support enabled.");
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholder support disabled.");
        }
        
        // Initialize files
        statsFile = new File(getDataFolder(), "stats.yml");
        
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register commands - this enables individual commands like /bet, /hit, /stand
        commandManager.registerCommands();
        
        // Load tables from config
        tableManager.loadTablesFromConfig();
        
        // Start version checking
        versionChecker.checkForUpdates();
        
        // Schedule periodic stats saving - configurable interval
        int statsSaveInterval = configManager.getStatsSaveInterval();
        long ticks = statsSaveInterval * 20L; // Convert seconds to ticks (20 ticks = 1 second)
        asyncUtils.scheduleRepeating("stats-autosave", this::savePlayerStats, ticks, ticks);
        getLogger().info("Stats auto-save scheduled every " + statsSaveInterval + " seconds");
        
        getLogger().info("Blackjack enabled successfully!");
    }
    
    @Override
    public void onDisable() {
        // Unregister PlaceholderAPI expansion
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        
        // Cancel all async tasks
        if (asyncUtils != null) {
            asyncUtils.cancelAllTasks();
        }
        
        // Cleanup and refund bets
        if (tableManager != null) {
            refundAllBets();
            tableManager.cleanup();
        }
        
        // Save player stats
        savePlayerStats();
        
        getLogger().info("Blackjack plugin disabled!");
    }
    
    /**
     * Create a default messages.yml file with all the required messages
     */
    private void createDefaultMessagesFile(File messagesFile) {
        try {
            if (!messagesFile.getParentFile().exists()) {
                messagesFile.getParentFile().mkdirs();
            }
            
            FileConfiguration messagesConfig = new YamlConfiguration();
            
            // Add all the default messages
            messagesConfig.set("prefix", "&8[&6Blackjack&8] &r");
            messagesConfig.set("no-permission", "&cYou don't have permission to do that!");
            messagesConfig.set("player-only-command", "&cThis command can only be used by players!");
            
            // Table management
            messagesConfig.set("table-created", "&aBlackjack table created!");
            messagesConfig.set("table-created-with-settings", "&aTable created! &7(min: &e%min_bet%&7, max: &e%max_bet%&7, players: &e%max_players%&7, distance: &e%max_join_distance%&7)");
            messagesConfig.set("createtable-invalid-arg", "&cInvalid argument: %error%");
            messagesConfig.set("table-removed", "&cBlackjack table removed!");
            messagesConfig.set("table-already-exists", "&cTable already exists at this location!");
            messagesConfig.set("table-remove-failed", "&cFailed to remove table!");
            messagesConfig.set("no-table-nearby", "&cNo table found nearby!");
            messagesConfig.set("settable-usage", "&eUsage: /bj settable <setting> <value>");
            messagesConfig.set("settable-unknown-setting", "&cUnknown setting '%setting%'. Valid: min-bet, max-bet, max-players, max-join-distance");
            messagesConfig.set("settable-invalid-value", "&cInvalid value: '%value%'");
            messagesConfig.set("settable-validation-error", "&cValidation failed: %error%");
            messagesConfig.set("settable-updated", "&aSetting &e%setting% &aset to &e%value% &aon nearest table.");
            
            // Player table status
            messagesConfig.set("already-at-table", "&cYou are already at a table! Use /leave to leave your current table.");
            messagesConfig.set("not-at-table", "&cYou're not at a table! Use /join near a table to play.");
            messagesConfig.set("auto-left-table", "&eYou moved too far from the table and were automatically removed.");
            messagesConfig.set("left-table", "&aYou left the table.");
            messagesConfig.set("left-table-bet-refunded", "&aYou left the table and your bet of $%amount% has been refunded.");
            messagesConfig.set("left-table-bet-forfeit", "&cYou left the table mid-game and forfeit your bet of $%amount%.");
            
            // Table joining
            messagesConfig.set("table-full", "&cThis table is full!");
            messagesConfig.set("too-far", "&cYou are too far from the table to join!");
            messagesConfig.set("no-seats", "&cNo available seats!");
            messagesConfig.set("join-error", "&cError joining table. Please try again.");
            messagesConfig.set("game-in-progress", "&cCannot perform this action during an active game!");
            
            // Betting
            messagesConfig.set("bet-required", "&cYou must place a bet before the game can start! Use /bet <amount>");
            messagesConfig.set("invalid-bet", "&cInvalid bet amount! Must be between %min_bet% and %max_bet%.");
            messagesConfig.set("insufficient-funds", "&cYou don't have enough money to bet $%amount%!");
            messagesConfig.set("bet-cooldown", "&cPlease wait a moment before changing your bet again.");
            messagesConfig.set("bet-set", "&aYour bet has been set to $%amount%!");
            messagesConfig.set("bet-already-set", "&eYour bet is already $%amount%!");
            messagesConfig.set("bet-refunded", "&aYour bet of $%amount% has been refunded.");
            messagesConfig.set("bet-refunded-shutdown", "&aBet refunded due to server shutdown: $%amount%");
            messagesConfig.set("bet-reduced-refunded", "&aBet reduced to $%amount% and refunded $%refund%!");
            messagesConfig.set("auto-bet-placed", "&aAuto-bet placed: $%amount%");
            messagesConfig.set("invalid-amount", "&cInvalid amount!");
            messagesConfig.set("bet-usage", "&cUsage: /bet <amount>");
            messagesConfig.set("bet-failed", "&cFailed to process bet!");
            messagesConfig.set("bet-refund-failed", "&cFailed to process bet refund!");
            messagesConfig.set("error-refund", "&cAn error occurred while refunding your bet. Contact a staff member!");
            messagesConfig.set("error-payout", "&cAn error occurred processing your payout. Contact a staff member!");
            
            // Quick bet menu
            messagesConfig.set("quick-bet-header", "&6&l=== Quick Bet Menu ===");
            messagesConfig.set("quick-bet-description", "&7Click on an amount to place your bet:");
            messagesConfig.set("quick-bet-border", "&e&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
            messagesConfig.set("quick-bet-title", "&6&l                          QUICK BET");
            
            // Game flow
            messagesConfig.set("game-started", "&aGame started! %player%'s turn.");
            messagesConfig.set("your-turn", "&aIt's your turn!");
            messagesConfig.set("hand-value", "&aYour hand value: %value%");
            messagesConfig.set("dealer-card", "&aDealer's visible card: %card% | Value: %value%");
            messagesConfig.set("dealer-value", "&aDealer's final hand value: %value%");
            
            // Game actions
            messagesConfig.set("player-busts", "&c%player% busts!");
            messagesConfig.set("player-stands", "&a%player% stands at %value%!");
            messagesConfig.set("player-wins", "&a%player% wins and gets back $%amount%!");
            messagesConfig.set("player-loses", "&c%player% loses their bet of $%amount%!");
            messagesConfig.set("player-pushes", "&e%player% pushes and gets their bet of $%amount% back!");
            messagesConfig.set("player-blackjack", "&6%player% got BLACKJACK and wins $%amount%!");
            
            // Double down
            messagesConfig.set("double-down-first-two-cards", "&cYou can only double down on your first two cards!");
            messagesConfig.set("double-down-already-used", "&cYou have already doubled down!");
            messagesConfig.set("double-down-insufficient-funds", "&cYou don't have enough money to double down!");
            
            // Hand display
            messagesConfig.set("hand-display", "&bHand: %hand% | %hand_value%");
            messagesConfig.set("dealer-shows", "&aDealer shows: %card% | %value%");
            
            // Statistics
            messagesConfig.set("stats-header", "&6=== Blackjack Statistics ===");
            messagesConfig.set("stats-other-player-header", "&6=== %player%'s Blackjack Statistics ===");
            messagesConfig.set("stats-hands-won", "&eHands Won: &a%value%");
            messagesConfig.set("stats-hands-lost", "&eHands Lost: &c%value%");
            messagesConfig.set("stats-hands-pushed", "&eHands Pushed: &7%value%");
            messagesConfig.set("stats-blackjacks", "&eBlackjacks: &6%value%");
            messagesConfig.set("stats-busts", "&eBusts: &c%value%");
            messagesConfig.set("stats-win-rate", "&eWin Rate: &a%value%%");
            messagesConfig.set("stats-current-streak", "&eCurrent Streak: &b%value%");
            messagesConfig.set("stats-best-streak", "&eBest Streak: &a%value%");
            messagesConfig.set("stats-total-winnings", "&eTotal Winnings: &2$%value%");
            messagesConfig.set("stats-no-permission", "&cYou don't have permission to check other players' statistics!");
            messagesConfig.set("stats-player-not-found", "&cPlayer not found: %player%");
            messagesConfig.set("stats-none-found", "&cNo statistics found!");
            messagesConfig.set("stats-none-found-player", "&cNo statistics found for %player%!");
            
            // Configuration
            messagesConfig.set("config-reloaded", "&aConfiguration reloaded!");
            
            // Help command
            messagesConfig.set("help-header", "&rAvailable Commands:");
            messagesConfig.set("help-admin-create", "&e/bj createtable [min-bet:<n>] [max-bet:<n>] [max-players:<n>] [max-join-distance:<n>] &7- Create a table");
            messagesConfig.set("help-admin-settable", "&e/bj settable <setting> <value> &7- Modify nearest table settings");
            messagesConfig.set("help-admin-remove", "&e/bj removetable &7- Remove the nearest table");
            messagesConfig.set("help-admin-reload", "&e/bj reload &7- Reload configuration");
            messagesConfig.set("help-join", "&e/join &7- Join the nearest table");
            messagesConfig.set("help-leave", "&e/leave &7- Leave your current table");
            messagesConfig.set("help-bet", "&e/bet <amount> &7- Place or change your bet");
            messagesConfig.set("help-start", "&e/start &7- Start a new game");
            messagesConfig.set("help-hit", "&e/hit &7- Take another card");
            messagesConfig.set("help-stand", "&e/stand &7- End your turn");
            messagesConfig.set("help-stats", "&e/bj stats &7- View your statistics");
            messagesConfig.set("help-stats-others", "&e/bj stats <player> &7- View another player's statistics");
            
            // Table broadcast messages
            messagesConfig.set("player-left-during-turn", "&c%player% %reason% during their turn.");
            messagesConfig.set("player-left-table", "&c%player% %reason%.");
            
            // Game action prompts
            messagesConfig.set("game-action-prompt", "&7Your turn: ");
            messagesConfig.set("game-action-separator", "&7 | ");
            messagesConfig.set("post-game-prompt", "&7Choose: ");
            
            // Betting category labels
            messagesConfig.set("betting-category-small", "&7Small: ");
            messagesConfig.set("betting-category-medium", "&7Medium: ");
            messagesConfig.set("betting-category-large", "&7Large: ");
            
            // Button configuration
            messagesConfig.set("buttons.hit.text", "&a&l[HIT]");
            messagesConfig.set("buttons.hit.command", "/hit");
            messagesConfig.set("buttons.hit.hover", "&eClick to take another card");
            
            messagesConfig.set("buttons.stand.text", "&c&l[STAND]");
            messagesConfig.set("buttons.stand.command", "/stand");
            messagesConfig.set("buttons.stand.hover", "&eClick to end your turn");
            
            messagesConfig.set("buttons.double-down.text", "&6&l[DOUBLE DOWN]");
            messagesConfig.set("buttons.double-down.command", "/doubledown");
            messagesConfig.set("buttons.double-down.hover", "&eClick to double your bet and take one card");
            
            messagesConfig.set("buttons.play-again.text", "&a&l[Play Again]");
            messagesConfig.set("buttons.play-again.command", "/start");
            messagesConfig.set("buttons.play-again.hover", "&eClick to start a new game");
            
            messagesConfig.set("buttons.leave-table.text", "&c&l[Leave Table]");
            messagesConfig.set("buttons.leave-table.command", "/leave");
            messagesConfig.set("buttons.leave-table.hover", "&eClick to leave the table");
            
            messagesConfig.set("buttons.custom-bet.text", "&b&l[CUSTOM BET]");
            messagesConfig.set("buttons.custom-bet.command", "/bet ");
            messagesConfig.set("buttons.custom-bet.hover", "&eClick to enter custom amount");
            
            // Button color configurations
            messagesConfig.set("buttons.small-bet-color", "&a");    // Green for small bets
            messagesConfig.set("buttons.medium-bet-color", "&e");   // Yellow for medium bets
            messagesConfig.set("buttons.large-bet-color", "&c");    // Red for large bets
            messagesConfig.set("buttons.huge-bet-color", "&d");     // Pink for huge bets
            
            messagesConfig.save(messagesFile);
            
        } catch (IOException e) {
            getLogger().severe("Could not create default messages.yml: " + e.getMessage());
        }
    }
    
    /**
     * Initialize economy provider
     */
    private EconomyProvider initializeEconomyProvider() {
        VaultEconomyProvider provider = new VaultEconomyProvider(this);
        
        if (provider.isEnabled()) {
            getLogger().info("Economy: " + provider.getProviderName());
            return provider;
        }
        
        // Try delayed initialization for late-loading economy plugins
        getServer().getScheduler().runTaskLater(this, () -> {
            if (provider.reconnect()) {
                economyProvider = provider;
                getLogger().info("Economy (delayed): " + provider.getProviderName());
            } else {
                getLogger().warning("No economy plugin found! Install Vault + an economy plugin (EssentialsX, HexaEcon, etc.)");
            }
        }, 40L);
        
        return provider; // Return even if not enabled, might work after delay
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Check if player is admin and notify about updates
        if (player.hasPermission("blackjack.admin")) {
            versionChecker.notifyAdmin(player);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Remove from bet tracking
        lastBetTime.remove(player);
        playerPersistentBets.remove(player);
        
        // Remove from table if they're at one
        if (tableManager != null) {
            tableManager.removePlayerFromTable(player, "disconnected from the server");
        }
        
        // Note: Stats are now saved periodically, not on every quit for better performance
    }
    
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        
        // Only check if player moved to a different block (optimization)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() && 
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        
        // Check if player is at a table
        if (tableManager != null) {
            BlackjackTable table = tableManager.getPlayerTable(player);
            if (table != null) {
                double distance = player.getLocation().distance(table.getCenterLocation());
                double maxDistance = table.getSettings().getMaxJoinDistance(configManager);
                
                if (distance > maxDistance) {
                    // Player moved too far from table, auto-leave
                    table.removePlayer(player, "moved too far from the table");
                    player.sendMessage(configManager.getMessage("auto-left-table")
                        .replace("%distance%", String.format("%.1f", distance))
                        .replace("%max_distance%", String.format("%.1f", maxDistance)));
                }
            }
        }
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.getMessage("player-only-command"));
            return true;
        }
        
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        
        String action = args[0].toLowerCase();
        
        return switch (action) {
            case "createtable" -> handleCreateTable(player, args);
            case "settable" -> handleSetTable(player, args);
            case "removetable" -> handleRemoveTable(player);
            case "join" -> handleJoin(player);
            case "leave" -> handleLeave(player);
            case "start" -> handleStart(player);
            case "hit" -> handleHit(player);
            case "stand" -> handleStand(player);
            case "doubledown" -> handleDoubleDown(player);
            case "bet" -> handleBet(player, args);
            case "stats" -> handleStats(player, args);
            case "reload" -> handleReload(player);
            default -> {
                sendHelp(player);
                yield true;
            }
        };
    }
    
    // Command handlers - clean and focused methods
    
    private boolean handleCreateTable(Player player, String[] args) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }

        StringBuilder err = new StringBuilder();
        TableSettings settings = TableSettings.parseArgs(args, 1, configManager, err);
        if (settings == null) {
            player.sendMessage(configManager.formatMessage("createtable-invalid-arg", "error", err.toString()));
            return true;
        }

        if (tableManager.createTable(player.getLocation(), settings)) {
            player.sendMessage(configManager.formatMessage("table-created-with-settings",
                "min_bet",          settings.getMinBet(configManager),
                "max_bet",          settings.getMaxBet(configManager),
                "max_players",      settings.getMaxPlayers(configManager),
                "max_join_distance", settings.getMaxJoinDistance(configManager)));
        } else {
            player.sendMessage(configManager.getMessage("table-already-exists"));
        }
        return true;
    }

    private boolean handleSetTable(Player player, String[] args) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }

        // /bj settable <setting> <value>
        if (args.length < 3) {
            player.sendMessage(configManager.getMessage("settable-usage"));
            return true;
        }

        BlackjackTable table = tableManager.findNearestTable(player.getLocation());
        if (table == null) {
            player.sendMessage(configManager.getMessage("no-table-nearby"));
            return true;
        }

        String setting = args[1].toLowerCase();
        String value   = args[2];
        TableSettings s = table.getSettings();

        try {
            switch (setting) {
                case "min-bet"            -> s.setMinBet(parsePositiveInt(value));
                case "max-bet"            -> s.setMaxBet(parsePositiveInt(value));
                case "max-players"        -> s.setMaxPlayers(Math.max(1, Math.min(8, parsePositiveInt(value))));
                case "max-join-distance"  -> s.setMaxJoinDistance(parsePositiveDouble(value));
                default -> {
                    player.sendMessage(configManager.formatMessage("settable-unknown-setting", "setting", setting));
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.formatMessage("settable-invalid-value", "value", value));
            return true;
        }

        String err = s.validate(configManager);
        if (err != null) {
            player.sendMessage(configManager.formatMessage("settable-validation-error", "error", err));
            return true;
        }

        tableManager.saveTableSettings(table);
        player.sendMessage(configManager.formatMessage("settable-updated", "setting", setting, "value", value));
        return true;
    }

    private int parsePositiveInt(String s) {
        int v = Integer.parseInt(s);
        if (v <= 0) throw new NumberFormatException("must be positive");
        return v;
    }

    private double parsePositiveDouble(String s) {
        double v = Double.parseDouble(s);
        if (v <= 0) throw new NumberFormatException("must be positive");
        return v;
    }
    
    private boolean handleRemoveTable(Player player) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }
        
        BlackjackTable nearestTable = tableManager.findNearestTable(player.getLocation());
        if (nearestTable != null) {
            if (tableManager.removeTable(nearestTable.getCenterLocation())) {
                player.sendMessage(configManager.getMessage("table-removed"));
            } else {
                player.sendMessage(configManager.getMessage("table-remove-failed"));
            }
        } else {
            player.sendMessage(configManager.getMessage("no-table-nearby"));
        }
        return true;
    }
    
    private boolean handleJoin(Player player) {
        if (tableManager.getPlayerTable(player) != null) {
            player.sendMessage(configManager.getMessage("already-at-table"));
            return true;
        }
        
        BlackjackTable nearestTable = tableManager.findNearestTable(player.getLocation());
        if (nearestTable != null) {
            nearestTable.addPlayer(player);
        } else {
            player.sendMessage(configManager.getMessage("no-table-nearby"));
        }
        return true;
    }
    
    private boolean handleLeave(Player player) {
        BlackjackTable table = tableManager.getPlayerTable(player);
        if (table != null) {
            // Remove player from table (this will handle bet refunding automatically if needed)
            table.removePlayer(player);
            
            // Clear persistent bet when leaving
            playerPersistentBets.remove(player);
        } else {
            player.sendMessage(configManager.getMessage("not-at-table"));
        }
        return true;
    }
    
    private boolean handleStart(Player player) {
        BlackjackTable table = tableManager.getPlayerTable(player);
        if (table != null) {
            // Auto-bet if player has a persistent bet amount but no current bet
            Integer currentBet = playerBets.get(player);
            Integer persistentBet = playerPersistentBets.get(player);
            
            if ((currentBet == null || currentBet == 0) && persistentBet != null && persistentBet > 0) {
                // Attempt to place the persistent bet automatically
                if (processBet(player, persistentBet)) {
                    player.sendMessage(configManager.formatMessage("auto-bet-placed", "amount", persistentBet));
                }
            }
            
            table.startGame();
        } else {
            player.sendMessage(configManager.getMessage("not-at-table"));
        }
        return true;
    }
    
    private boolean handleHit(Player player) {
        return GenericUtils.handleTableAction(player, tableManager, configManager, "hit", 
            table -> table.hit(player));
    }
    
    private boolean handleStand(Player player) {
        return GenericUtils.handleTableAction(player, tableManager, configManager, "stand", 
            table -> table.stand(player));
    }
    
    private boolean handleDoubleDown(Player player) {
        return GenericUtils.handleTableAction(player, tableManager, configManager, "doubledown", 
            table -> table.doubleDown(player));
    }
    
    private boolean handleBet(Player player, String[] args) {
        if (tableManager.getPlayerTable(player) == null) {
            player.sendMessage(configManager.getMessage("not-at-table"));
            return true;
        }
        
        if (args.length < 2) {
            player.sendMessage(configManager.getMessage("bet-usage"));
            return true;
        }
        
        Integer amount = GenericUtils.parseIntegerArgument(args[1], player, configManager, "invalid-amount");
        if (amount == null) {
            return true;
        }
        return processBet(player, amount);
    }
    
    private boolean handleStats(Player player, String[] args) {
        UUID targetUUID = player.getUniqueId();
        String targetName = player.getName();
        
        // Check if admin is checking another player's stats
        if (args.length > 1) {
            if (!player.hasPermission("blackjack.stats.others")) {
                player.sendMessage(configManager.getMessage("stats-no-permission"));
                return true;
            }
            
            targetName = args[1];
            Player targetPlayer = getServer().getPlayer(targetName);
            if (targetPlayer != null) {
                targetUUID = targetPlayer.getUniqueId();
                targetName = targetPlayer.getName();
            } else {
                // Try to find offline player using UUID (avoiding deprecated method)
                try {
                    // Attempt to get UUID from Mojang API or cache (implement as needed)
                    // Example: Use a UUID cache or external API here for production
                    // For now, fallback to searching known offline players
                    org.bukkit.OfflinePlayer[] offlinePlayers = getServer().getOfflinePlayers();
                    org.bukkit.OfflinePlayer offlinePlayer = null;
                    for (org.bukkit.OfflinePlayer op : offlinePlayers) {
                        if (op.getName() != null && op.getName().equalsIgnoreCase(targetName)) {
                            offlinePlayer = op;
                            break;
                        }
                    }
                    if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                        targetUUID = offlinePlayer.getUniqueId();
                        targetName = offlinePlayer.getName();
                    } else {
                        player.sendMessage(configManager.formatMessage("stats-player-not-found", "player", targetName));
                        return true;
                    }
                } catch (Exception ex) {
                    player.sendMessage(configManager.formatMessage("stats-player-not-found", "player", targetName));
                    return true;
                }
            }
        }
        
        // Load stats for the target player
        FileConfiguration statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        PlayerStats stats = GenericUtils.loadPlayerStats(statsConfig, targetUUID);
        
        if (stats.getTotalHands() == 0) {
            if (targetUUID.equals(player.getUniqueId())) {
                player.sendMessage(configManager.getMessage("stats-none-found"));
            } else {
                player.sendMessage(configManager.formatMessage("stats-none-found-player", "player", targetName));
            }
            return true;
        }
        
        // Use generic stats display method
        GenericUtils.sendStatsToPlayer(player, stats, configManager, targetName, 
            targetUUID.equals(player.getUniqueId()));
        
        return true;
    }
    
    private boolean handleReload(Player player) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }
        
        reloadConfig();
        
        // Reload messages configuration
        File messagesFile = new File(getDataFolder(), "messages.yml");
        FileConfiguration messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        
        configManager.reload(getConfig(), messagesConfig);
        player.sendMessage(configManager.getMessage("config-reloaded"));
        return true;
    }
    
    // Betting system - improved and thread-safe
    
    private boolean processBet(Player player, int amount) {
        // Resolve per-table bet limits (fall back to global config if not at a table)
        BlackjackTable playerTable = tableManager.getPlayerTable(player);
        int effectiveMin = playerTable != null
                ? playerTable.getSettings().getMinBet(configManager) : configManager.getMinBet();
        int effectiveMax = playerTable != null
                ? playerTable.getSettings().getMaxBet(configManager) : configManager.getMaxBet();

        // Validate bet amount
        if (amount < effectiveMin || amount > effectiveMax) {
            player.sendMessage(configManager.formatMessage("invalid-bet",
                "min_bet", effectiveMin, "max_bet", effectiveMax));
            return true;
        }
        
        // Check cooldown
        long currentTime = System.currentTimeMillis();
        long lastBet = lastBetTime.getOrDefault(player, 0L);
        if (currentTime - lastBet < configManager.getBetCooldown()) {
            player.sendMessage(configManager.getMessage("bet-cooldown"));
            return true;
        }
        
        // Check if player has enough money
        if (!economyProvider.hasEnough(player.getUniqueId(), BigDecimal.valueOf(amount))) {
            player.sendMessage(configManager.formatMessage("insufficient-funds", "amount", amount));
            return true;
        }
        
        // Process the bet
        int previousBet = playerBets.getOrDefault(player, 0);
        int difference = amount - previousBet;
        
        if (difference > 0) {
            // Taking more money
            if (economyProvider.subtract(player.getUniqueId(), BigDecimal.valueOf(difference))) {
                playerBets.put(player, amount);
                playerPersistentBets.put(player, amount); // Store for "Play Again"
                lastBetTime.put(player, currentTime);
                player.sendMessage(configManager.formatMessage("bet-set", "amount", amount));
                
                // Auto-start game if player is at table and no game in progress
                BlackjackTable table = tableManager.getPlayerTable(player);
                if (table != null && !table.isGameInProgress() && previousBet == 0) {
                    // First bet placed, try to start game
                    this.getServer().getScheduler().runTaskLater(this, () -> {
                        if (table.canStartGame()) {
                            table.startGame();
                        }
                    }, 20L); // 1 second delay to allow other players to bet
                }
            } else {
                player.sendMessage(configManager.getMessage("bet-failed"));
            }
        } else if (difference < 0) {
            // Refunding some money
            int refund = -difference;
            if (economyProvider.add(player.getUniqueId(), BigDecimal.valueOf(refund))) {
                playerBets.put(player, amount);
                playerPersistentBets.put(player, amount); // Store for "Play Again"
                lastBetTime.put(player, currentTime);
                player.sendMessage(configManager.formatMessage("bet-reduced-refunded", "amount", amount, "refund", refund));
            } else {
                player.sendMessage(configManager.getMessage("bet-refund-failed"));
            }
        } else {
            player.sendMessage(configManager.formatMessage("bet-already-set", "amount", amount));
        }
        
        return true;
    }
    
    private void refundAllBets() {
        for (Map.Entry<Player, Integer> entry : playerBets.entrySet()) {
            Player player = entry.getKey();
            Integer amount = entry.getValue();
            
            if (amount != null && amount > 0) {
                BlackjackTable table = tableManager.getPlayerTable(player);
                if (table == null || !table.isGameInProgress()) {
                    economyProvider.add(player.getUniqueId(), BigDecimal.valueOf(amount));
                    if (player.isOnline()) {
                        player.sendMessage(configManager.formatMessage("bet-refunded-shutdown", "amount", amount));
                    }
                }
            }
        }
        playerBets.clear();
    }
    
    // Player statistics system
    
    
    private void savePlayerStats() {
        if (playerStats.isEmpty()) {
            return;
        }
        
        FileConfiguration statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        
        for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
            GenericUtils.savePlayerStats(statsConfig, entry.getKey(), entry.getValue());
        }
        
        try {
            if (!statsFile.getParentFile().exists()) {
                statsFile.getParentFile().mkdirs();
            }
            statsConfig.save(statsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save player statistics: " + e.getMessage());
        }
    }
    
    private void sendHelp(Player player) {
        player.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("help-header"));
        
        if (player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("help-admin-create"));
            player.sendMessage(configManager.getMessage("help-admin-settable"));
            player.sendMessage(configManager.getMessage("help-admin-remove"));
            player.sendMessage(configManager.getMessage("help-admin-reload"));
        }
        
        player.sendMessage(configManager.getMessage("help-join"));
        player.sendMessage(configManager.getMessage("help-leave"));
        player.sendMessage(configManager.getMessage("help-bet"));
        player.sendMessage(configManager.getMessage("help-start"));
        player.sendMessage(configManager.getMessage("help-hit"));
        player.sendMessage(configManager.getMessage("help-stand"));
        player.sendMessage(configManager.getMessage("help-stats"));
        
        if (player.hasPermission("blackjack.stats.others")) {
            player.sendMessage(configManager.getMessage("help-stats-others"));
        }
    }
    
    // Getters for managers (used by other classes)
    public ConfigManager getConfigManager() { return configManager; }
    public TableManager getTableManager() { return tableManager; }
    public EconomyProvider getEconomyProvider() { return economyProvider; }
    public AsyncUtils getAsyncUtils() { return asyncUtils; }
    
    // Player data getters
    public Map<Player, Integer> getPlayerBets() { return playerBets; }
    public Map<Player, Integer> getPlayerPersistentBets() { return playerPersistentBets; }
    public Map<UUID, PlayerStats> getPlayerStats() { return playerStats; }
    public boolean isGSitEnabled() { return gSitEnabled; }
    
    public VersionChecker getVersionChecker() { return versionChecker; }
}
