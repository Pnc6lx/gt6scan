package bioast.mods.gt6scan.network.scanmessage;

import java.util.Map;

import bioast.mods.gt6scan.gui.ScanViewState;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The scan the server is currently streaming to this client. The view is created by the begin message and lives on
 * while the chunks arrive one by one, so the map can be opened right away and fills in chunk by chunk instead of
 * appearing only after the whole range was scanned.
 * <p>
 * The view is owned by the client, every message only adds its own chunk to it (see
 * {@link ScanViewState#applyChunk}). A cancelled scan simply stops halfway: the next begin message replaces the whole
 * view, so nothing of a partial scan can leak into the next result.
 */
@SideOnly(Side.CLIENT)
public final class ScanResultBuffer {
    private static ScanViewState current;

    private ScanResultBuffer() {}

    /** Starts a new scan, dropping whatever a previous one had collected. */
    public static void begin(int x, int z, int mode, int size, boolean allowTeleport) {
        current = new ScanViewState(x, z, mode, size, allowTeleport);
    }

    /** Adds the result of one chunk to the running view; {@link ScanChunkResponse} carries the data. */
    public static void applyChunk(int chunkIndex, short[] cells, Map<Short, Integer> counts, short[][] columns) {
        ScanViewState view = current;
        if (view != null) view.applyChunk(chunkIndex, cells, counts, columns);
    }

    /** The running scan, or null when no scan was started on this client yet. */
    public static ScanViewState current() {
        return current;
    }
}
