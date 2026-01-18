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
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
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
    private static final String MANUALLY_ADDED_KEY = "manuallyAdded";
    private static final String MANUALLY_REMOVED_KEY = "manuallyRemoved";

    // Script ID for collection log draw (fires when changing tabs/pages)
    private static final int COLLECTION_LOG_DRAW_LIST_SCRIPT = 2731;

    // Actions that should be restricted for locked items (O(1) lookup)
    private static final Set<String> RESTRICTED_ACTIONS = Set.of(
        "wear", "wield", "equip", "eat", "drink", "use",
        "read", "open", "check", "rub", "break", "activate", "commune"
    );

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
    private ClientToolbar clientToolbar;

    @Inject
    private ChatboxItemSearch chatboxItemSearch;

    @Inject
    private Gson gson;

    @Inject
    private ClogmanOverlay overlay;

    private ClogmanPanel panel;
    private NavigationButton navButton;

    // Collection log items loaded from JSON (id -> ClogItem)
    @Getter
    private Map<Integer, ClogItem> collectionLogItems = new HashMap<>();

    // Derived items loaded from JSON (name -> DerivedItem)
    @Getter
    private Map<String, DerivedItem> derivedItems = new HashMap<>();

    // Set of unlocked collection log item IDs for the current player
    private Set<Integer> unlockedClogItems = new HashSet<>();

    // Track manually added unlocks (user added via panel, not from clog scan)
    @Getter
    private Set<Integer> manuallyAdded = new HashSet<>();

    // Track manually removed/locked items (user locked via panel despite being in clog)
    @Getter
    private Set<Integer> manuallyRemoved = new HashSet<>();

    // Cache of items that are currently available (unlocked or dependencies met)
    @Getter
    private Set<Integer> availableItems = new HashSet<>();

    // Name to ID mapping for quick lookups (clog items)
    private Map<String, Integer> itemNameToId = new HashMap<>();

    // Map any clog item ID (including variants) to its primary ID
    private Map<Integer, Integer> clogIdToPrimaryId = new HashMap<>();

    // Derived items by ID for quick lookups
    private Map<Integer, DerivedItem> derivedItemsById = new HashMap<>();

    // Track collection log interface state
    private boolean collectionLogOpen = false;

    // Chat icon offset in the modIcons array (-1 means not loaded yet)
    private int chatIconOffset = -1;

    @Override
    protected void startUp() throws Exception
    {
        loadRestrictionData();
        overlayManager.add(overlay);

        // Create and register the side panel
        panel = new ClogmanPanel(this, itemManager, client, clientThread, chatboxItemSearch);

        // Load the sidebar icon
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/clogman-icon.png");

        navButton = NavigationButton.builder()
            .tooltip("Clogman Mode")
            .icon(icon)
            .priority(6)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loadUnlockedItems();
            recalculateAvailableItems();
            panel.refresh();
        }
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        clientToolbar.removeNavigation(navButton);
        unlockedClogItems.clear();
        manuallyAdded.clear();
        manuallyRemoved.clear();
        availableItems.clear();
        panel = null;
        navButton = null;
        chatIconOffset = -1;
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

            try (InputStreamReader reader = new InputStreamReader(is))
            {
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
        }
        catch (Exception e)
        {
            log.error("Failed to load restriction data", e);
        }
    }

    /**
     * Loads the clogman chat icon into the client's mod icons array.
     * This allows using <img=X> tags in chat/widget text.
     */
    private void loadChatIcon()
    {
        final IndexedSprite[] modIcons = client.getModIcons();

        // Already loaded or not ready
        if (chatIconOffset != -1 || modIcons == null)
        {
            return;
        }

        BufferedImage image = ImageUtil.loadImageResource(getClass(), "/clogman-chat-icon.png");
        if (image == null)
        {
            log.warn("Could not load clogman chat icon");
            return;
        }

        IndexedSprite indexedSprite = ImageUtil.getImageIndexedSprite(image, client);
        if (indexedSprite == null)
        {
            log.warn("Could not convert chat icon to IndexedSprite");
            return;
        }

        chatIconOffset = modIcons.length;

        final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
        newModIcons[newModIcons.length - 1] = indexedSprite;
        client.setModIcons(newModIcons);

        log.debug("Loaded clogman chat icon at offset {}", chatIconOffset);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Load chat icon when client is ready
            clientThread.invokeLater(() -> {
                loadChatIcon();
                return true;
            });

            // Use invokeLater with retry - player object may not be ready immediately
            clientThread.invokeLater(this::tryLoadUnlockedItems);
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            unlockedClogItems.clear();
            manuallyAdded.clear();
            manuallyRemoved.clear();
            availableItems.clear();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equals(CONFIG_GROUP))
        {
            return;
        }

        // Recalculate available items when clue restriction setting changes
        if (event.getKey().equals("restrictClueItems"))
        {
            recalculateAvailableItems();
            if (panel != null)
            {
                panel.refresh();
            }
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        if (!config.showChatIcon())
        {
            return;
        }

        if (!event.getEventName().equals("setChatboxInput"))
        {
            return;
        }

        // Ensure icon is loaded
        if (chatIconOffset == -1)
        {
            loadChatIcon();
            if (chatIconOffset == -1)
            {
                return; // Still failed to load
            }
        }

        Widget chatboxInput = client.getWidget(ComponentID.CHATBOX_INPUT);
        if (chatboxInput == null)
        {
            return;
        }

        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null)
        {
            return;
        }

        String text = chatboxInput.getText();
        int colonIdx = text.indexOf(':');
        if (colonIdx == -1)
        {
            return;
        }

        // Get the current name portion (may include channel prefix like "[CC]")
        String namePortion = text.substring(0, colonIdx);

        // Avoid adding icon if already present
        if (namePortion.contains("<img=" + chatIconOffset + ">"))
        {
            return;
        }

        // Insert icon at the beginning of the name
        String newText = "<img=" + chatIconOffset + ">" + namePortion + text.substring(colonIdx);
        chatboxInput.setText(newText);
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

        if (panel != null)
        {
            panel.refresh();
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
        manuallyAdded.clear();
        manuallyRemoved.clear();

        String playerName = getPlayerConfigKey();
        if (playerName == null)
        {
            log.warn("Cannot load unlocked items - player name is null");
            return;
        }

        // Load unlocked items
        String unlockedKey = playerName + "." + UNLOCKED_ITEMS_KEY;
        String savedUnlocked = configManager.getConfiguration(CONFIG_GROUP, unlockedKey);

        if (savedUnlocked != null && !savedUnlocked.isEmpty())
        {
            try
            {
                Type type = new TypeToken<Set<Integer>>(){}.getType();
                Set<Integer> loaded = gson.fromJson(savedUnlocked, type);
                if (loaded != null)
                {
                    unlockedClogItems.addAll(loaded);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load unlocked items", e);
            }
        }

        // Load manually added items
        String manualAddKey = playerName + "." + MANUALLY_ADDED_KEY;
        String savedManualAdd = configManager.getConfiguration(CONFIG_GROUP, manualAddKey);

        if (savedManualAdd != null && !savedManualAdd.isEmpty())
        {
            try
            {
                Type type = new TypeToken<Set<Integer>>(){}.getType();
                Set<Integer> loaded = gson.fromJson(savedManualAdd, type);
                if (loaded != null)
                {
                    manuallyAdded.addAll(loaded);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load manually added items", e);
            }
        }

        // Load manually removed items
        String manualRemoveKey = playerName + "." + MANUALLY_REMOVED_KEY;
        String savedManualRemove = configManager.getConfiguration(CONFIG_GROUP, manualRemoveKey);

        if (savedManualRemove != null && !savedManualRemove.isEmpty())
        {
            try
            {
                Type type = new TypeToken<Set<Integer>>(){}.getType();
                Set<Integer> loaded = gson.fromJson(savedManualRemove, type);
                if (loaded != null)
                {
                    manuallyRemoved.addAll(loaded);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load manually removed items", e);
            }
        }

        log.info("Loaded {} unlocked items ({} manual, {} locked) for player {}",
            unlockedClogItems.size(), manuallyAdded.size(), manuallyRemoved.size(), playerName);
    }

    private void saveUnlockedItems()
    {
        String playerName = getPlayerConfigKey();
        if (playerName == null)
        {
            log.warn("Cannot save unlocked items - player name is null");
            return;
        }

        // Save unlocked items
        String unlockedKey = playerName + "." + UNLOCKED_ITEMS_KEY;
        String unlockedJson = gson.toJson(unlockedClogItems);
        configManager.setConfiguration(CONFIG_GROUP, unlockedKey, unlockedJson);

        // Save manually added items
        String manualAddKey = playerName + "." + MANUALLY_ADDED_KEY;
        String manualAddJson = gson.toJson(manuallyAdded);
        configManager.setConfiguration(CONFIG_GROUP, manualAddKey, manualAddJson);

        // Save manually removed items
        String manualRemoveKey = playerName + "." + MANUALLY_REMOVED_KEY;
        String manualRemoveJson = gson.toJson(manuallyRemoved);
        configManager.setConfiguration(CONFIG_GROUP, manualRemoveKey, manualRemoveJson);

        log.debug("Saved {} unlocked items ({} manual, {} locked) for {}",
            unlockedClogItems.size(), manuallyAdded.size(), manuallyRemoved.size(), playerName);
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
     * Check if a clog item is "effectively unlocked" - either directly unlocked
     * or craftable from other effectively unlocked clog items.
     * Used for dependency checking, NOT for display (sidebar shows actual unlocks only).
     */
    public boolean isEffectivelyUnlocked(int clogItemId)
    {
        return isEffectivelyUnlocked(clogItemId, new HashSet<>());
    }

    private boolean isEffectivelyUnlocked(int clogItemId, Set<Integer> visited)
    {
        // Direct unlock - always counts
        if (unlockedClogItems.contains(clogItemId))
        {
            return true;
        }

        // Not a clog item we know about
        ClogItem clogItem = collectionLogItems.get(clogItemId);
        if (clogItem == null)
        {
            return false;
        }

        // If clue restrictions are disabled, all clue items are effectively unlocked
        if (!config.restrictClueItems() && isClueItem(clogItem))
        {
            return true;
        }

        // Cycle detection - prevent infinite recursion
        if (visited.contains(clogItemId))
        {
            return false;
        }
        visited.add(clogItemId);

        // Check if any crafting recipe is satisfiable
        List<List<Integer>> recipes = clogItem.getCraftableFrom();
        for (List<Integer> recipe : recipes)
        {
            boolean recipeWorks = true;
            for (int depId : recipe)
            {
                // Create new visited set for each branch
                if (!isEffectivelyUnlocked(depId, new HashSet<>(visited)))
                {
                    recipeWorks = false;
                    break;
                }
            }
            if (recipeWorks)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Recalculates which items are available based on current unlocks
     */
    public void recalculateAvailableItems()
    {
        availableItems.clear();

        // Add clog items that are effectively unlocked (actual OR craftable from actual)
        // This allows using e.g. Onyx if you have Uncut onyx
        for (Map.Entry<Integer, ClogItem> entry : collectionLogItems.entrySet())
        {
            Integer primaryId = entry.getKey();
            ClogItem clogItem = entry.getValue();

            if (isEffectivelyUnlocked(primaryId))
            {
                // Add all variant IDs for this clog item
                availableItems.addAll(clogItem.getAllIds());
                availableItems.add(primaryId);
            }
        }

        // Check derived items - available if all clog dependencies are effectively unlocked
        for (Map.Entry<String, DerivedItem> entry : derivedItems.entrySet())
        {
            DerivedItem derived = entry.getValue();
            if (derived.clogDependencies != null && !derived.clogDependencies.isEmpty())
            {
                boolean allDepsUnlocked = true;
                for (int depId : derived.clogDependencies)
                {
                    if (!isEffectivelyUnlocked(depId))
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
    public void unlockItem(int itemId, boolean isManual)
    {
        if (!collectionLogItems.containsKey(itemId))
        {
            return;
        }

        if (unlockedClogItems.add(itemId))
        {
            ClogItem item = collectionLogItems.get(itemId);
            log.info("Unlocked collection log item: {} (ID: {})", item.name, itemId);

            // Remove from manually removed if it was there
            boolean wasManuallyLocked = manuallyRemoved.remove(itemId);

            // Only track as manual addition if this is actually a manual unlock
            if (isManual && !wasManuallyLocked)
            {
                manuallyAdded.add(itemId);
            }

            // Capture items available before recalculation
            Set<Integer> previouslyAvailable = new HashSet<>(availableItems);

            saveUnlockedItems();
            recalculateAvailableItems();

            if (config.chatMessageOnUnlock())
            {
                sendUnlockMessage(item.name);
            }

            // Show newly available derived items
            if (config.showNewlyAvailable())
            {
                List<String> newlyAvailable = findNewlyAvailableItems(previouslyAvailable);
                if (!newlyAvailable.isEmpty())
                {
                    sendNewlyAvailableMessage(newlyAvailable);
                }
            }

            if (panel != null)
            {
                panel.refresh();
            }
        }
    }

    /**
     * Find derived items that are newly available after an unlock
     */
    private List<String> findNewlyAvailableItems(Set<Integer> previouslyAvailable)
    {
        List<String> newlyAvailable = new ArrayList<>();

        for (Map.Entry<String, DerivedItem> entry : derivedItems.entrySet())
        {
            DerivedItem derived = entry.getValue();
            List<Integer> itemIds = derived.getAllItemIds();

            if (itemIds.isEmpty())
            {
                continue;
            }

            // Check if this item is now available but wasn't before
            int primaryId = itemIds.get(0);
            if (availableItems.contains(primaryId) && !previouslyAvailable.contains(primaryId))
            {
                newlyAvailable.add(derived.name);
            }
        }

        // Sort alphabetically
        newlyAvailable.sort(String::compareToIgnoreCase);
        return newlyAvailable;
    }

    /**
     * Send chat message about newly available items
     */
    private void sendNewlyAvailableMessage(List<String> items)
    {
        StringBuilder sb = new StringBuilder();

        // Show up to 3 items explicitly
        int showCount = Math.min(items.size(), 3);
        for (int i = 0; i < showCount; i++)
        {
            if (i > 0)
            {
                sb.append(", ");
            }
            sb.append(capitalize(items.get(i)));
        }

        // Add "and X more" if there are more items
        int remaining = items.size() - showCount;
        if (remaining > 0)
        {
            sb.append(" and ").append(remaining).append(" more");
        }

        String message = new ChatMessageBuilder()
            .append(ChatColorType.NORMAL)
            .append("New items unlocked: ")
            .append(ChatColorType.HIGHLIGHT)
            .append(sb.toString())
            .build();

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(message)
            .build());
    }

    /**
     * Capitalize the first letter of a string
     */
    private String capitalize(String input)
    {
        if (input == null || input.isEmpty())
        {
            return input;
        }
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    /**
     * Locks an item (moves from unlocked to manually locked)
     * Used when user wants to lock an item they actually have in their clog
     */
    public void lockItem(int itemId)
    {
        if (unlockedClogItems.remove(itemId))
        {
            ClogItem item = collectionLogItems.get(itemId);
            log.info("Locked item: {} (ID: {})", item != null ? item.name : "Unknown", itemId);

            // Check if this was a manual unlock (not from clog)
            boolean wasManuallyAdded = manuallyAdded.remove(itemId);

            // Only add to manually removed if it wasn't a manual unlock
            // (i.e., it's from the actual collection log)
            if (!wasManuallyAdded)
            {
                manuallyRemoved.add(itemId);
            }

            saveUnlockedItems();
            recalculateAvailableItems();

            if (panel != null)
            {
                panel.refresh();
            }
        }
    }

    /**
     * Resets all unlocks (for panel use)
     */
    public void resetAllUnlocks()
    {
        int count = unlockedClogItems.size();
        unlockedClogItems.clear();
        manuallyAdded.clear();
        manuallyRemoved.clear();
        saveUnlockedItems();
        recalculateAvailableItems();
        log.info("Reset all unlocks. Cleared {} items.", count);

        if (panel != null)
        {
            panel.refresh();
        }
    }

    /**
     * Resets only manual changes (re-adds locked items, removes manual additions)
     */
    public void resetManualChanges()
    {
        int addedCount = manuallyAdded.size();
        int removedCount = manuallyRemoved.size();

        // Re-add manually locked items (they're back in the unlocked list)
        unlockedClogItems.addAll(manuallyRemoved);

        // Remove manual additions (they weren't real)
        unlockedClogItems.removeAll(manuallyAdded);

        // Clear manual tracking
        manuallyAdded.clear();
        manuallyRemoved.clear();

        saveUnlockedItems();
        recalculateAvailableItems();

        log.info("Reset manual changes. Re-added {} locked items, removed {} manual additions.",
            removedCount, addedCount);

        if (panel != null)
        {
            panel.refresh();
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

    /**
     * Checks if a clog item is from the Treasure Trails (Clues) section
     */
    private boolean isClueItem(ClogItem item)
    {
        if (item == null || item.tabs == null)
        {
            return false;
        }
        for (String tab : item.tabs)
        {
            if (tab.contains("Treasure Trail"))
            {
                return true;
            }
        }
        return false;
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
        // Check the direct item ID field first (works for most item interactions)
        int itemId = entry.getItemId();
        if (itemId > 0)
        {
            return itemId;
        }

        // Try to get item ID from widget (for inventory/bank/equipment items)
        Widget widget = entry.getWidget();
        if (widget != null)
        {
            int widgetItemId = widget.getItemId();
            if (widgetItemId > 0)
            {
                return widgetItemId;
            }
        }

        // Return -1 if no valid item ID found
        // Don't fall back to getIdentifier() as it could be an object/NPC ID
        return -1;
    }

    private boolean isRestrictedAction(String option)
    {
        return RESTRICTED_ACTIONS.contains(option);
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
        ItemComposition itemComp = itemManager.getItemComposition(itemId);
        String itemName = itemComp != null ? itemComp.getName() : "Unknown item";
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

        // Show which collection log items are required
        List<String> requiredItems = getRequiredClogItems(itemId);
        if (!requiredItems.isEmpty())
        {
            String label = requiredItems.size() == 1 ? "Clog required: " : "Clogs required: ";
            String reqMessage = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append(label)
                .append(ChatColorType.HIGHLIGHT)
                .append(String.join(", ", requiredItems))
                .build();

            chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(reqMessage)
                .build());
        }
    }

    /**
     * Get the list of required collection log item names for a locked item.
     * Uses effective unlocking - considers items craftable from other clog items.
     */
    private List<String> getRequiredClogItems(int itemId)
    {
        List<String> required = new ArrayList<>();

        // Check if it's a clog item itself
        Integer primaryClogId = clogIdToPrimaryId.get(itemId);
        if (primaryClogId != null)
        {
            ClogItem clogItem = collectionLogItems.get(primaryClogId);
            if (clogItem != null && !isEffectivelyUnlocked(primaryClogId))
            {
                required.add(clogItem.name);
            }
            return required;
        }

        // Check if it's a derived item
        DerivedItem derived = derivedItemsById.get(itemId);
        if (derived != null && derived.clogDependencies != null)
        {
            for (int depId : derived.clogDependencies)
            {
                if (!isEffectivelyUnlocked(depId))
                {
                    ClogItem clogItem = collectionLogItems.get(depId);
                    if (clogItem != null)
                    {
                        required.add(clogItem.name);
                    }
                }
            }
        }

        return required;
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
        // Handle game messages for collection log unlocks
        if (event.getType() == ChatMessageType.GAMEMESSAGE)
        {
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
                    unlockItem(itemId, false);
                }
                else
                {
                    log.warn("Could not find item ID for unlocked item: {}", itemName);
                }
            }
            return;
        }

        // Handle chat messages to add icon to local player's messages
        if (!config.showChatIcon() || chatIconOffset == -1)
        {
            return;
        }

        // Only process player chat types
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.PUBLICCHAT &&
            type != ChatMessageType.MODCHAT &&
            type != ChatMessageType.CLAN_CHAT &&
            type != ChatMessageType.CLAN_GUEST_CHAT &&
            type != ChatMessageType.FRIENDSCHAT &&
            type != ChatMessageType.PRIVATECHAT &&
            type != ChatMessageType.MODPRIVATECHAT)
        {
            return;
        }

        // Check if this message is from the local player
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getName() == null)
        {
            return;
        }

        String messageName = Text.removeTags(event.getName());
        if (!localPlayer.getName().equals(messageName))
        {
            return;
        }

        // Add icon to the message
        String name = event.getName();
        // Don't add icon if name already has tags (ironman icon, etc.)
        if (!name.equals(Text.removeTags(name)))
        {
            return;
        }

        final MessageNode messageNode = event.getMessageNode();
        messageNode.setName("<img=" + chatIconOffset + ">" + name);
        client.refreshChat();
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
        int migratedItems = 0;
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
                // Respect manual removals - don't auto-add if user has locked this item
                if (!manuallyRemoved.contains(itemId))
                {
                    if (unlockedClogItems.add(itemId))
                    {
                        ClogItem clogItem = collectionLogItems.get(itemId);
                        log.debug("Found obtained item: {} (ID: {})", clogItem.name, itemId);
                        newUnlocks++;
                    }

                    // If this was manually added before, it's now a real unlock
                    manuallyAdded.remove(itemId);
                }
            }
            else if (!isObtained && collectionLogItems.containsKey(itemId))
            {
                // Migration: If item is unlocked but not obtained, it must be a manual addition
                // This handles upgrading from pre-manual-tracking versions
                if (unlockedClogItems.contains(itemId) && !manuallyAdded.contains(itemId))
                {
                    manuallyAdded.add(itemId);
                    ClogItem clogItem = collectionLogItems.get(itemId);
                    log.debug("Migrated to manual unlock: {} (ID: {})", clogItem.name, itemId);
                    migratedItems++;
                }
            }
        }

        log.debug("Scanned {} items, found {} new unlocks, migrated {} items", scannedItems, newUnlocks, migratedItems);

        if (newUnlocks > 0 || migratedItems > 0)
        {
            if (newUnlocks > 0)
            {
                log.info("Scanned collection log page, found {} new unlocks (total: {})", newUnlocks, unlockedClogItems.size());
            }

            if (migratedItems > 0)
            {
                log.info("Migrated {} unlocked items to manual unlocks", migratedItems);
            }

            saveUnlockedItems();
            recalculateAvailableItems();

            if (newUnlocks > 0)
            {
                sendSyncMessage(newUnlocks);
            }

            if (panel != null)
            {
                panel.refresh();
            }
        }
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
     * Returns a copy of the unlocked collection log item IDs.
     * Returns a defensive copy to prevent ConcurrentModificationException
     * when iterating from EDT while client thread modifies the set.
     */
    public Set<Integer> getUnlockedClogItems()
    {
        return new HashSet<>(unlockedClogItems);
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
        @SerializedName("craftable_from")
        public List<List<Integer>> craftableFrom;  // Optional: recipes to craft this from other clog items

        /**
         * Get all valid item IDs for this clog item.
         */
        public List<Integer> getAllIds()
        {
            return allIds != null ? allIds : java.util.Collections.emptyList();
        }

        /**
         * Get crafting recipes from other clog items (if any).
         * Outer list: OR (any recipe works), Inner list: AND (all deps needed)
         */
        public List<List<Integer>> getCraftableFrom()
        {
            return craftableFrom != null ? craftableFrom : java.util.Collections.emptyList();
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
