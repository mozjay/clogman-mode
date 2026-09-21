package com.clogman;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
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
import net.runelite.api.gameval.VarPlayerID;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    // Fired once per obtained item, for the whole log, the tick after the collection log opens
    private static final int COLLECTION_LOG_ITEM_SCRIPT = 4100;

    // Fired once the collection log interface has finished constructing
    private static final int COLLECTION_LOG_SETUP_SCRIPT = 7797;

    // Resets the collection log's tab view - closes the Search toggle back out after it's served
    // its purpose of making the client report every item
    private static final int COLLECTION_LOG_INIT_SCRIPT = 2240;

    // Player-owned house adventure log interfaces; a collection log opened from one may be another player's
    private static final int ADVENTURE_LOG_GROUP = 187;
    private static final int ADVENTURE_LOG_NEW_GROUP = 947;

    // World types with their own collection log for the same character; never sync from these
    private static final EnumSet<WorldType> NON_STANDARD_WORLDS = EnumSet.of(
        WorldType.SEASONAL, WorldType.DEADMAN, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE,
        WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING, WorldType.PVP_ARENA);

    // Region ID for the Grand Exchange, used to scope GE search restriction to the GE itself
    // (the same search widget is reused elsewhere, e.g. sailing's mermaid riddle item search)
    private static final int GE_REGION_ID = 12598;

    // Actions that should be restricted for locked items (O(1) lookup)
    private static final Set<String> RESTRICTED_ACTIONS = Set.of(
        "wear", "wield", "equip", "eat", "drink", "use",
        "read", "open", "rub", "break", "activate", "commune"
    );

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    @Getter
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

    @Inject
    private ClogmanUnlockPopupOverlay popupOverlay;

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
    private boolean adventureLogOpen = false;

    // Collection log was opened from an adventure log, so it may belong to another player: never sync it
    private boolean collectionLogReadOnly = false;

    // Obtained item ids reported by the collection log since the last sync, and the tick the last one arrived
    private final Set<Integer> pendingClogSync = new HashSet<>();
    private int lastClogItemTick = -1;
    // Whether COLLECTION_LOG_ITEM_SCRIPT has fired at all since the log was last opened - tells a
    // burst that reported nothing apart from one that never started
    private boolean clogItemScriptSeenThisOpen = false;
    // Whether the Search toggle has already been triggered for the current open - it re-fires
    // COLLECTION_LOG_SETUP_SCRIPT itself, so this stops that becoming an infinite loop
    private boolean collectionLogSearchTriggeredThisOpen = false;

    // Chat icon offset in the modIcons array (-1 means not loaded yet)
    private int chatIconOffset = -1;

    // Track when player is actually logging in (not just scene loading)
    private boolean loggingIn = false;

    @Override
    protected void startUp() throws Exception
    {
        loadRestrictionData();
        overlayManager.add(overlay);
        overlayManager.add(popupOverlay);

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
        overlayManager.remove(popupOverlay);
        popupOverlay.clear();
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
        if (event.getGameState() == GameState.LOGGING_IN)
        {
            loggingIn = true;
        }

        if (event.getGameState() == GameState.LOGGED_IN && loggingIn)
        {
            loggingIn = false;

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
            popupOverlay.clear();
            loggingIn = false;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equals(CONFIG_GROUP))
        {
            return;
        }

        // Recalculate available items when any restriction setting that changes
        // what counts as unlocked is toggled
        if (event.getKey().equals("restrictClueItems")
            || event.getKey().equals("restrictCraftableUnlocks")
            || event.getKey().equals("restrictShopBuyable")
            || event.getKey().equals("restrictDropObtainable"))
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
            .append("Open your Collection Log to sync your unlocks!")
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

        // Drop anything that isn't a current primary clog ID (e.g. from an old data version, or
        // a hand-edited/imported config) - collectionLogItems.get(itemId) is assumed non-null
        // everywhere else once an ID is in these sets.
        filterUnknownClogIds(unlockedClogItems, "unlocked");
        filterUnknownClogIds(manuallyAdded, "manually added");
        filterUnknownClogIds(manuallyRemoved, "manually removed");

        log.info("Loaded {} unlocked items ({} manual, {} locked) for player {}",
            unlockedClogItems.size(), manuallyAdded.size(), manuallyRemoved.size(), playerName);
    }

    private void filterUnknownClogIds(Set<Integer> itemIds, String label)
    {
        if (itemIds.removeIf(itemId -> !collectionLogItems.containsKey(itemId)))
        {
            log.warn("Dropped unknown item ID(s) from saved {} items - not a current primary clog ID", label);
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
        // A manual lock is authoritative - the exemptions below must not override
        // it, or the sidebar would list an item as manually locked while the game
        // still let you use it. Checked inside the recursion so the veto also
        // blocks any craftable-from path that would route through this item.
        if (manuallyRemoved.contains(clogItemId))
        {
            return false;
        }

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

        // If shop restrictions are disabled, anything a shop sells is effectively
        // unlocked - buying it generally doesn't credit the collection log, so the
        // player can legitimately own one without the unlock
        if (!config.restrictShopBuyable() && clogItem.isShopBuyable())
        {
            return true;
        }

        // Cycle detection - prevent infinite recursion
        if (visited.contains(clogItemId))
        {
            return false;
        }
        visited.add(clogItemId);

        // Check if any crafting recipe is satisfiable (unless craftable-from unlocks are restricted)
        if (!config.restrictCraftableUnlocks())
        {
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
        }

        return false;
    }

    /**
     * Get a derived item's dependency sets under the current drop setting.
     * Every caller must go through this so the "why is this locked" messages
     * can't contradict what recalculateAvailableItems actually did.
     */
    public List<List<Integer>> getEffectiveDependencies(DerivedItem derived)
    {
        return derived.getEffectiveDependencies(!config.restrictDropObtainable());
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

        // Check derived items - available if any dependency set has all deps unlocked (OR of AND)
        for (Map.Entry<String, DerivedItem> entry : derivedItems.entrySet())
        {
            DerivedItem derived = entry.getValue();
            List<List<Integer>> depSets = getEffectiveDependencies(derived);
            if (depSets.isEmpty())
            {
                // No dependencies under the current setting - unrestricted
                availableItems.addAll(derived.getAllItemIds());
            }
            else
            {
                boolean anySetSatisfied = false;
                for (List<Integer> depSet : depSets)
                {
                    boolean allDepsUnlocked = true;
                    for (int depId : depSet)
                    {
                        if (!isEffectivelyUnlocked(depId))
                        {
                            allDepsUnlocked = false;
                            break;
                        }
                    }

                    if (allDepsUnlocked)
                    {
                        anySetSatisfied = true;
                        break;
                    }
                }

                if (anySetSatisfied)
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
        unlockItems(Collections.singletonList(itemId), isManual);
    }

    /**
     * Unlocks several collection log items as one event: one save, one chat message and one
     * popup, so a batch of manual unlocks from the side panel doesn't spam.
     */
    public void unlockItems(Collection<Integer> itemIds, boolean isManual)
    {
        List<String> unlockedNames = new ArrayList<>();
        for (int itemId : itemIds)
        {
            if (!collectionLogItems.containsKey(itemId) || !unlockedClogItems.add(itemId))
            {
                continue;
            }

            ClogItem item = collectionLogItems.get(itemId);
            log.info("Unlocked collection log item: {} (ID: {})", item.name, itemId);
            unlockedNames.add(item.name);

            // Re-unlocking a manually locked item restores it; only a genuinely new manual unlock is tracked
            boolean wasManuallyLocked = manuallyRemoved.remove(itemId);
            if (isManual && !wasManuallyLocked)
            {
                manuallyAdded.add(itemId);
            }
        }

        if (unlockedNames.isEmpty())
        {
            return;
        }

        // Capture items available before recalculation
        Set<Integer> previouslyAvailable = new HashSet<>(availableItems);

        saveUnlockedItems();
        recalculateAvailableItems();

        List<String> extraUnlocks = findNewlyAvailable(previouslyAvailable, itemIds);

        if (config.chatMessageOnUnlock())
        {
            sendUnlockMessage(unlockedNames);
        }

        if (config.showNewlyAvailable() && !extraUnlocks.isEmpty())
        {
            sendNewlyAvailableMessage(extraUnlocks);
        }

        // On-screen popup, only when the unlock made something else available. Manual unlocks
        // never get a native popup, so the overlay shows them standalone. The panel may call
        // this off the client thread (and while logged out), so hop onto it for the overlay.
        if (config.showUnlockPopup() && !extraUnlocks.isEmpty() && client.getGameState() == GameState.LOGGED_IN)
        {
            final boolean expectNativePopup = !isManual;
            final String firstName = unlockedNames.get(0);
            clientThread.invoke(() -> popupOverlay.enqueue(firstName, extraUnlocks, expectNativePopup));
        }

        if (panel != null)
        {
            panel.refresh();
        }
    }

    /**
     * Names of everything an unlock made available other than the unlocked item itself: other
     * collection log items now effectively unlocked (e.g. Onyx once Uncut onyx is unlocked) and
     * derived items whose dependencies are now met. Sorted alphabetically.
     */
    private List<String> findNewlyAvailable(Set<Integer> previouslyAvailable, Collection<Integer> unlockedItemIds)
    {
        List<String> names = new ArrayList<>();

        for (Map.Entry<Integer, ClogItem> entry : collectionLogItems.entrySet())
        {
            if (!unlockedItemIds.contains(entry.getKey()) && isNewlyAvailable(entry.getKey(), previouslyAvailable))
            {
                names.add(entry.getValue().name);
            }
        }

        for (DerivedItem derived : derivedItems.values())
        {
            List<Integer> itemIds = derived.getAllItemIds();
            if (!itemIds.isEmpty() && isNewlyAvailable(itemIds.get(0), previouslyAvailable))
            {
                names.add(capitalize(derived.name));
            }
        }

        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private boolean isNewlyAvailable(int itemId, Set<Integer> previouslyAvailable)
    {
        return availableItems.contains(itemId) && !previouslyAvailable.contains(itemId);
    }

    /**
     * Send chat message about newly available items
     */
    private void sendNewlyAvailableMessage(List<String> items)
    {
        // Show up to 3 items explicitly, then "and X more"
        int showCount = Math.min(items.size(), 3);
        int remaining = items.size() - showCount;
        String list = String.join(", ", items.subList(0, showCount))
            + (remaining > 0 ? " and " + remaining + " more" : "");

        String message = new ChatMessageBuilder()
            .append(ChatColorType.NORMAL)
            .append("New items unlocked: ")
            .append(ChatColorType.HIGHLIGHT)
            .append(list)
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
        if (lockQuietly(itemId))
        {
            saveUnlockedItems();
            recalculateAvailableItems();

            if (panel != null)
            {
                panel.refresh();
            }
        }
    }

    /**
     * Locks an item without saving or refreshing, so a batch (e.g. an import) can do that once.
     * Returns whether the item was unlocked.
     */
    private boolean lockQuietly(int itemId)
    {
        if (!unlockedClogItems.remove(itemId))
        {
            return false;
        }

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
        return true;
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

    // === EXPORT / IMPORT ===

    /**
     * Writes the unlock state to a file as item ID -> name maps. IDs are what import reads;
     * names are only there for humans. Nothing about the account goes in.
     */
    public void writeExport(File file) throws IOException
    {
        Set<Integer> real = new HashSet<>(unlockedClogItems);
        real.removeAll(manuallyAdded);

        Export export = new Export();
        export.formatVersion = Export.FORMAT_VERSION;
        export.unlocks = names(real);
        export.manualUnlocks = names(manuallyAdded);
        export.manualLocks = names(manuallyRemoved);

        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
        {
            gson.newBuilder().setPrettyPrinting().create().toJson(export, writer);
        }
        log.info("Exported {} unlocks, {} manual unlocks and {} locks to {}",
            real.size(), manuallyAdded.size(), manuallyRemoved.size(), file);
    }

    private Map<Integer, String> names(Collection<Integer> itemIds)
    {
        Map<Integer, String> names = new HashMap<>();
        for (Integer itemId : itemIds)
        {
            ClogItem item = collectionLogItems.get(itemId);
            names.put(itemId, item != null ? item.name : "Unknown");
        }

        Map<Integer, String> sorted = new LinkedHashMap<>();
        names.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(String::compareToIgnoreCase))
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public Export readExport(File file) throws IOException
    {
        Export export;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
        {
            export = gson.fromJson(reader, Export.class);
        }
        catch (JsonParseException e)
        {
            throw new IOException("Not a valid Clogman export file.", e);
        }

        if (export == null || export.formatVersion < 1)
        {
            throw new IOException("Not a Clogman export file.");
        }
        if (export.formatVersion > Export.FORMAT_VERSION)
        {
            throw new IOException("This file was made by a newer version of Clogman Mode. Please update the plugin.");
        }

        if (export.unlocks == null)
        {
            export.unlocks = Collections.emptyMap();
        }
        if (export.manualUnlocks == null)
        {
            export.manualUnlocks = Collections.emptyMap();
        }
        if (export.manualLocks == null)
        {
            export.manualLocks = Collections.emptyMap();
        }
        return export;
    }

    /**
     * Works out what an import would change here without touching anything. Imported locks only
     * apply to items unlocked here (as lockItem does); imported unlocks never override a local
     * lock and are always treated as manual, so the clog sync can confirm them later.
     */
    public ImportSummary previewImport(Export export)
    {
        ImportSummary summary = new ImportSummary();
        for (Integer id : export.manualLocks.keySet())
        {
            Integer itemId = clogIdToPrimaryId.get(id);
            if (itemId == null)
            {
                summary.unknown++;
            }
            else if (unlockedClogItems.contains(itemId))
            {
                summary.locks.add(itemId);
            }
        }
        collectImportUnlocks(export.unlocks.keySet(), summary, summary.clogUnlocks);
        collectImportUnlocks(export.manualUnlocks.keySet(), summary, summary.manualUnlocks);
        return summary;
    }

    private void collectImportUnlocks(Collection<Integer> ids, ImportSummary summary, Set<Integer> into)
    {
        for (Integer id : ids)
        {
            Integer itemId = clogIdToPrimaryId.get(id);
            if (itemId == null)
            {
                summary.unknown++;
            }
            else if (summary.locks.contains(itemId) || manuallyRemoved.contains(itemId))
            {
                summary.skippedLocked++;
            }
            else if (!unlockedClogItems.contains(itemId) && !summary.clogUnlocks.contains(itemId))
            {
                into.add(itemId);
            }
        }
    }

    public void applyImport(ImportSummary summary, boolean clogUnlocks, boolean manualUnlocks, boolean locks)
    {
        int locked = 0;
        if (locks)
        {
            for (Integer itemId : summary.locks)
            {
                if (lockQuietly(itemId))
                {
                    locked++;
                }
            }
        }

        List<Integer> unlockIds = new ArrayList<>();
        if (clogUnlocks)
        {
            unlockIds.addAll(summary.clogUnlocks);
        }
        if (manualUnlocks)
        {
            unlockIds.addAll(summary.manualUnlocks);
        }
        log.info("Importing {} unlocks and {} locks", unlockIds.size(), locked);

        // unlockItems saves, recalculates and refreshes; locks on their own need that done here
        if (!unlockIds.isEmpty())
        {
            unlockItems(unlockIds, true);
        }
        else if (locked > 0)
        {
            saveUnlockedItems();
            recalculateAvailableItems();

            if (panel != null)
            {
                panel.refresh();
            }
        }
    }

    private void sendUnlockMessage(List<String> itemNames)
    {
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("Clogman: ")
            .append(ChatColorType.NORMAL)
            .append("Unlocked ")
            .append(ChatColorType.HIGHLIGHT)
            .append(joinNames(itemNames))
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
        if (!config.restrictItemUsage() || !isStandardWorld())
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
        // Non-standard worlds (PvP Arena, Deadman, etc.) hand out temporary or
        // separate loadouts unrelated to the account's real unlocks - never restrict there
        if (!isStandardWorld())
        {
            return;
        }

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
        if (!config.restrictGrandExchange() || !isStandardWorld())
        {
            return;
        }

        clientThread.invokeLater(this::filterGrandExchangeResults);
    }

    private void filterGrandExchangeResults()
    {
        if (client.getLocalPlayer().getWorldLocation().getRegionID() != GE_REGION_ID)
        {
            return;
        }

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

        // Show the way with the fewest missing clog items, and what the alternatives are
        List<String> requiredItems = getRequiredClogItems(itemId);
        if (!requiredItems.isEmpty())
        {
            String reqMessage = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append("Unlock via: ")
                .append(ChatColorType.HIGHLIGHT)
                .append(String.join(", ", requiredItems))
                .append(ChatColorType.NORMAL)
                .append(describeAlternatives(itemId))
                .build();

            chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(reqMessage)
                .build());
        }
    }

    /**
     * Suffix describing the other ways to unlock an item: the crafting recipes for a collection
     * log item (e.g. Oathplate helm from Oathplate shards), or the option count for a derived item.
     */
    private String describeAlternatives(int itemId)
    {
        Integer primaryClogId = clogIdToPrimaryId.get(itemId);
        if (primaryClogId != null)
        {
            if (config.restrictCraftableUnlocks())
            {
                return "";
            }

            List<String> recipes = new ArrayList<>();
            for (List<Integer> recipe : collectionLogItems.get(primaryClogId).getCraftableFrom())
            {
                List<String> ingredients = new ArrayList<>();
                for (int id : recipe)
                {
                    ClogItem ingredient = collectionLogItems.get(id);
                    ingredients.add(ingredient != null ? ingredient.name : String.valueOf(id));
                }
                recipes.add(String.join(" + ", ingredients));
            }
            return recipes.isEmpty() ? "" : " (or craft from " + String.join(" / ", recipes) + ")";
        }

        DerivedItem derived = derivedItemsById.get(itemId);
        int options = derived != null ? getEffectiveDependencies(derived).size() : 0;
        return options > 1 ? " (1 of " + options + " options)" : "";
    }

    /**
     * "A", "A and B", or "A, B and C"
     */
    private static String joinNames(List<String> names)
    {
        if (names.size() == 1)
        {
            return names.get(0);
        }
        if (names.size() > 5)
        {
            return String.join(", ", names.subList(0, 5)) + " and " + (names.size() - 5) + " more";
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
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
        if (derived != null)
        {
            List<List<Integer>> depSets = getEffectiveDependencies(derived);
            if (!depSets.isEmpty())
            {
                // Find the dep set with fewest missing items (closest to complete)
                List<String> bestMissing = null;
                for (List<Integer> depSet : depSets)
                {
                    List<String> missing = new ArrayList<>();
                    for (int depId : depSet)
                    {
                        if (!isEffectivelyUnlocked(depId))
                        {
                            ClogItem clogItem = collectionLogItems.get(depId);
                            if (clogItem != null)
                            {
                                missing.add(clogItem.name);
                            }
                        }
                    }

                    if (bestMissing == null || missing.size() < bestMissing.size())
                    {
                        bestMissing = missing;
                        if (missing.isEmpty())
                        {
                            break;  // Found a complete set, no need to check others
                        }
                    }
                }

                if (bestMissing != null)
                {
                    required.addAll(bestMissing);
                }
            }
        }

        return required;
    }

    // === COLLECTION LOG DETECTION ===

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        int groupId = event.getGroupId();
        if (groupId == ADVENTURE_LOG_GROUP || groupId == ADVENTURE_LOG_NEW_GROUP)
        {
            adventureLogOpen = true;
        }
        else if (groupId == InterfaceID.COLLECTION_LOG)
        {
            // The adventure log is still open when a log opened from it loads; its close event follows
            collectionLogOpen = true;
            collectionLogReadOnly = adventureLogOpen;
            clogItemScriptSeenThisOpen = false;
            collectionLogSearchTriggeredThisOpen = false;
            log.debug("Collection log opened{}", collectionLogReadOnly ? " from an adventure log, ignoring its contents" : "");
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        int groupId = event.getGroupId();
        if (groupId == ADVENTURE_LOG_GROUP || groupId == ADVENTURE_LOG_NEW_GROUP)
        {
            adventureLogOpen = false;
        }
        else if (groupId == InterfaceID.COLLECTION_LOG)
        {
            // The item script never fired at all, so syncCollectionLogWhenQuiet was never scheduled
            // and syncCollectionLog's own log line never ran either.
            if (!collectionLogReadOnly && !clogItemScriptSeenThisOpen)
            {
                log.warn("Collection log closed without the client reporting any items - sync did not run");
            }
            collectionLogOpen = false;
            collectionLogReadOnly = false;
            log.debug("Collection log closed");
        }
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event)
    {
        // Native notification popup open/hold animation scripts - drives the unlock popup timing
        int scriptId = event.getScriptId();
        if (scriptId == ScriptID.NOTIFICATION_START || scriptId == ScriptID.NOTIFICATION_DELAY)
        {
            popupOverlay.onNotificationScript(scriptId);
        }
        else if (scriptId == COLLECTION_LOG_ITEM_SCRIPT)
        {
            clogItemScriptSeenThisOpen = true;
            // Deliberately not gated on collectionLogOpen - this script can fire after the widget
            // has already closed, and its own firing is signal enough that a log is being read.
            if (isOwnCollectionLogEligible())
            {
                // Args: [script id, item id, quantity, ...]. The whole log arrives as a burst of
                // chunks within a tick or two of opening, so collect it and process once it goes quiet.
                Object[] args = event.getScriptEvent().getArguments();
                if (args.length > 1 && args[1] instanceof Integer)
                {
                    pendingClogSync.add((Integer) args[1]);
                }
                if (lastClogItemTick < 0)
                {
                    clientThread.invokeLater(this::syncCollectionLogWhenQuiet);
                }
                lastClogItemTick = client.getTickCount();
            }
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        // Opening the collection log alone doesn't make the client request every item's obtained
        // state - toggling its own Search feature does, so trigger that automatically here rather
        // than requiring the player to do it manually.
        if (event.getScriptId() == COLLECTION_LOG_SETUP_SCRIPT && !collectionLogSearchTriggeredThisOpen
            && isOwnCollectionLogEligible())
        {
            // Toggling Search re-fires this same script - latch first so it can't repeat forever.
            collectionLogSearchTriggeredThisOpen = true;
            clientThread.invokeLater(() ->
            {
                int searchToggle = net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE;
                client.menuAction(-1, searchToggle, MenuAction.CC_OP, 1, -1, "Search", null);
                client.runScript(COLLECTION_LOG_INIT_SCRIPT);
            });
        }
    }

    /**
     * Re-run each client cycle until the burst has been quiet for two game ticks. Returns true
     * to stop. Logging out mid-burst stops the tick counter, so give up rather than spin.
     */
    private boolean syncCollectionLogWhenQuiet()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            if (!pendingClogSync.isEmpty())
            {
                log.warn("Collection log sync abandoned - no longer logged in with {} item(s) pending", pendingClogSync.size());
            }
            pendingClogSync.clear();
            lastClogItemTick = -1;
            return true;
        }

        if (client.getTickCount() < lastClogItemTick + 2)
        {
            return false;
        }

        lastClogItemTick = -1;
        syncCollectionLog();
        return true;
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
    }

    /**
     * Applies the obtained items the collection log reported when it was opened. Only ever adds
     * unlocks: anything the log doesn't show stays as it is, so a manual lock or a partial burst
     * can't remove a confirmed unlock.
     */
    private void syncCollectionLog()
    {
        Set<Integer> reported = new HashSet<>(pendingClogSync);
        pendingClogSync.clear();

        // The log is the source of truth, but only when we're sure we saw all of it: it must
        // still be open (closing it cuts the burst short) and the burst must cover at least
        // as many items as the game's own obtained count. Otherwise only ever add.
        int gameCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
        boolean complete = collectionLogOpen && gameCount > 0 && reported.size() >= gameCount;

        Set<Integer> obtained = new HashSet<>();
        List<Integer> unmapped = new ArrayList<>();
        for (Integer reportedId : reported)
        {
            Integer itemId = clogIdToPrimaryId.get(reportedId);
            if (itemId != null)
            {
                obtained.add(itemId);
            }
            else
            {
                unmapped.add(reportedId);
            }
        }
        if (!unmapped.isEmpty())
        {
            log.warn("Collection log sync: {} reported item ID(s) not in clog_restrictions.json: {}", unmapped.size(), unmapped);
        }

        List<String> newUnlocks = new ArrayList<>();
        int confirmedManual = 0;
        int skippedLocked = 0;
        List<String> nowManual = new ArrayList<>();

        for (Integer itemId : obtained)
        {
            if (manuallyRemoved.contains(itemId))
            {
                skippedLocked++;
                continue;
            }

            ClogItem item = requireClogItem(itemId);
            if (item == null)
            {
                continue;
            }

            if (unlockedClogItems.add(itemId))
            {
                newUnlocks.add(item.name);
            }

            // A manual unlock the log now confirms is a real unlock
            if (manuallyAdded.remove(itemId))
            {
                confirmedManual++;
            }
        }

        // Anything unlocked that the (complete) log doesn't show is a manual unlock; keep it, but classify it as one
        if (complete)
        {
            for (Integer itemId : unlockedClogItems)
            {
                if (obtained.contains(itemId))
                {
                    continue;
                }
                ClogItem item = requireClogItem(itemId);
                if (item == null)
                {
                    continue;
                }
                if (manuallyAdded.add(itemId))
                {
                    nowManual.add(item.name);
                }
            }
        }

        logSyncDiagnostics(reported.size(), gameCount, complete, unmapped, skippedLocked);

        if (newUnlocks.isEmpty() && confirmedManual == 0 && nowManual.isEmpty())
        {
            return;
        }

        log.info("Collection log sync: {} new unlocks {}, {} manual unlocks confirmed, {} reclassified as manual {} ({} items reported, game count {}, complete: {}, total unlocked: {})",
            newUnlocks.size(), newUnlocks.size() <= 20 ? newUnlocks : "(first sync)", confirmedManual,
            nowManual.size(), nowManual, reported.size(), gameCount, complete, unlockedClogItems.size());

        saveUnlockedItems();
        recalculateAvailableItems();

        if (!newUnlocks.isEmpty())
        {
            sendSyncMessage(newUnlocks.size());
        }

        if (panel != null)
        {
            panel.refresh();
        }
    }

    /**
     * Looks up a clog item by its primary ID, warning once if it isn't one - callers should
     * always have a valid ID here, but saved/imported state can go stale as the data updates.
     */
    private ClogItem requireClogItem(int itemId)
    {
        ClogItem item = collectionLogItems.get(itemId);
        if (item == null)
        {
            log.warn("Collection log sync: item ID {} has no clog_restrictions.json entry", itemId);
        }
        return item;
    }

    /**
     * Logs what a sync actually saw: how many items were reported, how many were recognised, and
     * whether the whole log was seen.
     */
    private void logSyncDiagnostics(int reportedCount, int gameCount, boolean complete,
                                      List<Integer> unmapped, int skippedLocked)
    {
        log.debug("Collection log sync: {} items reported, {} mapped, {} unmapped, {} skipped (manually locked), "
                + "game count {}, complete: {}",
            reportedCount, reportedCount - unmapped.size(), unmapped.size(), skippedLocked, gameCount, complete);
    }

    boolean isStandardWorld()
    {
        return Collections.disjoint(client.getWorldType(), NON_STANDARD_WORLDS);
    }

    // Whether the currently open collection log is the player's own, on a world where it should
    // be tracked at all - shared by both the item-capture and Search-toggle triggers.
    private boolean isOwnCollectionLogEligible()
    {
        return !collectionLogReadOnly && isStandardWorld();
    }

    private void sendSyncMessage(int count)
    {
        String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("Clogman: ")
            .append(ChatColorType.NORMAL)
            .append("Synced " + count + " new items from your collection log.")
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
     * Whether this clog item was actually obtained, as opposed to counting as
     * unlocked through a config exemption or a crafting recipe. Membership test
     * only, so callers rendering a list of rows don't copy the whole set per row.
     */
    public boolean isDirectlyUnlocked(int clogItemId)
    {
        return unlockedClogItems.contains(clogItemId);
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

    /**
     * Unlock state as written to and read from an export file. Only item data, no account details.
     */
    public static class Export
    {
        public static final int FORMAT_VERSION = 1;

        public int formatVersion;
        public Map<Integer, String> unlocks;        // Real collection log unlocks
        public Map<Integer, String> manualUnlocks;
        public Map<Integer, String> manualLocks;
    }

    /**
     * What an import would change here: the primary clog IDs to unlock or lock, plus counts of
     * what was ignored.
     */
    public static class ImportSummary
    {
        public final Set<Integer> clogUnlocks = new LinkedHashSet<>();
        public final Set<Integer> manualUnlocks = new LinkedHashSet<>();
        public final Set<Integer> locks = new LinkedHashSet<>();
        public int unknown;        // IDs not in the clog data
        public int skippedLocked;  // Unlocks skipped because the item is locked here
    }

    public static class ClogItem
    {
        public String name;
        public List<String> tabs;
        @SerializedName("all_ids")
        public List<Integer> allIds;  // All variant IDs for this clog item (e.g., new/used states)
        @SerializedName("craftable_from")
        public List<List<Integer>> craftableFrom;  // Optional: recipes to craft this from other clog items
        @SerializedName("shop_buyable")
        public boolean shopBuyable;  // Optional: some shop stocks this item

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

        /**
         * Whether some shop stocks this item, which generally does not credit
         * the collection log. Which shop and what it charges is left to the
         * wiki - the plugin only needs to know a shop route exists.
         */
        public boolean isShopBuyable()
        {
            return shopBuyable;
        }
    }

    public static class DerivedItem
    {
        public String name;
        @SerializedName("item_ids")
        public List<Integer> itemIds;  // All valid item IDs for this derived item
        @SerializedName("clog_dependencies")
        public List<List<Integer>> clogDependencies;  // Outer list: OR, Inner list: AND)
        @SerializedName("clog_dependencies_drop_free")
        public List<List<Integer>> clogDependenciesDropFree;  // Optional: deps when drop-obtainable items count as free
        @SerializedName("drop_obtainable")
        public boolean dropObtainable;  // Optional: this item drops directly from something

        /**
         * Get all valid item IDs for this derived item.
         */
        public List<Integer> getAllItemIds()
        {
            return itemIds != null ? itemIds : java.util.Collections.emptyList();
        }

        /**
         * Get clog dependency sets
         * Returns empty list if no dependencies.
         */
        public List<List<Integer>> getClogDependencies()
        {
            return clogDependencies != null ? clogDependencies : java.util.Collections.emptyList();
        }

        /**
         * Whether this item drops directly from a monster or reward, rather
         * than only being craftable.
         */
        public boolean isDropObtainable()
        {
            return dropObtainable;
        }

        /**
         * Get the dependency sets that apply under the current drop setting.
         *
         * The generated data ships two answers because the plugin has no recipe
         * graph of its own: it can't work out that freeing Splitbark body also
         * frees Bloodbark body. An absent drop-free list means the answer is
         * unchanged; an empty one means the item is unrestricted.
         *
         * Returns empty list if the item is unrestricted.
         */
        public List<List<Integer>> getEffectiveDependencies(boolean dropsAreFree)
        {
            if (dropsAreFree && clogDependenciesDropFree != null)
            {
                return clogDependenciesDropFree;
            }
            return getClogDependencies();
        }
    }
}
