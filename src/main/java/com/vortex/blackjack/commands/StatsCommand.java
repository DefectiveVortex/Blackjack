package com.vortex.blackjack.commands;

import com.vortex.blackjack.BlackjackPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handle blackjack stats forwarding with admin support for checking other players' stats.
 */
public class StatsCommand extends BlackjackCommand {
    private final BlackjackPlugin plugin;
    
    public StatsCommand(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isPlayer(sender)) {
            sendPlayerOnlyMessage(sender);
            return true;
        }
        
        // Forward to main plugin with "stats" prepended
        String[] newArgs = new String[args.length + 1];

        newArgs[0] = "stats";
        System.arraycopy(args, 0, newArgs, 1, args.length);
        
        return plugin.onCommand(sender, command, label, newArgs);
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isPlayer(sender)) {
            return super.onTabComplete(sender, command, alias, args);
        }
        
        Player player = getPlayer(sender);
        
        // Only show player name completions to admins
        if (args.length == 1 && player.hasPermission("blackjack.stats.others")) {
            List<String> completions = new ArrayList<>();
            
            // Add online player names
            for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                completions.add(onlinePlayer.getName());
            }
            
            return filterCompletions(completions, args[0]);
        }
        
        return super.onTabComplete(sender, command, alias, args);
    }
}
