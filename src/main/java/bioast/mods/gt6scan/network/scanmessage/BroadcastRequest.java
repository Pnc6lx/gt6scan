package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Right click on the map: the report of one chunk is sent to the server instead of being written into the local
 * chat, so the server can broadcast it to every player (see {@link HandlerBroadcastServer}). The text is built on
 * the client because the material and mode names are localised there; the server only checks and forwards it.
 */
public class BroadcastRequest implements IMessage {
    String text;

    public BroadcastRequest(String text) {
        this.text = text;
    }

    public BroadcastRequest() {
        // invalid
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        text = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, text == null ? "" : text);
    }
}
