package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Last message of a scan: every {@link ScanChunkResponse} of the range has been sent, so the client can now build
 * the view out of what it collected and open the map. It carries no data of its own, the totals are the sum of the
 * per chunk counts the client already received.
 */
public class ScanDoneResponse implements IMessage {
    public ScanDoneResponse() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // nothing to read
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // nothing to write
    }
}
