package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Scan result in detrav style: instead of one entry per ore block (14 bytes each, which can exceed the ~2MB packet
 * limit and crash the client on large scans) only the data the GUI actually needs is sent:
 * <ul>
 * <li>the topmost ore material per column ({@link #topMat}), which is all the map shows</li>
 * <li>the total block count per material ({@link #counts}), which is all the ore list shows</li>
 * <li>the block count per material and chunk ({@link #chunkCounts}), used by the chunk hover tooltip</li>
 * </ul>
 * The whole payload is GZIP compressed, so even huge scan ranges stay far below the packet limit.
 */
public class ScanResponse implements IMessage {
    int x, z, mode;
    int chunkSize;
    /**
     * Whether the map may offer the T teleport for this scan: the server checked the world's cheat mode and the
     * mod config, the client must not decide this on its own.
     */
    boolean teleportAllowed;
    /** Topmost ore material per column, index = gridX * mapPx + gridZ; 0 = none. */
    short[] topMat;
    /** Total ore block count per material id. */
    HashMap<Short, Integer> counts = new HashMap<>();
    /** Block count per chunk, key = (chunkIndex << 16) | (matID & 0xFFFF). */
    HashMap<Integer, Integer> chunkCounts = new HashMap<>();

    public ScanResponse(int x, int z, int mode, int chunkSize, boolean teleportAllowed, short[] topMat,
        HashMap<Short, Integer> counts, HashMap<Integer, Integer> chunkCounts) {
        this.x = x;
        this.z = z;
        this.mode = mode;
        this.chunkSize = chunkSize;
        this.teleportAllowed = teleportAllowed;
        this.topMat = topMat;
        this.counts = counts;
        this.chunkCounts = chunkCounts;
    }

    public ScanResponse() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteBufInputStream(buf)))) {
            x = in.readInt();
            z = in.readInt();
            mode = in.readInt();
            chunkSize = in.readInt();
            teleportAllowed = in.readBoolean();
            topMat = new short[in.readInt()];
            for (int i = 0; i < topMat.length; i++) topMat[i] = in.readShort();
            counts = readShortMap(in);
            chunkCounts = readIntMap(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode scan response", e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new ByteBufOutputStream(buf)))) {
            out.writeInt(x);
            out.writeInt(z);
            out.writeInt(mode);
            out.writeInt(chunkSize);
            out.writeBoolean(teleportAllowed);
            out.writeInt(topMat.length);
            for (short mat : topMat) out.writeShort(mat);
            writeShortMap(out, counts);
            writeIntMap(out, chunkCounts);
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode scan response", e);
        }
    }

    private static HashMap<Short, Integer> readShortMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        HashMap<Short, Integer> map = new HashMap<>(Math.max(2, size * 2));
        for (int i = 0; i < size; i++) map.put(in.readShort(), in.readInt());
        return map;
    }

    private static HashMap<Integer, Integer> readIntMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        HashMap<Integer, Integer> map = new HashMap<>(Math.max(2, size * 2));
        for (int i = 0; i < size; i++) map.put(in.readInt(), in.readInt());
        return map;
    }

    private static void writeShortMap(DataOutputStream out, Map<Short, Integer> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<Short, Integer> entry : map.entrySet()) {
            out.writeShort(entry.getKey());
            out.writeInt(entry.getValue());
        }
    }

    private static void writeIntMap(DataOutputStream out, Map<Integer, Integer> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            out.writeInt(entry.getKey());
            out.writeInt(entry.getValue());
        }
    }
}
