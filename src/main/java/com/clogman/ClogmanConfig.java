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

    @ConfigSection(
        name = "Notifications",
        description = "Configure unlock notifications",
        position = 1
    )
    String notificationsSection = "notifications";

    @ConfigSection(
        name = "Visual",
        description = "Configure visual appearance",
        position = 2
    )
    String visualSection = "visual";

    // === RESTRICTIONS SECTION ===

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

    @ConfigItem(
        keyName = "restrictClueItems",
        name = "Restrict Clue Items",
        description = "Restrict items from Treasure Trail rewards in the Collection Log",
        section = restrictionsSection,
        position = 3
    )
    default boolean restrictClueItems()
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
        description = "Show which derived items become available when unlocking a collection log item",
        section = notificationsSection,
        position = 1
    )
    default boolean showNewlyAvailable()
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
        return 70;
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
        return 70;
    }
}
