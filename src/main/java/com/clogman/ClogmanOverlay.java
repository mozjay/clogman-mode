package com.clogman;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Overlay that dims locked items in inventory and bank
 */
public class ClogmanOverlay extends WidgetItemOverlay
{
    private final Client client;
    private final ClogmanPlugin plugin;
    private final ClogmanConfig config;
    private final ItemManager itemManager;

    @Inject
    public ClogmanOverlay(Client client, ClogmanPlugin plugin, ClogmanConfig config, ItemManager itemManager)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;

        showOnInventory();
        showOnBank();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        if (plugin.isItemLocked(itemId))
        {
            Rectangle bounds = widgetItem.getCanvasBounds();
            if (bounds == null)
            {
                return;
            }

            // Determine opacity based on where item is displayed
            int opacity;
            Widget widget = widgetItem.getWidget();
            if (widget != null && isInBank(widget))
            {
                opacity = config.bankOpacity();
            }
            else
            {
                opacity = config.inventoryOpacity();
            }

            // Draw a semi-transparent overlay to dim the item
            Color dimColor = new Color(0, 0, 0, 255 - opacity);
            graphics.setColor(dimColor);
            graphics.fill(bounds);
        }
    }

    private boolean isInBank(Widget widget)
    {
        // Bank item container is group 12 (WidgetID.BANK_GROUP_ID)
        int groupId = widget.getParentId() >> 16;
        return groupId == 12;
    }
}
