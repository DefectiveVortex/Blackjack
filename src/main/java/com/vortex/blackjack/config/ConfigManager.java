package com.vortex.blackjack.config;

import com.vortex.blackjack.util.ServerCompat;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Centralized configuration management with validation and caching
 */
public class ConfigManager {
    /** A table is built with four chairs, so it can never seat more than four players. */
    public static final int MAX_SEATS = 4;

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
    private boolean cardDisplaysEnabled;
    private Sound cardDealSound;
    private Sound winSound;
    private Sound loseSound;
    private Sound pushSound;
    private Particle winParticle;
    private Particle loseParticle;

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
        maxPlayers = Math.max(1, Math.min(MAX_SEATS, config.getInt("table.max-players", 4)));

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
        cardDisplaysEnabled = config.getBoolean("display.card.enabled", true);

        cardDealSound = resolveSound(config.getString("sounds.card-deal.sound"), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON);
        winSound = resolveSound(config.getString("sounds.win.sound"), Sound.ENTITY_PLAYER_LEVELUP);
        loseSound = resolveSound(config.getString("sounds.lose.sound"), Sound.ENTITY_VILLAGER_NO);
        pushSound = resolveSound(config.getString("sounds.push.sound"), Sound.BLOCK_NOTE_BLOCK_PLING);

        winParticle = ServerCompat.particle(config.getString("particles.win.type"), "HAPPY_VILLAGER", "VILLAGER_HAPPY");
        loseParticle = ServerCompat.particle(config.getString("particles.lose.type"), "ANGRY_VILLAGER", "VILLAGER_ANGRY");

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
    public boolean areCardDisplaysEnabled() { return cardDisplaysEnabled; }

    /** Language code from config.yml, e.g. "en", "ko", "tr", "ru". */
    public String getLanguage() {
        return config.getString("language", "en").trim().toLowerCase(Locale.ROOT);
    }

    // Auto-leave settings
    public int getAutoLeaveTimeoutSeconds() {
        return Math.max(10, config.getInt("game.auto-leave-timeout-seconds", 30));
    }

    /** Seconds a player has to act on their turn; 0 or less turns the timer off. */
    public int getTurnTimeoutSeconds() {
        int seconds = config.getInt("game.turn-timeout-seconds", 30);
        return seconds <= 0 ? 0 : Math.max(5, seconds);
    }

    // Seating and interaction
    public boolean shouldSeatPlayers() {
        return config.getBoolean("table.seat-players", true);
    }

    public boolean isClickToJoin() {
        return config.getBoolean("table.click-to-join", true);
    }

    /** True for the chip menu, false for the clickable amounts in chat. */
    public boolean useBetMenu() {
        return !"chat".equalsIgnoreCase(config.getString("betting.menu", "gui"));
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

    // Resource pack settings
    public boolean shouldSendResourcePack() {
        return config.getBoolean("resource-pack.send-on-join", true);
    }

    public String getResourcePackUrl() {
        return config.getString("resource-pack.url", "");
    }

    public String getResourcePackSha1() {
        return config.getString("resource-pack.sha1", "");
    }

    public boolean isResourcePackRequired() {
        return config.getBoolean("resource-pack.required", false);
    }

    /**
     * Resolve a sound from config. Accepts the enum-style names the default config uses
     * (BLOCK_NOTE_BLOCK_PLING) as well as namespaced keys (minecraft:block.note_block.pling).
     * Sound became an interface in 1.21.3, so Sound.valueOf can't be called safely on every
     * version; walking the registry works on all of them.
     */
    private Sound resolveSound(String soundName, Sound fallback) {
        if (soundName == null || soundName.isBlank()) {
            return fallback;
        }

        String wanted = soundName.trim();
        if (wanted.startsWith("minecraft:")) {
            wanted = wanted.substring("minecraft:".length());
        }
        wanted = wanted.replace('.', '_').toUpperCase(Locale.ROOT);

        try {
            for (Sound sound : Registry.SOUNDS) {
                String key = ((Keyed) sound).getKey().getKey();
                if (key.replace('.', '_').toUpperCase(Locale.ROOT).equals(wanted)) {
                    return sound;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
        return fallback;
    }

    // Sound configuration
    public Sound getCardDealSound() {
        return cardDealSound;
    }

    public float getCardDealVolume() {
        return (float) config.getDouble("sounds.card-deal.volume", 1.0);
    }

    public float getCardDealPitch() {
        return (float) config.getDouble("sounds.card-deal.pitch", 1.2);
    }

    public Sound getWinSound() {
        return winSound;
    }

    public Sound getLoseSound() {
        return loseSound;
    }

    public Sound getPushSound() {
        return pushSound;
    }

    // Particle configuration (null when no candidate name exists on this server version)
    public Particle getWinParticle() {
        return winParticle;
    }

    public Particle getLoseParticle() {
        return loseParticle;
    }

    // Message handling
    public String getMessage(String path) {
        // The messages file carries the bundled English file as its defaults, so a translation
        // that lacks a key still shows the English text instead of an error.
        String message = messagesConfig.getString(path);
        if (message == null) {
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

    /**
     * Wrap an amount in the language's currency format ("$%amount%" in English).
     */
    public String formatCurrency(Object amount) {
        return formatMessage("currency-format", "amount", amount);
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
                "doubledown", "bet", "stats", "reload", "cleanup" -> "/bj " + action.toLowerCase() + arguments;
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
