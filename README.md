# Clogman Mode

A RuneLite plugin that restricts item usage (and more!) based on Collection Log (clog) unlocks. Unlike bronzeman, this plugin only restricts collection log items, as well as _clog derived_ items.

> I made this plugin to make the game for fun to play, I hope it does the same for you. If you have any suggestions to improve the experience, raise an issue on github and let me know!

## Features

- Automatic tracking of new collection log unlocks
- Configurable restrictions including item usage, bank withdrawal, grand exchange purchasing, etc.
- Plugin side panel which shows all unlocked clog items and allows manual addition and removals of unlocks; play however you want!
- Configurable locked item dimming and gold clogman helm icon next to user's name in chat.
- Chat notifications of newly unlocked items, including derived items which depend on new clog unlocks, as well as clog requirements upon attempted usage of restricted items.
- Toggleable on-screen unlock popup whenever an unlock makes additional items available, showing how many extra items were unlocked and their names.
- Export and import of unlocks and manual locks as a JSON file from the side panel, to back them up, share a ruleset with other players, or combine unlocks from several accounts.

## Syncing your collection log
When you first install the plugin, it doesn't know what you've already unlocked. To sync:

1. Open your Collection Log in-game
2. The plugin reads every obtained item in the log and syncs your unlocks

You'll see a message: "Clogman: Synced X new items from your collection log."

Collection logs viewed from another player's house are ignored, as are Leagues, Deadman and beta worlds.

New unlocks are detected automatically via the "New item added to your collection log" chat message. You may need to turn this on in your actual in-game settings.

## Exporting and importing unlocks
The Export and Import buttons at the bottom of the side panel save and load your unlocks and manual locks as a JSON file. Importing only ever adds: imported unlocks are treated as manual, and imported locks never override your own.

## How it works

#### Collection Log items
Any item that appears in the Collection Log is restricted until you obtain it. Once you get the drop and it's added to your collection log, the item becomes unlocked.

#### Derived items
Items crafted from collection log items are also restricted until you unlock their dependencies. For example:

- Tormented bracelet requires: Zenyte shard, Onyx
- Confliction gauntlets requires: Zenyte shard, Onyx, Mokhaiotl cloth, Demon tear

#### Restriction logic
An item is only restricted if **every** way to get it requires collection log items. If there's any recipe that doesn't need a clog item, the item won't be restricted.

#### Unlock rules
The **Unlock Rules** settings decide what counts as unlocked. Tick to restrict. The defaults are recommended, but customise them however you like.

- **Clue Items** - Treasure Trail rewards.
- **Craftable-From** - clog items craftable from other clog items, e.g. Onyx from Uncut onyx. Off by default.
- **Shop-Buyable** - clog items sold in shops, e.g. Uncut onyx for tokkul. Blanket: also covers reward shop gear like Graceful and Void, where buying *is* how you earn the slot.
- **Drop-Obtainable** - derived items that also drop directly, e.g. Splitbark body from the Chaos Fanatic. Never applies to clog items, since obtaining one as a drop is what fills the slot.

Manual unlocks and locks override all of these. A manually locked item stays locked along with anything depending on it, so locking Onyx also keeps Amulet of fury locked.

#### Restricted item data
The plugin includes a pre-generated JSON file (`clog_restrictions.json`) containing collection log items, derived items with their clog dependencies, and clog items craftable from other clog items.

This data was generated from the OSRS Wiki using [osrs-clog-dependencies](https://github.com/mozjay/osrs-clog-dependencies), which was created specifically to aid this plugin. Newly added item restrictions should take around a week to be added after a new release.

