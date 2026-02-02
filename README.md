# Clogman Mode

A RuneLite plugin that restricts item usage and provides other miscellanious facilities automatically, based on Collection Log (clog) unlocks. Unlike similar restricted gamemodes, this plugin only restricts collection log items, as well as **derived items**, i.e. items that are dependent on collection log items.

## Features

- Automatic tracking of new collection log unlocks
- Configurable restrictions including item usage, bank withdrawal, grand exchange purchasing, etc.
- Plugin side panel which visually shows all unlocked clog items and allows manual addition and removals of unlocks, as well as unlock search/filtering.
- Configurable locked item dimming and gold helm icon next to user's name in chat.
- Chat notifications of newly unlocked items, including derived items which depend on new clog unlocks, as well as clog requirements upon attempted usage of restricted items.

## Syncing your collection log

When you first install the plugin, it doesn't know what you've already unlocked. To sync:

1. Open your Collection Log in-game
2. Browse through each tab
3. The plugin scans each page you view and syncs obtained items

You'll see a message: "Clogman: Synced X items from collection log. Browse all tabs to sync everything!"

New unlocks are detected automatically via the "New item added to your collection log" chat message. You may need to turn this on in your actual in-game settings.

## How it works

#### Collection Log items
Any item that appears in the Collection Log is restricted until you obtain it. Once you get the drop and it's added to your collection log, the item becomes unlocked.

#### Derived items
Items crafted from collection log items are also restricted until you unlock their dependencies. For example:

- Tormented bracelet requires: Zenyte shard, Onyx
- Confliction gauntlets requires: Zenyte shard, Onyx, Mokhaiotl cloth, Demon tear

#### Effective unlocking
Some collection log items can be crafted from other collection log items. The plugin handles this. For example: Onyx can be crafted from Uncut onyx, therefore if you have unlocked Uncut onyx, the plugin deems you have effectively locked Onyx, for dependency purposes.

#### Restriction logic
An item is only restricted if **all** recipes to create it require collection log items. If there's any recipe that doesn't need a clog item, the item won't be restricted.

#### Restricted item data
The plugin includes a pre-generated JSON file (`clog_restrictions.json`) containing collection log items, derived items with their clog dependencies, and clog items craftable from other clog items.

This data was generated from the OSRS Wiki using [osrs-clog-dependencies](https://github.com/mozjay/osrs-clog-dependencies), which was created specifically to aid this plugin.

## Notes
- This plugin was created to make the game more fun to play for *me*, rather than be a strictly defined gamemode. Still, it's designed to be highly customizable, i.e. you can add/remove unlocks as you wish.
- There may be some items that aren't/are restricted as they should be. If you notice any, feel free to raise an issue on the github repository and I'll try to incorporate them.
- At present, some items which are themselves clogs (or dependent) may be restricted, even though you can buy them from shops, e.g. obsidian equipment/armour, or regular crystal weapon seeds. I'm not intending to change this: if you'd like to use these items, or their dependents, without the collection log unlocked, you can add them as unlocks manually in the side panel.
