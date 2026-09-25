package com.vortex.blackjack.table;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.game.BlackjackEngine;
import com.vortex.blackjack.model.Card;
import com.vortex.blackjack.model.Deck;
import com.vortex.blackjack.util.ChatUtils;
import com.vortex.blackjack.util.ServerCompat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
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
    private final int id;
    private final Location centerLoc;
    private final TableSettings settings;
    
    // Game state
    private final List<Player> players = new ArrayList<>();
    private final Map<Player, List<Card>> playerHands = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerSeats = new ConcurrentHashMap<>();
    private final Set<Player> finishedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Player> doubleDownPlayers = ConcurrentHashMap.newKeySet();
    private boolean gameInProgress = false;
    private boolean settlingResults = false;
    private Player currentPlayer;
    private List<Card> dealerHand = new ArrayList<>();
    private Deck deck = new Deck();
    private final Map<Player, Integer> roundBets = new ConcurrentHashMap<>();
    
    // Display entities
    private final Map<Player, List<ItemDisplay>> playerCardDisplays = new ConcurrentHashMap<>();
    private final Map<Player, List<ItemDisplay>> playerDealerDisplays = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageTime = new HashMap<>();
    
    // Auto-leave tracking
    private final Map<Player, Long> gameEndTimes = new ConcurrentHashMap<>();
    private BukkitTask autoLeaveTask;

    // Seats: invisible armor stands players ride while at the table
    public static final String SEAT_TAG = "blackjack-seat";
    private final Map<Player, ArmorStand> seatEntities = new ConcurrentHashMap<>();
    // When a player sneaked to leave mid-round and still has to confirm
    private final Map<UUID, Long> pendingLeaves = new ConcurrentHashMap<>();

    // Turn timer
    private BukkitTask turnTimerTask;
    
    public BlackjackTable(BlackjackPlugin plugin, TableManager tableManager,
                          ConfigManager configManager, int id, Location centerLoc,
                          TableSettings settings) {
        this.plugin = plugin;
        this.tableManager = tableManager;
        this.configManager = configManager;
        this.chatUtils = new ChatUtils(configManager);
        this.gameEngine = new BlackjackEngine();
        this.id = id;
        this.centerLoc = centerLoc;
        this.settings = settings;
        purgeTrackedDisplays();
    }

    public int getId() {
        return id;
    }

    public TableSettings getSettings() {
        return settings;
    }
    
    /**
     * Add a player to this table at the next free seat
     */
    public boolean addPlayer(Player player) {
        return addPlayer(player, -1);
    }

    /**
     * Add a player to this table, at {@code preferredSeat} if it's free (e.g. the chair they clicked).
     */
    public boolean addPlayer(Player player, int preferredSeat) {
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

            if (settlingResults) {
                player.sendMessage(configManager.getMessage("betting-locked"));
                return false;
            }
            
            if (!player.getWorld().equals(centerLoc.getWorld())
                    || player.getLocation().distance(centerLoc) > settings.getMaxJoinDistance(configManager)) {
                player.sendMessage(configManager.getMessage("too-far"));
                return false;
            }

            // Don't let someone take a seat they can't afford to play from
            int tableMinBet = settings.getMinBet(configManager);
            if (!plugin.getEconomyProvider().hasEnough(player.getUniqueId(), BigDecimal.valueOf(tableMinBet))) {
                player.sendMessage(configManager.formatMessage("join-insufficient-funds", "min_bet", tableMinBet));
                return false;
            }
            
            int seatNumber = isSeatFree(preferredSeat) ? preferredSeat : getNextAvailableSeatNumber();
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

                // Teleport to seat. The table is registered afterwards on purpose: the teleport
                // listener removes seated players who teleport out of range, and it must not see this one.
                Location seatLoc = getSeatLocation(seatNumber);
                if (seatLoc != null) {
                    seatLoc.setYaw(seatYaw(seatNumber));
                    seatLoc.setPitch(20f); // looking at the cards
                    player.teleport(seatLoc);
                    
                    if (configManager.shouldSeatPlayers()) {
                        sitDown(player, seatNumber);
                    } else if (plugin.isGSitEnabled()) {
                        // GSit integration - make player sit down if GSit is available
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline() && players.contains(player)) {
                                // Use GSit's sit command
                                player.performCommand("sit");
                            }
                        }, 5L); // 0.25 second delay to allow teleport to complete
                    }
                }
                tableManager.setPlayerTable(player, this);

                broadcastTableMessage(configManager.formatMessage("player-joined-table", "player", player.getName()));
                if (configManager.shouldSeatPlayers()) {
                    player.sendMessage(configManager.getMessage("seat-hint"));
                }
                plugin.getCardResourcePack().offer(player);
                
                // Let them pick a bet: chip menu, or the clickable amounts in chat
                if (configManager.useBetMenu()) {
                    plugin.getBetMenu().open(player);
                } else {
                    chatUtils.sendBettingOptions(player);
                }
                
                return true;
            } catch (Exception e) {
                // Cleanup on error
                standUp(player);
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
        removePlayer(player, configManager.getMessage("leave-reason-left"));
    }
    
    /**
     * Remove a player from this table with custom reason
     */
    public void removePlayer(Player player, String reason) {
        synchronized (this) {
            if (!players.contains(player)) return;

            Integer betAmount = plugin.getPlayerBets().get(player);
            boolean hasBet = betAmount != null && betAmount > 0;
            boolean inRound = roundBets.containsKey(player);
            // A hand that has stood, doubled or busted is already decided. Refunding it would let
            // a player walk away from a known loss, so only a hand still being played is refundable.
            boolean handStillLive = !finishedPlayers.contains(player) && !isPlayerBusted(player);

            if (settlingResults && inRound) {
                // The dealer is done and payouts are only waiting on the reveal delay: settle now,
                // before the hand is cleared, so leaving can neither dodge nor lose the result.
                handlePayout(player, gameEngine.calculateHandValue(dealerHand));
                roundBets.remove(player);
                player.sendMessage(configManager.getMessage("left-table"));
            } else if (gameInProgress && inRound && hasBet) {
                plugin.getPlayerBets().remove(player);
                roundBets.remove(player);
                if (handStillLive && configManager.shouldRefundOnLeave()) {
                    refundLeavingPlayer(player, betAmount);
                } else {
                    player.sendMessage(configManager.formatMessage("left-table-bet-forfeit", "amount", betAmount));
                }
            } else if (hasBet) {
                // Bet placed for a round that never started; nothing was at stake yet.
                plugin.getPlayerBets().remove(player);
                refundLeavingPlayer(player, betAmount);
            } else {
                player.sendMessage(configManager.getMessage("left-table"));
            }

            // Cleanup player data
            standUp(player);
            pendingLeaves.remove(player.getUniqueId());
            players.remove(player);
            playerSeats.remove(player);
            playerHands.remove(player);
            finishedPlayers.remove(player);
            doubleDownPlayers.remove(player);
            tableManager.setPlayerTable(player, null);

            // Remove display entities
            List<ItemDisplay> cardDisplays = playerCardDisplays.remove(player);
            if (cardDisplays != null) {
                cardDisplays.forEach(display -> {
                    removeTrackedDisplay(display);
                });
            }
            
            List<ItemDisplay> dealerDisplays = playerDealerDisplays.remove(player);
            if (dealerDisplays != null) {
                dealerDisplays.forEach(display -> {
                    removeTrackedDisplay(display);
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
    
    private void refundLeavingPlayer(Player player, int amount) {
        if (plugin.getEconomyProvider().add(player.getUniqueId(), BigDecimal.valueOf(amount))) {
            player.sendMessage(configManager.formatMessage("left-table-bet-refunded", "amount", amount));
        } else {
            player.sendMessage(configManager.getMessage("error-refund"));
            plugin.getLogger().severe("Failed to refund bet of " + amount + " for " + player.getName() + " when leaving the table");
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
            settlingResults = false;
            deck = new Deck();
            clearAllDisplays();
            finishedPlayers.clear();
            doubleDownPlayers.clear();
            roundBets.clear();
            
            // Deal initial cards (2 per player)
            for (Player player : players) {
                List<Card> hand = new ArrayList<>();
                hand.add(deck.drawCard());
                hand.add(deck.drawCard());
                playerHands.put(player, hand);
                roundBets.put(player, plugin.getPlayerBets().getOrDefault(player, 0));
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
            startTurnTimer();
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
            
            // Double the bet. Payouts read roundBets, so it has to be doubled too or a winning
            // double down only pays back the original stake.
            if (!plugin.getEconomyProvider().subtract(player.getUniqueId(), java.math.BigDecimal.valueOf(currentBet))) {
                player.sendMessage(configManager.getMessage("bet-failed"));
                return;
            }
            plugin.getPlayerBets().put(player, currentBet * 2);
            roundBets.put(player, currentBet * 2);
            
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
                "value", formatHandValue(value)), true);
            
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
            startTurnTimer();
        } else {
            endGame();
        }
    }

    /**
     * Give the current player turn-timeout-seconds to act, counting down on their action bar,
     * then stand for them. Without it one idle player holds up the whole table until they leave.
     */
    private void startTurnTimer() {
        cancelTurnTimer();
        int timeout = configManager.getTurnTimeoutSeconds();
        Player turnPlayer = currentPlayer;
        if (timeout <= 0 || turnPlayer == null) {
            return;
        }

        int[] secondsLeft = {timeout};
        turnTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!gameInProgress || currentPlayer != turnPlayer) {
                cancelTurnTimer();
                return;
            }
            if (secondsLeft[0] <= 0) {
                cancelTurnTimer();
                broadcastTableMessage(configManager.formatMessage("turn-timeout", "player", turnPlayer.getName()), true);
                stand(turnPlayer);
                return;
            }
            chatUtils.sendActionBar(turnPlayer, configManager.formatMessage("turn-timer", "seconds", secondsLeft[0]));
            secondsLeft[0]--;
        }, 0L, 20L);
    }

    private void cancelTurnTimer() {
        if (turnTimerTask != null) {
            turnTimerTask.cancel();
            turnTimerTask = null;
        }
    }
    
    private void endGame() {
        synchronized (this) {
            if (!gameInProgress) return;
            cancelTurnTimer();
            
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
            settlingResults = true;
            
            // Update dealer displays and show final hand with cards and value
            updateDealerDisplays();
            int dealerValue = gameEngine.calculateHandValue(dealerHand);
            String dealerHandDisplay = formatHand(dealerHand);
            String dealerValueDisplay = formatHandValue(dealerValue);
            broadcastTableMessage(configManager.formatMessage("dealer-final-hand",
                "hand", dealerHandDisplay, "value", dealerValueDisplay), true);
            
            // Handle payouts for each player with a small delay to let dealer cards show.
            // Anyone offline by then is settled inside removePlayer.
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
                settlingResults = false;
                roundBets.clear();
                
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
        Integer betAmount = roundBets.get(player);
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
                    "payout", String.valueOf(blackjackPayout)), true);
                playWinSound(player);
                // Record profit, not the returned stake, to match regular wins
                updatePlayerStats(player, true, (double) (blackjackPayout - betAmount));
                break;
            case PLAYER_WIN:
            case DEALER_BUST:
                // Regular win pays 2:1 (bet back + equal amount)
                int winPayout = betAmount * 2;
                plugin.getEconomyProvider().add(player.getUniqueId(), java.math.BigDecimal.valueOf(winPayout));
                broadcastTableMessage(configManager.formatMessage("player-wins", 
                    "player", player.getName(), 
                    "payout", String.valueOf(winPayout)), true);
                playWinSound(player);
                updatePlayerStats(player, true, (double) betAmount);
                break;
            case DEALER_WIN:
            case DEALER_BLACKJACK:
            case PLAYER_BUST:
                // Player loses their bet (already taken when bet was placed)
                broadcastTableMessage(configManager.formatMessage("player-loses", 
                    "player", player.getName(), 
                    "amount", String.valueOf(betAmount)), true);
                playLoseSound(player);
                updatePlayerStats(player, false, (double) -betAmount);
                break;
            case PUSH:
                // Push - return bet to player
                plugin.getEconomyProvider().add(player.getUniqueId(), java.math.BigDecimal.valueOf(betAmount));
                broadcastTableMessage(configManager.formatMessage("player-push", 
                    "player", player.getName(), 
                    "amount", String.valueOf(betAmount)), true);
                if (configManager.areSoundsEnabled()) {
                    player.playSound(player.getLocation(), configManager.getPushSound(), 1.0F, 1.0F);
                }
                updatePlayerStats(player, null, 0.0); // Push doesn't count as win or loss
                break;
        }
        
        // Clear the bet
        plugin.getPlayerBets().remove(player);
    }
    
    private void updatePlayerStats(Player player, Boolean won, double winnings) {
        // Load the saved record first; starting from a blank one would overwrite the player's history
        com.vortex.blackjack.model.PlayerStats stats = plugin.getOrLoadStats(player.getUniqueId());
        plugin.markStatsDirty();

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
        for (int i = 0; i < settings.getMaxPlayers(configManager); i++) {
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

    /** Seat number of the chair at (dx, dz) blocks from the table centre, or -1. */
    static int seatAtOffset(int dx, int dz) {
        if (dz == 0 && dx == 2) return 0;
        if (dx == 0 && dz == 2) return 1;
        if (dz == 0 && dx == -2) return 2;
        if (dx == 0 && dz == -2) return 3;
        return -1;
    }

    /** Yaw that faces the table from a seat (0 = south, 90 = west, 180 = north, 270 = east). */
    private static float seatYaw(int seatNumber) {
        return switch (seatNumber) {
            case 0 -> 90f;   // east chair looks west
            case 1 -> 180f;  // south chair looks north
            case 2 -> 270f;  // west chair looks east
            default -> 0f;   // north chair looks south
        };
    }

    private boolean isSeatFree(int seatNumber) {
        return seatNumber >= 0 && seatNumber < settings.getMaxPlayers(configManager)
            && !playerSeats.containsValue(seatNumber);
    }

    /**
     * Sit the player on their chair by mounting them on an invisible marker armor stand. The stand is
     * placed so the player's hips rest on the stair's top surface (half a block above the chair block).
     */
    private void sitDown(Player player, int seatNumber) {
        Location chair = getSeatLocation(seatNumber);
        if (chair == null || chair.getWorld() == null) {
            return;
        }
        double hipsY = chair.getBlockY() + 0.5;
        Location standLoc = new Location(chair.getWorld(), chair.getBlockX() + 0.5,
            hipsY - ServerCompat.RIDER_HIP_HEIGHT + ServerCompat.STAND_ABOVE_RIDER_FEET, chair.getBlockZ() + 0.5,
            seatYaw(seatNumber), 0f);

        ArmorStand stand = chair.getWorld().spawn(standLoc, ArmorStand.class);
        stand.setMarker(true);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setBasePlate(false);
        stand.setPersistent(false);   // like the cards, never saved with the chunk
        stand.addScoreboardTag(SEAT_TAG);
        stand.addScoreboardTag(getTableDisplayTag());
        stand.addPassenger(player);
        seatEntities.put(player, stand);
    }

    /** Take the player off their seat (if they have one) and remove it. */
    private void standUp(Player player) {
        ArmorStand stand = seatEntities.remove(player);
        if (stand != null) {
            stand.eject();
            stand.remove();
        }
    }

    /**
     * Put a player who got off their seat back on it. While they still hold sneak the server takes
     * them straight off again, so keep re-seating every tick until they've stayed on for half a
     * second (or five seconds pass, or they leave the table).
     */
    public void reseat(Player player) {
        new BukkitRunnable() {
            private int ticks;
            private int mountedTicks;

            @Override
            public void run() {
                ArmorStand stand = seatEntities.get(player);
                if (stand == null || !stand.isValid() || !player.isOnline() || ++ticks > 100) {
                    cancel();
                    return;
                }
                if (stand.getPassengers().contains(player)) {
                    if (++mountedTicks >= 10) {
                        cancel();
                    }
                    return;
                }
                mountedTicks = 0;
                stand.addPassenger(player);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * What leaving right now would do to the player's bet, so sneaking out can ask first when a
     * round is in progress. Mirrors the rules in {@link #removePlayer(Player, String)}.
     */
    public LeaveOutcome leaveOutcome(Player player) {
        Integer bet = plugin.getPlayerBets().get(player);
        boolean inRound = roundBets.containsKey(player);
        if (bet == null || bet <= 0 || !gameInProgress || !inRound) {
            return LeaveOutcome.NOTHING_AT_STAKE; // no round, or a pending bet that gets refunded
        }
        boolean handStillLive = !finishedPlayers.contains(player) && !isPlayerBusted(player);
        return handStillLive && configManager.shouldRefundOnLeave() ? LeaveOutcome.REFUND : LeaveOutcome.FORFEIT;
    }

    public enum LeaveOutcome { NOTHING_AT_STAKE, REFUND, FORFEIT }

    /**
     * Sneak-to-leave: returns true if the player should leave now. Mid-round the first sneak only
     * warns (and puts them back on the seat); a second sneak within three seconds confirms.
     */
    public boolean confirmSneakLeave(Player player) {
        LeaveOutcome outcome = leaveOutcome(player);
        if (outcome == LeaveOutcome.NOTHING_AT_STAKE) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long first = pendingLeaves.get(player.getUniqueId());
        if (first != null && now - first <= 3000) {
            pendingLeaves.remove(player.getUniqueId());
            return true;
        }
        pendingLeaves.put(player.getUniqueId(), now);
        Integer bet = plugin.getPlayerBets().get(player);
        player.sendMessage(configManager.formatMessage(
            outcome == LeaveOutcome.REFUND ? "seat-leave-confirm-refund" : "seat-leave-confirm-forfeit",
            "amount", bet == null ? 0 : bet));
        reseat(player); // sneaking takes them off the seat; put them back
        return false;
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
            // Lying flat, the card's top edge points along (-sin z, 0, cos z). Point it from the
            // chair towards the dealer so the player reads the card the right way up.
            float zRotation = switch (seatNumber) {
                case 0 -> (float) (Math.PI / 2);   // east chair: top towards the west
                case 1 -> (float) Math.PI;         // south chair: top towards the north
                case 2 -> (float) (-Math.PI / 2);  // west chair: top towards the east
                default -> 0.0f;                   // north chair: top towards the south
            };

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
        
        String model = card != null ? card.getCardIdentifier().toLowerCase() : "back";
        ItemStack cardItem = new ItemStack(Material.CLOCK);
        ItemMeta meta = cardItem.getItemMeta();
        if (ServerCompat.ITEM_MODELS) {
            meta.setItemModel(new NamespacedKey("playing_cards", "card/" + model));
        } else {
            // Before 1.21.2 there's no item_model component; Playing Cards 1.2+ maps these
            // CustomModelData values on the clock to the same card models
            meta.setCustomModelData(CardModels.legacyCustomModelData(model));
        }
        cardItem.setItemMeta(meta);
        display.setItemStack(cardItem);

        // Never saved with the chunk: if the chunk unloads or the server dies mid-game, the card
        // disappears instead of being left on the table forever (issue #8).
        display.setPersistent(false);
        display.addScoreboardTag(CardDisplayCleaner.CARD_TAG);
        display.addScoreboardTag(getTableDisplayTag());
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
    
    private void sendPlayerMessage(Player player, String message, boolean important) {
        // Always use compact mode - no config needed
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastMessageTime.get(playerId);
        
        // Important messages (payouts, results, dealer final hand, double down) skip the cooldown.
        // Callers flag them explicitly: matching on the English text broke every translation.
        if (important || lastTime == null || currentTime - lastTime > 1500) {
            // Check if message is already formatted (contains color codes or special characters)
            if (message.contains("§") || message.contains("&") || important) {
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
        broadcastTableMessage(message, false);
    }
    
    private void broadcastTableMessage(String message, boolean important) {
        // Send to all players at the table with spam reduction
        for (Map.Entry<Player, Integer> entry : playerSeats.entrySet()) {
            Player player = entry.getKey();
            if (player != null && player.isOnline()) {
                sendPlayerMessage(player, message, important);
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
        
        return "" + ChatColor.BOLD + valueColor
            + configManager.formatMessage("hand-value-format", "value", value) + ChatColor.RESET;
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
        
        if (configManager.areParticlesEnabled() && configManager.getWinParticle() != null) {
            player.spawnParticle(configManager.getWinParticle(), 
                player.getLocation().add(0.0, 2.0, 0.0), 20, 0.5, 0.5, 0.5);
        }
    }
    
    private void playLoseSound(Player player) {
        if (configManager.areSoundsEnabled()) {
            player.playSound(player.getLocation(), configManager.getLoseSound(), 1.0F, 1.0F);
        }
        
        if (configManager.areParticlesEnabled() && configManager.getLoseParticle() != null) {
            player.spawnParticle(configManager.getLoseParticle(), 
                player.getLocation().add(0.0, 2.0, 0.0), 10, 0.5, 0.5, 0.5);
        }
    }

    private String getTableDisplayTag() {
        return "blackjack-table:" + centerLoc.getWorld().getName() + ":" + centerLoc.getBlockX() + ":" + centerLoc.getBlockY() + ":" + centerLoc.getBlockZ();
    }

    private void removeTrackedDisplay(ItemDisplay display) {
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    private void purgeTrackedDisplays() {
        if (centerLoc.getWorld() == null) {
            return;
        }

        String tableTag = getTableDisplayTag();
        for (Entity entity : centerLoc.getWorld().getNearbyEntities(centerLoc, 8.0, 4.0, 8.0, entity ->
            entity.getScoreboardTags().contains(CardDisplayCleaner.CARD_TAG) &&
            entity.getScoreboardTags().contains(tableTag))) {
            entity.remove();
        }
    }
    
    // Display management methods - ORIGINAL IMPLEMENTATION
    private void updateCardDisplays(Player player, List<Card> hand) {
        int seatNumber = playerSeats.get(player);
        Location baseDisplayLoc = getSeatLocation(seatNumber);
        
        if (playerCardDisplays.containsKey(player)) {
            for (ItemDisplay display : playerCardDisplays.get(player)) {
                removeTrackedDisplay(display);
            }
            playerCardDisplays.get(player).clear();
        }

        playerCardDisplays.putIfAbsent(player, new ArrayList<>());
        double cardSpacing = configManager.getCardSpacing();
        double playerHeight = configManager.getPlayerCardHeight();
        double distanceFromPlayer = 1.0; // Original hardcoded value
        // With 3D cards off (or unsupported below 1.21.2) the hand is only shown in chat
        int cardsToShow = configManager.areCardDisplaysEnabled() ? hand.size() : 0;

        for (int i = 0; i < cardsToShow; i++) {
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
                    removeTrackedDisplay(display);
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

            int cardsToShow = configManager.areCardDisplaysEnabled() ? dealerHand.size() : 0;
            for (int i = 0; i < cardsToShow; i++) {
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
        purgeTrackedDisplays();

        // Clear displays for all players (not just current players list)
        for (List<ItemDisplay> cardDisplays : playerCardDisplays.values()) {
            if (cardDisplays != null) {
                cardDisplays.forEach(display -> {
                    removeTrackedDisplay(display);
                });
            }
        }
        
        for (List<ItemDisplay> dealerDisplays : playerDealerDisplays.values()) {
            if (dealerDisplays != null) {
                dealerDisplays.forEach(display -> {
                    removeTrackedDisplay(display);
                });
            }
        }
        
        playerCardDisplays.clear();
        playerDealerDisplays.clear();
    }
    
    /**
     * True if the entity is one of this table's cards or seats.
     */
    public boolean ownsEntity(Entity entity) {
        for (ArmorStand seat : seatEntities.values()) {
            if (seat.getUniqueId().equals(entity.getUniqueId())) return true;
        }
        return ownsDisplay(entity);
    }

    /**
     * True if the entity is one of the cards this table currently has laid out.
     */
    public boolean ownsDisplay(Entity entity) {
        UUID id = entity.getUniqueId();
        for (List<ItemDisplay> displays : playerCardDisplays.values()) {
            for (ItemDisplay display : displays) {
                if (display.getUniqueId().equals(id)) return true;
            }
        }
        for (List<ItemDisplay> displays : playerDealerDisplays.values()) {
            for (ItemDisplay display : displays) {
                if (display.getUniqueId().equals(id)) return true;
            }
        }
        return false;
    }
    
    /**
     * Cleanup all resources for this table
     */
    public void cleanup() {
        cancelTurnTimer();
        cancelAutoLeaveTimer();
        for (Player seated : new ArrayList<>(seatEntities.keySet())) {
            standUp(seated);
        }
        pendingLeaves.clear();
        purgeTrackedDisplays();
        clearAllDisplays();
        players.clear();
        playerHands.clear();
        playerSeats.clear();
        finishedPlayers.clear();
        doubleDownPlayers.clear();
        playerCardDisplays.clear();
        playerDealerDisplays.clear();
        lastMessageTime.clear();
        roundBets.clear();
        settlingResults = false;
    }
    
    // Getters
    public Location getCenterLocation() { return centerLoc; }
    public List<Player> getPlayers() { return new ArrayList<>(players); }
    public boolean isGameInProgress() { return gameInProgress; }
    public boolean isBettingLocked() { return gameInProgress || settlingResults; }
    
    // PlaceholderAPI support methods
    public int getPlayerCount() { return players.size(); }
    public int getAvailableSeats() { return Math.max(0, settings.getMaxPlayers(configManager) - players.size()); }
    public boolean isFull() { return players.size() >= settings.getMaxPlayers(configManager); }
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
        if (gameInProgress || settlingResults || players.isEmpty()) {
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
            removePlayer(player, configManager.getMessage("leave-reason-inactive"));
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
