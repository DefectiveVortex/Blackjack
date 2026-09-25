package com.vortex.blackjack.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

/**
 * Runtime feature checks for APIs newer than the oldest server we load on (1.20).
 * The plugin is compiled against 1.21.4, so every call to a newer method has to be
 * guarded by one of these flags or the JVM throws NoSuchMethodError on old servers.
 */
public final class ServerCompat {
    /** ItemMeta#setItemModel arrived in 1.21.2; the card textures depend on it. */
    public static final boolean ITEM_MODELS = hasMethod(ItemMeta.class, "setItemModel", NamespacedKey.class);

    /** Player#addResourcePack (stacking packs instead of replacing) arrived in 1.20.3. */
    public static final boolean STACKED_RESOURCE_PACKS = hasMethod(Player.class, "addResourcePack",
        UUID.class, String.class, byte[].class, String.class, boolean.class);

    /**
     * 1.20.5 moved riders onto entity attachment points. A player riding a marker armor stand now
     * has their feet 0.6 below the stand; before that it was 0.35.
     */
    public static final double STAND_ABOVE_RIDER_FEET = isAtLeast(1, 20, 5) ? 0.6 : 0.35;

    /** Height of a seated player's hips above their feet position. */
    public static final double RIDER_HIP_HEIGHT = 0.6;

    private ServerCompat() {
    }

    /** Compare against Bukkit's version string ("1.20.1-R0.1-SNAPSHOT", "26.3-..."). */
    static boolean isAtLeast(int major, int minor, int patch) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
            .matcher(org.bukkit.Bukkit.getBukkitVersion());
        if (!m.find()) {
            return true; // unknown format: assume a current server
        }
        int[] have = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
            m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
        int[] want = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            if (have[i] != want[i]) {
                return have[i] > want[i];
            }
        }
        return true;
    }

    /**
     * Resolve a particle by name, trying each candidate in order. Several particles were
     * renamed in 1.20.5 (VILLAGER_HAPPY became HAPPY_VILLAGER), and referencing the new
     * constant directly would fail to link on older servers.
     */
    public static Particle particle(String... names) {
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                return Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            type.getMethod(name, parameters);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
