package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * First message of a scan, sent before any block is scanned: everything the client needs to set up the result grid,
 * so the {@link ScanChunkResponse}s that follow only have to carry the data of their own chunk. The scan itself is
 * processed one chunk at a time by the server, see {@code HandlerServer.ScanJob}.
 * <p>
 * The payload is a handful of ints, so it is sent uncompressed (unlike the per chunk messages, whose block grid
 * profits from GZIP).
 */
public class ScanBeginResponse implements IMessage {
    /** Block coordinates of the grid origin, i.e. the corner chunk's x/z shifted by 4. */
    int x, z;
    int mode;
    /** Edge length of the scan in chunks; the client grid is {@code chunkSize * 16} columns wide. */
    int chunkSize;
    /**
     * Whether the map may offer the T teleport for this scan: the server checked the world's cheat mode and the
     * mod config, the client must not decide this on its own.
     */
    boolean teleportAllowed;

    public ScanBeginResponse(int x, int z, int mode, int chunkSize, boolean teleportAllowed) {
        this.x = x;
        this.z = z;
        this.mode = mode;
        this.chunkSize = chunkSize;
        this.teleportAllowed = teleportAllowed;
    }

    public ScanBeginResponse() {
        // invalid
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        z = buf.readInt();
        mode = buf.readInt();
        chunkSize = buf.readInt();
        teleportAllowed = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeInt(mode);
        buf.writeInt(chunkSize);
        buf.writeBoolean(teleportAllowed);
    }
}
