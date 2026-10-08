package inventoryreader.ir;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/** The menu's shared title bar and tab row, so every tab screen looks and switches the same way. */
public final class MenuTabs {
    public enum Tab {
        SHOPPING_LIST("List"),
        RESOURCES("Resources"),
        RECIPES("Recipes"),
        FORGE("Forge"),
        SETTINGS("Settings");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    public static final int TAB_Y = 24;
    public static final int TAB_HEIGHT = 18;
    /** First y below the header and tab row. */
    public static final int HEADER_BOTTOM = 44;
    private static final int LEFT = 20;
    private static final int GAP = 4;
    private static final int MAX_TAB_WIDTH = 110;
    private static final int GOLD = 0xFFFFB728;

    private MenuTabs() {}

    /** Opens the menu on the given tab. */
    public static void open(Tab tab) {
        Screen screen = switch (tab) {
            case SHOPPING_LIST -> new ShoppingListScreen();
            case RESOURCES -> new SandboxViewer(SandboxViewer.Mode.RESOURCES);
            case RECIPES -> new SandboxViewer(SandboxViewer.Mode.RECIPES);
            case FORGE -> new SandboxViewer(SandboxViewer.Mode.FORGE);
            case SETTINGS -> new SettingsScreen();
        };
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** One button per tab; the active tab's button is disabled. */
    public static List<Button> buttons(Tab active, int screenWidth) {
        List<Button> buttons = new ArrayList<>();
        Tab[] tabs = Tab.values();
        int width = tabWidth(screenWidth);
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            Button button = Button.builder(Component.literal(tab.label), b -> open(tab))
                .bounds(LEFT + i * (width + GAP), TAB_Y, width, TAB_HEIGHT)
                .build();
            button.active = tab != active;
            buttons.add(button);
        }
        return buttons;
    }

    /** Title bar, tab strip background and the active-tab underline. Draw before the screen's widgets. */
    public static void renderHeader(GuiGraphicsExtractor context, Font font, int screenWidth, Tab active) {
        context.fill(0, 0, screenWidth, 22, 0xFF17293A);
        context.outline(0, 0, screenWidth, 22, 0xFF223344);
        context.text(font, Component.literal(InventoryReader.NAME).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            12, 7, GOLD, false);
        context.fill(0, 22, screenWidth, HEADER_BOTTOM, 0xFF131313);
        int width = tabWidth(screenWidth);
        int x = LEFT + active.ordinal() * (width + GAP);
        context.fill(x, HEADER_BOTTOM - 2, x + width, HEADER_BOTTOM, 0xFF5FAF3F);
    }

    private static int tabWidth(int screenWidth) {
        int count = Tab.values().length;
        return Math.min(MAX_TAB_WIDTH, (screenWidth - 2 * LEFT - (count - 1) * GAP) / count);
    }
}
