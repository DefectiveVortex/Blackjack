package com.vortex.blackjack.commands;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.util.ChatUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Handle blackjack bet forwarding with smart amount suggestions and configurable chat betting.
 */
public class BetCommand extends BlackjackCommand {
    private final BlackjackPlugin plugin;
    private final ChatUtils chatUtils;
    
    public BetCommand(BlackjackPlugin plugin) {
        this.plugin = plugin;
        this.chatUtils = new ChatUtils(plugin.getConfigManager());
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isPlayer(sender)) {
            sendPlayerOnlyMessage(sender);
            return true;
        }
        
        Player player = getPlayer(sender);
        
        if (args.length == 0) {
            // Show configurable chat-based betting options
            showChatBettingOptions(player);
            return true;
        }
        
        // Forward to main plugin with "bet" prepended
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = "bet";
        System.arraycopy(args, 0, newArgs, 1, args.length);
        
        return plugin.onCommand(sender, command, label, newArgs);
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // Create tab completions from config values
            List<String> completions = new java.util.ArrayList<>();
            
            // Add quick bet amounts from config
            plugin.getConfigManager().getSmallBets().forEach(amount -> completions.add(amount.toString()));
            plugin.getConfigManager().getMediumBets().forEach(amount -> completions.add(amount.toString()));
            plugin.getConfigManager().getLargeBets().forEach(amount -> completions.add(amount.toString()));
            
            return filterCompletions(completions, args[0]);
        }
        return super.onTabComplete(sender, command, alias, args);
    }
    
    private void showChatBettingOptions(Player player) {
        player.sendMessage(plugin.getConfigManager().getMessage("quick-bet-header"));
        player.sendMessage(plugin.getConfigManager().getMessage("quick-bet-description"));
        
        // Use configurable chat betting options
        chatUtils.sendBettingOptions(player);
    }
}
