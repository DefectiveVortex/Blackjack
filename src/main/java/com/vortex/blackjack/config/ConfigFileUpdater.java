package com.vortex.blackjack.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Keeps user-owned YAML files compatible with the bundled defaults.
 */
public final class ConfigFileUpdater {
    private ConfigFileUpdater() {
    }

    public static YamlConfiguration update(JavaPlugin plugin, String resourceName, File targetFile) {
        ensureFileExists(plugin, resourceName, targetFile);

        YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(targetFile);
        YamlConfiguration defaultConfig = loadBundledDefaults(plugin, resourceName);
        if (defaultConfig == null) {
            return currentConfig;
        }

        int changedValues = 0;
        changedValues += addMissingDefaults(currentConfig, defaultConfig);

        if ("messages.yml".equals(resourceName)) {
            changedValues += migrateMessages(currentConfig);
        }

        if (changedValues > 0) {
            saveWithBackup(plugin, resourceName, targetFile, currentConfig, changedValues);
        }

        return currentConfig;
    }

    private static void ensureFileExists(JavaPlugin plugin, String resourceName, File targetFile) {
        if (targetFile.exists()) {
            return;
        }

        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for " + resourceName);
            return;
        }

        try {
            plugin.saveResource(resourceName, false);
            plugin.getLogger().info("Created " + resourceName + " from bundled defaults.");
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Bundled " + resourceName + " was not found.", e);
        }
    }

    private static YamlConfiguration loadBundledDefaults(JavaPlugin plugin, String resourceName) {
        try (InputStream defaultStream = plugin.getResource(resourceName)) {
            if (defaultStream == null) {
                plugin.getLogger().warning("Could not update " + resourceName + ": bundled defaults are missing.");
                return null;
            }

            try (InputStreamReader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + resourceName + " defaults.", e);
            return null;
        }
    }

    private static int addMissingDefaults(YamlConfiguration currentConfig, YamlConfiguration defaultConfig) {
        int added = 0;

        for (String path : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(path) || currentConfig.contains(path)) {
                continue;
            }

            currentConfig.set(path, defaultConfig.get(path));
            added++;
        }

        return added;
    }

    private static int migrateMessages(YamlConfiguration messagesConfig) {
        int changed = 0;

        changed += normalizeCommandPath(messagesConfig, "buttons.hit.command");
        changed += normalizeCommandPath(messagesConfig, "buttons.stand.command");
        changed += normalizeCommandPath(messagesConfig, "buttons.double-down.command");
        changed += normalizeCommandPath(messagesConfig, "buttons.play-again.command");
        changed += normalizeCommandPath(messagesConfig, "buttons.leave-table.command");
        changed += normalizeCommandPath(messagesConfig, "buttons.custom-bet.command");

        changed += replaceText(messagesConfig, "already-at-table", "Use /leave", "Use /bj leave");
        changed += replaceText(messagesConfig, "not-at-table", "Use /join", "Use /bj join");
        changed += replaceText(messagesConfig, "bet-required", "Use /bet <amount>", "Use /bj bet <amount>");
        changed += replaceText(messagesConfig, "bet-usage", "Usage: /bet <amount>", "Usage: /bj bet <amount>");

        changed += replaceLeadingCommand(messagesConfig, "help-join", "/join", "/bj join");
        changed += replaceLeadingCommand(messagesConfig, "help-leave", "/leave", "/bj leave");
        changed += replaceLeadingCommand(messagesConfig, "help-bet", "/bet", "/bj bet");
        changed += replaceLeadingCommand(messagesConfig, "help-start", "/start", "/bj start");
        changed += replaceLeadingCommand(messagesConfig, "help-hit", "/hit", "/bj hit");
        changed += replaceLeadingCommand(messagesConfig, "help-stand", "/stand", "/bj stand");
        changed += replaceLeadingCommand(messagesConfig, "help-admin-create", "/createtable", "/bj createtable");
        changed += replaceLeadingCommand(messagesConfig, "help-admin-settable", "/settable", "/bj settable");
        changed += replaceLeadingCommand(messagesConfig, "help-admin-remove", "/removetable", "/bj removetable");

        return changed;
    }

    private static int normalizeCommandPath(YamlConfiguration config, String path) {
        String currentValue = config.getString(path);
        if (currentValue == null) {
            return 0;
        }

        String normalizedValue = normalizeBlackjackCommand(currentValue);
        if (currentValue.equals(normalizedValue)) {
            return 0;
        }

        config.set(path, normalizedValue);
        return 1;
    }

    private static String normalizeBlackjackCommand(String command) {
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

    private static int replaceText(YamlConfiguration config, String path, String oldText, String newText) {
        String currentValue = config.getString(path);
        if (currentValue == null || !currentValue.contains(oldText)) {
            return 0;
        }

        config.set(path, currentValue.replace(oldText, newText));
        return 1;
    }

    private static int replaceLeadingCommand(YamlConfiguration config, String path, String oldCommand, String newCommand) {
        String currentValue = config.getString(path);
        if (currentValue == null) {
            return 0;
        }

        int commandIndex = currentValue.indexOf(oldCommand);
        if (commandIndex < 0 || currentValue.indexOf("/bj", commandIndex) == commandIndex) {
            return 0;
        }

        config.set(path, currentValue.substring(0, commandIndex) + newCommand
            + currentValue.substring(commandIndex + oldCommand.length()));
        return 1;
    }

    private static int findFirstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void saveWithBackup(JavaPlugin plugin, String resourceName, File targetFile,
                                       YamlConfiguration currentConfig, int changedValues) {
        try {
            File backupFile = new File(targetFile.getParentFile(), resourceName + ".pre-update.bak");
            Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            currentConfig.save(targetFile);
            plugin.getLogger().info("Updated " + resourceName + " with " + changedValues
                + " missing or migrated value(s). Existing values were preserved. Backup: " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not update " + resourceName + ".", e);
        }
    }
}
