# Blackjack

A blackjack plugin for Spigot and Paper servers where the tables are actual objects in the world. An admin builds a table where they're standing, players right-click a chair to sit down, bets go through Vault, and the cards are dealt onto the felt as item displays. Every hand is also printed in chat with clickable buttons, so the game is fully playable even for someone who hasn't loaded the card textures.

## Features

- Tables are built in place: a 3×3 ring of blocks with a chair on each side, seating up to four players. Right-click a chair to sit on it, sneak to stand up and leave.
- Bets are picked from a chip menu (or clickable amounts in chat, if you prefer).
- A turn timer keeps one idle player from holding up the table.
- Standard rules. The dealer draws to 17 (hitting soft 17 is a config option), a natural blackjack pays 3:2, a normal win pays 1:1, and you can double down on your first two cards.
- Bets are handled through Vault, so it works with EssentialsX, CMI, or any other Vault economy.
- Each table can have its own bet limits, seat count and join distance.
- Per-player statistics (wins, losses, pushes, blackjacks, busts, streaks and net winnings) survive restarts.
- Over 40 PlaceholderAPI placeholders for scoreboards, tab lists and chat.
- Ships in English, Korean, Turkish and Russian, and every message can be edited.
- Clickable chat buttons for hitting, standing, doubling down and quick bets.
- Sounds and particles on wins and losses, automatic sitting through GSit, and an update notice for admins.

## Requirements

