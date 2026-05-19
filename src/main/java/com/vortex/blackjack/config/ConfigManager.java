package com.vortex.blackjack.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Centralized configuration management with validation and caching
 */
public class ConfigManager {
    private FileConfiguration config;
    private FileConfiguration messagesConfig;
    
    // Cached values for performance
    private int minBet;
    private int maxBet;
    private long betCooldown;
    private double maxJoinDistance;
    private int maxPlayers;
    private Material tableMaterial;
    private Material chairMaterial;
    private boolean soundsEnabled;
    private boolean particlesEnabled;
    private boolean hitSoft17;
    
    public ConfigManager(FileConfiguration config, FileConfiguration messagesConfig) {
        this.config = config;
        this.messagesConfig = messagesConfig;
        loadAndValidateConfig();
    }
    
    // Backward compatibility constructor
    public ConfigManager(FileConfiguration config) {
        this.config = config;
        this.messagesConfig = config; // Use main config for messages if no separate messages config
        loadAndValidateConfig();
    }
    
    private void loadAndValidateConfig() {
        // Betting settings
        minBet = Math.max(1, config.getInt("betting.min-bet", 10));
        maxBet = Math.max(minBet, config.getInt("betting.max-bet", 10000));
        betCooldown = Math.max(0, config.getLong("betting.cooldown-ms", 2000L));
        
        // Table settings
        maxJoinDistance = Math.max(1.0, config.getDouble("table.max-join-distance", 10.0));
        maxPlayers = Math.max(1, Math.min(8, config.getInt("table.max-players", 4)));
        
        // Materials with fallbacks
        try {
            tableMaterial = Material.valueOf(config.getString("table.table-material", "GREEN_TERRACOTTA"));
        } catch (IllegalArgumentException e) {
            tableMaterial = Material.GREEN_TERRACOTTA;
        }
        
        try {
            chairMaterial = Material.valueOf(config.getString("table.chair-material", "DARK_OAK_STAIRS"));
        } catch (IllegalArgumentException e) {
            chairMaterial = Material.DARK_OAK_STAIRS;
        }
        
        // Audio/visual settings
        soundsEnabled = config.getBoolean("sounds.enabled", true);
        particlesEnabled = config.getBoolean("particles.enabled", true);
        
        // Game rules
        hitSoft17 = config.getBoolean("game.hit-soft-17", false);
    }
    
    // Getters
    public int getMinBet() { return minBet; }
    public int getMaxBet() { return maxBet; }
    public long getBetCooldown() { return betCooldown; }
    public double getMaxJoinDistance() { return maxJoinDistance; }
    public int getMaxPlayers() { return maxPlayers; }
    public Material getTableMaterial() { return tableMaterial; }
    public Material getChairMaterial() { return chairMaterial; }
    public boolean areSoundsEnabled() { return soundsEnabled; }
    public boolean areParticlesEnabled() { return particlesEnabled; }
    public boolean shouldHitSoft17() { return hitSoft17; }
    
    // Auto-leave settings
    public int getAutoLeaveTimeoutSeconds() {
        return Math.max(10, config.getInt("game.auto-leave-timeout-seconds", 30));
    }
    
    // Quick bet settings
    public java.util.List<Integer> getSmallBets() {
        return config.getIntegerList("betting.quick-bets.small");
    }
    
    public java.util.List<Integer> getMediumBets() {
        return config.getIntegerList("betting.quick-bets.medium");
    }
    
    public java.util.List<Integer> getLargeBets() {
        return config.getIntegerList("betting.quick-bets.large");
    }
    
    // Display settings
    public float getCardScale() {
        return (float) config.getDouble("display.card.scale", 0.35);
    }
    
    public double getCardSpacing() {
        return config.getDouble("display.card.spacing", 0.25);
    }
    
    public double getPlayerCardHeight() {
        return config.getDouble("display.card.player.height", 1.05);
    }
    
