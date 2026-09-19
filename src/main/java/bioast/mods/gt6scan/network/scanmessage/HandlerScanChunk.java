package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Adds one streamed chunk to the result grid. This is the message that replaced the single huge response, so a scan
 * of any size now arrives in small pieces instead of one packet that could pass the packet size limit and drop the
 * connection.
 */
@SideOnly(Side.CLIENT)
public class HandlerScanChunk implements IMessageHandler<ScanChunkResponse, IMessage> {
    @Override
    public IMessage onMessage(ScanChunkResponse message, MessageContext ctx) {
        if (ctx.side != Side.CLIENT) return null;
        ScanResultBuffer.applyChunk(message.chunkIndex, message.cells, message.counts, message.columns);
        return null;
    }
}
