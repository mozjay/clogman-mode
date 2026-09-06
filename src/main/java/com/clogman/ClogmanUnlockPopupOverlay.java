package com.clogman;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * On-screen popup reading "X new items unlocked!" with a smaller line of item names.
 * <p>
 * It is anchored beneath the game's own "Collection log" notification, running one game tick behind
 * it: shown that long after it has finished opening and lingering that long after it starts
 * closing, so both are on screen for the same time. Unlocks with no native popup (manual unlocks from
 * the side panel, or the popup disabled in game settings) are shown standalone at the top-centre of
 * the viewport for a fixed time instead. The panel is drawn with the game's own popup sprites,
 * captured from the native widgets the first time one is seen.
 * <p>
 * All state is only touched on the client thread.
 */
@Slf4j
@Singleton
public class ClogmanUnlockPopupOverlay extends Overlay
{
    private static final String COLLECTION_LOG_TITLE = "Collection log";
    private static final String NEW_ITEM_PREFIX = "New item:";

    // How long an unlock waits for a native popup before being shown standalone. Also keeps
    // standalone popups clear of a native popup that has just closed.
    private static final long GRACE_MILLIS = 1500;
    // How long unlocks with no native popup wait before showing, so a batch of manual unlocks
    // merges into one popup before anything is drawn (one game tick)
    private static final long SETTLE_MILLIS = 600;
    // Anchored popups run one game tick behind the native popup: they appear that long after it
    // has opened and linger that long after it starts closing
    private static final long SHIFT_MILLIS = 600;
    private static final long STANDALONE_MILLIS = 6000;
    private static final long FADE_IN_MILLIS = 150;
    private static final long FADE_OUT_MILLIS = 300;
    // Consecutive frames the native popup must shrink before it counts as closing
    private static final int CLOSING_FRAMES = 2;

    private static final int GAP = 3;
    private static final int TOP_MARGIN = 2;
    private static final int MIN_WIDTH = 140;
    private static final int MAX_WIDTH = 260;
    private static final int PADDING = 4;
    private static final int SIDE_PADDING = 10;
    private static final int ICON_SIZE = 24;
    private static final int TEXT_GAP = 6;

    // Matches the native popup: orange heading, white body text
    private static final Color TITLE_COLOR = new Color(0xFF981F);
    private static final Color NAMES_COLOR = Color.WHITE;
    private static final Color FALLBACK_BACKGROUND = new Color(0x2B2620);
    private static final Color FALLBACK_BORDER = new Color(0x5A5040);

    private final Client client;
    private final ClogmanConfig config;
    private final SpriteManager spriteManager;
    private final BufferedImage icon;

    private final Deque<PendingPopup> queue = new ArrayDeque<>();
    private PendingPopup current;
    // Whether current follows the native popup; once that closes it lingers unanchored
    private boolean currentAnchored;
    // When current becomes visible (0 = not yet decided) and when it is gone (0 = open-ended,
    // i.e. until the native popup closes)
    private long showFromMillis;
    private long showUntilMillis;
    // Row an anchored popup was last drawn on, kept for its linger after the native popup closes
    private int anchoredY;

    // Native popup animation tracking
    private long lastNativeSeenMillis;
    private int maxNativeHeight;
    private int shrinkingFrames;
    private boolean nativeOpening;

    private NativeStyle style = NativeStyle.DEFAULT;
    private final Map<Integer, BufferedImage> spriteCache = new HashMap<>();
    private final Map<String, BufferedImage> flipCache = new HashMap<>();