    public double getDealerCardHeight() {
        return config.getDouble("display.card.dealer.height", 1.2);
    }
    
    /**
     * Safely get a Sound enum from a string name with fallback
     */
    private Sound getSoundFromString(String soundName, Sound fallback) {
        if (soundName == null || soundName.trim().isEmpty()) {
            return fallback;
        }
        
        try {
            // Use Registry API for Minecraft 1.21.3+
            NamespacedKey key = NamespacedKey.minecraft(soundName.toLowerCase().replace("_", ""));
            Sound sound = Registry.SOUNDS.get(key);
            if (sound != null) {
                return sound;
            }
            
            // Fallback: try direct match with the enum constant
            if (soundName.equals("BLOCK_WOODEN_BUTTON_CLICK_ON")) return Sound.BLOCK_WOODEN_BUTTON_CLICK_ON;
            if (soundName.equals("ENTITY_PLAYER_LEVELUP")) return Sound.ENTITY_PLAYER_LEVELUP;
            if (soundName.equals("ENTITY_VILLAGER_NO")) return Sound.ENTITY_VILLAGER_NO;
            if (soundName.equals("BLOCK_NOTE_BLOCK_PLING")) return Sound.BLOCK_NOTE_BLOCK_PLING;
            
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
    
    // Sound configuration
    public Sound getCardDealSound() {
        String soundName = config.getString("sounds.card-deal.sound", "BLOCK_WOODEN_BUTTON_CLICK_ON");
        return getSoundFromString(soundName, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON);
    }
    
    public float getCardDealVolume() {
        return (float) config.getDouble("sounds.card-deal.volume", 1.0);
    }
    
    public float getCardDealPitch() {
        return (float) config.getDouble("sounds.card-deal.pitch", 1.2);
    }
    
    public Sound getWinSound() {
        String soundName = config.getString("sounds.win.sound", "ENTITY_PLAYER_LEVELUP");
        return getSoundFromString(soundName, Sound.ENTITY_PLAYER_LEVELUP);
    }
    
    public Sound getLoseSound() {
        String soundName = config.getString("sounds.lose.sound", "ENTITY_VILLAGER_NO");
        return getSoundFromString(soundName, Sound.ENTITY_VILLAGER_NO);
    }
    
    public Sound getPushSound() {
        String soundName = config.getString("sounds.push.sound", "BLOCK_NOTE_BLOCK_PLING");
        return getSoundFromString(soundName, Sound.BLOCK_NOTE_BLOCK_PLING);
    }
    
    // Particle configuration
    public Particle getWinParticle() {
        try {
            return Particle.valueOf(config.getString("particles.win.type", "HAPPY_VILLAGER"));
        } catch (IllegalArgumentException e) {
            return Particle.HAPPY_VILLAGER;
        }
    }
    
    public Particle getLoseParticle() {
        try {
            return Particle.valueOf(config.getString("particles.lose.type", "ANGRY_VILLAGER"));
        } catch (IllegalArgumentException e) {
            return Particle.ANGRY_VILLAGER;
        }
    }
    
    // Message handling
    public String getMessage(String path) {
        String message;
        // Try messages config first, then fall back to main config with "messages." prefix
        if (messagesConfig.contains(path)) {
            message = messagesConfig.getString(path);
        } else {
            message = config.getString("messages." + path);
        }
        
        if (message == null) {
            message = "&cMessage not found: " + path;
        }
        
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String formatMessage(String path, Object... args) {
        String message = getMessage(path);
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                message = message.replace("%" + args[i] + "%", String.valueOf(args[i + 1]));
            }
        }
        return message;
    }

    public void reload(FileConfiguration newConfig, FileConfiguration newMessagesConfig) {
        if (newConfig != null) {
            this.config = newConfig;
        }
        if (newMessagesConfig != null) {
            this.messagesConfig = newMessagesConfig;
        }
        loadAndValidateConfig();
    }
    
    // Backward compatibility reload method
    public void reload(FileConfiguration newConfig) {
        reload(newConfig, newConfig);
    }
    
    // Performance settings
    public int getStatsSaveInterval() {
        return config.getInt("performance.stats-save-interval", 3);
    }
    
    // Game settings
    public boolean shouldRefundOnLeave() {
        return config.getBoolean("game-settings.refund-on-leave", true);
    }
    
    // Button configuration methods
    public String getButtonText(String buttonName) {
        return ChatColor.translateAlternateColorCodes('&', 
            messagesConfig.getString("buttons." + buttonName + ".text", "&7[" + buttonName.toUpperCase() + "]"));
    }
    
    public String getButtonCommand(String buttonName) {
        String command = messagesConfig.getString("buttons." + buttonName + ".command", getDefaultButtonCommand(buttonName));
        return normalizeBlackjackCommand(command);
    }

    private String getDefaultButtonCommand(String buttonName) {
        return switch (buttonName) {
            case "double-down" -> "/bj doubledown";
            case "play-again" -> "/bj start";
            case "leave-table" -> "/bj leave";
            case "custom-bet" -> "/bj bet ";
            default -> "/bj " + buttonName;
        };
    }

    private String normalizeBlackjackCommand(String command) {
        if (command == null || command.isBlank()) {
            return "/bj";
        }

        String raw = command.startsWith("/") ? command.substring(1) : command;
        String loweredRaw = raw.toLowerCase();
        if (loweredRaw.equals("bj") || loweredRaw.startsWith("bj ")
            || loweredRaw.equals("blackjack") || loweredRaw.startsWith("blackjack ")) {
            return command;
        }

        int firstWhitespace = findFirstWhitespace(raw);
        String action = firstWhitespace < 0 ? raw : raw.substring(0, firstWhitespace);
        String arguments = firstWhitespace < 0 ? "" : raw.substring(firstWhitespace);

        return switch (action.toLowerCase()) {
            case "createtable", "settable", "removetable", "join", "leave", "start", "hit", "stand",
                "doubledown", "bet", "stats", "reload" -> "/bj " + action.toLowerCase() + arguments;
            case "dd" -> "/bj doubledown" + arguments;
            case "bjversion", "version" -> "/bj version" + arguments;
            default -> command;
        };
    }

    private int findFirstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
    
    public String getButtonHover(String buttonName) {
        return ChatColor.translateAlternateColorCodes('&', 
            messagesConfig.getString("buttons." + buttonName + ".hover", "Click to " + buttonName));
    }
    
    public String getBetColorByAmount(int amount) {
        if (amount >= 5000) {
            return ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("buttons.huge-bet-color", "&d"));
        } else if (amount >= 1000) {
            return ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("buttons.large-bet-color", "&c"));
        } else if (amount >= 100) {
            return ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("buttons.medium-bet-color", "&e"));
        } else {
            return ChatColor.translateAlternateColorCodes('&', messagesConfig.getString("buttons.small-bet-color", "&a"));
        }
    }
    
    public String getGameActionPrompt() {
        return ChatColor.translateAlternateColorCodes('&', 
            messagesConfig.getString("game-action-prompt", "&7Your turn: "));
    }
    
    public String getGameActionSeparator() {
        return ChatColor.translateAlternateColorCodes('&', 
            messagesConfig.getString("game-action-separator", "&7 | "));
    }
    
    public String getPostGamePrompt() {
        return ChatColor.translateAlternateColorCodes('&', 
            messagesConfig.getString("post-game-prompt", "&7Choose: "));
    }
    
    // Betting category labels
    public String getBettingCategoryLabel(String category) {
        return ChatColor.translateAlternateColorCodes('&', 
            messagesConfig.getString("betting-category-" + category, "&7" + category.substring(0, 1).toUpperCase() + category.substring(1) + ": "));
    }
}
