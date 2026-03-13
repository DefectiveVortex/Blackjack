package com.vortex.blackjack.table;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.game.BlackjackEngine;
import com.vortex.blackjack.model.Card;
import com.vortex.blackjack.model.Deck;
import com.vortex.blackjack.util.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single blackjack table with game logic
 */
public class BlackjackTable {
    private final BlackjackPlugin plugin;
    private final TableManager tableManager;
    private final ConfigManager configManager;
    private final ChatUtils chatUtils;
    private final BlackjackEngine gameEngine;
    private final Location centerLoc;
    private final TableSettings settings;
    
    // Game state
    private final List<Player> players = new ArrayList<>();
    private final Map<Player, List<Card>> playerHands = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerSeats = new ConcurrentHashMap<>();
    private final Set<Player> finishedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Player> doubleDownPlayers = ConcurrentHashMap.newKeySet();
    private boolean gameInProgress = false;
    private Player currentPlayer;
    private List<Card> dealerHand = new ArrayList<>();
    private Deck deck = new Deck();
    
    // Display entities
    private final Map<Player, List<ItemDisplay>> playerCardDisplays = new ConcurrentHashMap<>();
    private final Map<Player, List<ItemDisplay>> playerDealerDisplays = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageTime = new HashMap<>();
    
    // Auto-leave tracking
    private final Map<Player, Long> gameEndTimes = new ConcurrentHashMap<>();
    private BukkitTask autoLeaveTask;
    
    public BlackjackTable(BlackjackPlugin plugin, TableManager tableManager,
                          ConfigManager configManager, Location centerLoc,
                          TableSettings settings) {
        this.plugin = plugin;
        this.tableManager = tableManager;
        this.configManager = configManager;
        this.chatUtils = new ChatUtils(configManager);
        this.gameEngine = new BlackjackEngine();
        this.centerLoc = centerLoc;
        this.settings = settings;
    }

    public TableSettings getSettings() {
        return settings;
    }
    
