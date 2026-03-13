package com.vortex.blackjack.commands;

import com.vortex.blackjack.BlackjackPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles /createtable with tab completion for per-table settings.
 */
public class CreateTableCommand extends BlackjackCommand {

    private static final List<String> SETTING_KEYS = Arrays.asList(
            "min-bet:", "max-bet:", "max-players:", "max-join-distance:"
    );

    private final BlackjackPlugin plugin;

    public CreateTableCommand(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isPlayer(sender)) {
            sendPlayerOnlyMessage(sender);
            return true;
        }

        // Prepend "createtable" and forward to main plugin
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = "createtable";
        System.arraycopy(args, 0, newArgs, 1, args.length);

        return plugin.onCommand(sender, command, label, newArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("blackjack.admin")) {
            return List.of();
        }

        // Collect which setting keys have already been typed
        List<String> alreadyUsed = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            int colon = args[i].indexOf(':');
            if (colon > 0) {
                alreadyUsed.add(args[i].substring(0, colon) + ":");
            }
        }

        // Current partial token
        String partial = args[args.length - 1];

        // Suggest setting keys that haven't been used yet
        List<String> suggestions = new ArrayList<>();
        for (String key : SETTING_KEYS) {
            if (!alreadyUsed.contains(key)) {
                suggestions.add(key);
            }
        }

        return filterCompletions(suggestions, partial);
    }
}
