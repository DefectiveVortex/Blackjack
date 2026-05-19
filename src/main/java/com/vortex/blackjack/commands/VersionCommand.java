package com.vortex.blackjack.commands;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.util.VersionChecker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Handle blackjack version output for checking plugin version.
 */
public class VersionCommand extends BlackjackCommand {
    private final VersionChecker versionChecker;
    
    public VersionCommand(BlackjackPlugin plugin, VersionChecker versionChecker) {
        this.versionChecker = versionChecker;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blackjack.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        
        sender.sendMessage("§6§l▬▬▬ BLACKJACK PLUGIN VERSION INFO ▬▬▬");
        sender.sendMessage("");
        sender.sendMessage("§fPlugin: §aBlackjack");
        sender.sendMessage("§fAuthor: §bDefectiveVortex");
        sender.sendMessage("§fCurrent Version: §a" + versionChecker.getCurrentVersion());
        
        if (versionChecker.getLatestVersion() != null) {
            sender.sendMessage("§fLatest Version: §a" + versionChecker.getLatestVersion());
        }
        
        sender.sendMessage("");
        
        if (versionChecker.isOutdated()) {
            sender.sendMessage("§c⚠ UPDATE AVAILABLE!");
            sender.sendMessage("§bDownload: §9https://github.com/DefectiveVortex/Blackjack/releases/latest");
        } else {
            sender.sendMessage("§a✓ UP TO DATE!");
        }
        
        sender.sendMessage("");
        sender.sendMessage("§7GitHub: §9https://github.com/DefectiveVortex/Blackjack");
        
        return true;
    }
}