    @Inject
    public ClogmanUnlockPopupOverlay(Client client, ClogmanConfig config, SpriteManager spriteManager)
    {
        this.client = client;
        this.config = config;
        this.spriteManager = spriteManager;

        BufferedImage loaded = ImageUtil.loadImageResource(ClogmanUnlockPopupOverlay.class, "/clogman-icon.png");
        this.icon = loaded != null ? ImageUtil.resizeImage(loaded, ICON_SIZE, ICON_SIZE) : null;

        // Drawn in canvas coordinates above the game's widgets (the native popup is one), at a
        // position derived from the native popup, so it is not draggable.
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGHEST);
        setMovable(false);
    }

    /**
     * Queues a popup for an unlock. Unlocks without a native popup are merged into any standalone
     * popup already pending, so a batch of manual unlocks yields a single popup.
     *
     * @param clogItemName      collection log item name, matched against the native popup text
     * @param names             the additional items the unlock made available (size is the count shown)
     * @param expectNativePopup whether the game will show its own popup for this unlock
     */
    public void enqueue(String clogItemName, List<String> names, boolean expectNativePopup)
    {
        if (names.isEmpty())
        {
            return;
        }

        if (!expectNativePopup)
        {
            PendingPopup standalone = findPendingStandalone();
            if (standalone != null)
            {
                standalone.names.addAll(names);
                standalone.layout = null;
                return;
            }
        }

        queue.addLast(new PendingPopup(clogItemName, new ArrayList<>(names), expectNativePopup));
    }

    public void clear()
    {
        queue.clear();
        current = null;
    }

    /**
     * Forwarded from the plugin's ScriptPreFired subscriber. NOTIFICATION_START runs the native
     * popup's open animation; NOTIFICATION_DELAY holds it at full size.
     */
    public void onNotificationScript(int scriptId)
    {
        if (scriptId == ScriptID.NOTIFICATION_START)
        {
            nativeOpening = true;
            maxNativeHeight = 0;
            shrinkingFrames = 0;
        }
        else if (scriptId == ScriptID.NOTIFICATION_DELAY)
        {
            nativeOpening = false;
        }
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return null;
        }
        if (!config.showUnlockPopup())
        {
            clear();
            return null;
        }

        long now = System.currentTimeMillis();
        NativePopup nativePopup = observeNativePopup(now);
        updateCurrent(nativePopup, now);
        if (current == null)
        {
            return null;
        }

        int centerX;
        int y;
        if (currentAnchored)
        {
            if (nativeOpening)
            {
                return null;
            }
            if (showFromMillis == 0)
            {
                showFromMillis = now + SHIFT_MILLIS;
            }
            centerX = nativePopup.centerX();
            y = nativePopup.bottom() + GAP;
            anchoredY = y;
        }
        else if (anchoredY > 0)
        {
            // Lingering after its native popup: hold the row it was anchored on
            centerX = getViewportCenterX();
            y = anchoredY;
        }
        else if (nativePopup != null)
        {
            // Standalone beneath a native popup for something we aren't tracking, rather than over it
            centerX = nativePopup.centerX();
            y = nativePopup.bottom() + GAP;
        }
        else
        {
            centerX = getViewportCenterX();
            y = TOP_MARGIN;
        }

        if (now < showFromMillis)
        {
            return null;
        }
        float alpha = clamp01((now - showFromMillis) / (float) FADE_IN_MILLIS);
        if (showUntilMillis > 0)
        {
            alpha = Math.min(alpha, clamp01((showUntilMillis - now) / (float) FADE_OUT_MILLIS));
        }
        return drawPanel(graphics, centerX, y, alpha);
    }

    // === NATIVE POPUP ===

    /**
     * The game's notification popup, if one is visible. Any notification counts (level-ups, combat
     * tasks, etc.) so our standalone popups keep clear of them; only collection log ones carry an
     * item name and can be anchored to.
     */
    private NativePopup observeNativePopup(long now)
    {
        String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
        Rectangle bounds = title == null || title.isEmpty() ? null : getNativeBounds();
        if (bounds == null)
        {
            maxNativeHeight = 0;
            shrinkingFrames = 0;
            return null;
        }

        lastNativeSeenMillis = now;
        captureNativeStyle();

        if (bounds.height < maxNativeHeight)
        {
            shrinkingFrames++;
        }
        else
        {
            maxNativeHeight = bounds.height;
            shrinkingFrames = 0;
        }

        String itemName = COLLECTION_LOG_TITLE.equalsIgnoreCase(title)
            ? parseNativeItemName(client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN))
            : null;
        return new NativePopup(bounds, itemName, shrinkingFrames >= CLOSING_FRAMES);
    }

    private void updateCurrent(NativePopup nativePopup, long now)
    {
        String nativeItem = nativePopup != null ? nativePopup.itemName : null;

        if (current != null && currentAnchored
            && (nativePopup == null || nativePopup.closing || !current.clogItemName.equalsIgnoreCase(nativeItem)))
        {
            // The native popup is going: linger for the same shift we appeared with, or give up
            // if we never got to appear
            currentAnchored = false;
            showUntilMillis = showFromMillis > 0 ? Math.max(now, showFromMillis) + SHIFT_MILLIS : now;
        }
        if (current != null && showUntilMillis > 0 && now >= showUntilMillis)
        {
            current = null;
        }

        // A native collection log popup takes precedence over any unanchored popup in progress
        if (nativeItem != null && (current == null || !currentAnchored))
        {
            PendingPopup match = pollMatching(nativeItem);
            if (match != null)
            {
                start(match, true, 0, 0);
            }
        }

        // Otherwise show the oldest pending unlock standalone once it has had its chance at a
        // native popup (or, for unlocks that never get one, once its batch has settled)
        if (current == null && nativePopup == null && now - lastNativeSeenMillis > GRACE_MILLIS)
        {
            PendingPopup head = queue.peekFirst();
            if (head != null && now - head.enqueuedAt > (head.expectNativePopup ? GRACE_MILLIS : SETTLE_MILLIS))
            {
                start(queue.pollFirst(), false, now, now + STANDALONE_MILLIS);
            }
        }
    }

    private void start(PendingPopup popup, boolean anchored, long showFrom, long showUntil)
    {
        current = popup;
        currentAnchored = anchored;
        showFromMillis = showFrom;
        showUntilMillis = showUntil;
        anchoredY = 0;
    }

    private Rectangle getNativeBounds()
    {
        Rectangle bounds = null;
        for (int componentId : new int[]{InterfaceID.NotificationDisplay.FRAME, InterfaceID.NotificationDisplay.BACKGROUND})
        {
            Widget widget = client.getWidget(componentId);
            if (widget == null || widget.isHidden())
            {
                continue;
            }
            Rectangle b = widget.getBounds();
            if (b != null && b.width > 0 && b.height > 0)
            {
                bounds = bounds == null ? new Rectangle(b) : bounds.union(b);
            }
        }
        return bounds;
    }

    private static String parseNativeItemName(String mainText)
    {
        if (mainText == null)
        {
            return null;
        }
        String text = Text.removeTags(mainText).trim();
        if (text.regionMatches(true, 0, NEW_ITEM_PREFIX, 0, NEW_ITEM_PREFIX.length()))
        {
            text = text.substring(NEW_ITEM_PREFIX.length()).trim();
        }
        return text.isEmpty() ? null : text;
    }

    private PendingPopup pollMatching(String itemName)
    {
        for (Iterator<PendingPopup> it = queue.iterator(); it.hasNext(); )
        {
            PendingPopup pending = it.next();
            if (pending.clogItemName.equalsIgnoreCase(itemName))
            {
                it.remove();
                return pending;
            }
        }
        return null;
    }

    private PendingPopup findPendingStandalone()
    {
        for (PendingPopup pending : queue)
        {
            if (!pending.expectNativePopup)
            {
                return pending;
            }
        }
        return null;
    }

    /**
     * Reads the sprites the native popup is drawn with so our panel can reuse them. Captured once
     * per session; the frame's 8 dynamic children are classified by where they sit.
     */
    private void captureNativeStyle()
    {
        if (style.captured)
        {
            return;
        }

        Widget background = client.getWidget(InterfaceID.NotificationDisplay.BACKGROUND);
        Widget frame = client.getWidget(InterfaceID.NotificationDisplay.FRAME);
        Widget[] pieces = frame != null ? frame.getDynamicChildren() : null;
        Rectangle frameBounds = frame != null ? frame.getBounds() : null;
        if (background == null || background.getSpriteId() <= 0 || pieces == null || pieces.length < 8
            || frameBounds == null || frameBounds.width <= 0 || frameBounds.height <= 0)
        {
            return;
        }

        NativeStyle captured = new NativeStyle();
        captured.background = background.getSpriteId();
        captured.backgroundTiled = background.getSpriteTiling();

        int centerX = frameBounds.x + frameBounds.width / 2;
        int centerY = frameBounds.y + frameBounds.height / 2;
        for (Widget piece : pieces)
        {
            Rectangle b = piece != null ? piece.getBounds() : null;
            if (b == null || b.width <= 0 || b.height <= 0 || piece.getSpriteId() <= 0)
            {
                continue;
            }
            int id = piece.getSpriteId();
            boolean wide = b.width >= frameBounds.width / 2;
            boolean tall = b.height >= frameBounds.height / 2;
            boolean top = b.y + b.height / 2 < centerY;
            boolean left = b.x + b.width / 2 < centerX;

            if (wide && !tall)
            {
                if (top) captured.edgeTop = id; else captured.edgeBottom = id;
            }
            else if (tall && !wide)
            {
                if (left) captured.edgeLeft = id; else captured.edgeRight = id;
            }
            else if (!wide)
            {
                if (top && left) captured.cornerTopLeft = id;
                else if (top) captured.cornerTopRight = id;
                else if (left) captured.cornerBottomLeft = id;
                else captured.cornerBottomRight = id;
            }
        }

        if (!captured.isComplete())
        {
            log.debug("Could not classify native popup frame pieces");
            return;
        }

        captured.captured = true;
        style = captured;
        spriteCache.clear();
        flipCache.clear();
        log.info("Captured native popup style: background={} (tiled={}), corners=[{}, {}, {}, {}], edges=[top={}, bottom={}, left={}, right={}]",
            captured.background, captured.backgroundTiled,
            captured.cornerTopLeft, captured.cornerTopRight, captured.cornerBottomLeft, captured.cornerBottomRight,
            captured.edgeTop, captured.edgeBottom, captured.edgeLeft, captured.edgeRight);
    }

    // === DRAWING ===

    private Dimension drawPanel(Graphics2D graphics, int centerX, int y, float alpha)
    {
        BufferedImage background = sprite(style.background);
        BufferedImage topLeft = sprite(style.cornerTopLeft);
        BufferedImage topRight = sprite(style.cornerTopRight);
        BufferedImage bottomLeft = sprite(style.cornerBottomLeft);
        BufferedImage bottomRight = sprite(style.cornerBottomRight);
        BufferedImage edgeTop = sprite(style.edgeTop);
        BufferedImage edgeBottom = sprite(style.edgeBottom);
        BufferedImage edgeLeft = sprite(style.edgeLeft);
        BufferedImage edgeRight = sprite(style.edgeRight);
        boolean spritesOk = background != null && topLeft != null && topRight != null && bottomLeft != null
            && bottomRight != null && edgeTop != null && edgeBottom != null && edgeLeft != null && edgeRight != null;

        int insetX = spritesOk ? Math.max(edgeLeft.getWidth(), edgeRight.getWidth()) : 1;
        int insetY = spritesOk ? Math.max(edgeTop.getHeight(), edgeBottom.getHeight()) : 1;
        int iconSpan = icon != null ? ICON_SIZE + TEXT_GAP : 0;
        int chromeWidth = insetX * 2 + SIDE_PADDING * 2 + iconSpan;

        Layout layout = current.layout;
        if (layout == null || layout.chromeWidth != chromeWidth)
        {
            layout = Layout.of(graphics, current.names, chromeWidth);
            current.layout = layout;
        }

        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, chromeWidth + layout.textWidth));
        int textBlockHeight = layout.titleMetrics.getAscent() + layout.titleMetrics.getDescent() + layout.namesMetrics.getHeight();
        int contentHeight = Math.max(ICON_SIZE, textBlockHeight);
        int height = insetY * 2 + PADDING * 2 + contentHeight;
        int x = centerX - width / 2;

        Composite originalComposite = graphics.getComposite();
        Object originalTextAa = graphics.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        if (alpha < 1f)
        {
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, alpha)));
        }
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        if (spritesOk)
        {
            tile(graphics, background, style.backgroundTiled, x + insetX, y + insetY, width - insetX * 2, height - insetY * 2);
            drawFrame(graphics, x, y, width, height, topLeft, topRight, bottomLeft, bottomRight, edgeTop, edgeBottom, edgeLeft, edgeRight);
        }
        else
        {
            graphics.setColor(FALLBACK_BACKGROUND);
            graphics.fillRect(x, y, width, height);
            graphics.setColor(FALLBACK_BORDER);
            graphics.drawRect(x, y, width - 1, height - 1);
        }

        // Icon and text are centred as one group (only matters at the minimum width), and each
        // text line is centred within the text column
        int contentX = x + insetX + SIDE_PADDING;
        int contentY = y + insetY + PADDING;
        int groupX = contentX + (width - chromeWidth - layout.textWidth) / 2;
        int textX = groupX + iconSpan;
        if (icon != null)
        {
            graphics.drawImage(icon, groupX, contentY + (contentHeight - ICON_SIZE) / 2, null);
        }

        int titleBaseline = contentY + (contentHeight - textBlockHeight) / 2 + layout.titleMetrics.getAscent();
        graphics.setFont(layout.titleMetrics.getFont());
        drawShadowedString(graphics, layout.title, textX + (layout.textWidth - layout.titleMetrics.stringWidth(layout.title)) / 2, titleBaseline, TITLE_COLOR);
        int namesBaseline = titleBaseline + layout.titleMetrics.getDescent() + layout.namesMetrics.getAscent();
        graphics.setFont(layout.namesMetrics.getFont());
        drawShadowedString(graphics, layout.names, textX + (layout.textWidth - layout.namesMetrics.stringWidth(layout.names)) / 2, namesBaseline, NAMES_COLOR);

        graphics.setComposite(originalComposite);
        if (originalTextAa != null)
        {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, originalTextAa);
        }
        return new Dimension(width, height);
    }

    private void drawFrame(Graphics2D graphics, int x, int y, int width, int height,
                           BufferedImage topLeft, BufferedImage topRight, BufferedImage bottomLeft, BufferedImage bottomRight,
                           BufferedImage edgeTop, BufferedImage edgeBottom, BufferedImage edgeLeft, BufferedImage edgeRight)
    {
        // The game reuses one sprite for mirrored pieces (e.g. only a top edge and a right edge
        // exist), so mirror whenever a piece shares the sprite of its opposite
        BufferedImage tr = flipped(topRight, style.cornerTopRight == style.cornerTopLeft, false);
        BufferedImage bl = flipped(bottomLeft, false, style.cornerBottomLeft == style.cornerTopLeft);
        BufferedImage br = flipped(bottomRight,
            style.cornerBottomRight == style.cornerTopLeft || style.cornerBottomRight == style.cornerBottomLeft,
            style.cornerBottomRight == style.cornerTopLeft || style.cornerBottomRight == style.cornerTopRight);
        BufferedImage bottom = flipped(edgeBottom, false, style.edgeBottom == style.edgeTop);
        BufferedImage left = flipped(edgeLeft, style.edgeLeft == style.edgeRight, false);

        graphics.drawImage(topLeft, x, y, null);
        graphics.drawImage(tr, x + width - tr.getWidth(), y, null);
        graphics.drawImage(bl, x, y + height - bl.getHeight(), null);
        graphics.drawImage(br, x + width - br.getWidth(), y + height - br.getHeight(), null);

        tile(graphics, edgeTop, true, x + topLeft.getWidth(), y, width - topLeft.getWidth() - tr.getWidth(), edgeTop.getHeight());
        tile(graphics, bottom, true, x + bl.getWidth(), y + height - bottom.getHeight(), width - bl.getWidth() - br.getWidth(), bottom.getHeight());
        tile(graphics, left, true, x, y + topLeft.getHeight(), left.getWidth(), height - topLeft.getHeight() - bl.getHeight());
        tile(graphics, edgeRight, true, x + width - edgeRight.getWidth(), y + tr.getHeight(), edgeRight.getWidth(), height - tr.getHeight() - br.getHeight());
    }

    /**
     * Fills the rectangle with the image, either repeated at its natural size or stretched.
     */
    private static void tile(Graphics2D graphics, BufferedImage image, boolean repeat, int x, int y, int width, int height)
    {
        if (width <= 0 || height <= 0)
        {
            return;
        }
        if (!repeat)
        {
            graphics.drawImage(image, x, y, width, height, null);
            return;
        }
        Shape originalClip = graphics.getClip();
        graphics.clipRect(x, y, width, height);
        for (int ty = y; ty < y + height; ty += image.getHeight())
        {
            for (int tx = x; tx < x + width; tx += image.getWidth())
            {
                graphics.drawImage(image, tx, ty, null);
            }
        }
        graphics.setClip(originalClip);
    }

    private static void drawShadowedString(Graphics2D graphics, String text, int x, int y, Color color)
    {
        graphics.setColor(Color.BLACK);
        graphics.drawString(text, x + 1, y + 1);
        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }

    private BufferedImage sprite(int spriteId)
    {
        BufferedImage image = spriteCache.get(spriteId);
        if (image == null && spriteId > 0)
        {
            // Null while the cache is still loading; retried next frame
            image = spriteManager.getSprite(spriteId, 0);
            if (image != null)
            {
                spriteCache.put(spriteId, image);
            }
        }
        return image;
    }

    private BufferedImage flipped(BufferedImage image, boolean horizontal, boolean vertical)
    {
        if (!horizontal && !vertical)
        {
            return image;
        }
        String key = System.identityHashCode(image) + ":" + horizontal + ":" + vertical;
        return flipCache.computeIfAbsent(key, k -> ImageUtil.flipImage(image, horizontal, vertical));
    }

    private int getViewportCenterX()
    {
        return client.isResized()
            ? client.getRealDimensions().width / 2
            : client.getViewportXOffset() + client.getViewportWidth() / 2;
    }

    private static float clamp01(float value)
    {
        return Math.max(0f, Math.min(1f, value));
    }

    // === DATA ===

    private static class NativePopup
    {
        final Rectangle bounds;
        final String itemName; // null unless a collection log popup
        final boolean closing;

        NativePopup(Rectangle bounds, String itemName, boolean closing)
        {
            this.bounds = bounds;
            this.itemName = itemName;
            this.closing = closing;
        }

        int centerX()
        {
            return bounds.x + bounds.width / 2;
        }

        int bottom()
        {
            return bounds.y + bounds.height;
        }
    }

    private static class PendingPopup
    {
        final String clogItemName;
        final List<String> names;
        final boolean expectNativePopup;
        final long enqueuedAt = System.currentTimeMillis();
        Layout layout;

        PendingPopup(String clogItemName, List<String> names, boolean expectNativePopup)
        {
            this.clogItemName = clogItemName;
            this.names = names;
            this.expectNativePopup = expectNativePopup;
        }
    }

    /**
     * Measured text for a popup, computed once rather than every frame.
     */
    private static class Layout
    {
        final int chromeWidth;
        final String title;
        final String names;
        final int textWidth;
        final FontMetrics titleMetrics;
        final FontMetrics namesMetrics;

        private Layout(int chromeWidth, String title, String names, int textWidth,
                       FontMetrics titleMetrics, FontMetrics namesMetrics)
        {
            this.chromeWidth = chromeWidth;
            this.title = title;
            this.names = names;
            this.textWidth = textWidth;
            this.titleMetrics = titleMetrics;
            this.namesMetrics = namesMetrics;
        }

        static Layout of(Graphics2D graphics, List<String> items, int chromeWidth)
        {
            FontMetrics titleMetrics = graphics.getFontMetrics(FontManager.getRunescapeBoldFont());
            FontMetrics namesMetrics = graphics.getFontMetrics(FontManager.getRunescapeSmallFont());
            int maxTextWidth = MAX_WIDTH - chromeWidth;

            int count = items.size();
            String title = truncate(count == 1 ? "1 new item unlocked!" : count + " new items unlocked!", titleMetrics, maxTextWidth);
            String names = fitNames(items, namesMetrics, maxTextWidth);
            int textWidth = Math.max(titleMetrics.stringWidth(title), namesMetrics.stringWidth(names));
            return new Layout(chromeWidth, title, names, textWidth, titleMetrics, namesMetrics);
        }

        /**
         * Joins as many names as fit on one line, then "and K more" for the rest.
         */
        private static String fitNames(List<String> names, FontMetrics metrics, int maxWidth)
        {
            for (int shown = names.size(); shown >= 1; shown--)
            {
                int remaining = names.size() - shown;
                String candidate = String.join(", ", names.subList(0, shown))
                    + (remaining > 0 ? " and " + remaining + " more" : "");
                if (metrics.stringWidth(candidate) <= maxWidth)
                {
                    return candidate;
                }
            }
            return truncate(names.get(0), metrics, maxWidth);
        }

        private static String truncate(String text, FontMetrics metrics, int maxWidth)
        {
            if (metrics.stringWidth(text) <= maxWidth)
            {
                return text;
            }
            StringBuilder sb = new StringBuilder(text);
            while (sb.length() > 0 && metrics.stringWidth(sb + "...") > maxWidth)
            {
                sb.setLength(sb.length() - 1);
            }
            return sb + "...";
        }
    }

    /**
     * Sprite IDs used to draw the panel. Defaults to the standard steel border and dark trade
     * backing until the real native popup has been observed.
     */
    private static class NativeStyle
    {
        static final NativeStyle DEFAULT = new NativeStyle();

        static
        {
            DEFAULT.background = SpriteID.TRADEBACKING_DARK;
            DEFAULT.backgroundTiled = true;
            DEFAULT.cornerTopLeft = SpriteID.Steelborder.TOP_LEFT;
            DEFAULT.cornerTopRight = SpriteID.Steelborder.TOP_RIGHT;
            DEFAULT.cornerBottomLeft = SpriteID.Steelborder.BOTTOM_LEFT;
            DEFAULT.cornerBottomRight = SpriteID.Steelborder.BOTTOM_RIGHT;
            DEFAULT.edgeTop = SpriteID.Steelborder2.EDGE_TOP;
            DEFAULT.edgeBottom = SpriteID.Steelborder2.EDGE_TOP;
            DEFAULT.edgeLeft = SpriteID.Steelborder2.EDGE_RIGHT;
            DEFAULT.edgeRight = SpriteID.Steelborder2.EDGE_RIGHT;
        }

        boolean captured;
        int background;
        boolean backgroundTiled;
        int cornerTopLeft;
        int cornerTopRight;
        int cornerBottomLeft;
        int cornerBottomRight;
        int edgeTop;
        int edgeBottom;
        int edgeLeft;
        int edgeRight;

        boolean isComplete()
        {
            return background > 0 && cornerTopLeft > 0 && cornerTopRight > 0 && cornerBottomLeft > 0
                && cornerBottomRight > 0 && edgeTop > 0 && edgeBottom > 0 && edgeLeft > 0 && edgeRight > 0;
        }
    }
}
