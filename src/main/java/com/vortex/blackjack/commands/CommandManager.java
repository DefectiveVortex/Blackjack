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
     * Register all individual commands for better UX
     */
    public void registerCommands() {
        // Main command (keeps existing functionality)
        PluginCommand mainCmd = plugin.getCommand("blackjack");
        if (mainCmd != null) {
            mainCmd.setExecutor(plugin);
        }
        
        // Individual commands for better UX
        registerCommand("bet", new BetCommand(plugin));
        registerCommand("hit", new SimpleForwardCommand(plugin, "hit"));
        registerCommand("stand", new SimpleForwardCommand(plugin, "stand"));
        registerCommand("join", new SimpleForwardCommand(plugin, "join"));
        registerCommand("leave", new SimpleForwardCommand(plugin, "leave"));
        registerCommand("start", new SimpleForwardCommand(plugin, "start"));
        registerCommand("stats", new SimpleForwardCommand(plugin, "stats"));
        
        // Admin commands
        registerCommand("createtable", new SimpleForwardCommand(plugin, "createtable"));
        registerCommand("removetable", new SimpleForwardCommand(plugin, "removetable"));
        
        plugin.getLogger().info("Registered individual commands for better user experience!");
    }
    
    private void registerCommand(String name, BlackjackCommand executor) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            plugin.getLogger().warning("Could not register command: " + name);
        }
    }
}
