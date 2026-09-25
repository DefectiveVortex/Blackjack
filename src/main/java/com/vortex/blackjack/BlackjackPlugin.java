package com.vortex.blackjack;

import com.vortex.blackjack.commands.CommandManager;
import com.vortex.blackjack.config.ConfigFileUpdater;
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.economy.EconomyProvider;
import com.vortex.blackjack.economy.VaultEconomyProvider;
import com.vortex.blackjack.gui.BetMenu;
import com.vortex.blackjack.integration.BlackjackPlaceholderExpansion;
import com.vortex.blackjack.integration.CardResourcePack;
import com.vortex.blackjack.model.PlayerStats;
import com.vortex.blackjack.table.BlackjackTable;
import com.vortex.blackjack.table.CardDisplayCleaner;
import com.vortex.blackjack.table.TableInteractListener;
import com.vortex.blackjack.table.TableManager;
import com.vortex.blackjack.table.TableSettings;
import com.vortex.blackjack.util.AsyncUtils;
import com.vortex.blackjack.util.GenericUtils;
import com.vortex.blackjack.util.ServerCompat;
import com.vortex.blackjack.util.VersionChecker;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    
    // Card visuals: resource pack offer and cleanup of stray card entities
    private CardResourcePack cardResourcePack;
    private CardDisplayCleaner cardDisplayCleaner;
    private BetMenu betMenu;
    
    // Version checker
    private VersionChecker versionChecker;
    
    // Player data - thread-safe collections
    private final Map<Player, Integer> playerBets = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerPersistentBets = new ConcurrentHashMap<>(); // Keeps bet amount for "Play Again"
    private final Map<Player, Long> lastBetTime = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    
    // Files
    private File statsFile;
    private volatile boolean statsDirty = false;
    
    @Override
    public void onEnable() {
        // Create and update configuration files before managers read them.
        saveDefaultConfig();
        ConfigFileUpdater.update(this, "config.yml", new File(getDataFolder(), "config.yml"));
        reloadConfig();
        
        configManager = new ConfigManager(getConfig(), loadMessages());
        
        // Initialize core managers
        tableManager = new TableManager(this, configManager);
        commandManager = new CommandManager(this);
        asyncUtils = new AsyncUtils(this);
        versionChecker = new VersionChecker(this);
        cardResourcePack = new CardResourcePack(this);
        cardDisplayCleaner = new CardDisplayCleaner(this, tableManager);
        betMenu = new BetMenu(this);

        if (!ServerCompat.ITEM_MODELS) {
            getLogger().info("This server is older than 1.21.2, so cards are textured through CustomModelData. "
                + "Players need Playing Cards 1.2 or newer to see them.");
        }
        
        // Initialize economy provider - Vault with EssentialsX fallback
        economyProvider = initializeEconomyProvider();
        
        if (economyProvider == null || !economyProvider.isAvailable()) {
            boolean vaultInstalled = getServer().getPluginManager().getPlugin("Vault") != null;
            if (!vaultInstalled) {
                getLogger().severe("Vault is required. Install Vault plus a Vault-compatible economy plugin, then restart the server.");
            } else {
                getLogger().severe("Vault was found, but no economy provider was registered. Install a Vault-compatible economy plugin (EssentialsX, EconomyAPI, CMI, HexaEcon, etc.).");
            }
            getLogger().severe("Disabling Blackjack...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // GSit is only used when the plugin isn't seating players itself (table.seat-players: false)
        gSitEnabled = getServer().getPluginManager().getPlugin("GSit") != null;
        if (!configManager.shouldSeatPlayers()) {
            getLogger().info(gSitEnabled
                ? "GSit found: players will sit with /sit when they join a table."
                : "table.seat-players is off and GSit isn't installed, so players stand next to their chair.");
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
        getServer().getPluginManager().registerEvents(cardDisplayCleaner, this);
        getServer().getPluginManager().registerEvents(betMenu, this);
        getServer().getPluginManager().registerEvents(new TableInteractListener(this, tableManager), this);
        
        // Register command prefix aliases: /blackjack and /bj
        commandManager.registerCommands();
        
        // Load tables from config
        tableManager.loadTables();
        
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
     * Load the messages file for the language set in config.yml.
     * English lives in messages.yml (unchanged from older versions, so existing edits keep working);
     * other languages live in messages_<code>.yml. Bundled translations are copied out on first use
     * and topped up with new keys on update. A server can add its own language by dropping in a
     * messages_<code>.yml. Anything a translation is missing falls back to the bundled English text.
     */
    private FileConfiguration loadMessages() {
        String language = getConfig().getString("language", "en").trim().toLowerCase(Locale.ROOT);
        String fileName = language.isEmpty() || language.equals("en") ? "messages.yml" : "messages_" + language + ".yml";
        File messagesFile = new File(getDataFolder(), fileName);

        YamlConfiguration messages;
        if (isBundled(fileName)) {
            messages = ConfigFileUpdater.update(this, fileName, messagesFile);
        } else if (messagesFile.exists()) {
            messages = YamlConfiguration.loadConfiguration(messagesFile);
        } else {
            getLogger().warning("No messages file for language '" + language + "' (expected " + fileName
                + "). Bundled languages: en, ko, tr, ru. Falling back to English.");
            messages = ConfigFileUpdater.update(this, "messages.yml", new File(getDataFolder(), "messages.yml"));
        }

        try (InputStream stream = getResource("messages.yml")) {
            if (stream != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            getLogger().warning("Could not read bundled English messages: " + e.getMessage());
        }
        return messages;
    }

    private boolean isBundled(String resourceName) {
        try (InputStream stream = getResource(resourceName)) {
            return stream != null;
        } catch (IOException e) {
            return false;
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
        cardResourcePack.forget(player);
        betMenu.forget(player);
        
        // Remove from table if they're at one
        if (tableManager != null) {
            tableManager.removePlayerFromTable(player, configManager.getMessage("leave-reason-disconnected"));
        }
        
        // Note: Stats are now saved periodically, not on every quit for better performance
    }
    
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check if player moved to a different block (optimization)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() && 
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        
        leaveTableIfOutOfRange(event.getPlayer(), event.getTo());
    }

    // Teleports, respawns and world changes don't fire PlayerMoveEvent. Without these a player
    // could /home away and stay seated, holding the seat and the turn while their cards sat on
    // a table in a chunk that might unload.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        leaveTableIfOutOfRange(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        leaveTableIfOutOfRange(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        leaveTableIfOutOfRange(event.getPlayer(), event.getPlayer().getLocation());
    }

    private void leaveTableIfOutOfRange(Player player, Location destination) {
        if (tableManager == null || destination == null) {
            return;
        }
        
        BlackjackTable table = tableManager.getPlayerTable(player);
        if (table == null) {
            return;
        }
        
        Location center = table.getCenterLocation();
        double maxDistance = table.getSettings().getMaxJoinDistance(configManager);
        boolean sameWorld = destination.getWorld() != null && destination.getWorld().equals(center.getWorld());
        if (sameWorld && destination.distance(center) <= maxDistance) {
            return;
        }
        
        // Player moved too far from table, auto-leave
        table.removePlayer(player, configManager.getMessage("leave-reason-too-far"));
        player.sendMessage(configManager.getMessage("auto-left-table"));
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
            case "removetable" -> handleRemoveTable(player, args);
            case "tables" -> handleTables(player);
            case "join" -> handleJoin(player);
            case "leave" -> handleLeave(player);
            case "start" -> handleStart(player);
            case "hit" -> handleHit(player);
            case "stand" -> handleStand(player);
            case "doubledown", "dd" -> handleDoubleDown(player);
            case "bet" -> handleBet(player, args);
            case "stats" -> handleStats(player, args);
            case "reload" -> handleReload(player);
            case "version" -> handleVersion(player);
            case "cleanup" -> handleCleanup(player, args);
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

        BlackjackTable created = tableManager.createTable(player.getLocation(), settings);
        if (created != null) {
            player.sendMessage(configManager.formatMessage("table-created-with-settings",
                "id",               created.getId(),
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
        // Validate a copy so a rejected value never reaches the live table
        TableSettings updated = new TableSettings(s.getRawMinBet(), s.getRawMaxBet(),
                s.getRawMaxPlayers(), s.getRawMaxJoinDistance());

        try {
            switch (setting) {
                case "min-bet"            -> updated.setMinBet(parsePositiveInt(value));
                case "max-bet"            -> updated.setMaxBet(parsePositiveInt(value));
                case "max-players"        -> updated.setMaxPlayers(parsePositiveInt(value));
                case "max-join-distance"  -> updated.setMaxJoinDistance(parsePositiveDouble(value));
                default -> {
                    player.sendMessage(configManager.formatMessage("settable-unknown-setting", "setting", setting));
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            player.sendMessage(configManager.formatMessage("settable-invalid-value", "value", value));
            return true;
        }

        String err = updated.validate(configManager);
        if (err != null) {
            player.sendMessage(configManager.formatMessage("settable-validation-error", "error", err));
            return true;
        }

        s.setMinBet(updated.getRawMinBet());
        s.setMaxBet(updated.getRawMaxBet());
        s.setMaxPlayers(updated.getRawMaxPlayers());
        s.setMaxJoinDistance(updated.getRawMaxJoinDistance());

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
    
    private boolean handleRemoveTable(Player player, String[] args) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }
        
        BlackjackTable nearestTable;
        if (args.length > 1) {
            // /bj removetable <id>, for tables you can't stand next to (or can't find)
            Integer id = GenericUtils.parseIntegerArgument(args[1], player, configManager, "invalid-amount");
            if (id == null) {
                return true;
            }
            nearestTable = tableManager.getTable(id);
            if (nearestTable == null) {
                player.sendMessage(configManager.formatMessage("table-not-found-id", "id", id));
                return true;
            }
        } else {
            nearestTable = tableManager.findNearestTable(player.getLocation());
        }
        if (nearestTable != null) {
            if (tableManager.removeTable(nearestTable)) {
                player.sendMessage(configManager.getMessage("table-removed"));
            } else {
                player.sendMessage(configManager.getMessage("table-remove-failed"));
            }
        } else {
            player.sendMessage(configManager.getMessage("no-table-nearby"));
        }
        return true;
    }
    
    /**
     * /bj tables - every table with its ID, position and how full it is.
     */
    private boolean handleTables(Player player) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }

        java.util.Collection<BlackjackTable> all = tableManager.getTables();
        if (all.isEmpty()) {
            player.sendMessage(configManager.getMessage("tables-none"));
            return true;
        }
        player.sendMessage(configManager.formatMessage("tables-header", "count", all.size()));
        for (BlackjackTable table : all) {
            Location c = table.getCenterLocation();
            player.sendMessage(configManager.formatMessage("tables-entry",
                "id", table.getId(),
                "world", c.getWorld().getName(),
                "x", c.getBlockX(), "y", c.getBlockY(), "z", c.getBlockZ(),
                "players", table.getPlayerCount(),
                "max_players", table.getSettings().getMaxPlayers(configManager)));
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
            if (table.isBettingLocked()) {
                player.sendMessage(configManager.getMessage("betting-locked"));
                return true;
            }

            // Auto-bet if player has a persistent bet amount but no current bet
            Integer currentBet = playerBets.get(player);
            Integer persistentBet = playerPersistentBets.get(player);
            
            if ((currentBet == null || currentBet == 0) && persistentBet != null && persistentBet > 0) {
                // Attempt to place the persistent bet automatically
                if (placeBet(player, persistentBet)) {
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

        BlackjackTable table = tableManager.getPlayerTable(player);
        if (table != null && table.isBettingLocked()) {
            player.sendMessage(configManager.getMessage("betting-locked"));
            return true;
        }
        
        if (args.length < 2) {
            betMenu.open(player); // no amount given: pick one from the chip menu
            return true;
        }
        
        Integer amount = GenericUtils.parseIntegerArgument(args[1], player, configManager, "invalid-amount");
        if (amount == null) {
            return true;
        }
        return placeBet(player, amount);
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
        
        // Prefer the live record; the file can lag behind it by one autosave
        PlayerStats stats = playerStats.get(targetUUID);
        if (stats == null) {
            stats = GenericUtils.loadPlayerStats(YamlConfiguration.loadConfiguration(statsFile), targetUUID);
        }
        
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
        
        ConfigFileUpdater.update(this, "config.yml", new File(getDataFolder(), "config.yml"));
        reloadConfig();
        
        // Re-read the language too, so switching it only needs /bj reload
        configManager.reload(getConfig(), loadMessages());
        player.sendMessage(configManager.getMessage("config-reloaded"));
        return true;
    }

    private boolean handleVersion(Player player) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }

        player.sendMessage(configManager.getMessage("version-header"));
        player.sendMessage(configManager.getMessage("version-plugin"));
        player.sendMessage(configManager.getMessage("version-author"));
        player.sendMessage(configManager.formatMessage("version-current", "version", versionChecker.getCurrentVersion()));

        if (versionChecker.getLatestVersion() != null) {
            player.sendMessage(configManager.formatMessage("version-latest", "version", versionChecker.getLatestVersion()));
        }

        player.sendMessage(versionChecker.getVersionStatus());
        player.sendMessage(configManager.getMessage("version-github"));
        return true;
    }

    /**
     * /bj cleanup [radius] - remove leftover card displays near the admin (issue #8).
     * Cards belonging to a round in progress are left alone.
     */
    private boolean handleCleanup(Player player, String[] args) {
        if (!player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("no-permission"));
            return true;
        }

        double radius = 16.0;
        if (args.length > 1) {
            try {
                radius = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(configManager.getMessage("cleanup-usage"));
                return true;
            }
            if (radius <= 0) {
                player.sendMessage(configManager.getMessage("cleanup-usage"));
                return true;
            }
        }
        radius = Math.min(radius, 128.0);

        int removed = cardDisplayCleaner.removeNear(player.getLocation(), radius);
        String radiusText = String.valueOf((int) Math.round(radius));
        if (removed == 0) {
            player.sendMessage(configManager.formatMessage("cleanup-none", "radius", radiusText));
        } else {
            player.sendMessage(configManager.formatMessage("cleanup-done", "count", removed, "radius", radiusText));
        }
        return true;
    }
    
    // Betting system - improved and thread-safe
    
    /**
     * Place or change the player's bet for the next round (commands, quick-bet buttons, bet menu).
     */
    public boolean placeBet(Player player, int amount) {
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
            
            // Rounds still in progress can never finish once the plugin stops, so they are
            // voided and every stake goes back (previously in-progress bets were simply lost).
            if (amount != null && amount > 0) {
                economyProvider.add(player.getUniqueId(), BigDecimal.valueOf(amount));
                if (player.isOnline()) {
                    player.sendMessage(configManager.formatMessage("bet-refunded-shutdown", "amount", amount));
                }
            }
        }
        playerBets.clear();
    }
    
    // Player statistics system
    
    
    /**
     * Stats for a player, loaded from stats.yml the first time they're needed this session.
     */
    public PlayerStats getOrLoadStats(UUID playerId) {
        return playerStats.computeIfAbsent(playerId,
            id -> GenericUtils.loadPlayerStats(YamlConfiguration.loadConfiguration(statsFile), id));
    }

    public void markStatsDirty() {
        statsDirty = true;
    }
    
    private void savePlayerStats() {
        // Only write when a hand has finished since the last save. The old check (map not empty)
        // rewrote stats.yml every few seconds forever once anyone had played.
        if (!statsDirty || playerStats.isEmpty()) {
            return;
        }
        statsDirty = false;
        
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
            statsDirty = true; // try again next interval
            getLogger().severe("Could not save player statistics: " + e.getMessage());
        }
    }
    
    private void sendHelp(Player player) {
        player.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("help-header"));
        
        if (player.hasPermission("blackjack.admin")) {
            player.sendMessage(configManager.getMessage("help-admin-create"));
            player.sendMessage(configManager.getMessage("help-admin-settable"));
            player.sendMessage(configManager.getMessage("help-admin-remove"));
            player.sendMessage(configManager.getMessage("help-admin-tables"));
            player.sendMessage(configManager.getMessage("help-admin-reload"));
            player.sendMessage(configManager.getMessage("help-admin-version"));
            player.sendMessage(configManager.getMessage("help-admin-cleanup"));
        }
        
        player.sendMessage(configManager.getMessage("help-join"));
        player.sendMessage(configManager.getMessage("help-leave"));
        player.sendMessage(configManager.getMessage("help-bet"));
        player.sendMessage(configManager.getMessage("help-start"));
        player.sendMessage(configManager.getMessage("help-hit"));
        player.sendMessage(configManager.getMessage("help-stand"));
        player.sendMessage(configManager.getMessage("help-doubledown"));
        player.sendMessage(configManager.getMessage("help-stats"));
        
        if (player.hasPermission("blackjack.stats.others")) {
            player.sendMessage(configManager.getMessage("help-stats-others"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("blackjack")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("join");
            completions.add("leave");
            completions.add("bet");
            completions.add("start");
            completions.add("hit");
            completions.add("stand");
            completions.add("doubledown");
            completions.add("stats");

            if (sender.hasPermission("blackjack.admin")) {
                completions.add("createtable");
                completions.add("settable");
                completions.add("removetable");
                completions.add("reload");
                completions.add("version");
                completions.add("cleanup");
                completions.add("tables");
            }

            return filterCompletions(completions, args[0]);
        }

        if (sender.hasPermission("blackjack.admin")
            && args[0].equalsIgnoreCase("createtable")
            && args.length >= 2) {
            List<String> settingKeys = new ArrayList<>();
            settingKeys.add("min-bet:");
            settingKeys.add("max-bet:");
            settingKeys.add("max-players:");
            settingKeys.add("max-join-distance:");

            List<String> alreadyUsed = new ArrayList<>();
            for (int i = 1; i < args.length - 1; i++) {
                int colon = args[i].indexOf(':');
                if (colon > 0) {
                    alreadyUsed.add(args[i].substring(0, colon) + ":");
                }
            }
            settingKeys.removeAll(alreadyUsed);
            return filterCompletions(settingKeys, args[args.length - 1]);
        }

        if (sender instanceof Player player
            && player.hasPermission("blackjack.admin")
            && args[0].equalsIgnoreCase("settable")) {
            if (args.length == 2) {
                List<String> settings = new ArrayList<>();
                settings.add("min-bet");
                settings.add("max-bet");
                settings.add("max-players");
                settings.add("max-join-distance");
                return filterCompletions(settings, args[1]);
            }

            if (args.length == 3) {
                String currentValue = getCurrentTableSettingValue(player, args[1]);
                if (currentValue != null) {
                    return filterCompletions(List.of(currentValue), args[2]);
                }
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("bet")) {
            List<String> completions = new ArrayList<>();
            configManager.getSmallBets().forEach(amount -> completions.add(amount.toString()));
            configManager.getMediumBets().forEach(amount -> completions.add(amount.toString()));
            configManager.getLargeBets().forEach(amount -> completions.add(amount.toString()));
            return filterCompletions(completions, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("removetable") && sender.hasPermission("blackjack.admin")) {
            List<String> ids = new ArrayList<>();
            for (BlackjackTable table : tableManager.getTables()) {
                ids.add(String.valueOf(table.getId()));
            }
            return filterCompletions(ids, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stats")
            && sender.hasPermission("blackjack.stats.others")) {
            List<String> completions = new ArrayList<>();
            for (Player onlinePlayer : getServer().getOnlinePlayers()) {
                completions.add(onlinePlayer.getName());
            }
            return filterCompletions(completions, args[1]);
        }

        return Collections.emptyList();
    }

    private String getCurrentTableSettingValue(Player player, String setting) {
        BlackjackTable table = tableManager.findNearestTable(player.getLocation());
        if (table == null) {
            return null;
        }

        TableSettings settings = table.getSettings();
        return switch (setting.toLowerCase()) {
            case "min-bet" -> String.valueOf(settings.getMinBet(configManager));
            case "max-bet" -> String.valueOf(settings.getMaxBet(configManager));
            case "max-players" -> String.valueOf(settings.getMaxPlayers(configManager));
            case "max-join-distance" -> String.valueOf(settings.getMaxJoinDistance(configManager));
            default -> null;
        };
    }

    private List<String> filterCompletions(List<String> completions, String partial) {
        List<String> result = new ArrayList<>();
        StringUtil.copyPartialMatches(partial, completions, result);
        Collections.sort(result);
        return result;
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
    public CardResourcePack getCardResourcePack() { return cardResourcePack; }
    public BetMenu getBetMenu() { return betMenu; }
    
    public VersionChecker getVersionChecker() { return versionChecker; }
}
