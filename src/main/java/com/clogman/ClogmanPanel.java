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
    private final JLabel statsLabel;
    private final IconTextField searchField;

    // All entries (unfiltered) - source of truth for display
    private List<UnlockEntry> allEntries = new ArrayList<>();

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
            public void insertUpdate(DocumentEvent e) { filterList(); }
            @Override
            public void removeUpdate(DocumentEvent e) { filterList(); }
            @Override
            public void changedUpdate(DocumentEvent e) { filterList(); }
        });

        // List of unlocked items
        listModel = new DefaultListModel<>();
        unlockList = new JList<>(listModel);
        unlockList.setCellRenderer(new UnlockRenderer());
        unlockList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        unlockList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane scrollPane = new JScrollPane(unlockList);
        scrollPane.setPreferredSize(new Dimension(0, 300));
        scrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));

        // Button panel
        JPanel buttonPanel = new JPanel(new GridBagLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 0, 2, 0);

        JButton addButton = new JButton("Add Unlock");
        addButton.addActionListener(e -> onAdd());
        gbc.gridy = 0;
        buttonPanel.add(addButton, gbc);

        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> onRemove());
        gbc.gridy = 1;
        buttonPanel.add(removeButton, gbc);

        JButton clearButton = new JButton("Reset All Unlocks");
        clearButton.addActionListener(e -> onClearAll());
        gbc.gridy = 2;
        buttonPanel.add(clearButton, gbc);

        // Help text
        JLabel helpLabel = new JLabel("<html>Browse your Collection Log in-game to sync unlocks automatically.</html>");
        helpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        helpLabel.setBorder(new EmptyBorder(10, 0, 0, 0));

        // Layout
        JPanel headerPanel = new JPanel(new BorderLayout(0, 5));
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        headerPanel.add(statsLabel, BorderLayout.NORTH);
        headerPanel.add(searchField, BorderLayout.SOUTH);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topPanel.add(headerPanel, BorderLayout.NORTH);
        topPanel.add(scrollPane, BorderLayout.CENTER);

        add(topPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);
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

        for (Integer itemId : plugin.getUnlockedClogItems())
        {
            ClogmanPlugin.ClogItem clogItem = clogItems.get(itemId);
            String name = clogItem != null ? clogItem.name : "Unknown (ID: " + itemId + ")";

            // Pre-load and cache icon
            BufferedImage img = itemManager.getImage(itemId);
            ImageIcon icon = img != null ? new ImageIcon(img) : null;

            allEntries.add(new UnlockEntry(itemId, name, icon));
        }

        // Sort alphabetically
        allEntries.sort(Comparator.comparing(e -> e.name.toLowerCase()));

        // Update stats
        int unlocked = plugin.getUnlockedCount();
        int total = plugin.getTotalClogItems();
        statsLabel.setText("Unlocked: " + unlocked + " / " + total);

        // Apply current filter
        filterList();
    }

    private void filterList()
    {
        String search = searchField.getText().toLowerCase().trim();
        listModel.clear();

        for (UnlockEntry entry : allEntries)
        {
            if (search.isEmpty() || entry.name.toLowerCase().contains(search))
            {
                listModel.addElement(entry);
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

    private void onRemove()
    {
        List<UnlockEntry> selected = unlockList.getSelectedValuesList();
        if (selected.isEmpty())
        {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Remove " + selected.size() + " unlock(s)?",
            "Confirm Removal",
            JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION)
        {
            for (UnlockEntry entry : selected)
            {
                plugin.removeUnlock(entry.itemId);
            }
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
