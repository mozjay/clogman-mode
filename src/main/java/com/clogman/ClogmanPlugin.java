package com.clogman;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.*;

@Slf4j
@PluginDescriptor(
    name = "Clogman Mode",
    description = "Restricts item usage based on collection log unlocks and item dependencies",
    tags = {"collection", "log", "clog", "bronzeman", "restriction", "ironman"}
)
public class ClogmanPlugin extends Plugin
{
    private static final String CONFIG_GROUP = "clogman";
    private static final String UNLOCKED_ITEMS_KEY = "unlockedItems";

    // Script ID for collection log draw (fires when changing tabs/pages)
    private static final int COLLECTION_LOG_DRAW_LIST_SCRIPT = 2731;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClogmanConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Gson gson;

    @Inject
    private ClogmanOverlay overlay;

    // Collection log items loaded from JSON (id -> ClogItem)
    @Getter
    private Map<Integer, ClogItem> collectionLogItems = new HashMap<>();

    // Derived items loaded from JSON (name -> DerivedItem)
    @Getter
    private Map<String, DerivedItem> derivedItems = new HashMap<>();

    // Set of unlocked collection log item IDs for the current player
    @Getter
    private Set<Integer> unlockedClogItems = new HashSet<>();

    // Cache of items that are currently available (unlocked or dependencies met)
    @Getter
    private Set<Integer> availableItems = new HashSet<>();

    // Name to ID mapping for quick lookups (clog items)
    private Map<String, Integer> itemNameToId = new HashMap<>();

    // Map any clog item ID (including variants) to its primary ID
    private Map<Integer, Integer> clogIdToPrimaryId = new HashMap<>();

    // Derived items by ID for quick lookups
    private Map<Integer, DerivedItem> derivedItemsById = new HashMap<>();

    // Track if we've done initial sync from collection log
    private boolean hasScannedCollectionLog = false;

    // Track collection log interface state
    private boolean collectionLogOpen = false;

