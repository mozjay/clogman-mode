package com.clogman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("clogman")
public interface ClogmanConfig extends Config
{
    @ConfigSection(
        name = "Restrictions",
        description = "Configure what restrictions are enabled",
        position = 0
    )
    String restrictionsSection = "restrictions";

    // The "(tick to restrict)" belongs in the name, not just the description:
    // the description is hover-only, and "Unlock Rules" over a row of ticked
    // boxes otherwise reads as "these are unlocked" - the opposite of what a
    // ticked box does here.
    @ConfigSection(
        name = "Unlock Rules (tick to restrict)",
        description = "What counts as unlocked, rather than where restrictions apply",
        position = 1
    )
    String unlockRulesSection = "unlockRules";

    @ConfigSection(
        name = "Notifications",
        description = "Configure unlock notifications",
        position = 2
    )
    String notificationsSection = "notifications";

    @ConfigSection(
        name = "Visual",
        description = "Configure visual appearance",
        position = 3
    )
    String visualSection = "visual";

    // === RESTRICTIONS SECTION ===
    // Where restrictions are enforced. What counts as locked in the first
    // place is decided by the Unlock Rules section below.

    @ConfigItem(
        keyName = "restrictGrandExchange",
        name = "Restrict Grand Exchange",
        description = "Prevent buying locked items from the Grand Exchange",
        section = restrictionsSection,
        position = 0
    )
    default boolean restrictGrandExchange()
    {
        return true;
    }

    @ConfigItem(
        keyName = "restrictItemUsage",
        name = "Restrict Item Usage",
        description = "Prevent using/wearing/wielding locked items",
        section = restrictionsSection,
        position = 1
    )
    default boolean restrictItemUsage()
    {
        return true;
    }

    @ConfigItem(
        keyName = "restrictBankWithdraw",
        name = "Restrict Bank Withdraw",
        description = "Prevent withdrawing locked items from bank",
        section = restrictionsSection,
        position = 2
    )
    default boolean restrictBankWithdraw()
    {
        return true;
    }

    // === UNLOCK RULES SECTION ===
    // These decide what counts as an unlock, and so what is locked at all.
    // Ticked always means "restrict", so the polarity matches the section
    // above even though the names drop the prefix.

    @ConfigItem(
        keyName = "restrictClueItems",
        name = "Clue Items",
        description = "Restrict items from Treasure Trail rewards in the Collection Log",
        section = unlockRulesSection,
        position = 0
    )
    default boolean restrictClueItems()
    {
        return true;
    }

    @ConfigItem(
        keyName = "restrictCraftableUnlocks",
        name = "Craftable-From",
        description = "Require collection log items to be unlocked directly, not via crafting, e.g. Onyx from Uncut onyx",
        section = unlockRulesSection,
        position = 1
    )
    default boolean restrictCraftableUnlocks()
    {
        return false;
    }

    @ConfigItem(
        keyName = "restrictShopBuyable",
        name = "Shop-Buyable",
        description = "Restrict collection log items sold in shops, e.g. Uncut onyx for tokkul",
        section = unlockRulesSection,
        position = 2
    )
    default boolean restrictShopBuyable()
    {
        return true;
    }

    @ConfigItem(
        keyName = "restrictDropObtainable",
        name = "Drop-Obtainable*",
        description = "Restrict derived items with their own drop source, e.g. Splitbark body from Chaos Fanatic",
        section = unlockRulesSection,
        position = 3
    )
    default boolean restrictDropObtainable()
    {
        return true;
    }

    // === NOTIFICATIONS SECTION ===

    @ConfigItem(
        keyName = "chatMessageOnUnlock",
        name = "Chat Message on Unlock",
        description = "Send a chat message when an item is unlocked",
        section = notificationsSection,
        position = 0
    )
    default boolean chatMessageOnUnlock()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showNewlyAvailable",
        name = "Show Newly Available Items",
        description = "Send a chat message listing the additional items an unlock makes available",
        section = notificationsSection,
        position = 1
    )
    default boolean showNewlyAvailable()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showUnlockPopup",
        name = "Show Unlock Popup",
        description = "Show an on-screen popup beneath the collection log popup listing the additional items an unlock makes available",
        section = notificationsSection,
        position = 2
    )
    default boolean showUnlockPopup()
    {
        return true;
    }

    // === VISUAL SECTION ===

    @ConfigItem(
        keyName = "inventoryOpacity",
        name = "Inventory Dim Opacity",
        description = "Opacity level for locked items in inventory (0 = invisible, 255 = fully visible)",
        section = visualSection,
        position = 0
    )
    @Range(min = 0, max = 255)
    default int inventoryOpacity()
    {
        return 100;
    }

    @ConfigItem(
        keyName = "bankOpacity",
        name = "Bank Dim Opacity",
        description = "Opacity level for locked items in bank (0 = invisible, 255 = fully visible)",
        section = visualSection,
        position = 1
    )
    @Range(min = 0, max = 255)
    default int bankOpacity()
    {
        return 100;
    }

    @ConfigItem(
        keyName = "showChatIcon",
        name = "Show Chat Icon",
        description = "Display the Clogman icon before your name in chat",
        section = visualSection,
        position = 2
    )
    default boolean showChatIcon()
    {
        return false;
    }
}
