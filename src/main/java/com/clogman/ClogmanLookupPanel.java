package com.clogman;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lookup tab: search any collection log or derived item and see which unlocks it needs.
 * Everything comes from data already in memory, so it works while logged out. Icons are
 * created once per item and only for rows actually shown, never inside a renderer.
 */
class ClogmanLookupPanel extends JPanel
{
    private static final String EXPLORER_URL = "https://mozjay.github.io/osrs-clog-dependencies/";
    private static final String HINT =
        "Type an item name, then click a result to see what unlocks it.";
    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_RESULTS = 30;
    private static final int TEXT_WIDTH = 170;
    private static final int HEADER_TEXT_WIDTH = 130;
    private static final int RESULT_ROWS = 8;

    private final ClogmanPlugin plugin;
    private final ItemManager itemManager;

    private final List<Entry> index = new ArrayList<>();
    private final Map<Integer, Entry> clogEntries = new HashMap<>();
    private final Map<Integer, ImageIcon> iconCache = new HashMap<>();

    private final IconTextField searchField = new IconTextField();
    private final DefaultListModel<Entry> resultsModel = new DefaultListModel<>();
    private final JList<Entry> results = new JList<>(resultsModel);
    private final JPanel detail = new JPanel();
    private Entry selected;

    ClogmanLookupPanel(ClogmanPlugin plugin, ItemManager itemManager)
    {
        this.plugin = plugin;
        this.itemManager = itemManager;
        buildIndex();

        setLayout(new BorderLayout(0, 5));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        searchField.setIcon(IconTextField.Icon.SEARCH);
        searchField.setPreferredSize(new Dimension(0, 30));
        searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
        searchField.getDocument().addDocumentListener(new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e) { search(); }
            public void removeUpdate(DocumentEvent e) { search(); }
            public void changedUpdate(DocumentEvent e) { search(); }
        });
        add(searchField, BorderLayout.NORTH);

        results.setCellRenderer(new ResultRenderer());
        results.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setVisibleRowCount(RESULT_ROWS);
        results.addListSelectionListener(e ->
        {
            if (!e.getValueIsAdjusting() && results.getSelectedValue() != null)
            {
                show(results.getSelectedValue());
            }
        });
        JScrollPane resultsScroll = new JScrollPane(results);
        resultsScroll.setBorder(BorderFactory.createEmptyBorder());

        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setBackground(ColorScheme.DARK_GRAY_COLOR);
        detail.setBorder(new EmptyBorder(8, 0, 0, 0));
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(ColorScheme.DARK_GRAY_COLOR);
        center.add(resultsScroll, BorderLayout.NORTH);
        center.add(detail, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JLabel link = new JLabel("<html><u>Full dependency graph explorer</u></html>");
        link.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(EXPLORER_URL);
        link.setBorder(new EmptyBorder(8, 0, 0, 0));
        link.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                LinkBrowser.browse(EXPLORER_URL);
            }
        });
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.add(link, BorderLayout.NORTH);
        JLabel footerNote = wrapped(
            "Restrictions depend on the plugin settings. Manual locks and unlocks override them.",
            ColorScheme.LIGHT_GRAY_COLOR);
        footerNote.setBorder(new EmptyBorder(8, 0, 0, 0));
        footer.add(footerNote, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        showHint(HINT);
    }

    /**
     * Unlock state may have changed: redraw the open card and the result rows.
     */
    void refresh()
    {
        if (selected != null)
        {
            show(selected);
        }
        results.repaint();
    }

    private void buildIndex()
    {
        for (Map.Entry<Integer, ClogmanPlugin.ClogItem> e : plugin.getCollectionLogItems().entrySet())
        {
            Entry entry = new Entry(e.getKey(), e.getValue().name, e.getValue(), null);
            index.add(entry);
            clogEntries.put(e.getKey(), entry);
        }
        for (ClogmanPlugin.DerivedItem derived : plugin.getDerivedItems().values())
        {
            if (!derived.getAllItemIds().isEmpty())
            {
                index.add(new Entry(derived.getAllItemIds().get(0), capitalize(derived.name), null, derived));
            }
        }
        index.sort(Comparator.comparing(e -> e.lowerName));
    }

    private void search()
    {
        String query = searchField.getText().toLowerCase().trim();
        resultsModel.clear();
        if (query.length() < MIN_QUERY_LENGTH)
        {
            // Clearing the search closes the open card too - the field's own
            // clear button is otherwise the only way out of it
            selected = null;
            results.clearSelection();
            showHint(HINT);
            return;
        }

        // Prefix matches first, then anything containing the query
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : index)
        {
            if (entry.lowerName.startsWith(query))
            {
                matches.add(entry);
            }
        }
        for (Entry entry : index)
        {
            if (matches.size() >= MAX_RESULTS)
            {
                break;
            }
            if (!entry.lowerName.startsWith(query) && entry.lowerName.contains(query))
            {
                matches.add(entry);
            }
        }
        if (matches.size() > MAX_RESULTS)
        {
            matches = matches.subList(0, MAX_RESULTS);
        }

        for (Entry entry : matches)
        {
            icon(entry.itemId);  // warm the cache off the paint path
            resultsModel.addElement(entry);
        }
    }

    private void show(Entry entry)
    {
        selected = entry;
        detail.removeAll();

        JPanel header = new JPanel(new BorderLayout(5, 0));
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setAlignmentX(LEFT_ALIGNMENT);
        JLabel iconLabel = new JLabel(icon(entry.itemId));
        iconLabel.setPreferredSize(new Dimension(32, 32));
        header.add(iconLabel, BorderLayout.WEST);
        JPanel titles = new JPanel(new GridLayout(0, 1));
        titles.setBackground(ColorScheme.DARK_GRAY_COLOR);
        titles.add(wrapped("<b>" + entry.name + "</b>", Color.WHITE, HEADER_TEXT_WIDTH));
        boolean have = isUnlocked(entry);
        titles.add(wrapped(have ? "Unlocked" : "Locked",
            have ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR, HEADER_TEXT_WIDTH));
        header.add(titles, BorderLayout.CENTER);
        detail.add(header);
        detail.add(wrapped(describe(entry), ColorScheme.LIGHT_GRAY_COLOR));

        addSources(entry);

        if (entry.clog != null)
        {
            // Crafting only counts as an unlock while the craftable-from toggle
            // allows it, so don't advertise recipes that currently do nothing
            List<List<Integer>> recipes = entry.clog.getCraftableFrom();
            if (!recipes.isEmpty() && !plugin.getConfig().restrictCraftableUnlocks())
            {
                addWays("Or craft it from", recipes);
            }
        }
        else
        {
            addWays("Unlock via", plugin.getEffectiveDependencies(entry.derived));
        }

        detail.revalidate();
        detail.repaint();
    }

    /**
     * Note the non-recipe ways to get this item. Only that a route exists -
     * which shop or which monster is the wiki's job, and for some items that
     * list runs to dozens of entries.
     */
    private void addSources(Entry entry)
    {
        if (entry.clog != null)
        {
            if (entry.clog.isShopBuyable())
            {
                detail.add(section("Also sold in a shop"));
            }
            return;
        }

        if (!entry.derived.isDropObtainable())
        {
            return;
        }

        detail.add(section("Also drops directly"));

        // Worth calling out only in the non-obvious case: the drop setting is
        // off, yet this item is still restricted because the drop itself is
        // gated (the shade chest only drops a runescroll once you have read
        // the matching book).
        if (!plugin.getConfig().restrictDropObtainable()
            && !plugin.getEffectiveDependencies(entry.derived).isEmpty())
        {
            detail.add(wrapped("This drop is itself gated by the requirements below.",
                ColorScheme.LIGHT_GRAY_COLOR));
        }
    }

    private void addWays(String heading, List<List<Integer>> ways)
    {
        if (ways.isEmpty())
        {
            return;
        }

        List<List<Integer>> sorted = new ArrayList<>(ways);
        sorted.sort(Comparator.comparingInt(this::missingCount));

        String count = sorted.size() == 1 ? "" : " (" + sorted.size() + " ways)";
        detail.add(section(heading + count + ":"));

        for (int i = 0; i < sorted.size(); i++)
        {
            if (sorted.size() > 1)
            {
                detail.add(wrapped("Option " + (i + 1), ColorScheme.LIGHT_GRAY_COLOR));
            }
            for (int clogId : sorted.get(i))
            {
                detail.add(requirementRow(clogId));
            }
        }
    }

    private JComponent requirementRow(int clogId)
    {
        ClogmanPlugin.ClogItem clog = plugin.getCollectionLogItems().get(clogId);
        String name = clog != null ? clog.name : "Unknown (" + clogId + ")";
        boolean have = plugin.isEffectivelyUnlocked(clogId);
        String via = have && !plugin.getUnlockedClogItems().contains(clogId) ? craftedVia(clog) : null;

        String text = "&nbsp;&nbsp;" + (have ? "&#10003; " : "&#10007; ") + name
            + (via != null ? " <font color='#a5a5a5'>(via " + via + ")</font>" : "");
        JLabel row = wrapped(text, have ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);

        Entry target = clogEntries.get(clogId);
        if (target != null)
        {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.setToolTipText("Show " + name);
            row.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    show(target);
                }
            });
        }
        return row;
    }

    /**
     * Names of the first recipe whose ingredients are all effectively unlocked.
     */
    private String craftedVia(ClogmanPlugin.ClogItem clog)
    {
        if (clog == null)
        {
            return null;
        }
        for (List<Integer> recipe : clog.getCraftableFrom())
        {
            if (recipe.stream().allMatch(plugin::isEffectivelyUnlocked))
            {
                List<String> names = new ArrayList<>();
                for (int id : recipe)
                {
                    ClogmanPlugin.ClogItem ingredient = plugin.getCollectionLogItems().get(id);
                    names.add(ingredient != null ? ingredient.name : String.valueOf(id));
                }
                return String.join(" + ", names);
            }
        }
        return null;
    }

    private int missingCount(List<Integer> way)
    {
        int missing = 0;
        for (int id : way)
        {
            if (!plugin.isEffectivelyUnlocked(id))
            {
                missing++;
            }
        }
        return missing;
    }

    private boolean isUnlocked(Entry entry)
    {
        return entry.clog != null ? plugin.isEffectivelyUnlocked(entry.itemId) : !plugin.isItemLocked(entry.itemId);
    }

    private String describe(Entry entry)
    {
        if (entry.clog == null)
        {
            if (plugin.getEffectiveDependencies(entry.derived).isEmpty())
            {
                return "Made from collection log items, but also drops directly";
            }
            return "Made from collection log items";
        }
        boolean direct = plugin.getUnlockedClogItems().contains(entry.itemId);
        return "Collection log item" + (isUnlocked(entry) && !direct ? ", unlocked via crafting" : "");
    }

    private void showHint(String text)
    {
        detail.removeAll();
        detail.add(wrapped(text, ColorScheme.LIGHT_GRAY_COLOR));
        detail.revalidate();
        detail.repaint();
    }

    private JLabel section(String text)
    {
        JLabel label = wrapped(text, Color.WHITE);
        label.setBorder(new EmptyBorder(8, 0, 2, 0));
        return label;
    }

    private JLabel wrapped(String html, Color color)
    {
        return wrapped(html, color, TEXT_WIDTH);
    }

    private JLabel wrapped(String html, Color color, int width)
    {
        JLabel label = new JLabel("<html><body style='width:" + width + "px'>" + html + "</body></html>");
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private ImageIcon icon(int itemId)
    {
        ImageIcon icon = iconCache.get(itemId);
        if (icon == null)
        {
            AsyncBufferedImage image = itemManager.getImage(itemId);
            icon = new ImageIcon(image);
            image.onLoaded(() -> SwingUtilities.invokeLater(this::repaint));
            iconCache.put(itemId, icon);
        }
        return icon;
    }

    private static String capitalize(String input)
    {
        return input == null || input.isEmpty() ? input : Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    private static class Entry
    {
        final int itemId;
        final String name;
        final String lowerName;
        final ClogmanPlugin.ClogItem clog;
        final ClogmanPlugin.DerivedItem derived;

        Entry(int itemId, String name, ClogmanPlugin.ClogItem clog, ClogmanPlugin.DerivedItem derived)
        {
            this.itemId = itemId;
            this.name = name;
            this.lowerName = name.toLowerCase();
            this.clog = clog;
            this.derived = derived;
        }
    }

    /**
     * Solid coloured dot: a crisp unlock-state marker that doesn't depend on font glyphs.
     */
    private static class DotIcon implements Icon
    {
        private static final int SIZE = 8;
        private final Color color;

        DotIcon(Color color)
        {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, SIZE, SIZE);
            g2.dispose();
        }

        @Override
        public int getIconWidth()
        {
            return SIZE;
        }

        @Override
        public int getIconHeight()
        {
            return SIZE;
        }
    }

    private static final Icon UNLOCKED_DOT = new DotIcon(ColorScheme.PROGRESS_COMPLETE_COLOR);
    private static final Icon LOCKED_DOT = new DotIcon(ColorScheme.PROGRESS_ERROR_COLOR);

    private class ResultRenderer extends JPanel implements ListCellRenderer<Entry>
    {
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel stateLabel = new JLabel();

        ResultRenderer()
        {
            setLayout(new BorderLayout(5, 0));
            setBorder(new EmptyBorder(2, 5, 2, 5));
            iconLabel.setPreferredSize(new Dimension(24, 24));
            nameLabel.setForeground(Color.WHITE);
            // Don't let long names widen the list: the label takes what's left and clips with "..."
            nameLabel.setPreferredSize(new Dimension(0, 24));
            stateLabel.setBorder(new EmptyBorder(0, 4, 0, 2));
            add(iconLabel, BorderLayout.WEST);
            add(nameLabel, BorderLayout.CENTER);
            add(stateLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry value, int index,
                                                      boolean isSelected, boolean cellHasFocus)
        {
            iconLabel.setIcon(iconCache.get(value.itemId));
            nameLabel.setText(value.name);
            setToolTipText(value.name);
            stateLabel.setIcon(isUnlocked(value) ? UNLOCKED_DOT : LOCKED_DOT);
            setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
            return this;
        }
    }
}
