package com.vortex.blackjack.integration;

import com.vortex.blackjack.BlackjackPlugin;
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.util.ServerCompat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offers the Playing Cards resource pack to players when they sit down at a table, so the 3D
 * cards render without anyone having to find and install the pack themselves.
 *
 * On 1.20.3+ it uses Player#addResourcePack, which stacks on top of the server's own pack instead
 * of replacing it. Older servers can only replace, so there the pack is only offered when
 * server.properties doesn't set one.
 */
public class CardResourcePack {
    // A fixed id means a second offer replaces our pack on the client rather than adding a copy.
    private static final UUID PACK_ID =
        UUID.nameUUIDFromBytes("blackjack:playing_cards".getBytes(StandardCharsets.UTF_8));

    private final BlackjackPlugin plugin;
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();

    public CardResourcePack(BlackjackPlugin plugin) {
        this.plugin = plugin;
    }

    /** Offer the pack once per session. */
    public void offer(Player player) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.shouldSendResourcePack() || !config.areCardDisplaysEnabled()) {
            return;
        }
        String serverPack = Bukkit.getServer().getResourcePack();
        if (!ServerCompat.STACKED_RESOURCE_PACKS && serverPack != null && !serverPack.isBlank()) {
            return; // replacing it would strip the server's own pack from the player
        }

        String url = config.getResourcePackUrl();
        if (url == null || url.isBlank() || !offered.add(player.getUniqueId())) {
            return;
        }

        byte[] hash = parseSha1(config.getResourcePackSha1());
        String prompt = config.getMessage("resource-pack-prompt");
        if (ServerCompat.STACKED_RESOURCE_PACKS) {
            player.addResourcePack(PACK_ID, url.trim(), hash, prompt, config.isResourcePackRequired());
        } else {
            player.setResourcePack(url.trim(), hash, prompt, config.isResourcePackRequired());
        }
    }

    /** Forget the player on quit so they're offered the pack again next session. */
    public void forget(Player player) {
        offered.remove(player.getUniqueId());
    }

    private byte[] parseSha1(String sha1) {
        if (sha1 == null || sha1.isBlank()) {
            return null; // the client will download the pack without cache verification
        }
        try {
            byte[] hash = HexFormat.of().parseHex(sha1.trim());
            if (hash.length == 20) {
                return hash;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to the warning
        }
        plugin.getLogger().warning("resource-pack.sha1 is not a valid SHA-1 hash; sending the pack without one.");
        return null;
    }
}
