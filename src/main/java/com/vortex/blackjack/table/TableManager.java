package com.vortex.blackjack.table;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages blackjack tables - creation, removal, and lookup
 */
public class TableManager {
    private final BlackjackPlugin plugin;
    private final ConfigManager configManager;
    private final Map<Location, BlackjackTable> tables = new ConcurrentHashMap<>();
    private final Map<Player, BlackjackTable> playerTables = new ConcurrentHashMap<>();

    public TableManager(BlackjackPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Load tables from configuration on startup
     */
    public void loadTablesFromConfig() {
        if (!plugin.getConfig().contains("tables")) {
            return;
        }

        for (String worldName : plugin.getConfig().getConfigurationSection("tables").getKeys(false)) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("World not found: " + worldName);
                continue;
            }

            ConfigurationSection worldSection = plugin.getConfig().getConfigurationSection("tables." + worldName);
            for (String locString : worldSection.getKeys(false)) {
                try {
                    String[] parts = locString.split("_");
                    if (parts.length != 3) continue;

                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);

                    TableSettings settings = loadSettingsFromConfig(worldSection, locString);
                    Location loc = new Location(world, x, y, z);
                    createTable(loc, settings, false); // Don't save to config again
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid table location format: " + locString);
                }
            }
        }

        plugin.getLogger().info("Loaded " + tables.size() + " blackjack tables");
    }

    /**
     * Create a new blackjack table at the specified location using global default settings.
     */
    public boolean createTable(Location centerLoc) {
        return createTable(centerLoc, new TableSettings(), true);
    }

    /**
     * Create a new blackjack table at the specified location with per-table settings.
     */
    public boolean createTable(Location centerLoc, TableSettings settings) {
        return createTable(centerLoc, settings, true);
    }

    private boolean createTable(Location centerLoc, TableSettings settings, boolean saveToConfig) {
        // Check if table already exists at this location
        for (Location loc : tables.keySet()) {
            if (loc.equals(centerLoc)) {
                return false; // Table already exists
            }
        }

        World world = centerLoc.getWorld();
        if (world == null) return false;

        int centerX = centerLoc.getBlockX();
        int centerY = centerLoc.getBlockY();
        int centerZ = centerLoc.getBlockZ();

        // Ensure chunk is loaded
        world.getChunkAt(centerLoc).load();

        Material tableMaterial = configManager.getTableMaterial();
        Material chairMaterial = configManager.getChairMaterial();

        // Create table blocks (3x3 hollow square)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) { // Don't place block in center
                    Location blockLoc = new Location(world, centerX + x, centerY, centerZ + z);
                    blockLoc.getBlock().setType(tableMaterial);
                }
            }
        }

        // Place chairs at cardinal directions
        placeStair(world, centerX + 2, centerY, centerZ, BlockFace.EAST, chairMaterial);
        placeStair(world, centerX - 2, centerY, centerZ, BlockFace.WEST, chairMaterial);
        placeStair(world, centerX, centerY, centerZ + 2, BlockFace.SOUTH, chairMaterial);
        placeStair(world, centerX, centerY, centerZ - 2, BlockFace.NORTH, chairMaterial);

        // Save to config if requested
        if (saveToConfig) {
            saveTableToConfig(centerLoc, settings);
        }

        // Create table object
        BlackjackTable table = new BlackjackTable(plugin, this, configManager, centerLoc, settings);
        tables.put(centerLoc, table);

        return true;
    }

    private void placeStair(World world, int x, int y, int z, BlockFace facing, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        if (block.getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(facing);
            block.setBlockData(stairs);
        }
    }

    /**
     * Remove a table at the specified location
     */
    public boolean removeTable(Location tableLoc) {
        BlackjackTable table = tables.remove(tableLoc);
        if (table == null) return false;

        // Remove all players from the table
        table.removeAllPlayers();
        table.cleanup();

        // Remove from config
        String worldName = tableLoc.getWorld().getName();
        int centerX = tableLoc.getBlockX();
        int centerY = tableLoc.getBlockY();
        int centerZ = tableLoc.getBlockZ();

        if (plugin.getConfig().contains("tables." + worldName)) {
            ConfigurationSection tablesSection = plugin.getConfig().getConfigurationSection("tables." + worldName);
            String key = centerX + "_" + centerY + "_" + centerZ;
            tablesSection.set(key, null);
            plugin.saveConfig();
        }

        return true;
    }

    /**
     * Find the nearest table to a player's location.
     * Each table is checked against its own max-join-distance setting.
     */
    public BlackjackTable findNearestTable(Location playerLoc) {
        double closestDistance = Double.MAX_VALUE;
        BlackjackTable closestTable = null;

        for (Map.Entry<Location, BlackjackTable> entry : tables.entrySet()) {
            Location tableLoc = entry.getKey();
            if (!tableLoc.getWorld().equals(playerLoc.getWorld())) {
                continue;
            }

            BlackjackTable table = entry.getValue();
            double distance = tableLoc.distance(playerLoc);
            double tableMaxDist = table.getSettings().getMaxJoinDistance(configManager);

            if (distance <= tableMaxDist && distance < closestDistance) {
                closestDistance = distance;
                closestTable = table;
            }
        }

        return closestTable;
    }

    /**
     * Get the table a player is currently at
     */
    public BlackjackTable getPlayerTable(Player player) {
        return playerTables.get(player);
    }

    /**
     * Set which table a player is at
     */
    public void setPlayerTable(Player player, BlackjackTable table) {
        if (table == null) {
            playerTables.remove(player);
        } else {
            playerTables.put(player, table);
        }
    }

    /**
     * Remove player from any table they're at
     */
    public void removePlayerFromTable(Player player) {
        removePlayerFromTable(player, "left the table");
    }

    /**
     * Remove player from any table they're at with custom reason
     */
    public void removePlayerFromTable(Player player, String reason) {
        BlackjackTable table = playerTables.remove(player);
        if (table != null) {
            table.removePlayer(player, reason);
        }
    }

    /**
     * Get all tables
     */
    public Map<Location, BlackjackTable> getAllTables() {
        return tables;
    }

    /**
     * Persist updated settings for an existing table (used by /bj settable).
     */
    public void saveTableSettings(BlackjackTable table) {
        saveTableToConfig(table.getCenterLocation(), table.getSettings());
    }

    /**
     * Cleanup all tables
     */
    public void cleanup() {
        for (BlackjackTable table : tables.values()) {
            table.cleanup();
        }
        tables.clear();
        playerTables.clear();
    }

    // -------------------------------------------------------------------------
    // Config persistence helpers
    // -------------------------------------------------------------------------

    private String buildTablePath(Location loc) {
        return "tables." + loc.getWorld().getName() + "."
                + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    private void saveTableToConfig(Location loc, TableSettings settings) {
        String path = buildTablePath(loc);
        if (settings.getRawMinBet() == null && settings.getRawMaxBet() == null
                && settings.getRawMaxPlayers() == null
                && settings.getRawMaxJoinDistance() == null) {
            // No overrides — use compact boolean form
            plugin.getConfig().set(path, true);
        } else {
            // Store sub-keys; null values are omitted (Bukkit skips null sets)
            plugin.getConfig().set(path + ".min-bet",           settings.getRawMinBet());
            plugin.getConfig().set(path + ".max-bet",           settings.getRawMaxBet());
            plugin.getConfig().set(path + ".max-players",       settings.getRawMaxPlayers());
            plugin.getConfig().set(path + ".max-join-distance", settings.getRawMaxJoinDistance());
        }
        plugin.saveConfig();
    }

    private TableSettings loadSettingsFromConfig(ConfigurationSection worldSection, String locKey) {
        Object raw = worldSection.get(locKey);
        if (raw instanceof Boolean) {
            return new TableSettings(); // Legacy format — all fields use global defaults
        }
        ConfigurationSection sec = worldSection.getConfigurationSection(locKey);
        if (sec == null) {
            return new TableSettings();
        }
        Integer minBet   = sec.contains("min-bet")           ? sec.getInt("min-bet")             : null;
        Integer maxBet   = sec.contains("max-bet")           ? sec.getInt("max-bet")              : null;
        Integer maxP     = sec.contains("max-players")       ? sec.getInt("max-players")          : null;
        Double  maxDist  = sec.contains("max-join-distance") ? sec.getDouble("max-join-distance") : null;
        return new TableSettings(minBet, maxBet, maxP, maxDist);
    }
}
