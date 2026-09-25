package com.vortex.blackjack.table;

import com.vortex.blackjack.config.ConfigManager;

/**
 * Per-table settings overrides. Null fields fall back to the global ConfigManager values.
 */
public class TableSettings {

    private Integer minBet;
    private Integer maxBet;
    private Integer maxPlayers;
    private Double  maxJoinDistance;

    /** All-nulls constructor — every field resolves to the global config default. */
    public TableSettings() {}

    /** Full constructor used when loading persisted settings from config. */
    public TableSettings(Integer minBet, Integer maxBet,
                         Integer maxPlayers, Double maxJoinDistance) {
        this.minBet          = minBet;
        this.maxBet          = maxBet;
        this.maxPlayers      = maxPlayers;
        this.maxJoinDistance = maxJoinDistance;
    }

    // -------------------------------------------------------------------------
    // Resolved getters — always return a usable value
    // -------------------------------------------------------------------------

    public int getMinBet(ConfigManager cfg) {
        return minBet != null ? minBet : cfg.getMinBet();
    }

    public int getMaxBet(ConfigManager cfg) {
        return maxBet != null ? maxBet : cfg.getMaxBet();
    }

    public int getMaxPlayers(ConfigManager cfg) {
        // Older configs may hold values up to 8; a table only ever has four chairs.
        int value = maxPlayers != null ? maxPlayers : cfg.getMaxPlayers();
        return Math.max(1, Math.min(ConfigManager.MAX_SEATS, value));
    }

    public double getMaxJoinDistance(ConfigManager cfg) {
        return maxJoinDistance != null ? maxJoinDistance : cfg.getMaxJoinDistance();
    }

    // -------------------------------------------------------------------------
    // Raw nullable getters (for serialisation — null means "not set")
    // -------------------------------------------------------------------------

    public Integer getRawMinBet()          { return minBet; }
    public Integer getRawMaxBet()          { return maxBet; }
    public Integer getRawMaxPlayers()      { return maxPlayers; }
    public Double  getRawMaxJoinDistance() { return maxJoinDistance; }

    // -------------------------------------------------------------------------
    // Setters (used by /bj settable)
    // -------------------------------------------------------------------------

    public void setMinBet(Integer v)         { this.minBet          = v; }
    public void setMaxBet(Integer v)         { this.maxBet          = v; }
    public void setMaxPlayers(Integer v)     { this.maxPlayers      = v; }
    public void setMaxJoinDistance(Double v) { this.maxJoinDistance = v; }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Returns a (translated) error description if the current settings are invalid, or null if OK.
     */
    public String validate(ConfigManager cfg) {
        int lo = getMinBet(cfg);
        int hi = getMaxBet(cfg);
        if (lo > hi) return cfg.formatMessage("table-error-min-exceeds-max", "min_bet", lo, "max_bet", hi);
        if (maxPlayers != null && (maxPlayers < 1 || maxPlayers > ConfigManager.MAX_SEATS))
            return cfg.formatMessage("table-error-max-players-range", "max", ConfigManager.MAX_SEATS);
        if (maxJoinDistance != null && maxJoinDistance < 1.0)
            return cfg.getMessage("table-error-min-distance");
        return null;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /**
     * Parses named-argument tokens of the form "key:value" starting at {@code startIndex}.
     * Recognised keys: min-bet, max-bet, max-players, max-join-distance.
     * On error writes a description into {@code errorOut} and returns null.
     */
    public static TableSettings parseArgs(String[] tokens, int startIndex,
                                          ConfigManager cfg, StringBuilder errorOut) {
        TableSettings s = new TableSettings();
        for (int i = startIndex; i < tokens.length; i++) {
            String tok   = tokens[i];
            int    colon = tok.indexOf(':');
            if (colon < 0) {
                errorOut.append(cfg.formatMessage("table-error-invalid-format", "arg", tok));
                return null;
            }
            String key = tok.substring(0, colon).toLowerCase();
            String val = tok.substring(colon + 1);
            try {
                switch (key) {
                    case "min-bet"            -> s.setMinBet(parsePositiveInt(val));
                    case "max-bet"            -> s.setMaxBet(parsePositiveInt(val));
                    case "max-players"        -> s.setMaxPlayers(parsePositiveInt(val));
                    case "max-join-distance"  -> s.setMaxJoinDistance(parsePositiveDouble(val));
                    default -> {
                        errorOut.append(cfg.formatMessage("table-error-unknown-setting", "setting", key));
                        return null;
                    }
                }
            } catch (NumberFormatException e) {
                errorOut.append(cfg.formatMessage("table-error-invalid-value", "setting", key, "value", val));
                return null;
            }
        }
        String err = s.validate(cfg);
        if (err != null) {
            errorOut.append(err);
            return null;
        }
        return s;
    }

    private static int parsePositiveInt(String s) {
        int v = Integer.parseInt(s);
        if (v <= 0) throw new NumberFormatException("must be positive");
        return v;
    }

    private static double parsePositiveDouble(String s) {
        double v = Double.parseDouble(s);
        if (v <= 0) throw new NumberFormatException("must be positive");
        return v;
    }
}
