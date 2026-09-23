package bioast.mods.gt6scan.proxy;

import bioast.mods.gt6scan.network.scanmessage.BroadcastRequest;
import bioast.mods.gt6scan.network.scanmessage.HandlerBroadcastServer;
import bioast.mods.gt6scan.network.scanmessage.HandlerDummy;
import bioast.mods.gt6scan.network.scanmessage.HandlerServer;
import bioast.mods.gt6scan.network.scanmessage.HandlerSurfaceServer;
import bioast.mods.gt6scan.network.scanmessage.ScanBeginResponse;
import bioast.mods.gt6scan.network.scanmessage.ScanChunkResponse;
import bioast.mods.gt6scan.network.scanmessage.ScanDoneResponse;
import bioast.mods.gt6scan.network.scanmessage.ScanRequest;
import bioast.mods.gt6scan.network.scanmessage.SurfaceRequest;
import bioast.mods.gt6scan.utils.ScanScheduler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import gregapi.api.Abstract_Proxy;
import gregapi.data.LH;
import gregapi.util.UT;
import net.minecraft.world.World;

public class CommonProxy extends Abstract_Proxy {
    public static SimpleNetworkWrapper simpleNetworkWrapper;

    public CommonProxy() {
        //MinecraftForge.EVENT_BUS.register(this);


    }

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        loadLang();
        // scans are spread over server ticks by the scheduler, like detrav's prospector does it
        ScanScheduler.register();
        simpleNetworkWrapper = NetworkRegistry.INSTANCE.newSimpleChannel("Scanning_channel");
        simpleNetworkWrapper.registerMessage(HandlerServer.class, ScanRequest.class, 1, Side.SERVER);
        // a scan is answered one chunk at a time: the map is set up by the begin message, every scanned chunk follows
        // on its own (small) message and the done message tells the client that it may open the map. The dedicated
        // server never handles these itself, but it has to know their ids to be able to send them, hence the dummies.
        registerServerId(ScanBeginResponse.class, 2);
        registerServerId(ScanChunkResponse.class, 5);
        registerServerId(ScanDoneResponse.class, 6);
        // surface of a map cell: teleport the player there, or report it back for a JourneyMap waypoint
        simpleNetworkWrapper.registerMessage(HandlerSurfaceServer.class, SurfaceRequest.class, 3, Side.SERVER);
        // right click on the map: the chunk report is broadcast to every player in the public chat
        simpleNetworkWrapper.registerMessage(HandlerBroadcastServer.class, BroadcastRequest.class, 4,
            Side.SERVER);
    }

    /**
     * Registers a server to client message on the server side without a handler of its own: the server only needs the
     * discriminator id of the message to be able to send it, the client side registrations (see ClientProxy) do the
     * real work. {@link HandlerDummy} is generic over the message type on purpose, so one class covers all of them.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void registerServerId(Class<? extends IMessage> messageType, int discriminator) {
        simpleNetworkWrapper.registerMessage(HandlerDummy.class, (Class) messageType, discriminator, Side.SERVER);
    }

    /**
     * Registers the English defaults of all translatable keys.
     * Translations are provided via assets/gt6scan/lang/*.lang (e.g. zh_CN.lang).
     */
    private void loadLang() {
        // Tooltips
        LH.add("gt6scan.tooltip.open_gui", "Right Click to Open the Mode GUI.");
        LH.add("gt6scan.tooltip.mode_suffix", "Mode.");
        LH.add("gt6scan.tooltip.size", "Range: %s chunks");
        // Chat
        LH.add("gt6scan.chat.broadcast", "%1$s %2$s chunk (x %3$d - %4$d, z %5$d - %6$d): %7$s");
        LH.add("gt6scan.chat.no_energy", "Not Enough Energy for a Scan!");
        LH.add("gt6scan.chat.out_of_reach", "That position is out of the scanned range!");
        LH.add("gt6scan.chat.no_surface", "There is no block to stand on at that position!");
        LH.add("gt6scan.chat.no_journeymap", "JourneyMap is not installed, cannot create a waypoint.");
        LH.add("gt6scan.chat.waypoint_created", "Created %1$s waypoint(s): %2$s");
        LH.add("gt6scan.chat.waypoint_failed",
            "Could not create the waypoint (%1$s), opening JourneyMap's waypoint editor.");
        LH.add("gt6scan.chat.waypoint_no_editor",
            "Could not create the waypoint (%1$s), and JourneyMap's waypoint editor could not be opened either.");
        LH.add("gt6scan.chat.no_nei", "NotEnoughItems is not installed, cannot add a bookmark.");
        LH.add("gt6scan.chat.bookmark_exists", "That item is already bookmarked, nothing was added.");
        LH.add("gt6scan.chat.no_bookmark_item", "That material has no item form to bookmark.");
        LH.add("gt6scan.chat.login_notice", "To Use the ScannerMod For GT6, You Must have ModularUI2 on your client.");
        LH.add("gt6scan.chat.scanning", "Scanning...");
        LH.add("gt6scan.chat.cancelled", "Cancelled The Previous Scan.");
        // GUI
        LH.add("gt6scan.gui.natural_gas", "Natural Gas");
        LH.add("gt6scan.gui.nan", "NaN");
        LH.add("gt6scan.gui.status", "%s , %s , %s. Dim: %s. Mode: %s");
        LH.add("gt6scan.gui.search", "Search name...");
        LH.add("gt6scan.gui.select_mode", "Select Scan Mode");
        LH.add("gt6scan.gui.range", "Range: %1$s chunks (%2$s blocks)");
        LH.add("gt6scan.gui.mode_hint", "Click a mode to start the scan. The map opens when it finishes.");
        LH.add("gt6scan.gui.scanning", "Scanning %s...");
        LH.add("gt6scan.gui.teleport_hint", "Press T to teleport to this position");
        // JourneyMap waypoint names of the bedrock modes: "<prefix> (<material>)"
        LH.add("gt6scan.waypoint.bedrock_ore", "bedrock ore");
        LH.add("gt6scan.waypoint.fluid_bedrock", "fluid bedrock");
        LH.add("gt6scan.waypoint.name", "%1$s (%2$s)");
        LH.add("gt6scan.gui.invert_off", "Invert");
        LH.add("gt6scan.gui.invert_on", "Inverted");
        LH.add("gt6scan.gui.invert_hint", "Inverts the whole interface and the map colours.");
        LH.add("gt6scan.gui.clear_filter", "Clear Filter");
        LH.add("gt6scan.gui.clear_filter_n", "Clear Filter (%d)");
        LH.add("gt6scan.gui.filter_hint", "Tick entries on the right to show only those on the map.");
        LH.add("gt6scan.gui.filter_count", "%d entries selected");
        LH.add("gt6scan.gui.row_selected", "Selected: shown on the map");
        LH.add("gt6scan.gui.row_unselected", "Not selected");
        LH.add("gt6scan.gui.row_hint", "Left click to add or remove this material.");
        LH.add("gt6scan.gui.cell_info", "Column");
        LH.add("gt6scan.gui.chunk_info", "Chunk");
        LH.add("gt6scan.gui.chunk_empty", "Nothing found in this chunk");
        LH.add("gt6scan.gui.waypoint_hint", "Press B to create a JourneyMap waypoint");
        LH.add("gt6scan.gui.hover_hint", "Left click: bookmark this item/fluid in NEI, right click: broadcast");
        // Scan Modes
        LH.add("gt6scan.mode.none", "None");
        LH.add("gt6scan.mode.large", "Large Ores");
        LH.add("gt6scan.mode.small", "Small Ores");
        LH.add("gt6scan.mode.dense_and_normal", "Dense and Normal Ores");
        LH.add("gt6scan.mode.bedrock", "Bedrock Ores");
        LH.add("gt6scan.mode.fluid_bedrock", "Bedrock Fluids");
        LH.add("gt6scan.mode.rock", "Rocks");
        LH.add("gt6scan.mode.fluid", "Fluids");
        LH.add("gt6scan.mode.oredict", "Other Mods' Ores");
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
    }

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
    }

    public World getClientWorld() {
        return null;
    }

    /**
     * Opens the mode selection GUI. Client proxy overrides this; on the dedicated server it is never called (the
     * behaviour only calls it with {@code aWorld.isRemote}).
     */
    public void openScannerGui(net.minecraft.entity.player.EntityPlayer player, net.minecraft.item.ItemStack stack) {
        // no-op on the server
    }

//    @SubscribeEvent
//    public void onPlayerUseEvent(PlayerInteractEvent aEvent) {
//        if (aEvent.action == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
//        }
//    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        UT.Entities.sendchat(event.player, LH.Chat.PURPLE + LH.get("gt6scan.chat.login_notice") + LH.Chat.GRAY);
    }
}
