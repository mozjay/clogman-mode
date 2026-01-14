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
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

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

    @Inject
    private ScheduledExecutorService executor;

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
                    // Build name to ID mapping
                    for (Map.Entry<Integer, ClogItem> entry : collectionLogItems.entrySet())
                    {
                        itemNameToId.put(entry.getValue().name.toLowerCase(), entry.getKey());
                    }
                    log.info("Loaded {} collection log items", collectionLogItems.size());
                }

                if (data.derivedItems != null)
                {
                    derivedItems = data.derivedItems;
                    // Build ID to derived item mapping
                    for (DerivedItem derived : derivedItems.values())
                    {
                        if (derived.itemId != null)
                        {
                            derivedItemsById.put(derived.itemId, derived);
                        }
                    }
                    log.info("Loaded {} derived items ({} with IDs)", derivedItems.size(), derivedItemsById.size());
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
            clientThread.invokeLater(() -> {
                loadUnlockedItems();
                recalculateAvailableItems();
            });
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            unlockedClogItems.clear();
            availableItems.clear();
        }
    }

    private void loadUnlockedItems()
    {
        unlockedClogItems.clear();
        String playerName = getPlayerConfigKey();
        if (playerName == null)
        {
            return;
        }

        String savedData = configManager.getConfiguration(CONFIG_GROUP, playerName + "." + UNLOCKED_ITEMS_KEY);
        if (savedData != null && !savedData.isEmpty())
        {
            try
            {
                Type type = new TypeToken<Set<Integer>>(){}.getType();
                Set<Integer> loaded = gson.fromJson(savedData, type);
                if (loaded != null)
                {
                    unlockedClogItems.addAll(loaded);
                    log.info("Loaded {} unlocked items for player {}", unlockedClogItems.size(), playerName);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load unlocked items", e);
            }
        }
    }

    private void saveUnlockedItems()
    {
        String playerName = getPlayerConfigKey();
        if (playerName == null)
        {
            return;
        }

        String json = gson.toJson(unlockedClogItems);
        configManager.setConfiguration(CONFIG_GROUP, playerName + "." + UNLOCKED_ITEMS_KEY, json);
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

        // All unlocked clog items are available
        availableItems.addAll(unlockedClogItems);

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

                if (allDepsUnlocked && derived.itemId != null)
                {
                    availableItems.add(derived.itemId);
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
        if (!collectionLogItems.containsKey(itemId) && !derivedItemsById.containsKey(itemId))
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

        // Filter out actions on locked items
        MenuEntry entry = event.getMenuEntry();
        int itemId = entry.getIdentifier();

        // Check if this is an item-related action
        MenuAction type = entry.getType();
        if (isItemAction(type))
        {
            // Get item ID from the widget if possible
            Widget widget = entry.getWidget();
            if (widget != null)
            {
                itemId = widget.getItemId();
            }

            if (itemId > 0 && isItemLocked(itemId))
            {
                String option = Text.removeTags(entry.getOption()).toLowerCase();
                // Block usage actions on locked items
                if (isRestrictedAction(option))
                {
                    entry.setDeprioritized(true);
                }
            }
        }
    }

    private boolean isItemAction(MenuAction type)
    {
        return type == MenuAction.CC_OP ||
               type == MenuAction.CC_OP_LOW_PRIORITY ||
               type == MenuAction.ITEM_USE ||
               type == MenuAction.ITEM_FIRST_OPTION ||
               type == MenuAction.ITEM_SECOND_OPTION ||
               type == MenuAction.ITEM_THIRD_OPTION ||
               type == MenuAction.ITEM_FOURTH_OPTION ||
               type == MenuAction.ITEM_FIFTH_OPTION;
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
               option.equals("open");
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

        // GE search results come in pairs: item icon and item name
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
                    children[i].setHidden(true);     // Icon background
                    children[i + 1].setHidden(true); // Item sprite
                    children[i + 2].setHidden(true); // Item name
                }
            }
        }
    }

    // === BANK WITHDRAW RESTRICTION ===

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!config.restrictBankWithdraw())
        {
            return;
        }

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

    // Collection log widget group ID
    private static final int COLLECTION_LOG_GROUP_ID = 621;
    // Widget IDs within collection log
    private static final int COLLECTION_LOG_ITEM_CONTAINER = 621 << 16 | 35;

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == COLLECTION_LOG_GROUP_ID)
        {
            collectionLogOpen = true;
            // Scan collection log when it's opened
            clientThread.invokeLater(this::scanCollectionLog);
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == COLLECTION_LOG_GROUP_ID)
        {
            collectionLogOpen = false;
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        // Script 2730 is fired when collection log entries are updated (changing tabs/pages)
        if (event.getScriptId() == 2730 && collectionLogOpen)
        {
            clientThread.invokeLater(this::scanCollectionLog);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
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
     * Scans the currently visible collection log page for obtained items.
     * Users should browse through their collection log to sync their unlocks.
     */
    private void scanCollectionLog()
    {
        Widget itemContainer = client.getWidget(COLLECTION_LOG_ITEM_CONTAINER);
        if (itemContainer == null)
        {
            return;
        }

        Widget[] items = itemContainer.getDynamicChildren();
        if (items == null)
        {
            return;
        }

        int newUnlocks = 0;
        for (Widget item : items)
        {
            int itemId = item.getItemId();
            if (itemId <= 0)
            {
                continue;
            }

            // Items with opacity 0 are obtained, greyed out items have higher opacity
            boolean isObtained = item.getOpacity() == 0;

            if (isObtained && collectionLogItems.containsKey(itemId))
            {
                if (unlockedClogItems.add(itemId))
                {
                    newUnlocks++;
                }
            }
        }

        if (newUnlocks > 0)
        {
            log.info("Scanned collection log page, found {} new unlocks", newUnlocks);
            saveUnlockedItems();
            recalculateAvailableItems();

            if (!hasScannedCollectionLog)
            {
                sendSyncMessage(newUnlocks);
            }
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
    }

    public static class DerivedItem
    {
        public String name;
        @SerializedName("item_id")
        public Integer itemId;
        @SerializedName("clog_dependencies")
        public List<Integer> clogDependencies;
    }
}
