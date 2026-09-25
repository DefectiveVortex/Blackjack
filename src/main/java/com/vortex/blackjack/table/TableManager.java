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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Manages blackjack tables - creation, removal, and lookup.
 *
 * Tables live in tables.yml under a numeric ID. Versions before 2.5 kept them in config.yml,
 * keyed by world and block position; those entries are moved over once on first start.
 */
public class TableManager {
    private final BlackjackPlugin plugin;
    private final ConfigManager configManager;
    private final Map<Integer, BlackjackTable> tables = new ConcurrentSkipListMap<>();
    private final Map<Player, BlackjackTable> playerTables = new ConcurrentHashMap<>();
    private final File tablesFile;
    private YamlConfiguration tablesConfig;

    /** A block that belongs to a table: a chair (seat 0-3) or the table top (seat -1). */
    public record TableBlock(BlackjackTable table, int seat) {}

    public TableManager(BlackjackPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.tablesFile = new File(plugin.getDataFolder(), "tables.yml");
    }

    /**
     * Load tables on startup, moving pre-2.5 entries out of config.yml first if needed.
     */
    public void loadTables() {
        tablesConfig = YamlConfiguration.loadConfiguration(tablesFile);
        migrateFromConfig();

        ConfigurationSection section = tablesConfig.getConfigurationSection("tables");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                int id;
                try {
                    id = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Skipping table with a non-numeric ID in tables.yml: " + key);
                    continue;
                }
                if (entry == null) continue;

                String worldName = entry.getString("world", "");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    // Kept in tables.yml untouched, so it comes back once the world is loaded again
                    plugin.getLogger().warning("Table #" + id + " is in world '" + worldName + "', which isn't loaded; skipping it.");
                    continue;
                }