    /**
     * Add a player to this table
     */
    public boolean addPlayer(Player player) {
        synchronized (this) {
            if (players.contains(player)) {
                player.sendMessage(configManager.getMessage("already-at-table"));
                return false;
            }
            
            if (tableManager.getPlayerTable(player) != null) {
                player.sendMessage(configManager.getMessage("already-at-table"));
                return false;
            }
            
            if (players.size() >= settings.getMaxPlayers(configManager)) {
                player.sendMessage(configManager.getMessage("table-full"));
                return false;
            }
            
            if (gameInProgress) {
                player.sendMessage(configManager.getMessage("game-in-progress"));
                return false;
            }
            
            if (player.getLocation().distance(centerLoc) > settings.getMaxJoinDistance(configManager)) {
                player.sendMessage(configManager.getMessage("too-far"));
                return false;
            }
            
            int seatNumber = getNextAvailableSeatNumber();
            if (seatNumber == -1) {
                player.sendMessage(configManager.getMessage("no-seats"));
                return false;
            }
            
            try {
                // Add player to table
                players.add(player);
                playerSeats.put(player, seatNumber);
                playerHands.put(player, new ArrayList<>());
                playerCardDisplays.put(player, new ArrayList<>());
                playerDealerDisplays.put(player, new ArrayList<>());
                tableManager.setPlayerTable(player, this);
                
                // Teleport to seat
                Location seatLoc = getSeatLocation(seatNumber);
                if (seatLoc != null) {
                    player.teleport(seatLoc);
                    
                    // Rotate player 90 degrees to the right before sitting
                    Location currentLoc = player.getLocation();
                    float newYaw = currentLoc.getYaw() + 90f;
                    currentLoc.setYaw(newYaw);
                    player.teleport(currentLoc);
                    
                    // GSit integration - make player sit down if GSit is available
                    if (plugin.isGSitEnabled()) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline() && players.contains(player)) {
                                // Use GSit's sit command
                                player.performCommand("sit");
                            }
                        }, 5L); // 0.25 second delay to allow teleport to complete
                    }
                }
                
                broadcastTableMessage(configManager.formatMessage("player-joined-table", "player", player.getName()));
                
                // Show betting options if UX features enabled
                if (configManager.areParticlesEnabled()) { // Using as UX enabled check
                    chatUtils.sendBettingOptions(player);
                }
                
                return true;
            } catch (Exception e) {
                // Cleanup on error
                players.remove(player);
                playerSeats.remove(player);
                playerHands.remove(player);
                playerCardDisplays.remove(player);
                playerDealerDisplays.remove(player);
                tableManager.setPlayerTable(player, null);
                
                player.sendMessage(configManager.getMessage("join-error"));
                plugin.getLogger().severe("Error adding player to table: " + e.getMessage());
                return false;
            }
        }
    }
    
    /**
     * Remove a player from this table
     */
    public void removePlayer(Player player) {
        removePlayer(player, "left the table");
    }
    
    /**
     * Remove a player from this table with custom reason
     */
    public void removePlayer(Player player, String reason) {
        synchronized (this) {
            if (!players.contains(player)) return;
            
            // Check if player has a bet that needs to be refunded
            boolean shouldRefundBet = gameInProgress && configManager.shouldRefundOnLeave();
            Integer betAmount = plugin.getPlayerBets().get(player);
            
            // Cleanup player data
            players.remove(player);
            playerSeats.remove(player);
            playerHands.remove(player);
            finishedPlayers.remove(player);
            doubleDownPlayers.remove(player);
            tableManager.setPlayerTable(player, null);
            
            // Refund bet if player leaves mid-game and refunds are enabled
            if (shouldRefundBet && betAmount != null && betAmount > 0) {
                plugin.getPlayerBets().remove(player);
                if (plugin.getEconomyProvider().add(player.getUniqueId(), BigDecimal.valueOf(betAmount))) {
                    player.sendMessage(configManager.formatMessage("left-table-bet-refunded", "amount", betAmount));
                } else {
                    player.sendMessage(configManager.getMessage("error-refund"));
                    plugin.getLogger().severe("Failed to refund bet for " + player.getName() + " when leaving mid-game");
                }
            } else if (gameInProgress && betAmount != null && betAmount > 0) {
                // Player left mid-game but refunds are disabled - remove bet without refunding
                plugin.getPlayerBets().remove(player);
                player.sendMessage(configManager.formatMessage("left-table-bet-forfeit", "amount", betAmount));
            } else {
                player.sendMessage(configManager.getMessage("left-table"));
            }
            
            // Remove display entities
            List<ItemDisplay> cardDisplays = playerCardDisplays.remove(player);
            if (cardDisplays != null) {
                cardDisplays.forEach(display -> {
                    if (display != null && !display.isDead()) {
                        display.remove();
                    }
                });
            }
            
            List<ItemDisplay> dealerDisplays = playerDealerDisplays.remove(player);
            if (dealerDisplays != null) {
                dealerDisplays.forEach(display -> {
                    if (display != null && !display.isDead()) {
                        display.remove();
                    }
                });
            }
            
            // Handle game state
            if (players.isEmpty()) {
                endGame();
            } else if (gameInProgress && currentPlayer != null && currentPlayer.equals(player)) {
                nextTurn();
                broadcastTableMessage(configManager.formatMessage("player-left-during-turn", "player", player.getName(), "reason", reason));
            } else {
                broadcastTableMessage(configManager.formatMessage("player-left-table", "player", player.getName(), "reason", reason));
            }
        }
    }
    
    /**
     * Remove all players from the table
     */
    public void removeAllPlayers() {
        synchronized (this) {
            List<Player> playersToRemove = new ArrayList<>(players);
            for (Player player : playersToRemove) {
                removePlayer(player);
            }
        }
    }
    
    /**
     * Start a new game at this table
     */
    public void startGame() {
        synchronized (this) {
            if (gameInProgress) {
                broadcastTableMessage(configManager.getMessage("game-in-progress"));
                return;
            }
            
            if (players.isEmpty()) {
                broadcastTableMessage(configManager.getMessage("game-error-no-players"));
                return;
            }
            
            // Check if all players have placed bets
            java.util.Map<org.bukkit.entity.Player, Integer> playerBets = plugin.getPlayerBets();
            java.util.List<org.bukkit.entity.Player> playersWithoutBets = new java.util.ArrayList<>();
            
            for (org.bukkit.entity.Player player : players) {
                Integer bet = playerBets.get(player);
                if (bet == null || bet <= 0) {
                    playersWithoutBets.add(player);
                }
            }
            
            if (!playersWithoutBets.isEmpty()) {
                for (org.bukkit.entity.Player player : playersWithoutBets) {
                    player.sendMessage(configManager.getMessage("bet-required"));
                }
                broadcastTableMessage(configManager.getMessage("game-error-all-must-bet"));
                return;
            }
            
            // Initialize game
            gameInProgress = true;
            deck = new Deck();
            clearAllDisplays();
            finishedPlayers.clear();
            doubleDownPlayers.clear();
            
            // Deal initial cards (2 per player)
            for (Player player : players) {
                List<Card> hand = new ArrayList<>();
                hand.add(deck.drawCard());
                hand.add(deck.drawCard());
                playerHands.put(player, hand);
                updateCardDisplays(player, hand);
            }
            
            // Deal dealer cards
            dealerHand = new ArrayList<>();
            dealerHand.add(deck.drawCard());
            dealerHand.add(deck.drawCard());
            updateDealerDisplays();
            
            // Start first player's turn
            currentPlayer = players.get(0);
            broadcastTableMessage(configManager.formatMessage("game-started", "player", currentPlayer.getName()));
            
            // Send interactive turn message (doubledown available on first turn)
            chatUtils.sendGameActionBar(currentPlayer, true);
        }
    }
    
    /**
     * Player hits (takes another card)
     */
    public void hit(Player player) {
        synchronized (this) {
            if (!gameInProgress || !player.equals(currentPlayer)) {
                return;
            }
            
            List<Card> hand = playerHands.get(player);
            Card newCard = deck.drawCard();
            hand.add(newCard);
            
            playCardSound(player.getLocation());
            updateCardDisplays(player, hand);
            
            int value = gameEngine.calculateHandValue(hand);
            // Don't send individual hand value - it's already shown in updateCardDisplays
            
            if (gameEngine.isBusted(hand)) {
                finishedPlayers.add(player);
                broadcastTableMessage(configManager.formatMessage("player-busts", "player", player.getName()));
                playLoseSound(player);
                nextTurn();
            } else if (value == 21) {
                finishedPlayers.add(player);
                broadcastTableMessage(configManager.formatMessage("player-hits-21", "player", player.getName()));
                playWinSound(player);
                nextTurn();
            } else {
                // Send action buttons again (no doubledown after hitting)
                chatUtils.sendGameActionBar(player, false);
            }
        }
    }
    
    /**
     * Player stands (ends their turn)
     */
    public void stand(Player player) {
        synchronized (this) {
            if (!gameInProgress || !player.equals(currentPlayer)) {
                return;
            }
            
            finishedPlayers.add(player);
            int value = gameEngine.calculateHandValue(playerHands.get(player));
            broadcastTableMessage(configManager.formatMessage("player-stands", 
                "player", player.getName(), 
                "value", formatHandValue(value)));
            nextTurn();
        }
    }
    
    /**
     * Player doubles down (doubles bet, gets exactly one more card, then stands)
     */
    public void doubleDown(Player player) {
        synchronized (this) {
            if (!gameInProgress || !player.equals(currentPlayer)) {
                return;
            }
            
            // Check if double down is allowed (only on first 2 cards)
            List<Card> hand = playerHands.get(player);
            if (hand.size() != 2) {
                player.sendMessage(configManager.getMessage("double-down-first-two-cards"));
                return;
            }
            
            // Check if player has already doubled down
            if (doubleDownPlayers.contains(player)) {
                player.sendMessage(configManager.getMessage("double-down-already-used"));
                return;
            }
            
            // Check if player has sufficient funds
            Integer currentBet = plugin.getPlayerBets().get(player);
            if (currentBet == null) {
                currentBet = 0;
            }
            
            if (!plugin.getEconomyProvider().hasEnough(player.getUniqueId(), java.math.BigDecimal.valueOf(currentBet))) {
                player.sendMessage(configManager.getMessage("double-down-insufficient-funds"));
                return;
            }
            
            // Double the bet
            plugin.getEconomyProvider().subtract(player.getUniqueId(), java.math.BigDecimal.valueOf(currentBet));
            plugin.getPlayerBets().put(player, currentBet * 2);
            
            // Mark player as doubled down
            doubleDownPlayers.add(player);
            
            // Deal exactly one card
            Card newCard = deck.drawCard();
            hand.add(newCard);
            
            playCardSound(player.getLocation());
            updateCardDisplays(player, hand);
            
            int value = gameEngine.calculateHandValue(hand);
            broadcastTableMessage(configManager.formatMessage("player-doubles-down", 
                "player", player.getName(), 
                "value", formatHandValue(value)));
            
            // Player is automatically done after double down
            finishedPlayers.add(player);
            
            if (gameEngine.isBusted(hand)) {
                broadcastTableMessage(configManager.formatMessage("player-busts", "player", player.getName()));
                playLoseSound(player);
            } else if (value == 21) {
                broadcastTableMessage(configManager.formatMessage("player-hits-21", "player", player.getName()));
                playWinSound(player);
            }
            
            nextTurn();
        }
    }
    
    private void nextTurn() {
        if (finishedPlayers.size() >= players.size()) {
            endGame();
            return;
        }
        
        int currentIndex = players.indexOf(currentPlayer);
        int attempts = 0;
        do {
            currentIndex = (currentIndex + 1) % players.size();
            currentPlayer = players.get(currentIndex);
            attempts++;
            // Prevent infinite loop
            if (attempts >= players.size()) {
                endGame();
                return;
            }
        } while (finishedPlayers.contains(currentPlayer));
        
        if (currentPlayer != null && !finishedPlayers.contains(currentPlayer)) {
            // More compact turn announcement
            broadcastTableMessage(configManager.formatMessage("player-turn", "player", currentPlayer.getName()));
            
            // Show doubledown only if player has exactly 2 cards and hasn't doubled down yet
            List<Card> hand = playerHands.get(currentPlayer);
            boolean canDoubleDown = hand != null && hand.size() == 2 && !doubleDownPlayers.contains(currentPlayer);
            chatUtils.sendGameActionBar(currentPlayer, canDoubleDown);
        } else {
            endGame();
        }
    }
    
    private void endGame() {
        synchronized (this) {
            if (!gameInProgress) return;
            
            // Dealer logic
            boolean anyValidPlayers = players.stream()
                .anyMatch(p -> !gameEngine.isBusted(playerHands.get(p)));
            
            if (anyValidPlayers) {
                while (gameEngine.dealerShouldHit(dealerHand, configManager.shouldHitSoft17())) {
                    dealerHand.add(deck.drawCard());
                }
            }
            
            // Set game as finished BEFORE showing final dealer cards
            gameInProgress = false;
            
            // Update dealer displays and show final hand with cards and value
            updateDealerDisplays();
            int dealerValue = gameEngine.calculateHandValue(dealerHand);
            String dealerHandDisplay = formatHand(dealerHand);
            String dealerValueDisplay = formatHandValue(dealerValue);
            broadcastTableMessage("Dealer: " + dealerHandDisplay + " | " + dealerValueDisplay);
            
            // Handle payouts for each player with a small delay to let dealer cards show
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player player : new ArrayList<>(players)) {
                    if (player.isOnline()) {
                        handlePayout(player, dealerValue);
                    } else {
                        removePlayer(player);
                    }
                }
                
                // Reset game state immediately after payouts so new games can start
                resetGameState();
                
                // Show game ended message and buttons after payouts
                if (!players.isEmpty()) {
                    broadcastTableMessage(configManager.getMessage("game-ended"));
                    sendGameEndButtons();
                    startAutoLeaveTimer();
                }
            }, 20L); // 1 second delay
        }
    }
    
    private void resetGameState() {
        currentPlayer = null;
        finishedPlayers.clear();
        doubleDownPlayers.clear();
        playerHands.clear();
        dealerHand.clear();
        deck = new Deck();
    }
    
    private void handlePayout(Player player, int dealerValue) {
        List<Card> playerHand = playerHands.get(player);
        BlackjackEngine.GameResult result = gameEngine.determineResult(playerHand, dealerHand);
        
        // Get the player's bet amount
        Integer betAmount = plugin.getPlayerBets().get(player);
        if (betAmount == null) {
            betAmount = 0;
        }
        
        switch (result) {
            case PLAYER_BLACKJACK:
                // Blackjack pays 3:2
                int blackjackPayout = (int) (betAmount * 2.5); // bet + 1.5x bet = 2.5x bet
                plugin.getEconomyProvider().add(player.getUniqueId(), java.math.BigDecimal.valueOf(blackjackPayout));
                broadcastTableMessage(configManager.formatMessage("player-blackjack", 
                    "player", player.getName(), 
                    "payout", String.valueOf(blackjackPayout)));
                playWinSound(player);
                updatePlayerStats(player, true, (double) blackjackPayout);
                break;
            case PLAYER_WIN:
            case DEALER_BUST:
                // Regular win pays 2:1 (bet back + equal amount)
                int winPayout = betAmount * 2;
                plugin.getEconomyProvider().add(player.getUniqueId(), java.math.BigDecimal.valueOf(winPayout));
                broadcastTableMessage(configManager.formatMessage("player-wins", 
                    "player", player.getName(), 
                    "payout", String.valueOf(winPayout)));
                playWinSound(player);
                updatePlayerStats(player, true, (double) betAmount);
                break;
            case DEALER_WIN:
            case DEALER_BLACKJACK:
            case PLAYER_BUST:
                // Player loses their bet (already taken when bet was placed)
                broadcastTableMessage(configManager.formatMessage("player-loses", 
                    "player", player.getName(), 
                    "amount", String.valueOf(betAmount)));
                playLoseSound(player);
                updatePlayerStats(player, false, (double) -betAmount);
                break;
            case PUSH:
                // Push - return bet to player
                plugin.getEconomyProvider().add(player.getUniqueId(), java.math.BigDecimal.valueOf(betAmount));
                broadcastTableMessage(configManager.formatMessage("player-push", 
                    "player", player.getName(), 
                    "amount", String.valueOf(betAmount)));
                player.playSound(player.getLocation(), configManager.getPushSound(), 1.0F, 1.0F);
                updatePlayerStats(player, null, 0.0); // Push doesn't count as win or loss
                break;
        }
        
        // Clear the bet
        plugin.getPlayerBets().remove(player);
    }
    
    private void updatePlayerStats(Player player, Boolean won, double winnings) {
        com.vortex.blackjack.model.PlayerStats stats = plugin.getPlayerStats().get(player.getUniqueId());
        if (stats == null) {
            stats = new com.vortex.blackjack.model.PlayerStats();
            plugin.getPlayerStats().put(player.getUniqueId(), stats);
        }
        
        if (won == null) {
            // Push - use the increment method
            stats.incrementPushes();
        } else if (won) {
            // Win - use the increment method which also handles streaks
            stats.incrementWins();
            stats.addWinnings(winnings);
            
            // Check for blackjack
            List<Card> playerHand = playerHands.get(player);
            if (playerHand.size() == 2 && gameEngine.calculateHandValue(playerHand) == 21) {
                stats.incrementBlackjacks();
            }
        } else {
            // Loss - use the increment method which also handles streaks
            stats.incrementLosses();
            stats.addWinnings(winnings); // winnings will be negative
            
            // Check for bust
            List<Card> playerHand = playerHands.get(player);
            if (gameEngine.calculateHandValue(playerHand) > 21) {
                stats.incrementBusts();
            }
        }
    }
    
    // Helper methods
    private int getNextAvailableSeatNumber() {
        Set<Integer> takenSeats = new HashSet<>(playerSeats.values());
        for (int i = 0; i < configManager.getMaxPlayers(); i++) {
            if (!takenSeats.contains(i)) {
                return i;
            }
        }
        return -1;
    }
    
    private Location getSeatLocation(int seatNumber) {
        switch (seatNumber) {
            case 0:
                return centerLoc.clone().add(2.0, 0.0, 0.0);
            case 1:
                return centerLoc.clone().add(0.0, 0.0, 2.0);
            case 2:
                return centerLoc.clone().add(-2.0, 0.0, 0.0);
            case 3:
                return centerLoc.clone().add(0.0, 0.0, -2.0);
            default:
                return null;
        }
    }
    
    private Transformation createCardTransformation(boolean isDealer, int seatNumber) {
        if (isDealer) {
            float yRotation = switch (seatNumber) {
                case 0 -> (float) (-Math.PI / 2);
                case 1 -> (float) Math.PI;
                case 2 -> (float) (Math.PI / 2);
                case 3 -> 0.0f;
                default -> 0.0f;
            };
            return new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new AxisAngle4f(yRotation, 0.0f, 1.0f, 0.0f),
                new Vector3f(0.35f, 0.35f, 0.35f),
                new AxisAngle4f((float)Math.toRadians(15.0), 1.0f, 0.0f, 0.0f)
            );
        } else {
            float xRotation = (float) (Math.PI / 2);
            float zRotation = 0.0f;
            switch (seatNumber) {
                case 0:
                    zRotation = (float) (Math.PI / 2);
                    break;
                case 1:
                    zRotation = (float) Math.PI;
                    break;
                case 2:
                    zRotation = (float) (Math.PI / 2);
                    break;
                case 3:
                    zRotation = (float) Math.PI;
            }

            return new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new AxisAngle4f(xRotation, 1.0f, 0.0f, 0.0f),
                new Vector3f(0.35f, 0.35f, 0.35f),
                new AxisAngle4f(zRotation, 0.0f, 0.0f, 1.0f)
            );
        }
    }
    
    private ItemDisplay createCardDisplay(Location loc, Card card, boolean isDealer, int seatNumber) {
        World world = loc.getWorld();
        Location displayLoc = new Location(world, loc.getBlockX() + 0.5, loc.getBlockY(), loc.getBlockZ() + 0.5, 0.0f, 0.0f);
        ItemDisplay display = (ItemDisplay)world.spawn(displayLoc, ItemDisplay.class);
        
        if (card != null) {
            String cardIdentifier = getCardIdentifier(card);
            ItemStack cardItem = new ItemStack(Material.CLOCK);
            ItemMeta meta = cardItem.getItemMeta();
            meta.setItemModel(new NamespacedKey("playing_cards", "card/" + cardIdentifier.toLowerCase()));
            cardItem.setItemMeta(meta);
            display.setItemStack(cardItem);
        } else {
            ItemStack cardBack = new ItemStack(Material.CLOCK);
            ItemMeta meta = cardBack.getItemMeta();
            meta.setItemModel(new NamespacedKey("playing_cards", "card/back"));
            cardBack.setItemMeta(meta);
            display.setItemStack(cardBack);
        }

        Transformation transform = createCardTransformation(isDealer, seatNumber);
        display.setTransformation(transform);
        return display;
    }
    
    private String getCardIdentifier(Card card) {
        String suit = switch (card.getSuit()) {
            case "♠" -> "s";
            case "♥" -> "h";
            case "♦" -> "d";
            case "♣" -> "c";
            default -> throw new IllegalArgumentException("Invalid suit: " + card.getSuit());
        };
        
        String rank = switch (card.getRank()) {
            case "A" -> "1";
            case "J" -> "j";
            case "Q" -> "q";
            case "K" -> "k";
            default -> card.getRank().toLowerCase();
        };
        
        return suit + rank;
    }
    
    private void sendPlayerMessage(Player player, String message) {
        // Always use compact mode - no config needed
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastMessageTime.get(playerId);
        
        // Bypass cooldown for critical messages (payouts, results, dealer final hand, doubledown)
        boolean isCriticalMessage = message.contains("WINS!") || message.contains("BLACKJACK!") || 
                                  message.contains("loses") || message.contains("PUSH") ||
                                  message.contains("DOUBLES DOWN") ||
                                  message.startsWith("Dealer: ") && message.contains("|");
        
        // Only send if it's been more than 1.5 seconds since last message, OR if it's a critical message
        if (isCriticalMessage || lastTime == null || currentTime - lastTime > 1500) {
            // Check if message is already formatted (contains color codes or special characters)
            if (message.contains("§") || message.contains("&") || isCriticalMessage) {
                // Send directly - already formatted
                player.sendMessage(message);
            } else {
                // Wrap in table broadcast format
                player.sendMessage(configManager.formatMessage("table-message-broadcast", "message", message));
            }
            lastMessageTime.put(playerId, currentTime);
        }
    }
    
    private void broadcastTableMessage(String message) {
        // Send to all players at the table with spam reduction
        for (Map.Entry<Player, Integer> entry : playerSeats.entrySet()) {
            Player player = entry.getKey();
            if (player != null && player.isOnline()) {
                sendPlayerMessage(player, message);
            }
        }
    }
    
    private String formatHand(List<Card> hand) {
        StringBuilder handStr = new StringBuilder();
        for (int i = 0; i < hand.size(); i++) {
            if (i > 0) handStr.append(" ");
            handStr.append(formatCard(hand.get(i)));
        }
        return handStr.toString();
    }
    
    private String formatCard(Card card) {
        ChatColor suitColor;
        String suit = card.getSuit();
        
        // Color code by suit
        switch (suit) {
            case "♥", "♦" -> suitColor = ChatColor.RED;           // Hearts and Diamonds = Red
            case "♠", "♣" -> suitColor = ChatColor.DARK_GRAY;     // Spades and Clubs = Dark Gray
            default -> suitColor = ChatColor.WHITE;
        }
        
        return suitColor + card.getRank() + suit + ChatColor.RESET;
    }
    
    private String formatHandValue(int value) {
        ChatColor valueColor;
        if (value == 21) {
            valueColor = ChatColor.GOLD;          // 21 = Gold
        } else if (value > 21) {
            valueColor = ChatColor.RED;           // Bust = Red  
        } else if (value >= 18) {
            valueColor = ChatColor.GREEN;         // Good hand = Green
        } else {
            valueColor = ChatColor.YELLOW;        // Normal = Yellow
        }
        
        return "" + ChatColor.BOLD + valueColor + "Value: " + value + ChatColor.RESET;
    }
    
    public void broadcastToTable(String message) {
        broadcastTableMessage(message);
    }
    
    private void playCardSound(Location loc) {
        if (configManager.areSoundsEnabled()) {
            loc.getWorld().playSound(loc, configManager.getCardDealSound(), 
                configManager.getCardDealVolume(), configManager.getCardDealPitch());
        }
    }
    
    private void playWinSound(Player player) {
        if (configManager.areSoundsEnabled()) {
            player.playSound(player.getLocation(), configManager.getWinSound(), 1.0F, 1.0F);
        }
        
        if (configManager.areParticlesEnabled()) {
            player.spawnParticle(configManager.getWinParticle(), 
                player.getLocation().add(0.0, 2.0, 0.0), 20, 0.5, 0.5, 0.5);
        }
    }
    
    private void playLoseSound(Player player) {
        if (configManager.areSoundsEnabled()) {
            player.playSound(player.getLocation(), configManager.getLoseSound(), 1.0F, 1.0F);
        }
        
        if (configManager.areParticlesEnabled()) {
            player.spawnParticle(configManager.getLoseParticle(), 
                player.getLocation().add(0.0, 2.0, 0.0), 10, 0.5, 0.5, 0.5);
        }
    }
    
    // Display management methods - ORIGINAL IMPLEMENTATION
    private void updateCardDisplays(Player player, List<Card> hand) {
        int seatNumber = playerSeats.get(player);
        Location baseDisplayLoc = getSeatLocation(seatNumber);
        
        if (playerCardDisplays.containsKey(player)) {
            for (ItemDisplay display : playerCardDisplays.get(player)) {
                display.remove();
            }
            playerCardDisplays.get(player).clear();
        }

        playerCardDisplays.putIfAbsent(player, new ArrayList<>());
        double cardSpacing = configManager.getCardSpacing();
        double playerHeight = configManager.getPlayerCardHeight();
        double distanceFromPlayer = 1.0; // Original hardcoded value

        for (int i = 0; i < hand.size(); i++) {
            Card card = hand.get(i);
            Location spawnLoc = baseDisplayLoc.clone();
            ItemDisplay display = createCardDisplay(spawnLoc, card, false, seatNumber);
            Vector3f translation = new Vector3f();
            float xOffset = 0.0f;
            float zOffset = 0.0f;
            
            switch (seatNumber) {
                case 0:
                    xOffset = (float)(-distanceFromPlayer);
                    zOffset = (float)(i * cardSpacing - (hand.size() - 1) * cardSpacing / 2.0);
                    break;
                case 1:
                    xOffset = (float)(i * cardSpacing - (hand.size() - 1) * cardSpacing / 2.0);
                    zOffset = (float)(-distanceFromPlayer);
                    break;
                case 2:
                    xOffset = (float)distanceFromPlayer;
                    zOffset = (float)(-(i * cardSpacing) + (hand.size() - 1) * cardSpacing / 2.0);
                    break;
                case 3:
                    xOffset = (float)(-(i * cardSpacing) + (hand.size() - 1) * cardSpacing / 2.0);
                    zOffset = (float)distanceFromPlayer;
            }

            translation.set(xOffset, playerHeight, zOffset);
            Transformation currentTransform = display.getTransformation();
            Transformation newTransform = new Transformation(
                translation, currentTransform.getLeftRotation(), currentTransform.getScale(), currentTransform.getRightRotation()
            );
            display.setTransformation(newTransform);
            playerCardDisplays.get(player).add(display);
        }

        int handValue = gameEngine.calculateHandValue(hand);
        // Send colorized hand info - more compact and readable
        player.sendMessage(configManager.formatMessage("hand-display", 
            "hand", formatHand(hand), 
            "hand_value", formatHandValue(handValue)));
    }

    private void updateDealerDisplays() {
        for (Player player : players) {
            if (playerDealerDisplays.containsKey(player)) {
                for (ItemDisplay display : playerDealerDisplays.get(player)) {
                    display.remove();
                }
                playerDealerDisplays.get(player).clear();
            }
        }

        for (Player player : players) {
            playerDealerDisplays.putIfAbsent(player, new ArrayList<>());
            List<ItemDisplay> dealerDisplays = new ArrayList<>();
            int seatNumber = playerSeats.get(player);
            Location baseDisplayLoc = centerLoc.clone();
            double cardSpacing = configManager.getCardSpacing();
            double dealerHeight = configManager.getDealerCardHeight();
            double distanceFromCenter = 0.75; // Original hardcoded value
            
            if (!dealerHand.isEmpty()) {
                Card dealerVisibleCard = dealerHand.get(0);
                // More compact dealer card message
                player.sendMessage(configManager.formatMessage("dealer-shows", 
                    "card", formatCard(dealerVisibleCard), 
                    "value", dealerVisibleCard.getValue()));
            }

            for (int i = 0; i < dealerHand.size(); i++) {
                Card card = dealerHand.get(i);
                Location spawnLoc = baseDisplayLoc.clone();
                Card displayCard = gameInProgress && i > 0 ? null : card;
                ItemDisplay display = createCardDisplay(spawnLoc, displayCard, true, seatNumber);
                Vector3f translation = new Vector3f();
                float xOffset = 0.0f;
                float zOffset = 0.0f;
                
                switch (seatNumber) {
                    case 0:
                        xOffset = (float)distanceFromCenter;
                        zOffset = (float)(i * cardSpacing - (dealerHand.size() - 1) * cardSpacing / 2.0);
                        break;
                    case 1:
                        xOffset = (float)(i * cardSpacing - (dealerHand.size() - 1) * cardSpacing / 2.0);
                        zOffset = (float)distanceFromCenter;
                        break;
                    case 2:
                        xOffset = (float)(-distanceFromCenter);
                        zOffset = (float)(-(i * cardSpacing) + (dealerHand.size() - 1) * cardSpacing / 2.0);
                        break;
                    case 3:
                        xOffset = (float)(-(i * cardSpacing) + (dealerHand.size() - 1) * cardSpacing / 2.0);
                        zOffset = (float)(-distanceFromCenter);
                }

                translation.set(xOffset, dealerHeight, zOffset);
                Transformation currentTransform = display.getTransformation();
                Transformation newTransform = new Transformation(
                    translation, currentTransform.getLeftRotation(), currentTransform.getScale(), currentTransform.getRightRotation()
                );
                display.setTransformation(newTransform);
                dealerDisplays.add(display);
            }

            playerDealerDisplays.put(player, dealerDisplays);
        }
    }
    
    private void clearAllDisplays() {
        // Clear displays for all players (not just current players list)
        for (List<ItemDisplay> cardDisplays : playerCardDisplays.values()) {
            if (cardDisplays != null) {
                cardDisplays.forEach(display -> {
                    if (display != null && !display.isDead()) {
                        display.remove();
                    }
                });
            }
        }
        
        for (List<ItemDisplay> dealerDisplays : playerDealerDisplays.values()) {
            if (dealerDisplays != null) {
                dealerDisplays.forEach(display -> {
                    if (display != null && !display.isDead()) {
                        display.remove();
                    }
                });
            }
        }
        
        playerCardDisplays.clear();
        playerDealerDisplays.clear();
    }
    
    /**
     * Cleanup all resources for this table
     */
    public void cleanup() {
        clearAllDisplays();
        players.clear();
        playerHands.clear();
        playerSeats.clear();
        finishedPlayers.clear();
        doubleDownPlayers.clear();
        playerCardDisplays.clear();
        playerDealerDisplays.clear();
        lastMessageTime.clear();
    }
    
    // Getters
    public Location getCenterLocation() { return centerLoc; }
    public List<Player> getPlayers() { return new ArrayList<>(players); }
    public boolean isGameInProgress() { return gameInProgress; }
    
    // PlaceholderAPI support methods
    public int getPlayerCount() { return players.size(); }
    public int getAvailableSeats() { return configManager.getMaxPlayers() - players.size(); }
    public boolean isFull() { return players.size() >= configManager.getMaxPlayers(); }
    public Location getLocation() { return centerLoc; }
    
    public boolean hasPlayerHand(Player player) { return playerHands.containsKey(player); }
    public int getPlayerHandValue(Player player) { 
        List<Card> hand = playerHands.get(player);
        return hand != null ? gameEngine.calculateHandValue(hand) : 0;
    }
    public int getPlayerHandSize(Player player) {
        List<Card> hand = playerHands.get(player);
        return hand != null ? hand.size() : 0;
    }
    
    public boolean isPlayerTurn(Player player) { return currentPlayer == player; }
    public boolean isPlayerFinished(Player player) { return finishedPlayers.contains(player); }
    public boolean hasPlayerBlackjack(Player player) {
        List<Card> hand = playerHands.get(player);
        return hand != null && hand.size() == 2 && gameEngine.calculateHandValue(hand) == 21;
    }
    public boolean isPlayerBusted(Player player) {
        List<Card> hand = playerHands.get(player);
        return hand != null && gameEngine.calculateHandValue(hand) > 21;
    }
    public boolean canPlayerDoubleDown(Player player) {
        List<Card> hand = playerHands.get(player);
        return hand != null && hand.size() == 2 && !doubleDownPlayers.contains(player);
    }
    public boolean hasPlayerDoubledDown(Player player) { return doubleDownPlayers.contains(player); }
    
    public int getDealerVisibleValue() {
        if (dealerHand.isEmpty()) return 0;
        // Only show first card during game
        if (gameInProgress && dealerHand.size() >= 2) {
            List<Card> visibleCards = new ArrayList<>();
            visibleCards.add(dealerHand.get(0));
            return gameEngine.calculateHandValue(visibleCards);
        }
        return gameEngine.calculateHandValue(dealerHand);
    }
    public int getDealerCardCount() { return dealerHand.size(); }
    
    private void sendGameEndButtons() {
        for (Player player : players) {
            chatUtils.sendGameEndOptions(player);
        }
    }

    public boolean canStartGame() {
        if (gameInProgress || players.isEmpty()) {
            return false;
        }
        
        // Check if all players have bets
        java.util.Map<org.bukkit.entity.Player, Integer> playerBets = plugin.getPlayerBets();
        for (org.bukkit.entity.Player player : players) {
            Integer bet = playerBets.get(player);
            if (bet == null || bet <= 0) {
                return false;
            }
        }
        return true;
    }
    
    private void startAutoLeaveTimer() {
        // Cancel any existing auto-leave task
        if (autoLeaveTask != null) {
            autoLeaveTask.cancel();
        }
        
        // Record the game end time for all players
        long gameEndTime = System.currentTimeMillis();
        for (Player player : players) {
            gameEndTimes.put(player, gameEndTime);
        }
        
        // Start the auto-leave checker task
        autoLeaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAutoLeave, 20L * 5L, 20L * 5L); // Check every 5 seconds
    }
    
    private void checkAutoLeave() {
        if (gameInProgress || players.size() <= 1) {
            // Cancel auto-leave if game is in progress or only 1 player left
            if (autoLeaveTask != null) {
                autoLeaveTask.cancel();
                autoLeaveTask = null;
            }
            gameEndTimes.clear();
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        int timeoutMs = configManager.getAutoLeaveTimeoutSeconds() * 1000;
        
        List<Player> playersToRemove = new ArrayList<>();
        for (Player player : new ArrayList<>(players)) {
            Long gameEndTime = gameEndTimes.get(player);
            if (gameEndTime != null && (currentTime - gameEndTime) >= timeoutMs) {
                playersToRemove.add(player);
            }
        }
        
        // Remove inactive players
        for (Player player : playersToRemove) {
            if (player.isOnline()) {
                player.sendMessage(configManager.getMessage("auto-left-inactive"));
            }
            removePlayer(player, "was removed due to inactivity");
            gameEndTimes.remove(player);
        }
        
        // Cancel auto-leave task if no more players or only 1 left
        if (players.size() <= 1) {
            if (autoLeaveTask != null) {
                autoLeaveTask.cancel();
                autoLeaveTask = null;
            }
            gameEndTimes.clear();
        }
    }
    
    public void cancelAutoLeaveTimer() {
        if (autoLeaveTask != null) {
            autoLeaveTask.cancel();
            autoLeaveTask = null;
        }
        gameEndTimes.clear();
    }
}
