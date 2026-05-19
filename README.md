# 🃏 Blackjack Plugin

<div align="center">

**A premium physical blackjack table plugin for Minecraft servers**

*Create interactive card tables in your game world with realistic 3D card displays*

</div>

## ✨ Features

### 🎮 **Immersive Gameplay**
- **Physical Card Tables**: Place interactive blackjack tables anywhere in your world
- **3D Card Displays**: Realistic card animations with custom resource pack support
- **Multi-Player Support**: Up to 4 players per table with seamless turn management
- **Smart Game Logic**: Professional blackjack rules with dealer AI

### 💰 **Economy Integration**
- **Vault Compatible**: Works with any Vault-supported economy plugin (EssentialsX, EconomyAPI, CMI, etc.)
- **Flexible Betting**: Configurable bet limits and cooldown periods
- **Secure Transactions**: Anti-cheat measures and bet validation

### 📊 **Player Statistics**
- **Comprehensive Tracking**: Wins, losses, pushes, and streaks
- **Performance Metrics**: Track your best winning streaks and total earnings
- **Persistent Data**: Statistics saved across server restarts

### 🎨 **Visual & Audio**
- **Colorized Chat**: Suit-based card colors (Red ♥♦, Dark Gray ♠♣)
- **Particle Effects**: Customizable win/lose particle displays
- **Sound Effects**: Immersive audio feedback for game events
- **Compact Interface**: Clean, spam-free chat with essential information

## 🚀 Quick Start

### Installation
1. **Download** the latest `Blackjack.jar` from releases
2. **Install Vault** and a **Vault-compatible economy plugin** before starting the server
3. **Place** the jar in your server's `plugins` folder
4. **Install** the required `@playing_cards` resource pack
5. **Restart** your server
6. **Configure** settings in `config.yml` (optional)

### Basic Usage
```bash
# Create a table (Admin)
/bj createtable

# Join and play
/bj join          # Join nearest table
/bj bet 100       # Place your bet
/bj start         # Start the game
/bj hit           # Take another card
/bj stand         # End your turn
/bj stats         # View your statistics
```

## 🎯 Commands & Permissions

| Command | Description | Permission |
|---------|-------------|------------|
| `/bj createtable` | Create a new blackjack table | `blackjack.admin` |
| `/bj removetable` | Remove the nearest table | `blackjack.admin` |
| `/bj join` | Join the nearest table | `blackjack.play` |
| `/bj leave` | Leave your current table | `blackjack.play` |
| `/bj bet <amount>` | Place or change your bet | `blackjack.play` |
| `/bj hit` / `/bj stand` | Game actions | `blackjack.play` |
| `/bj stats [player]` | View statistics | `blackjack.play` |

## 🔌 PlaceholderAPI Integration

Blackjack includes **40+ PlaceholderAPI placeholders** for extensive integration with other plugins:

- **Player Statistics**: `%blackjack_stats_*%` (wins, losses, win rate, winnings, streaks)
- **Table Information**: `%blackjack_table_*%` (players, status, location)  
- **Game State**: `%blackjack_game_*%` (hand value, turn status, dealer info)
- **Betting**: `%blackjack_bet_*%` (current bets, limits, persistent bets)
- **Economy**: `%blackjack_economy_*%` (balance, affordability checks)

📖 **[View Complete PlaceholderAPI Documentation](https://github.com/DefectiveVortex/Blackjack/blob/main/PLACEHOLDERAPI.md)**

### Quick Examples:
```yaml
# Scoreboard integration
- "&fWin Rate: &b%blackjack_stats_win_rate%%"
- "&fAt Table: %blackjack_table_at_table%"
- "&fCurrent Bet: &6%blackjack_bet_current_formatted%"
```

## 🔌 Plugin Integration

### Economy & Features
- **Vault Compatible**: Works with any Vault-supported economy plugin
- **GSit Support**: Auto-sit at tables when GSit is installed
- **Version Checking**: Automatic update notifications for admins

## ⚙️ Configuration

Customize your blackjack experience:

```yaml
# Betting & Game Settings
betting:
  min-bet: 10
  max-bet: 10000
  cooldown-ms: 2000

table:
  max-players: 4
  max-join-distance: 10.0

# Audio & Visual Effects
sounds:
  enabled: true
particles:
  enabled: true
```

## 🎲 Game Features

- **🃏 3D Card Displays**: Realistic card animations with custom resource pack
- **🎯 Professional Rules**: Standard blackjack with configurable dealer behavior
- **🏆 Smart Payouts**: Blackjack 3:2, Regular wins 2:1, automatic economy integration
- **� Statistics**: Track wins, losses, streaks, and total winnings

## 🔧 Requirements

- **Minecraft Version**: 1.20 or higher
- **Server Software**: Spigot, Paper, or compatible forks
- **Java Version**: 21 or higher
- **Dependencies**: Vault + any economy plugin (EssentialsX, EconomyAPI, CMI, etc.)
- **Resource Pack**: [@playing_cards](https://modrinth.com/resourcepack/bjplayingcards)

## 🤝 Contributing

We welcome contributions! Please feel free to:
- Report bugs via GitHub Issues
- Suggest new features
- Submit pull requests
- Improve documentation

## 📞 Support

Need help? Reach out to us:

- **Discord**: `@vortexunwanted`
- **GitHub Issues**: [Report bugs/requests](https://github.com/DefectiveVortex/Blackjack/issues)

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

---
