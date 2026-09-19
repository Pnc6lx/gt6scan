package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** The resolved surface position of a map cell, see {@link SurfaceRequest}. */
public class SurfaceResponse implements IMessage {
    int x, y, z;

    public SurfaceResponse(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public SurfaceResponse() {
        // invalid
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }
}
