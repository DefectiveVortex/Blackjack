# PlaceholderAPI placeholders

With [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) installed, Blackjack registers the `blackjack` expansion automatically; there's nothing to download with `/papi ecloud`. The placeholders have been there since 2.3, and the list below matches 2.5.

A few conventions hold throughout:

- True/false placeholders return the strings `true` and `false`.
- Numeric placeholders return `0` when there's nothing to report. The `game_*` placeholders return an empty string when the player isn't seated, since there's no hand to describe.
- The `*_formatted` variants use the `currency-format` line from the active messages file (`$%amount%` in English) and shorten large amounts. `1500` becomes `$1.5K` and `2000000` becomes `$2M`, with up to two decimals.

## Statistics: `%blackjack_stats_*%`

Lifetime numbers for the player, read from `stats.yml`.

| Placeholder | Returns |
|---|---|
| `%blackjack_stats_hands_won%` | Hands won |
| `%blackjack_stats_hands_lost%` | Hands lost |
| `%blackjack_stats_hands_pushed%` | Hands pushed (tied with the dealer) |
| `%blackjack_stats_total_hands%` | Won + lost + pushed |
| `%blackjack_stats_blackjacks%` | Natural blackjacks |
| `%blackjack_stats_busts%` | Times the player went over 21 |
| `%blackjack_stats_current_streak%` | Current streak: positive while winning, negative while losing. Pushes don't change it |
| `%blackjack_stats_best_streak%` | Longest winning streak |
| `%blackjack_stats_win_rate%` | Win rate as a percentage with one decimal, e.g. `65.2`. Pushes count toward the total |
| `%blackjack_stats_win_rate_raw%` | Win rate as a fraction, e.g. `0.65` |
| `%blackjack_stats_total_winnings%` | Net winnings (negative when the player is down) |
| `%blackjack_stats_total_winnings_formatted%` | The same, in currency format |
| `%blackjack_stats_has_played%` | `true` once the player has finished at least one hand |

## Table: `%blackjack_table_*%`

The table the player is sitting at.

| Placeholder | Returns |
|---|---|
| `%blackjack_table_at_table%` | `true` while the player is seated at a table |
| `%blackjack_table_id%` | ID of that table, as shown by `/bj tables` (empty when not seated) |
| `%blackjack_table_players%` | Players at that table (`0` when not seated) |
| `%blackjack_table_max_players%` | Seats at that table; the server default when not seated |
| `%blackjack_table_seats_available%` | Free seats at that table (`0` when not seated) |
| `%blackjack_table_is_full%` | `true` if every seat is taken |
| `%blackjack_table_game_in_progress%` | `true` while a round is being played |
| `%blackjack_table_can_join%` | `true` when the player isn't already seated somewhere |
| `%blackjack_table_location_x%` | X of the table's centre block |
| `%blackjack_table_location_y%` | Y of the table's centre block |
| `%blackjack_table_location_z%` | Z of the table's centre block |
| `%blackjack_table_world%` | World the table is in (empty when not seated) |

## Current hand: `%blackjack_game_*%`

The round in progress. All of these are empty when the player isn't at a table, and reset to zero/`false` between rounds.

| Placeholder | Returns |
|---|---|
| `%blackjack_game_hand_value%` | Value of the player's hand, with aces counted in the player's favour |
| `%blackjack_game_hand_cards%` | Number of cards in the player's hand |
| `%blackjack_game_is_turn%` | `true` when it's this player's turn |
| `%blackjack_game_is_finished%` | `true` once the player has stood, doubled down or busted |
| `%blackjack_game_has_blackjack%` | `true` for a two-card 21 |
| `%blackjack_game_is_busted%` | `true` when the hand is over 21 |
| `%blackjack_game_can_double_down%` | `true` while the player still has their first two cards and hasn't doubled |
| `%blackjack_game_has_doubled_down%` | `true` after doubling down |
| `%blackjack_game_dealer_visible_value%` | Value of the dealer's face-up card during the round; the full hand once it's revealed |
| `%blackjack_game_dealer_card_count%` | Cards in the dealer's hand, face-down card included |

