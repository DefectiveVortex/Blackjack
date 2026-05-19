package com.vortex.blackjack.economy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Interface for economy operations to allow different economy plugin support
 */
public interface EconomyProvider {
    /**
     * Whether the provider is ready to service economy requests.
     */
    boolean isAvailable();
    
    /**
     * Check if a player has enough money
     */
    boolean hasEnough(UUID playerUuid, BigDecimal amount);
    
    /**
     * Add money to a player's account
     */
    boolean add(UUID playerUuid, BigDecimal amount);
    
    /**
     * Subtract money from a player's account
     */
    boolean subtract(UUID playerUuid, BigDecimal amount);
    
    /**
     * Get a player's balance
     */
    BigDecimal getBalance(UUID playerUuid);
    
    /**
     * Get the name of the economy provider
     */
    String getProviderName();
}
