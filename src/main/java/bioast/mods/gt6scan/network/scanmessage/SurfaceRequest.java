package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Asks the server for the surface of one map cell (the free space on top of the highest block that is neither air
 * nor a fluid). With {@code teleport} the player is moved there right away; without it the position comes back as a
 * {@link SurfaceResponse}, so a JourneyMap waypoint can be placed on exactly the spot a teleport would use.
 */
public class SurfaceRequest implements IMessage {
    int x, z;
    boolean teleport;

    public SurfaceRequest(int x, int z, boolean teleport) {
        this.x = x;
        this.z = z;
        this.teleport = teleport;
    }

    public SurfaceRequest() {
        // invalid
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        z = buf.readInt();
        teleport = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeBoolean(teleport);
    }
}