## Bets: `%blackjack_bet_*%`

| Placeholder | Returns |
|---|---|
| `%blackjack_bet_current%` | The player's stake for the current or next round (cleared when the round pays out) |
| `%blackjack_bet_current_formatted%` | The same, in currency format |
| `%blackjack_bet_has_bet%` | `true` if a stake is down |
| `%blackjack_bet_persistent%` | The amount **[Play Again]** will bet |
| `%blackjack_bet_persistent_formatted%` | The same, in currency format |
| `%blackjack_bet_has_persistent%` | `true` if there's a remembered bet |
| `%blackjack_bet_min_bet%` | Server-wide minimum bet from `config.yml` |
| `%blackjack_bet_max_bet%` | Server-wide maximum bet from `config.yml` |
| `%blackjack_bet_min_bet_formatted%` | Minimum bet in currency format |
| `%blackjack_bet_max_bet_formatted%` | Maximum bet in currency format |

The minimum and maximum here are the global defaults. A table with its own limits (set through `createtable` or `settable`) enforces those instead.

## Economy: `%blackjack_economy_*%`

| Placeholder | Returns |
|---|---|
| `%blackjack_economy_balance%` | The player's balance from the Vault economy |
| `%blackjack_economy_balance_formatted%` | The same, in currency format |
| `%blackjack_economy_can_afford_min%` | `true` if the balance covers the global minimum bet |
| `%blackjack_economy_can_afford_max%` | `true` if the balance covers the global maximum bet |

## Examples

A scoreboard section, using whatever scoreboard plugin you run:

```yaml
lines:
  - "&6&lBlackjack"
  - "&fHands won: &a%blackjack_stats_hands_won%"
  - "&fWin rate: &b%blackjack_stats_win_rate%%"
  - "&fWinnings: &e%blackjack_stats_total_winnings_formatted%"
  - ""
  - "&fAt a table: %blackjack_table_at_table%"
  - "&fCurrent bet: &6%blackjack_bet_current_formatted%"
```

A line in the TAB list:

```yaml
tablist-format:
  - "%player%"
  - "&7Blackjack: %blackjack_stats_win_rate%% WR"
```

PlaceholderAPI doesn't do if/else on its own, so turning `true`/`false` into text needs either your chat or scoreboard plugin's condition support or a helper expansion such as ChangeOutput (`/papi ecloud download ChangeOutput`). With ChangeOutput, a chat prefix that shows whether someone is playing looks roughly like this (check the expansion's page for the exact option names):

```
%changeoutput_equals_input:{blackjack_table_at_table}_matcher:true_ifmatch:&7[&aAt Table&7]_else:&7[&cNot Playing&7]%
```

The same approach works for a welcome message keyed on `%blackjack_stats_has_played%`, e.g. "Welcome back! Your win rate: …" for returning players and a pointer to the tables for new ones.

## Performance

The statistics placeholders read `stats.yml` and cache it for five seconds. The plugin itself saves stats every `performance.stats-save-interval` seconds (3 by default, and only when something changed). So a finished hand can take a few seconds to show up on a scoreboard.

Table, hand and bet placeholders are read straight from memory. The balance placeholders ask your economy plugin through Vault each time they're evaluated, so on a busy scoreboard that refreshes every tick, those are the ones worth putting on a slower update interval.

## Troubleshooting

If placeholders show up as the literal `%blackjack_...%` text:

1. Check that PlaceholderAPI is installed and enabled.
2. Check that Blackjack is 2.3 or newer and actually enabled. It disables itself without Vault and an economy plugin.
3. Look for the "PlaceholderAPI found!" line in the console during startup.
4. Test a single placeholder with `/papi parse me %blackjack_stats_total_hands%`, or `/papi parse <player> ...` from the console.
