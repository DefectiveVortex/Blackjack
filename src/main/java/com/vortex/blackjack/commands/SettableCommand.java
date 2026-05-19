package com.vortex.blackjack.commands;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.table.BlackjackTable;
import com.vortex.blackjack.table.TableSettings;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Handles blackjack table setting forwarding with setting name and value suggestions.
 */
public class SettableCommand extends BlackjackCommand {

    private static final List<String> SETTINGS = Arrays.asList(
            "min-bet", "max-bet", "max-players", "max-join-distance"
    );

    private final BlackjackPlugin plugin;

    public SettableCommand(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isPlayer(sender)) {
            sendPlayerOnlyMessage(sender);
            return true;
        }

        // Prepend "settable" and forward to main plugin
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = "settable";
        System.arraycopy(args, 0, newArgs, 1, args.length);

        return plugin.onCommand(sender, command, label, newArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("blackjack.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            // Suggest setting names
            return filterCompletions(SETTINGS, args[0]);
        }

        if (args.length == 2) {
            // Suggest the current value for the nearest table as a hint
            String setting = args[0].toLowerCase();
            String currentValue = getCurrentValue(player, setting);
            if (currentValue != null) {
                return filterCompletions(List.of(currentValue), args[1]);
            }
        }

        return List.of();
    }

    /** Returns the current (resolved) value for the given setting on the player's nearest table, or null. */
    private String getCurrentValue(Player player, String setting) {
        BlackjackTable table = plugin.getTableManager().findNearestTable(player.getLocation());
        if (table == null) return null;

        TableSettings s = table.getSettings();
        var cfg = plugin.getConfigManager();
        return switch (setting) {
            case "min-bet"           -> String.valueOf(s.getMinBet(cfg));
            case "max-bet"           -> String.valueOf(s.getMaxBet(cfg));
            case "max-players"       -> String.valueOf(s.getMaxPlayers(cfg));
            case "max-join-distance" -> String.valueOf(s.getMaxJoinDistance(cfg));
            default -> null;
        };
    }
}
