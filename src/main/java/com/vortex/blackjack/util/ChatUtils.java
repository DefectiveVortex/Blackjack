package com.vortex.blackjack.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;
import com.vortex.blackjack.config.ConfigManager;

/**
 * Utility for creating interactive chat messages with clickable actions
 */
public class ChatUtils {
    private final ConfigManager configManager;
    
    /**
     * Constructor for ChatUtils with ConfigManager dependency
     */
    public ChatUtils(ConfigManager configManager) {
        this.configManager = configManager;
    }
    
    /**
     * Send a clickable message to perform a command
     */
    public void sendClickableCommand(Player player, String message, String command, String hoverText) {
        player.spigot().sendMessage(GenericUtils.createClickableButton(message, command, hoverText));
    }
    
    /**
     * Show a short line above the hotbar (used for the turn countdown)
     */
    public void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    /**
     * Send a clickable message to suggest a command
     */
    public void sendClickableSuggestion(Player player, String message, String command, String hoverText) {
        TextComponent text = GenericUtils.createSuggestionButton(message, command, hoverText);
        player.spigot().sendMessage(text);
    }
    
    /**
     * Create a game action bar with clickable options
     */
    public void sendGameActionBar(Player player) {
        sendGameActionBar(player, true); // Default to showing doubledown
    }
    
    /**
     * Create a game action bar with clickable options
     */
    public void sendGameActionBar(Player player, boolean showDoubleDown) {
        TextComponent hitButton = GenericUtils.createClickableButton(
            configManager.getButtonText("hit"), 
            configManager.getButtonCommand("hit"), 
            configManager.getButtonHover("hit"));
        
        TextComponent standButton = GenericUtils.createClickableButton(
            configManager.getButtonText("stand"), 
            configManager.getButtonCommand("stand"), 
            configManager.getButtonHover("stand"));
        
        TextComponent separator = new TextComponent(configManager.getGameActionSeparator());
        
        // Combine components
        TextComponent fullMessage = new TextComponent(configManager.getGameActionPrompt());
        fullMessage.addExtra(hitButton);
        fullMessage.addExtra(separator);
        fullMessage.addExtra(standButton);
        
        // Add doubledown button if appropriate
        if (showDoubleDown) {
            TextComponent doubleDownButton = GenericUtils.createClickableButton(
                configManager.getButtonText("double-down"), 
                configManager.getButtonCommand("double-down"), 
                configManager.getButtonHover("double-down"));
            fullMessage.addExtra(separator);
            fullMessage.addExtra(doubleDownButton);
        }
        
        player.spigot().sendMessage(fullMessage);
    }
    
    /**
     * Create betting options with clickable amounts (configurable)
     */
    public void sendBettingOptions(Player player) {
        player.sendMessage(configManager.getMessage("quick-bet-border"));
        player.sendMessage(configManager.getMessage("quick-bet-title"));
        player.sendMessage("");
        
        // Use generic method to create betting button rows
        TextComponent smallBets = GenericUtils.createBettingButtonRow(
            configManager.getBettingCategoryLabel("small"), 
            configManager.getSmallBets(), 
            configManager);
        player.spigot().sendMessage(smallBets);
        
        TextComponent mediumBets = GenericUtils.createBettingButtonRow(
            configManager.getBettingCategoryLabel("medium"), 
            configManager.getMediumBets(), 
            configManager);
        player.spigot().sendMessage(mediumBets);
        
        TextComponent largeBets = GenericUtils.createBettingButtonRow(
            configManager.getBettingCategoryLabel("large"), 
            configManager.getLargeBets(), 
            configManager);
        player.spigot().sendMessage(largeBets);
        
        player.sendMessage("");
        
        // Custom bet option
        sendClickableSuggestion(player, configManager.getButtonText("custom-bet"), 
            configManager.getButtonCommand("custom-bet"), configManager.getButtonHover("custom-bet"));
        
        player.sendMessage(configManager.getMessage("quick-bet-border"));
    }
    
    /**
     * Send clickable "Play Again" and "Leave Table" buttons
     */
    public void sendGameEndOptions(Player player) {
        TextComponent playAgainButton = GenericUtils.createClickableButton(
            configManager.getButtonText("play-again"), 
            configManager.getButtonCommand("play-again"), 
            configManager.getButtonHover("play-again"));
        
        TextComponent leaveButton = GenericUtils.createClickableButton(
            configManager.getButtonText("leave-table"), 
            configManager.getButtonCommand("leave-table"), 
            configManager.getButtonHover("leave-table"));
        
        TextComponent separator = new TextComponent(configManager.getGameActionSeparator());
        
        // Combine components
        TextComponent fullMessage = new TextComponent(configManager.getPostGamePrompt());
        fullMessage.addExtra(playAgainButton);
        fullMessage.addExtra(separator);
        fullMessage.addExtra(leaveButton);
        
        player.spigot().sendMessage(fullMessage);
    }
}
