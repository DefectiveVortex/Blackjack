package com.vortex.blackjack.model;

import java.util.Collections;
import java.util.Stack;

/**
 * Represents a deck of playing cards for blackjack
 */
public class Deck {
    private static final String[] SUITS = {"♠", "♥", "♦", "♣"};
    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    
    private final Stack<Card> cards = new Stack<>();

    public Deck() {
        initializeDeck();
    }

    private void initializeDeck() {
        cards.clear();
        for (String suit : SUITS) {
            for (String rank : RANKS) {
                cards.push(new Card(suit, rank));
            }
        }
        Collections.shuffle(cards);
    }

    /**
     * Draw a card from the deck, reshuffling if empty
     */
    public Card drawCard() {
        if (cards.isEmpty()) {
            initializeDeck();
        }
        return cards.pop();
    }

    /**
     * Get the number of remaining cards
     */
    public int getRemainingCards() {
        return cards.size();
    }

    /**
     * Check if deck needs reshuffling (less than 10 cards remaining)
     */
    public boolean needsReshuffle() {
        return cards.size() < 10;
    }
}