                Location center = blockCenter(world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
                tables.put(id, new BlackjackTable(plugin, this, configManager, id, center, readSettings(entry)));
            }
        }

        plugin.getLogger().info("Loaded " + tables.size() + " blackjack tables");
    }

    /**
     * Before 2.5 tables were stored in config.yml as tables.<world>.<x>_<y>_<z>, either `true` or a
     * section of overrides. Copy them into tables.yml with fresh IDs and take them out of config.yml,
     * keeping a backup of the old config.
     */
    private void migrateFromConfig() {
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("tables");
        if (legacy == null) {
            return;
        }

        int moved = 0;
        for (String worldName : legacy.getKeys(false)) {
            ConfigurationSection worldSection = legacy.getConfigurationSection(worldName);
            if (worldSection == null) continue;

            for (String locKey : worldSection.getKeys(false)) {
                String[] parts = locKey.split("_");
                if (parts.length != 3) {
                    plugin.getLogger().warning("Invalid table location format: " + locKey);
                    continue;
                }
                try {
                    int x = Integer.parseInt(parts[0]);
                    int y = Integer.parseInt(parts[1]);
                    int z = Integer.parseInt(parts[2]);
                    if (hasEntryAt(worldName, x, y, z)) {
                        continue; // already moved by an earlier start that didn't finish
                    }
                    TableSettings settings = worldSection.isConfigurationSection(locKey)
                        ? readSettings(worldSection.getConfigurationSection(locKey))
                        : new TableSettings();
                    writeEntry(nextId(), worldName, x, y, z, settings);
                    moved++;
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid table location format: " + locKey);
                }
            }
        }

        if (!saveTablesFile()) {
            return; // leave config.yml alone so nothing is lost
        }

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            Files.copy(configFile.toPath(), new File(plugin.getDataFolder(), "config.yml.pre-tables.bak").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not back up config.yml before moving tables out of it: " + e.getMessage());
        }
        plugin.getConfig().set("tables", null);
        plugin.saveConfig();
        plugin.getLogger().info("Moved " + moved + " table(s) from config.yml to tables.yml (backup: config.yml.pre-tables.bak)");
    }

    /**
     * Build a table centred on the block the player is standing in.
     *
     * @return the new table, or null if it would overlap an existing one
     */
    public BlackjackTable createTable(Location playerLoc, TableSettings settings) {
        World world = playerLoc.getWorld();
        if (world == null) return null;

        int centerX = playerLoc.getBlockX();
        int centerY = playerLoc.getBlockY();
        int centerZ = playerLoc.getBlockZ();

        // A table plus its chairs covers 5x5 blocks, so centres closer than 5 would overlap
        for (BlackjackTable other : tables.values()) {
            Location c = other.getCenterLocation();
            if (world.equals(c.getWorld()) && Math.abs(c.getBlockX() - centerX) < 5
                    && Math.abs(c.getBlockZ() - centerZ) < 5 && Math.abs(c.getBlockY() - centerY) < 2) {
                return null;
            }
        }

        // Ensure chunk is loaded
        world.getChunkAt(playerLoc).load();

        Material tableMaterial = configManager.getTableMaterial();
        Material chairMaterial = configManager.getChairMaterial();

        // Create table blocks (3x3 hollow square)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) { // Don't place block in center
                    world.getBlockAt(centerX + x, centerY, centerZ + z).setType(tableMaterial);
                }
            }
        }

        // Place chairs at cardinal directions
        placeStair(world, centerX + 2, centerY, centerZ, BlockFace.EAST, chairMaterial);
        placeStair(world, centerX - 2, centerY, centerZ, BlockFace.WEST, chairMaterial);
        placeStair(world, centerX, centerY, centerZ + 2, BlockFace.SOUTH, chairMaterial);
        placeStair(world, centerX, centerY, centerZ - 2, BlockFace.NORTH, chairMaterial);

        int id = nextId();
        writeEntry(id, world.getName(), centerX, centerY, centerZ, settings);
        saveTablesFile();

        BlackjackTable table = new BlackjackTable(plugin, this, configManager, id,
            blockCenter(world, centerX, centerY, centerZ), settings);
        tables.put(id, table);
        return table;
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
     * Remove a table: everyone is sent away, its cards and seats go, and it's deleted from tables.yml.
     * Its felt and chairs are cleared too, as long as they're still the configured materials.
     */
    public boolean removeTable(BlackjackTable table) {
        if (tables.remove(table.getId()) == null) return false;

        // Remove all players from the table
        table.removeAllPlayers();
        table.cleanup();
        removeTableBlocks(table);

        tablesConfig.set("tables." + table.getId(), null);
        saveTablesFile();
        return true;
    }

    /**
     * Clear the felt ring and the chairs. Only blocks that are still the configured table or chair
     * material go, so anything an admin built or swapped in around the table is left alone.
     */
    private void removeTableBlocks(BlackjackTable table) {
        Location c = table.getCenterLocation();
        World world = c.getWorld();
        if (world == null) {
            return;
        }
        Material tableMaterial = configManager.getTableMaterial();
        Material chairMaterial = configManager.getChairMaterial();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean felt = Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && (dx != 0 || dz != 0);
                boolean chair = BlackjackTable.seatAtOffset(dx, dz) >= 0;
                Block block = world.getBlockAt(c.getBlockX() + dx, c.getBlockY(), c.getBlockZ() + dz);
                if ((felt && block.getType() == tableMaterial) || (chair && block.getType() == chairMaterial)) {
                    block.setType(Material.AIR);
                }
            }
        }
    }

    public BlackjackTable getTable(int id) {
        return tables.get(id);
    }

    /** All loaded tables, in ID order. */
    public Collection<BlackjackTable> getTables() {
        return List.copyOf(tables.values());
    }

    /**
     * Find the nearest table to a player's location.
     * Each table is checked against its own max-join-distance setting.
     */
    public BlackjackTable findNearestTable(Location playerLoc) {
        double closestDistance = Double.MAX_VALUE;
        BlackjackTable closestTable = null;

        for (BlackjackTable table : tables.values()) {
            Location tableLoc = table.getCenterLocation();
            if (!tableLoc.getWorld().equals(playerLoc.getWorld())) {
                continue;
            }

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
     * Which table (and which chair, if any) a block belongs to, or null if it isn't part of one.
     */
    public TableBlock findTableBlock(Block block) {
        for (BlackjackTable table : tables.values()) {
            Location c = table.getCenterLocation();
            if (!block.getWorld().equals(c.getWorld()) || block.getY() != c.getBlockY()) {
                continue;
            }
            int dx = block.getX() - c.getBlockX();
            int dz = block.getZ() - c.getBlockZ();
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && (dx != 0 || dz != 0)) {
                return new TableBlock(table, -1);
            }
            int seat = BlackjackTable.seatAtOffset(dx, dz);
            if (seat >= 0) {
                return new TableBlock(table, seat);
            }
        }
        return null;
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
     * Remove player from any table they're at with custom reason
     */
    public void removePlayerFromTable(Player player, String reason) {
        BlackjackTable table = playerTables.remove(player);
        if (table != null) {
            table.removePlayer(player, reason);
        }
    }

    /**
     * True if the entity is a card or seat currently in use by a table.
     */
    public boolean isActiveTableEntity(Entity entity) {
        for (BlackjackTable table : tables.values()) {
            if (table.ownsEntity(entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Persist updated settings for an existing table (used by /bj settable).
     */
    public void saveTableSettings(BlackjackTable table) {
        Location c = table.getCenterLocation();
        writeEntry(table.getId(), c.getWorld().getName(), c.getBlockX(), c.getBlockY(), c.getBlockZ(), table.getSettings());
        saveTablesFile();
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
    // tables.yml persistence
    // -------------------------------------------------------------------------

    private static Location blockCenter(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private int nextId() {
        int id = Math.max(1, tablesConfig.getInt("next-id", 1));
        tablesConfig.set("next-id", id + 1);
        return id;
    }

    private boolean hasEntryAt(String world, int x, int y, int z) {
        ConfigurationSection section = tablesConfig.getConfigurationSection("tables");
        if (section == null) return false;
        for (String key : section.getKeys(false)) {
            ConfigurationSection e = section.getConfigurationSection(key);
            if (e != null && world.equals(e.getString("world")) && e.getInt("x") == x
                    && e.getInt("y") == y && e.getInt("z") == z) {
                return true;
            }
        }
        return false;
    }

    private void writeEntry(int id, String world, int x, int y, int z, TableSettings settings) {
        String path = "tables." + id;
        tablesConfig.set(path, null);
        tablesConfig.set(path + ".world", world);
        tablesConfig.set(path + ".x", x);
        tablesConfig.set(path + ".y", y);
        tablesConfig.set(path + ".z", z);
        // Only overrides are written; anything unset follows config.yml
        tablesConfig.set(path + ".min-bet", settings.getRawMinBet());
        tablesConfig.set(path + ".max-bet", settings.getRawMaxBet());
        tablesConfig.set(path + ".max-players", settings.getRawMaxPlayers());
        tablesConfig.set(path + ".max-join-distance", settings.getRawMaxJoinDistance());
    }

    private boolean saveTablesFile() {
        tablesConfig.options().setHeader(List.of(
            "Blackjack tables, by ID. Managed by the plugin: use /bj createtable, /bj settable and",
            "/bj removetable instead of editing this while the server is running.",
            "Settings left out of a table follow config.yml."));
        try {
            tablesConfig.save(tablesFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save tables.yml: " + e.getMessage());
            return false;
        }
    }

    private static TableSettings readSettings(ConfigurationSection sec) {
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
