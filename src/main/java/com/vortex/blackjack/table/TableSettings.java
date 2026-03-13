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
        return maxPlayers != null ? maxPlayers : cfg.getMaxPlayers();
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
     * Returns an error description if the current settings are invalid, or null if OK.
     */
    public String validate(ConfigManager cfg) {
        int lo = getMinBet(cfg);
        int hi = getMaxBet(cfg);
        if (lo > hi) return "min-bet (" + lo + ") cannot exceed max-bet (" + hi + ")";
        if (maxPlayers != null && (maxPlayers < 1 || maxPlayers > 8))
            return "max-players must be between 1 and 8";
        if (maxJoinDistance != null && maxJoinDistance < 1.0)
            return "max-join-distance must be at least 1";
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
                errorOut.append("Invalid argument '").append(tok)
                        .append("' — expected format key:value");
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
                        errorOut.append("Unknown setting '").append(key)
                                .append("'. Valid: min-bet, max-bet, max-players, max-join-distance");
                        return null;
                    }
                }
            } catch (NumberFormatException e) {
                errorOut.append("Invalid value for '").append(key).append("': ").append(val);
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