| | |
|---|---|
| Server | Spigot, Paper, Purpur or another Bukkit fork, **1.20 or newer** |
| 3D cards | Any supported version; 1.20 to 1.21.1 need Playing Cards 1.2 or newer |
| Java | 17 or newer |
| Required plugins | [Vault](https://www.spigotmc.org/resources/vault.34315/) plus a Vault economy plugin (EssentialsX, CMI, etc.) |
| Optional plugins | [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/), [GSit](https://www.spigotmc.org/resources/gsit.62325/) |
| Resource pack | [Playing Cards](https://modrinth.com/resourcepack/bjplayingcards), for the card textures (offered to players automatically) |

Blackjack won't enable without Vault and an economy plugin. If it disables itself on startup, the console says which of the two is missing.

## Installation

1. Install Vault and an economy plugin if the server doesn't have them yet.
2. Drop `Blackjack.jar` from the [releases page](https://github.com/DefectiveVortex/Blackjack/releases) into `plugins/`.
3. Restart the server. `config.yml`, `messages.yml` and `tables.yml` are created in `plugins/Blackjack/`.
4. Stand where you want a table and run `/bj createtable`.

Players are offered the card resource pack the first time they sit down, so there's usually nothing else to set up. The next section explains how that works and how to turn it off.

## The card resource pack

Cards are item displays holding a clock, and the [Playing Cards](https://modrinth.com/resourcepack/bjplayingcards) pack gives that clock the right card texture. On 1.21.2 and newer the plugin points the clock at the card through the `item_model` component. That component doesn't exist before 1.21.2, so on 1.20 to 1.21.1 it sets CustomModelData instead (21000 to 21053), which Playing Cards maps to the same cards from version 1.2 on.

By default, the plugin offers the pack to each player the first time they sit down at a table in a session. On 1.20.3 and newer it's sent with `addResourcePack`, which layers it on top of your server resource pack instead of replacing it. Older servers can only replace the server pack, so there it's only offered when `server.properties` doesn't set one. The pack URL, SHA-1 and whether it's mandatory are all under `resource-pack:` in `config.yml`. If you already merge the textures into your own server pack, set `send-on-join: false`.

Which pack version you need:

- **1.21.9 and newer clients:** 1.1 or later. Version 1.0 declares an older `pack.mcmeta` format that these clients won't load, and the symptom is a table with no visible cards.
- **Servers on 1.20 to 1.21.1:** 1.2 or later, for the CustomModelData mapping.

The pack's source is in [`resourcepack/`](resourcepack/). `python3 resourcepack/build.py <version>` builds the zip and prints its SHA-1.

## Playing

Right-click one of a table's chairs to sit on it, or right-click the table (or use `/bj join`) to take any free seat. You need at least the table's minimum bet in your balance to sit down. Once seated, the chip menu opens: click a chip to bet that amount, or pick **Custom amount** and type any number in chat. `/bj bet <amount>` works too, `/bj bet` on its own reopens the menu, and so does right-clicking the table while you're seated.

A round starts automatically once everyone at the table has a bet down. You can also start it with `/bj start`. On your turn you get **[HIT]**, **[STAND]** and, on your first two cards, **[DOUBLE DOWN]** buttons. The equivalent commands are `/bj hit`, `/bj stand` and `/bj doubledown`. A countdown above your hotbar shows how long you have left (`turn-timeout-seconds`, 30 by default). When it runs out, you stand automatically. After the dealer plays out, payouts go straight into your balance, and **[Play Again]** starts the next round with the same bet.

Sneak to stand up and leave. Between rounds that's immediate. In the middle of a round you're warned first, with what will happen to your bet, and a second sneak within three seconds confirms.

What happens to your bet when you leave depends on the state of the round:

- **No round running:** a bet you placed for the next round is returned.
- **Your hand is still in play:** with `refund-on-leave: true` (the default) the bet is refunded. With `false` it's forfeited.
- **You've already stood, doubled down or busted:** the hand is decided and the bet is forfeited, whatever the setting.
- **The dealer has finished but payouts haven't gone out yet:** your hand is settled on the spot.

Walking or teleporting farther from the table than its join distance counts as leaving, and so does logging off. After a round, a player who sits idle while others are waiting is removed after `auto-leave-timeout-seconds`. If the server shuts down mid-round, the round is voided and every stake is refunded.

## Commands

Everything is a subcommand of `/blackjack`, or `/bj` for short.

| Command | What it does | Permission |
|---|---|---|
| `/bj join` | Sit down at the nearest table | `blackjack.play` |
| `/bj leave` | Leave your table | `blackjack.play` |
| `/bj bet [amount]` | Place or change your bet before the round starts; without an amount, open the chip menu | `blackjack.play` |
| `/bj start` | Start a round (reuses your last bet if you haven't placed one) | `blackjack.play` |
| `/bj hit` / `/bj stand` | Take a card / end your turn | `blackjack.play` |
| `/bj doubledown` (`/bj dd`) | Double your bet and take exactly one more card | `blackjack.play` |
| `/bj stats [player]` | Show your statistics, or someone else's | `blackjack.play`, plus `blackjack.stats.others` for other players |
| `/bj createtable [options]` | Build a table where you're standing | `blackjack.admin` |
| `/bj settable <setting> <value>` | Change a setting on the nearest table | `blackjack.admin` |
| `/bj removetable [id]` | Remove the nearest table, or the one with that ID, along with its blocks | `blackjack.admin` |
| `/bj tables` | List every table with its ID, position and players | `blackjack.admin` |
| `/bj cleanup [radius]` | Remove leftover card displays nearby (default radius 16) | `blackjack.admin` |
| `/bj reload` | Reload `config.yml` and the messages file | `blackjack.admin` |
| `/bj version` | Show the installed version and whether an update is out | `blackjack.admin` |

| Permission | Default | Grants |
|---|---|---|
| `blackjack.play` | everyone | Joining tables and playing |
| `blackjack.admin` | op | Creating, configuring and removing tables, reloading, cleanup |
| `blackjack.stats.others` | op | Looking up other players' statistics |

## Setting up tables

`/bj createtable` builds the table at your feet using `table-material` and `chair-material` from the config. A table uses the global limits unless you pass overrides:

```
/bj createtable min-bet:50 max-bet:5000 max-players:2 max-join-distance:8
```

You can change those later with `/bj settable <setting> <value>` while standing near the table. The settings are `min-bet`, `max-bet`, `max-players` (1 to 4) and `max-join-distance`. An invalid value is rejected, and the table keeps its old setting.

Every table gets a numeric ID, shown when it's created and by `/bj tables`. Tables and their overrides are stored in `tables.yml`, so `config.yml` stays yours to edit. Versions before 2.5 kept tables in `config.yml`; they're moved over automatically on first start, and the old config is kept as `config.yml.pre-tables.bak`. `/bj removetable` also clears the table's felt and chairs, but only blocks that are still `table-material` or `chair-material`, so anything you've built around a table stays.

## Configuration

`config.yml` has comments for every option. These are the ones most servers end up changing:

```yaml
language: en            # en, ko, tr, ru, or your own messages_<code>.yml

betting:
  min-bet: 10
  max-bet: 10000
  cooldown-ms: 2000     # minimum time between bet changes
  menu: gui             # gui (chip menu) or chat (clickable amounts)
  quick-bets:           # the chips in the menu, or the buttons in chat
    small: [10, 25, 50]
    medium: [100, 250, 500]
    large: [1000, 2500, 5000]

table:
  max-join-distance: 10.0
  max-players: 4        # 1-4, one per chair
  seat-players: true    # sit players on the chairs; sneak to stand up
  click-to-join: true   # right-click a chair or the table to sit down

game:
  hit-soft-17: false
  auto-leave-timeout-seconds: 30
  turn-timeout-seconds: 30   # 0 turns the turn timer off

display:
  card:
    enabled: true

resource-pack:
  send-on-join: true    # offer the Playing Cards pack when a player sits down
  required: false       # true kicks players who decline it

game-settings:
  refund-on-leave: true # refund a hand that's still in play when its player leaves
```

Sound names accept either the enum style used in the default config (`BLOCK_NOTE_BLOCK_PLING`) or a namespaced key (`minecraft:block.note_block.pling`). Particles use Bukkit's particle names.

Updating the plugin never overwrites your edits. New options are added to your existing `config.yml` and messages file, and the previous version is kept next to them as `*.pre-update.bak`.

## Translations

| Code | Language | File | Credit |
|---|---|---|---|
| `en` | English | `messages.yml` | |
| `ko` | Korean | `messages_ko.yml` | [yldst-dev](https://github.com/yldst-dev/Blackjack) |
| `tr` | Turkish | `messages_tr.yml` | [al2wastaken](https://github.com/al2wastaken/Blackjack) |
| `ru` | Russian | `messages_ru.yml` | |

Set `language:` in `config.yml` and run `/bj reload`. The file for that language is written to `plugins/Blackjack/` the first time it's used. Edit it freely: anything a translation leaves out falls back to the English text rather than showing an error.

For a language that isn't bundled, copy `messages.yml` to `messages_<code>.yml`, translate the values, and set `language: <code>`. Console output stays in English regardless.

## PlaceholderAPI

With PlaceholderAPI installed, the plugin registers the `blackjack` expansion. It covers player statistics (`%blackjack_stats_*%`), table state (`%blackjack_table_*%`), the current hand (`%blackjack_game_*%`), bets (`%blackjack_bet_*%`) and balances (`%blackjack_economy_*%`). For example, on a scoreboard:

```yaml
- "&fWin rate: &b%blackjack_stats_win_rate%%"
- "&fAt a table: %blackjack_table_at_table%"
- "&fCurrent bet: &6%blackjack_bet_current_formatted%"
```

The full list, with what each placeholder returns, is in [PLACEHOLDERAPI.md](PLACEHOLDERAPI.md).

## Troubleshooting

**The plugin disables itself on startup.** Vault or the economy plugin is missing. The console line just above the disable message says which.

**"Unsupported API version 1.21" on a 1.20 server.** That's 2.4 and earlier, which only loaded on 1.21+. From 2.5 the plugin loads on 1.20.

**Players see no cards on the table.** Check, in order: the player accepted the pack or has Playing Cards installed (1.1+ for 1.21.9+ clients, 1.2+ on a 1.20 to 1.21.1 server), and `display.card.enabled` is `true`.

**Players are seated but GSit does the sitting.** With `seat-players: true` (the default) the plugin seats players itself. Set it to `false` to go back to teleporting them next to the chair, which uses GSit's `/sit` when GSit is installed.

**Cards are stuck on a table.** Versions up to 2.4 could leave cards behind after a crash, or when a seated player teleported away and the chunk unloaded. Since 2.5, cards and seats aren't saved with the world, and leftovers from older versions are removed automatically when their chunk loads. For anything in a loaded chunk, stand nearby and run `/bj cleanup`. It never touches cards or seats belonging to a round in progress.

**Is there a Fabric version?** No. This is a Bukkit plugin and needs Spigot, Paper or a fork of them.

## Building from source

You need a JDK (17 or newer) and Maven:

```
mvn package
```

The jar is written to `target/Blackjack-<version>.jar`.

## Contributing and support

Bug reports and feature requests go to [GitHub Issues](https://github.com/DefectiveVortex/Blackjack/issues), and pull requests are welcome. For questions, I'm `@vortexunwanted` on Discord.

## License

MIT. See [LICENSE](LICENSE).
