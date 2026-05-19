package com.vortex.blackjack.commands;

import com.vortex.blackjack.BlackjackPlugin;
import org.bukkit.command.PluginCommand;

/**
 * Manages registration of all blackjack commands
 */
public class CommandManager {
    private final BlackjackPlugin plugin;
    
    public CommandManager(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Register the main plugin command and aliases.
     */
    public void registerCommands() {
        PluginCommand mainCmd = plugin.getCommand("blackjack");
        if (mainCmd != null) {
            mainCmd.setExecutor(plugin);
            mainCmd.setTabCompleter(plugin);
        } else {
            plugin.getLogger().warning("Could not register command: blackjack");
        }
    }
}
