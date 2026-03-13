# Blackjack Development Patterns

> Auto-generated skill from repository analysis

## Overview

This skill teaches the development patterns for a Java-based Blackjack plugin. The codebase follows a modular architecture with separate components for configuration management, command handling, game table logic, and utility functions. The project uses Maven for dependency management and follows plugin-based development patterns common in Java gaming applications.

## Coding Conventions

### File Naming
- **Classes**: PascalCase (e.g., `BlackjackPlugin.java`, `ConfigManager.java`, `BlackjackTable.java`)
- **Test files**: Follow `*.test.*` pattern
- **Configuration files**: lowercase with extensions (e.g., `config.yml`, `plugin.yml`)

### Code Structure
```java
// Package structure follows reverse domain naming
package com.vortex.blackjack.config;
package com.vortex.blackjack.commands;
package com.vortex.blackjack.table;
package com.vortex.blackjack.util;

// Mixed import style - organize by functionality
import com.vortex.blackjack.config.ConfigManager;
import com.vortex.blackjack.util.ChatUtils;
```

### Commit Style
- Freeform commit messages
- Average 12 characters in length
- Focus on concise, descriptive changes

## Workflows

### Version Release
**Trigger:** When preparing a new version release
**Command:** `/release`

1. **Update Maven configuration**
   - Modify version in `pom.xml`
   - Update `dependency-reduced-pom.xml` with matching version
   
2. **Update plugin metadata**
   - Change version in `src/main/java/com/vortex/blackjack/BlackjackPlugin.java`
   - Update `src/main/resources/plugin.yml` with new version number
   
3. **Update documentation**
   - Modify `README.md` with release notes and changelog
   - Document new features or breaking changes
   
4. **Update core components**
   - Review and modify `src/main/java/com/vortex/blackjack/table/BlackjackTable.java`
   - Update utility classes like `src/main/java/com/vortex/blackjack/util/ChatUtils.java`

**Example version update in plugin.yml:**
```yaml
name: Blackjack
version: 1.2.0
main: com.vortex.blackjack.BlackjackPlugin
```

### Configuration Management Update
**Trigger:** When modifying plugin configuration or settings
**Command:** `/update-config`

1. **Update configuration manager**
   - Modify `src/main/java/com/vortex/blackjack/config/ConfigManager.java` with new config logic
   - Add new configuration options or modify existing ones
   
2. **Update configuration file**
   - Add new settings to `src/main/resources/config.yml`
   - Maintain backward compatibility where possible
   
3. **Integrate configuration changes**
   - Update `src/main/java/com/vortex/blackjack/BlackjackPlugin.java` to handle new config
   - Ensure proper initialization and validation
   
4. **Update dependent components**
   - Modify `src/main/java/com/vortex/blackjack/table/BlackjackTable.java` to use new configuration

**Example config update:**
```java
public class ConfigManager {
    public void loadConfig() {
        // Load new configuration options
        boolean enableFeature = config.getBoolean("features.new-feature", false);
        // Handle configuration with defaults
    }
}
```

### Command System Enhancement
**Trigger:** When adding new commands or enhancing command system
**Command:** `/add-command`

1. **Create command class**
   - Create new command class following pattern: `*Command.java`
   - Implement command logic and validation
   
2. **Update command manager**
   - Modify `src/main/java/com/vortex/blackjack/commands/CommandManager.java`
   - Register new command with proper permissions and aliases
   
3. **Integrate with main plugin**
   - Update `src/main/java/com/vortex/blackjack/BlackjackPlugin.java`
   - Ensure proper command initialization
   
4. **Update plugin metadata**
   - Add command definitions to `src/main/resources/plugin.yml`
   - Include permissions, usage, and aliases

**Example command registration:**
```yaml
# plugin.yml
commands:
  blackjack:
    description: Main blackjack command
    usage: /blackjack <subcommand>
    permission: blackjack.use
    aliases: [bj]
```

## Testing Patterns

The project uses a custom testing framework with files following the `*.test.*` naming pattern. Tests should be placed alongside their corresponding source files and focus on:

- Configuration validation
- Command execution logic  
- Game table state management
- Utility function behavior

## Commands

| Command | Purpose |
|---------|---------|
| `/release` | Prepare and execute a version release |
| `/update-config` | Modify configuration system and related components |
| `/add-command` | Add new command functionality to the plugin |