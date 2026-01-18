package com.clogman;

import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import net.runelite.client.ui.components.IconTextField;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ClogmanPanel extends PluginPanel
{
    private final ClogmanPlugin plugin;
    private final ItemManager itemManager;
    private final Client client;
    private final ClientThread clientThread;
    private final ChatboxItemSearch chatboxItemSearch;

    private final JList<UnlockEntry> unlockList;
    private final DefaultListModel<UnlockEntry> listModel;
    private final JList<UnlockEntry> lockedList;
    private final DefaultListModel<UnlockEntry> lockedListModel;
    private final JLabel statsLabel;
    private final IconTextField searchField;
    private final JCheckBox manualOnlyCheckbox;

    // All entries (unfiltered) - source of truth for display
    private List<UnlockEntry> allEntries = new ArrayList<>();
    private List<UnlockEntry> allLockedEntries = new ArrayList<>();

    public ClogmanPanel(
        ClogmanPlugin plugin,
        ItemManager itemManager,
        Client client,
        ClientThread clientThread,
        ChatboxItemSearch chatboxItemSearch)
    {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.client = client;
        this.clientThread = clientThread;
        this.chatboxItemSearch = chatboxItemSearch;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Stats label at top
        statsLabel = new JLabel("Unlocked: 0 / 0");
        statsLabel.setForeground(Color.WHITE);
        statsLabel.setBorder(new EmptyBorder(0, 0, 5, 0));

        // Search field
        searchField = new IconTextField();
        searchField.setIcon(IconTextField.Icon.SEARCH);
        searchField.setPreferredSize(new Dimension(0, 30));
        searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
        searchField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e) { filterLists(); }
            @Override
            public void removeUpdate(DocumentEvent e) { filterLists(); }
            @Override
            public void changedUpdate(DocumentEvent e) { filterLists(); }
        });

        // Manual-only filter checkbox
        manualOnlyCheckbox = new JCheckBox("Show only manual unlocks");
        manualOnlyCheckbox.setForeground(Color.WHITE);
        manualOnlyCheckbox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        manualOnlyCheckbox.addActionListener(e -> filterLists());

        // List of unlocked items
        listModel = new DefaultListModel<>();
        unlockList = new JList<>(listModel);
        unlockList.setCellRenderer(new UnlockRenderer());
        unlockList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        unlockList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane unlockScrollPane = new JScrollPane(unlockList);
        unlockScrollPane.setPreferredSize(new Dimension(0, 250));
        unlockScrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));

        // List of manually locked items
        lockedListModel = new DefaultListModel<>();
        lockedList = new JList<>(lockedListModel);
        lockedList.setCellRenderer(new UnlockRenderer());
        lockedList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        lockedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane lockedScrollPane = new JScrollPane(lockedList);
        lockedScrollPane.setPreferredSize(new Dimension(0, 120));
        lockedScrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));

        // Unlock list buttons
        JPanel unlockButtonPanel = new JPanel(new GridBagLayout());
        unlockButtonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        unlockButtonPanel.setBorder(new EmptyBorder(5, 0, 5, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 0, 2, 0);

        JButton addButton = new JButton("Add Unlock");
        addButton.addActionListener(e -> onAdd());
        gbc.gridy = 0;
        unlockButtonPanel.add(addButton, gbc);

        JButton lockButton = new JButton("Lock Selected");
        lockButton.addActionListener(e -> onLock());
        gbc.gridy = 1;
        unlockButtonPanel.add(lockButton, gbc);

        // Locked items label
        JLabel lockedLabel = new JLabel("Manually Locked Items");
        lockedLabel.setForeground(Color.WHITE);
        lockedLabel.setBorder(new EmptyBorder(10, 0, 5, 0));

        // Locked list buttons
        JPanel lockedButtonPanel = new JPanel(new GridBagLayout());
        lockedButtonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        lockedButtonPanel.setBorder(new EmptyBorder(5, 0, 5, 0));

        JButton unlockButton = new JButton("Unlock Selected");
        unlockButton.addActionListener(e -> onUnlock());
        gbc.gridy = 0;
        lockedButtonPanel.add(unlockButton, gbc);

        // Reset buttons
        JPanel resetButtonPanel = new JPanel(new GridBagLayout());
        resetButtonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        resetButtonPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        JButton resetManualButton = new JButton("Reset Manual Changes");
        resetManualButton.addActionListener(e -> onResetManual());
        gbc.gridy = 0;
        resetButtonPanel.add(resetManualButton, gbc);

        JButton clearButton = new JButton("Reset All Unlocks");
        clearButton.addActionListener(e -> onClearAll());
        gbc.gridy = 1;
        resetButtonPanel.add(clearButton, gbc);

        // Help text
        JLabel helpLabel = new JLabel("<html>Browse your Collection Log in-game to sync unlocks automatically.</html>");
        helpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        helpLabel.setBorder(new EmptyBorder(10, 0, 0, 0));

        // Layout
        JPanel headerPanel = new JPanel(new BorderLayout(0, 5));
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        headerPanel.add(statsLabel, BorderLayout.NORTH);
        headerPanel.add(searchField, BorderLayout.CENTER);
        headerPanel.add(manualOnlyCheckbox, BorderLayout.SOUTH);

        // Unlock section
        JPanel unlockSection = new JPanel(new BorderLayout());
        unlockSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        unlockSection.add(unlockScrollPane, BorderLayout.CENTER);
        unlockSection.add(unlockButtonPanel, BorderLayout.SOUTH);

        // Locked section
        JPanel lockedSection = new JPanel(new BorderLayout());
        lockedSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        lockedSection.add(lockedLabel, BorderLayout.NORTH);
        lockedSection.add(lockedScrollPane, BorderLayout.CENTER);
        lockedSection.add(lockedButtonPanel, BorderLayout.SOUTH);

        // Center panel with both lists
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        centerPanel.add(headerPanel, BorderLayout.NORTH);
        centerPanel.add(unlockSection, BorderLayout.CENTER);
        centerPanel.add(lockedSection, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        // Bottom panel with reset buttons and help
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bottomPanel.add(resetButtonPanel, BorderLayout.NORTH);
        bottomPanel.add(helpLabel, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    /**
     * Refresh the panel data from the plugin on the EDT
     */
    public void refresh()
    {
        SwingUtilities.invokeLater(this::updateFromPlugin);
    }

    private void updateFromPlugin()
    {
        Map<Integer, ClogmanPlugin.ClogItem> clogItems = plugin.getCollectionLogItems();
        allEntries = new ArrayList<>();
        allLockedEntries = new ArrayList<>();

        // Build unlocked items list
        for (Integer itemId : plugin.getUnlockedClogItems())
        {
            ClogmanPlugin.ClogItem clogItem = clogItems.get(itemId);
            String name = clogItem != null ? clogItem.name : "Unknown (ID: " + itemId + ")";

            // Pre-load and cache icon
            BufferedImage img = itemManager.getImage(itemId);
            ImageIcon icon = img != null ? new ImageIcon(img) : null;

            allEntries.add(new UnlockEntry(itemId, name, icon));
        }

        // Build locked items list
        for (Integer itemId : plugin.getManuallyRemoved())
        {
            ClogmanPlugin.ClogItem clogItem = clogItems.get(itemId);
            String name = clogItem != null ? clogItem.name : "Unknown (ID: " + itemId + ")";

            // Pre-load and cache icon
            BufferedImage img = itemManager.getImage(itemId);
            ImageIcon icon = img != null ? new ImageIcon(img) : null;

            allLockedEntries.add(new UnlockEntry(itemId, name, icon));
        }

        // Sort alphabetically
        allEntries.sort(Comparator.comparing(e -> e.name.toLowerCase()));
        allLockedEntries.sort(Comparator.comparing(e -> e.name.toLowerCase()));

        // Update stats
        int unlocked = plugin.getUnlockedCount();
        int total = plugin.getTotalClogItems();
        statsLabel.setText("Unlocked: " + unlocked + " / " + total);

        // Apply current filter
        filterLists();
    }

    private void filterLists()
    {
        String search = searchField.getText().toLowerCase().trim();
        boolean manualOnly = manualOnlyCheckbox.isSelected();

        // Filter unlock list
        listModel.clear();
        for (UnlockEntry entry : allEntries)
        {
            // Check search filter
            if (!search.isEmpty() && !entry.name.toLowerCase().contains(search))
            {
                continue;
            }

            // Check manual-only filter
            if (manualOnly && !plugin.getManuallyAdded().contains(entry.itemId))
            {
                continue;
            }

            listModel.addElement(entry);
        }

        // Filter locked list
        lockedListModel.clear();
        for (UnlockEntry entry : allLockedEntries)
        {
            // Only apply search filter to locked list (manual-only doesn't apply here)
            if (search.isEmpty() || entry.name.toLowerCase().contains(search))
            {
                lockedListModel.addElement(entry);
            }
        }
    }

    private void onAdd()
    {
        // Use chatbox item search to find and add an item
        // This requires the game to be open
        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
        {
            JOptionPane.showMessageDialog(this,
                "You must be logged in to add unlocks.",
                "Not Logged In",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        clientThread.invoke(() ->
        {
            chatboxItemSearch
                .tooltipText("Select item to unlock")
                .onItemSelected((itemId) ->
                {
                    clientThread.invokeLater(() ->
                    {
                        // Find the clog item ID for this item
                        // The search might return a variant ID, so we need to check
                        Integer clogId = findClogItemId(itemId);
                        if (clogId != null)
                        {
                            plugin.unlockItem(clogId);
                            refresh();
                        }
                        else
                        {
                            // Item is not in collection log
                            SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(this,
                                    "This item is not in the Collection Log.",
                                    "Invalid Item",
                                    JOptionPane.WARNING_MESSAGE));
                        }
                        return true;
                    });
                })
                .build();
        });
    }

    /**
     * Find the collection log item ID for a given item ID.
     * Returns null if the item is not a clog item.
     */
    private Integer findClogItemId(int itemId)
    {
        // Direct match
        if (plugin.getCollectionLogItems().containsKey(itemId))
        {
            return itemId;
        }

        // Check if it's a variant of a clog item
        for (Map.Entry<Integer, ClogmanPlugin.ClogItem> entry : plugin.getCollectionLogItems().entrySet())
        {
            ClogmanPlugin.ClogItem clogItem = entry.getValue();
            if (clogItem.getAllIds().contains(itemId))
            {
                return entry.getKey();
            }
        }

        return null;
    }

    private void onLock()
    {
        List<UnlockEntry> selected = unlockList.getSelectedValuesList();
        if (selected.isEmpty())
        {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Lock " + selected.size() + " item(s)?\nThey will remain locked even if found in your collection log.",
            "Confirm Lock",
            JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION)
        {
            for (UnlockEntry entry : selected)
            {
                plugin.lockItem(entry.itemId);
            }
            refresh();
        }
    }

    private void onUnlock()
    {
        List<UnlockEntry> selected = lockedList.getSelectedValuesList();
        if (selected.isEmpty())
        {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Unlock " + selected.size() + " item(s)?",
            "Confirm Unlock",
            JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION)
        {
            for (UnlockEntry entry : selected)
            {
                // Re-add to unlocked items
                plugin.unlockItem(entry.itemId);
            }
            refresh();
        }
    }

    private void onResetManual()
    {
        if (plugin.getManuallyAdded().isEmpty() && plugin.getManuallyRemoved().isEmpty())
        {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Reset all manual changes?\n" +
            "- " + plugin.getManuallyAdded().size() + " manually added unlock(s) will be removed\n" +
            "- " + plugin.getManuallyRemoved().size() + " manually locked item(s) will be unlocked",
            "Confirm Reset Manual Changes",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION)
        {
            plugin.resetManualChanges();
            refresh();
        }
    }

    private void onClearAll()
    {
        if (listModel.isEmpty())
        {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to reset ALL unlocks?\nThis cannot be undone.",
            "Confirm Reset",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION)
        {
            plugin.resetAllUnlocks();
            refresh();
        }
    }

    /**
     * Custom list cell renderer that shows item icons
     */
    private class UnlockRenderer extends JPanel implements ListCellRenderer<UnlockEntry>
    {
        private final JLabel iconLabel;
        private final JLabel nameLabel;

        public UnlockRenderer()
        {
            setLayout(new BorderLayout(5, 0));
            setBorder(new EmptyBorder(2, 5, 2, 5));

            iconLabel = new JLabel();
            iconLabel.setPreferredSize(new Dimension(24, 24));
            add(iconLabel, BorderLayout.WEST);

            nameLabel = new JLabel();
            nameLabel.setForeground(Color.WHITE);
            add(nameLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends UnlockEntry> list,
            UnlockEntry value,
            int index,
            boolean isSelected,
            boolean cellHasFocus)
        {
            nameLabel.setText(value.name);

            // Use pre-cached icon (loaded once in updateFromPlugin)
            iconLabel.setIcon(value.icon);

            if (isSelected)
            {
                setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            }
            else
            {
                setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }

            return this;
        }
    }

    /**
     * Data class for unlock entries with cached icon
     */
    private static class UnlockEntry
    {
        final int itemId;
        final String name;
        final ImageIcon icon;

        UnlockEntry(int itemId, String name, ImageIcon icon)
        {
            this.itemId = itemId;
            this.name = name;
            this.icon = icon;
        }
    }
}
