package com.vortex.blackjack.table;

import java.util.ArrayList;
import java.util.List;

/**
 * Card model names in the Playing Cards resource pack, and the CustomModelData values used for
 * them on servers older than 1.21.2 (which can't send the item_model component).
 *
 * The legacy values are a contract with the pack: Playing Cards 1.2+ ships a clock model whose
 * overrides map {@code LEGACY_CMD_BASE + index} to {@code playing_cards:item/card/<name>}, in the
 * order of {@link #MODEL_NAMES}. Only ever append to that list.
 */
public final class CardModels {
    public static final int LEGACY_CMD_BASE = 21000;

    /** back, then s1..s10, sj, sq, sk, then the same for h, d, c, then the joker. */
    public static final List<String> MODEL_NAMES;

    static {
        List<String> names = new ArrayList<>();
        names.add("back");
        for (String suit : new String[] {"s", "h", "d", "c"}) {
            for (String rank : new String[] {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k"}) {
                names.add(suit + rank);
            }
        }
        names.add("j");
        MODEL_NAMES = List.copyOf(names);
    }

    private CardModels() {
    }

    /** CustomModelData for a card model name such as "s1", "hk" or "back". */
    public static int legacyCustomModelData(String modelName) {
        int index = MODEL_NAMES.indexOf(modelName);
        return LEGACY_CMD_BASE + Math.max(index, 0);
    }
}
