package inventoryreader.ir;

import net.hypixel.data.type.GameType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

/**
 * Listens to the official Hypixel Mod API location event. Only loaded when the hypixel-mod-api mod is
 * installed, so this class must not be referenced from code that runs without it.
 */
final class HypixelLocationListener {
    private HypixelLocationListener() {}

    static void register() {
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
        api.createHandler(ClientboundLocationPacket.class, packet -> SkyblockDetector.onModApiLocation(
            packet.getServerType().map(type -> type == GameType.SKYBLOCK).orElse(false),
            packet.getMode().orElse(null)
        ));
    }
}
