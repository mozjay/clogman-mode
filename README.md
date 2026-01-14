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

The plugin includes ~700 derived items with their collection log dependencies pre-calculated.

### Restriction Logic
An item is only restricted if **all** recipes to create it require collection log items. If there's any recipe that doesn't need a clog item, the item won't be restricted.

## Features

### Restrictions (Configurable)
- **Grand Exchange** - Locked items won't appear in GE search results
- **Item Usage** - Can't wear/wield/use/eat/drink locked items
- **Bank Withdraw** - Can't withdraw locked items from bank

### Visual
- **Item Dimming** - Locked items appear dimmed in inventory and bank
- Configurable opacity levels (0-255)

### Notifications
- **Chat Messages** - Get notified when you unlock an item
- **Screenshots** - Optionally take a screenshot on unlock (disabled by default)

## Syncing Your Collection Log

When you first install the plugin, it doesn't know what you've already unlocked. To sync:

1. Open your Collection Log in-game
2. Browse through each tab
3. The plugin scans each page you view and syncs obtained items

You'll see a message: "Clogman: Synced X items from collection log. Browse all tabs to sync everything!"

New unlocks are detected automatically via the "New item added to your collection log" chat message.

## Configuration

Access settings via RuneLite's configuration panel:

| Setting | Description | Default |
|---------|-------------|---------|
| Restrict Grand Exchange | Hide locked items from GE search | On |
| Restrict Item Usage | Block using/wearing locked items | On |
| Restrict Bank Withdraw | Block withdrawing locked items | On |
| Show Unlock Popup | Display popup on unlock | On |
| Chat Message on Unlock | Send chat message on unlock | On |
| Screenshot on Unlock | Take screenshot on unlock | Off |
| Inventory Dim Opacity | Opacity for locked items in inventory | 70 |
| Bank Dim Opacity | Opacity for locked items in bank | 70 |

## Data

The plugin includes a pre-generated JSON file (`clog_restrictions.json`) containing:
- **1,692** collection log items
- **697** derived items with their dependencies

This data was generated from the OSRS Wiki using the companion Python script in the `osrs_clog_dependencies` folder.

## Building

```bash
./gradlew build
```

The JAR will be created at `build/libs/clogman-mode-1.0.0.jar`

## Credits

Inspired by:
- [Another Bronzeman Mode](https://github.com/CodePanter/another-bronzeman-mode) - GE restriction approach
- [New Game Plus](https://github.com/Jetter-Work/new-game-plus) - Item usage restriction approach
