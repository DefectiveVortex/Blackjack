package com.vortex.blackjack.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Simple Vault economy provider - works with any Vault-compatible economy plugin
 */
public class VaultEconomyProvider implements EconomyProvider {
    private Economy economy;
    private boolean enabled = false;
    
    public VaultEconomyProvider(Plugin plugin) {
        setupEconomy();
    }
    
    /**
     * Attempt to connect/reconnect to economy service
     */
    public boolean reconnect() {
        return setupEconomy();
    }
    
    private boolean setupEconomy() {
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        
        economy = rsp.getProvider();
        enabled = (economy != null);
        return enabled;
    }
    
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAvailable() {
        return enabled && economy != null;
    }
    
    @Override
    public boolean hasEnough(UUID playerUuid, BigDecimal amount) {
        if (!isAvailable()) return false;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            return economy.has(player, amount.doubleValue());
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean add(UUID playerUuid, BigDecimal amount) {
        if (!isAvailable()) return false;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            return economy.depositPlayer(player, amount.doubleValue()).transactionSuccess();
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean subtract(UUID playerUuid, BigDecimal amount) {
        if (!isAvailable()) return false;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            return economy.withdrawPlayer(player, amount.doubleValue()).transactionSuccess();
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public BigDecimal getBalance(UUID playerUuid) {
        if (!isAvailable()) return BigDecimal.ZERO;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            return BigDecimal.valueOf(economy.getBalance(player));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
    
    @Override
    public String getProviderName() {
        return enabled && economy != null ? economy.getName() : "None";
    }
}
