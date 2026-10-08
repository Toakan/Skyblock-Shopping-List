package inventoryreader.ir;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Boxes with rounded corners, drawn from plain fills (Minecraft has no rounded-rectangle call). Corners are
 * stepped pixel quarter-circles; radius 0 draws an ordinary box.
 */
public final class RoundedBox {
    private RoundedBox() {}

    /** How far row {@code i} (0 = outermost) of a corner is pushed in. */
    private static int inset(int radius, int i) {
        double dy = radius - i - 0.5;
        return radius - (int) Math.round(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
    }

    private static int clampRadius(int radius, int w, int h) {
        return Math.max(0, Math.min(radius, Math.min(w, h) / 2));
    }

    /** Filled box; {@code top} / {@code bottom} choose which corners are rounded. */
    public static void fill(GuiGraphicsExtractor context, int x, int y, int w, int h, int radius,
                            boolean top, boolean bottom, int color) {
        int r = clampRadius(radius, w, h);
        if (r == 0) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        int bodyTop = top ? y + r : y;
        int bodyBottom = bottom ? y + h - r : y + h;
        if (bodyBottom > bodyTop) context.fill(x, bodyTop, x + w, bodyBottom, color);
        for (int i = 0; i < r; i++) {
            int in = inset(r, i);
            if (top) context.fill(x + in, y + i, x + w - in, y + i + 1, color);
            if (bottom) context.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, color);
        }
    }

    public static void fill(GuiGraphicsExtractor context, int x, int y, int w, int h, int radius, int color) {
        fill(context, x, y, w, h, radius, true, true, color);
    }

    /** One-pixel outline following the same rounded corners as {@link #fill}. */
    public static void outline(GuiGraphicsExtractor context, int x, int y, int w, int h, int radius, int color) {
        int r = clampRadius(radius, w, h);
        if (r == 0) {
            context.outline(x, y, w, h, color);
            return;
        }
        int right = x + w;
        int bottom = y + h;
        // Straight edges between the corners.
        context.fill(x + r, y, right - r, y + 1, color);
        context.fill(x + r, bottom - 1, right - r, bottom, color);
        context.fill(x, y + r, x + 1, bottom - r, color);
        context.fill(right - 1, y + r, right, bottom - r, color);
        // Corner steps: each row covers from its own inset to where the row above started, so the curve is joined.
        for (int i = 0; i < r; i++) {
            int in = inset(r, i);
            int end = Math.max(in + 1, i == 0 ? r : inset(r, i - 1));
            context.fill(x + in, y + i, x + end, y + i + 1, color);
            context.fill(right - end, y + i, right - in, y + i + 1, color);
            context.fill(x + in, bottom - 1 - i, x + end, bottom - i, color);
            context.fill(right - end, bottom - 1 - i, right - in, bottom - i, color);
        }
    }
}
