# Clogman Mode

A RuneLite plugin that restricts item usage based on Collection Log unlocks. Unlike standard Bronzeman mode, this plugin also restricts **derived items** - items that are crafted from collection log items.

## How It Works

### Collection Log Items
Any item that appears in the Collection Log is restricted until you obtain it. Once you get the drop and it's added to your collection log, the item becomes unlocked.

### Derived Items
Items crafted from collection log items are also restricted until you unlock their dependencies. For example:

- **Dragon sq shield** requires **Shield left half** (collection log item)
- **Amulet of torture** requires **Zenyte shard** (collection log item)
- **Toxic blowpipe** requires **Tanzanite fang** (collection log item)

The plugin includes ~627 derived items with their collection log dependencies pre-calculated.

### Effective Unlocking
Some collection log items can be crafted from other collection log items. The plugin handles this intelligently:

- **Onyx** can be crafted from **Uncut onyx**
- If you have Uncut onyx in your collection log, all Onyx-derived items (Amulet of fury, Berserker necklace, etc.) become available
- This works transitively: Zenyte shard + Uncut onyx unlocks all zenyte jewelry

Your sidebar unlock count still reflects your actual collection log progress.

### Restriction Logic
An item is only restricted if **all** recipes to create it require collection log items. If there's any recipe that doesn't need a clog item, the item won't be restricted.

## Features

### Side Panel
The plugin adds a side panel to manage your unlocks:
- View all unlocked collection log items
- Manually add/remove unlocks
- Reset all unlocks
- Shows unlock progress (X / 1692)

### Restrictions (Configurable)
- **Grand Exchange** - Locked items won't appear in GE search results
- **Item Usage** - Can't wear/wield/use/eat/drink locked items
- **Bank Withdraw** - Can't withdraw locked items from bank
- **Clue Items** - Optionally disable restrictions for Treasure Trail rewards

### Visual
- **Item Dimming** - Locked items appear dimmed in inventory and bank
- Configurable opacity levels (0-255)

### Notifications
- **Chat Messages** - Get notified when you unlock an item
- **Newly Available Items** - See which derived items become available after an unlock
- **Required Clogs** - When blocked from using an item, shows which collection log items you need

## Syncing Your Collection Log

When you first install the plugin, it doesn't know what you've already unlocked. To sync:

1. Open your Collection Log in-game
2. Browse through each tab
3. The plugin scans each page you view and syncs obtained items

You'll see a message: "Clogman: Synced X items from collection log. Browse all tabs to sync everything!"

New unlocks are detected automatically via the "New item added to your collection log" chat message.

## Configuration

Access settings via RuneLite's configuration panel:

### Restrictions
| Setting | Description | Default |
|---------|-------------|---------|
| Restrict Grand Exchange | Hide locked items from GE search | On |
| Restrict Item Usage | Block using/wearing locked items | On |
| Restrict Bank Withdraw | Block withdrawing locked items | On |
| Restrict Clue Items | Restrict Treasure Trail rewards | On |

### Notifications
| Setting | Description | Default |
|---------|-------------|---------|
| Chat Message on Unlock | Send chat message on unlock | On |
| Show Newly Available Items | Show derived items unlocked | On |

### Visual
| Setting | Description | Default |
|---------|-------------|---------|
| Inventory Dim Opacity | Opacity for locked items in inventory (0-255) | 70 |
| Bank Dim Opacity | Opacity for locked items in bank (0-255) | 70 |

## Data

The plugin includes a pre-generated JSON file (`clog_restrictions.json`) containing:
- **1,692** collection log items
- **627** derived items with their dependencies
- **27** clog items craftable from other clog items

This data was generated from the OSRS Wiki.

## Credits

Inspired by:
- [Another Bronzeman Mode](https://github.com/CodePanter/another-bronzeman-mode) - GE restriction approach
- [New Game Plus](https://github.com/Jetter-Work/new-game-plus) - Item usage restriction approach
