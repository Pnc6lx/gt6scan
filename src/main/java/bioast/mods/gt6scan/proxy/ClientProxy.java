package bioast.mods.gt6scan.proxy;

import bioast.mods.gt6scan.ScannerMod;
import bioast.mods.gt6scan.gui.ImeBridge;
import bioast.mods.gt6scan.gui.ScannerModeGui;
import bioast.mods.gt6scan.network.scanmessage.HandlerClient;
import bioast.mods.gt6scan.network.scanmessage.HandlerScanBegin;
import bioast.mods.gt6scan.network.scanmessage.HandlerScanChunk;
import bioast.mods.gt6scan.network.scanmessage.HandlerSurfaceClient;
import bioast.mods.gt6scan.network.scanmessage.ScanBeginResponse;
import bioast.mods.gt6scan.network.scanmessage.ScanChunkResponse;
import bioast.mods.gt6scan.network.scanmessage.ScanDoneResponse;
import bioast.mods.gt6scan.network.scanmessage.SurfaceResponse;
import com.cleanroommc.modularui.ModularUI;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

public class ClientProxy extends CommonProxy {
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        // a scan arrives one chunk at a time: the begin message sets up the result grid, every scanned chunk is added
        // to it, and the done message builds the view and opens the map (the ids match the dummies on the server)
        CommonProxy.simpleNetworkWrapper.registerMessage(HandlerScanBegin.class, ScanBeginResponse.class,
            2, Side.CLIENT);
        CommonProxy.simpleNetworkWrapper.registerMessage(HandlerScanChunk.class, ScanChunkResponse.class,
            5, Side.CLIENT);
        CommonProxy.simpleNetworkWrapper.registerMessage(HandlerClient.class, ScanDoneResponse.class,
            6, Side.CLIENT);
        CommonProxy.simpleNetworkWrapper.registerMessage(HandlerSurfaceClient.class, SurfaceResponse.class,
            4, Side.CLIENT);
        ImeBridge.register();
        if (!Loader.isModLoaded("InputFix") && !ModularUI.Mods.LWJGL3IFY.isLoaded()) {
            ScannerMod.debug.warn(
                "Chinese input in the scanner search field needs InputFix (or lwjgl3ify) on the client; without it "
                    + "the input method characters never reach the game.");
        }
    }

    @Override
    public World getClientWorld() {
        return FMLClientHandler.instance().getClient().theWorld;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void openScannerGui(EntityPlayer player, ItemStack stack) {
        ScannerModeGui.open(player);
    }
}
