package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Sets up the result grid of the scan the server just started; the chunks follow as {@link ScanChunkResponse} and the
 * map is opened by {@link HandlerClient} when the {@link ScanDoneResponse} arrives.
 */
@SideOnly(Side.CLIENT)
public class HandlerScanBegin implements IMessageHandler<ScanBeginResponse, IMessage> {
    @Override
    public IMessage onMessage(ScanBeginResponse message, MessageContext ctx) {
        if (ctx.side != Side.CLIENT) return null;
        ScanResultBuffer.begin(message.x, message.z, message.mode, message.chunkSize, message.teleportAllowed);
        // the map opens before the first chunk arrived and fills in while the scan runs, so the player sees the area
        // around them first instead of waiting for the whole range
        HandlerClient.open(ScanResultBuffer.current());
        return null;
    }
}