    @Override
    protected void startUp() throws Exception
    {
        loadRestrictionData();
        overlayManager.add(overlay);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loadUnlockedItems();
            recalculateAvailableItems();
        }
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        unlockedClogItems.clear();
        availableItems.clear();
    }

    @Provides
    ClogmanConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ClogmanConfig.class);
    }

    private void loadRestrictionData()
    {
        try (InputStream is = getClass().getResourceAsStream("/clog_restrictions.json"))
        {
            if (is == null)
            {
                log.error("Could not find clog_restrictions.json in resources");
                return;
            }

            InputStreamReader reader = new InputStreamReader(is);
            Type type = new TypeToken<RestrictionData>(){}.getType();
            RestrictionData data = gson.fromJson(reader, type);

            if (data != null)
            {
                if (data.collectionLogItems != null)
                {
                    collectionLogItems = data.collectionLogItems;
                    // Build name to ID mapping and all-IDs to primary mapping
                    int totalClogIdMappings = 0;
                    for (Map.Entry<Integer, ClogItem> entry : collectionLogItems.entrySet())
                    {
                        Integer primaryId = entry.getKey();
                        ClogItem clogItem = entry.getValue();

                        itemNameToId.put(clogItem.name.toLowerCase(), primaryId);

                        // Map all variant IDs to this primary ID
                        for (Integer variantId : clogItem.getAllIds())
                        {
                            clogIdToPrimaryId.put(variantId, primaryId);
                            totalClogIdMappings++;
                        }
                        // Also ensure primary ID is mapped
                        if (!clogIdToPrimaryId.containsKey(primaryId))
                        {
                            clogIdToPrimaryId.put(primaryId, primaryId);
                            totalClogIdMappings++;
                        }
                    }
                    log.info("Loaded {} collection log items ({} ID mappings)", collectionLogItems.size(), totalClogIdMappings);
                }

                if (data.derivedItems != null)
                {
                    derivedItems = data.derivedItems;
                    // Build ID to derived item mapping (including all variant IDs)
                    int totalIdMappings = 0;
                    for (DerivedItem derived : derivedItems.values())
                    {
                        for (Integer id : derived.getAllItemIds())
                        {
                            derivedItemsById.put(id, derived);
                            totalIdMappings++;
                        }
                    }
                    log.info("Loaded {} derived items ({} ID mappings)", derivedItems.size(), totalIdMappings);
                }
            }
        }
        catch (Exception e)
        {
            log.error("Failed to load restriction data", e);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Use invokeLater with retry - player object may not be ready immediately
            clientThread.invokeLater(this::tryLoadUnlockedItems);
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            unlockedClogItems.clear();
            availableItems.clear();
            hasScannedCollectionLog = false;
        }
    }

    /**
     * Attempts to load unlocked items. Returns true if successful (to stop retrying).
     * RuneLite's invokeLater will keep retrying while this returns false.
     */
    private boolean tryLoadUnlockedItems()
    {
        // Check if player is ready
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
        {
            // Player not ready yet - return false to retry
            return false;
        }

        loadUnlockedItems();
        recalculateAvailableItems();

        // Send reminder to open collection log if no items synced yet
        if (unlockedClogItems.isEmpty())
        {
            sendReminderMessage();
        }

        return true; // Stop retrying
    }

    private void sendReminderMessage()
    {
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("Clogman Mode: ")
            .append(ChatColorType.NORMAL)
            .append("Open your Collection Log and browse tabs to sync your unlocks!")
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(message)
            .build());
    }

    private void loadUnlockedItems()
    {
        unlockedClogItems.clear();
        String playerName = getPlayerConfigKey();
        if (playerName == null)
        {
            log.warn("Cannot load unlocked items - player name is null");
            return;
        }

        String configKey = playerName + "." + UNLOCKED_ITEMS_KEY;
        String savedData = configManager.getConfiguration(CONFIG_GROUP, configKey);
        log.debug("Loading config from key: {}.{}", CONFIG_GROUP, configKey);

        if (savedData != null && !savedData.isEmpty())
        {
            try
            {
                Type type = new TypeToken<Set<Integer>>(){}.getType();
                Set<Integer> loaded = gson.fromJson(savedData, type);
                if (loaded != null)
                {
                    unlockedClogItems.addAll(loaded);
                    log.info("Loaded {} unlocked items for player {} from saved config", unlockedClogItems.size(), playerName);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load unlocked items", e);
            }
        }
        else
        {
            log.info("No saved unlocked items found for player {} (first run or data cleared)", playerName);
        }
    }

    private void saveUnlockedItems()
    {
        String playerName = getPlayerConfigKey();
        if (playerName == null)
        {
            log.warn("Cannot save unlocked items - player name is null");
            return;
        }

        String configKey = playerName + "." + UNLOCKED_ITEMS_KEY;
        String json = gson.toJson(unlockedClogItems);
        configManager.setConfiguration(CONFIG_GROUP, configKey, json);
        log.debug("Saved {} unlocked items to config key: {}.{}", unlockedClogItems.size(), CONFIG_GROUP, configKey);
    }

    private String getPlayerConfigKey()
    {
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return null;
        }
        return player.getName();
    }

    /**
     * Recalculates which items are available based on current unlocks
     */
    public void recalculateAvailableItems()
    {
        availableItems.clear();

        // All unlocked clog items and their variants are available
        for (Integer primaryId : unlockedClogItems)
        {
            ClogItem clogItem = collectionLogItems.get(primaryId);
            if (clogItem != null)
            {
                // Add all variant IDs for this clog item
                availableItems.addAll(clogItem.getAllIds());
            }
            // Also add the primary ID itself
            availableItems.add(primaryId);
        }

        // Check derived items - available if all clog dependencies are unlocked
        for (Map.Entry<String, DerivedItem> entry : derivedItems.entrySet())
        {
            DerivedItem derived = entry.getValue();
            if (derived.clogDependencies != null && !derived.clogDependencies.isEmpty())
            {
                boolean allDepsUnlocked = true;
                for (int depId : derived.clogDependencies)
                {
                    if (!unlockedClogItems.contains(depId))
                    {
                        allDepsUnlocked = false;
                        break;
                    }
                }

                if (allDepsUnlocked)
                {
                    // Add all variant IDs (e.g., different imbue sources)
                    availableItems.addAll(derived.getAllItemIds());
                }
            }
        }

        log.debug("Recalculated available items: {} total", availableItems.size());
    }

    /**
     * Unlocks a collection log item
     */
    public void unlockItem(int itemId)
    {
        if (!collectionLogItems.containsKey(itemId))
        {
            return;
        }

        if (unlockedClogItems.add(itemId))
        {
            ClogItem item = collectionLogItems.get(itemId);
            log.info("Unlocked collection log item: {} (ID: {})", item.name, itemId);

            saveUnlockedItems();
            recalculateAvailableItems();

            if (config.chatMessageOnUnlock())
            {
                sendUnlockMessage(item.name);
            }
        }
    }

    private void sendUnlockMessage(String itemName)
    {
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("Clogman: ")
            .append(ChatColorType.NORMAL)
            .append("Unlocked ")
            .append(ChatColorType.HIGHLIGHT)
            .append(itemName)
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(message)
            .build());
    }

    /**
     * Checks if an item is available (unlocked or dependencies met)
     */
    public boolean isItemAvailable(int itemId)
    {
        // Items not in our data are always available
        // Check if it's a clog item (primary or variant) or a derived item
        boolean isClogItem = clogIdToPrimaryId.containsKey(itemId);
        boolean isDerivedItem = derivedItemsById.containsKey(itemId);

        if (!isClogItem && !isDerivedItem)
        {
            return true;
        }

        return availableItems.contains(itemId);
    }

    /**
     * Checks if an item is locked (restricted)
     */
    public boolean isItemLocked(int itemId)
    {
        return !isItemAvailable(itemId);
    }

    // === MENU ENTRY FILTERING FOR USAGE RESTRICTION ===

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (!config.restrictItemUsage())
        {
            return;
        }

        MenuEntry entry = event.getMenuEntry();
        int itemId = getItemIdFromMenuEntry(entry);

        if (itemId > 0 && isItemLocked(itemId))
        {
            String option = Text.removeTags(entry.getOption()).toLowerCase();
            if (isRestrictedAction(option))
            {
                // Gray out the option and deprioritize it
                entry.setOption(ColorUtil.prependColorTag(entry.getOption(), Color.GRAY));
                entry.setDeprioritized(true);
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        // Block usage of locked items
        if (config.restrictItemUsage())
        {
            String option = Text.removeTags(event.getMenuOption()).toLowerCase();
            if (isRestrictedAction(option))
            {
                int itemId = event.getItemId();
                if (itemId > 0 && isItemLocked(itemId))
                {
                    event.consume();
                    sendLockedMessage("use", itemId);
                    return;
                }
            }
        }

        // Block bank withdrawal of locked items
        if (config.restrictBankWithdraw())
        {
            String option = Text.removeTags(event.getMenuOption()).toLowerCase();
            if (option.startsWith("withdraw"))
            {
                int itemId = event.getItemId();
                if (itemId > 0 && isItemLocked(itemId))
                {
                    event.consume();
                    sendLockedMessage("withdraw", itemId);
                }
            }
        }
    }

    private int getItemIdFromMenuEntry(MenuEntry entry)
    {
        int itemId = entry.getIdentifier();

        // Try to get item ID from widget
        Widget widget = entry.getWidget();
        if (widget != null)
        {
            int widgetItemId = widget.getItemId();
            if (widgetItemId > 0)
            {
                itemId = widgetItemId;
            }
        }

        // Also check itemId field directly
        if (itemId <= 0)
        {
            itemId = entry.getItemId();
        }

        return itemId;
    }

    private boolean isRestrictedAction(String option)
    {
        return option.equals("wear") ||
               option.equals("wield") ||
               option.equals("equip") ||
               option.equals("eat") ||
               option.equals("drink") ||
               option.equals("use") ||
               option.equals("read") ||
               option.equals("open") ||
               option.equals("check") ||
               option.equals("rub") ||
               option.equals("break") ||
               option.equals("activate") ||
               option.equals("commune");
    }

    // === GRAND EXCHANGE RESTRICTION ===

    @Subscribe
    public void onGrandExchangeSearched(GrandExchangeSearched event)
    {
        if (!config.restrictGrandExchange())
        {
            return;
        }

        clientThread.invokeLater(this::filterGrandExchangeResults);
    }

    private void filterGrandExchangeResults()
    {
        Widget grandExchangeSearchResults = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (grandExchangeSearchResults == null)
        {
            return;
        }

        Widget[] children = grandExchangeSearchResults.getDynamicChildren();
        if (children == null)
        {
            return;
        }

        // GE search results come in groups of 3: background, item sprite, item name
        for (int i = 0; i < children.length; i += 3)
        {
            if (i + 2 >= children.length)
            {
                break;
            }

            Widget itemWidget = children[i + 2];
            if (itemWidget != null)
            {
                int itemId = itemWidget.getItemId();
                if (itemId > 0 && isItemLocked(itemId))
                {
                    // Hide locked items from search results
                    children[i].setHidden(true);     // Background
                    children[i + 1].setHidden(true); // Item sprite
                    children[i + 2].setHidden(true); // Item name
                }
            }
        }
    }

    private void sendLockedMessage(String action, int itemId)
    {
        String itemName = itemManager.getItemComposition(itemId).getName();
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("Clogman: ")
            .append(ChatColorType.NORMAL)
            .append("Cannot " + action + " ")
            .append(ChatColorType.HIGHLIGHT)
            .append(itemName)
            .append(ChatColorType.NORMAL)
            .append(" - item is locked!")
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(message)
            .build());
    }

    // === COLLECTION LOG DETECTION ===

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.COLLECTION_LOG)
        {
            collectionLogOpen = true;
            log.debug("Collection log opened");
            // Scan collection log when it's opened
            clientThread.invokeLater(this::scanCollectionLog);
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == InterfaceID.COLLECTION_LOG)
        {
            collectionLogOpen = false;
            log.debug("Collection log closed");
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        // Script 2731 is fired when collection log list is drawn (changing tabs/pages)
        if (event.getScriptId() == COLLECTION_LOG_DRAW_LIST_SCRIPT && collectionLogOpen)
        {
            clientThread.invokeLater(this::scanCollectionLog);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        // Handle debug commands (:: commands are processed locally, not broadcast)
        if (event.getType() == ChatMessageType.PUBLICCHAT)
        {
            String message = Text.removeTags(event.getMessage()).toLowerCase().trim();
            if (message.startsWith("::clogman"))
            {
                handleCommand(message);
                return;
            }
        }

        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String message = event.getMessage();
        // Check for collection log unlock message
        if (message.contains("New item added to your collection log:"))
        {
            // Extract item name from message
            int startIdx = message.indexOf(":") + 2;
            String itemName = Text.removeTags(message.substring(startIdx)).trim();

            // Find and unlock the item
            Integer itemId = itemNameToId.get(itemName.toLowerCase());
            if (itemId != null)
            {
                unlockItem(itemId);
            }
            else
            {
                log.warn("Could not find item ID for unlocked item: {}", itemName);
            }
        }
    }

    /**
     * Handle debug/admin commands
     */
    private void handleCommand(String message)
    {
        String[] parts = message.split(" ");
        String command = parts[0];

        switch (command)
        {
            case "::clogmanreset":
                handleResetCommand();
                break;

            case "::clogmanunlock":
                if (parts.length >= 2)
                {
                    handleUnlockCommand(parts[1]);
                }
                else
                {
                    sendCommandMessage("Usage: ::clogmanunlock <item_id>");
                }
                break;

            case "::clogmanstatus":
                handleStatusCommand();
                break;

            case "::clogmanhelp":
                sendCommandMessage("Commands: ::clogmanreset, ::clogmanunlock <id>, ::clogmanstatus");
                break;

            default:
                sendCommandMessage("Unknown command. Try ::clogmanhelp");
                break;
        }
    }

    private void handleResetCommand()
    {
        int count = unlockedClogItems.size();
        unlockedClogItems.clear();
        availableItems.clear();
        saveUnlockedItems();
        recalculateAvailableItems();
        sendCommandMessage("Reset complete. Cleared " + count + " unlocked items.");
    }

    private void handleUnlockCommand(String idStr)
    {
        try
        {
            int itemId = Integer.parseInt(idStr);

            if (!collectionLogItems.containsKey(itemId))
            {
                sendCommandMessage("Item ID " + itemId + " is not a collection log item.");
                return;
            }

            if (unlockedClogItems.contains(itemId))
            {
                sendCommandMessage("Item " + collectionLogItems.get(itemId).name + " is already unlocked.");
                return;
            }

            // Unlock the item (this triggers notification)
            unlockItem(itemId);
            sendCommandMessage("Manually unlocked: " + collectionLogItems.get(itemId).name + " (ID: " + itemId + ")");
        }
        catch (NumberFormatException e)
        {
            sendCommandMessage("Invalid item ID: " + idStr);
        }
    }

    private void handleStatusCommand()
    {
        int clogUnlocked = unlockedClogItems.size();
        int totalClog = collectionLogItems.size();
        int availableCount = availableItems.size();

        sendCommandMessage("Clogman Status:");
        sendCommandMessage("  Unlocked: " + clogUnlocked + "/" + totalClog + " collection log items");
        sendCommandMessage("  Available: " + availableCount + " total items (including derived)");
    }

    private void sendCommandMessage(String text)
    {
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("[Clogman] ")
            .append(ChatColorType.NORMAL)
            .append(text)
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(message)
            .build());
    }

    /**
     * Scans the currently visible collection log page for obtained items.
     * Users should browse through their collection log to sync their unlocks.
     */
    private void scanCollectionLog()
    {
        // Use ComponentID.COLLECTION_LOG_ENTRY_ITEMS (40697893 = 621 << 16 | 37)
        Widget itemContainer = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
        if (itemContainer == null)
        {
            log.debug("Collection log item container widget not found");
            return;
        }

        Widget[] items = itemContainer.getDynamicChildren();
        if (items == null || items.length == 0)
        {
            log.debug("No items found in collection log widget");
            return;
        }

        int newUnlocks = 0;
        int scannedItems = 0;

        for (Widget item : items)
        {
            int itemId = item.getItemId();
            if (itemId <= 0)
            {
                continue;
            }

            scannedItems++;

            // Items with opacity 0 are obtained, greyed out items have higher opacity
            boolean isObtained = item.getOpacity() == 0;

            if (isObtained && collectionLogItems.containsKey(itemId))
            {
                if (unlockedClogItems.add(itemId))
                {
                    ClogItem clogItem = collectionLogItems.get(itemId);
                    log.debug("Found obtained item: {} (ID: {})", clogItem.name, itemId);
                    newUnlocks++;
                }
            }
        }

        log.debug("Scanned {} items, found {} new unlocks", scannedItems, newUnlocks);

        if (newUnlocks > 0)
        {
            log.info("Scanned collection log page, found {} new unlocks (total: {})", newUnlocks, unlockedClogItems.size());
            saveUnlockedItems();
            recalculateAvailableItems();
            sendSyncMessage(newUnlocks);
        }

        hasScannedCollectionLog = true;
    }

    private void sendSyncMessage(int count)
    {
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("Clogman: ")
            .append(ChatColorType.NORMAL)
            .append("Synced " + count + " items from collection log. Browse all tabs to sync everything!")
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(message)
            .build());
    }

    /**
     * Returns the number of unlocked collection log items
     */
    public int getUnlockedCount()
    {
        return unlockedClogItems.size();
    }

    /**
     * Returns the total number of collection log items
     */
    public int getTotalClogItems()
    {
        return collectionLogItems.size();
    }

    // === DATA CLASSES ===

    public static class RestrictionData
    {
        public Map<Integer, ClogItem> collectionLogItems;
        public Map<String, DerivedItem> derivedItems;
    }

    public static class ClogItem
    {
        public String name;
        public List<String> tabs;
        @SerializedName("all_ids")
        public List<Integer> allIds;  // All variant IDs for this clog item (e.g., new/used states)

        /**
         * Get all valid item IDs for this clog item.
         */
        public List<Integer> getAllIds()
        {
            return allIds != null ? allIds : java.util.Collections.emptyList();
        }
    }

    public static class DerivedItem
    {
        public String name;
        @SerializedName("item_ids")
        public List<Integer> itemIds;  // All valid item IDs for this derived item
        @SerializedName("clog_dependencies")
        public List<Integer> clogDependencies;

        /**
         * Get all valid item IDs for this derived item.
         */
        public List<Integer> getAllItemIds()
        {
            return itemIds != null ? itemIds : java.util.Collections.emptyList();
        }
    }
}
