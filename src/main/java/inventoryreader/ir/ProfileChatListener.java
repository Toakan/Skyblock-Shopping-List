package inventoryreader.ir;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's "Profile ID: <uuid>" line, sent each time you join SkyBlock, and tells
 * {@link ProfileManager}. Observes messages only; never cancels or edits them.
 */
public final class ProfileChatListener {
    /** The whole line. Player chat always starts with a name or channel tag, so it can't match. */
    private static final Pattern PROFILE_ID = Pattern.compile(
        "^Profile ID: ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    private ProfileChatListener() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> read(message, overlay));
        // Also watch before other mods may hide the line. Always returns true: this never cancels anything.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            read(message, overlay);
            return true;
        });
    }

    private static void read(Component message, boolean overlay) {
        if (overlay) return;
        Matcher m = PROFILE_ID.matcher(message.getString().strip());
        if (m.matches()) ProfileManager.onProfileId(m.group(1));
    }
}
