package bioast.mods.gt6scan.network.scanmessage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import bioast.mods.gt6scan.network.ScanMode;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import gregapi.data.FL;
import gregapi.oredict.OreDictMaterial;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

/**
 * The result of exactly one scanned chunk, sent as soon as that chunk is done. This is what keeps a scan from
 * producing one huge response: the old single response of the whole range could pass the ~2MB packet limit of
 * {@code S3FPacketCustomPayload} on big ranges (the server then closed the connection, "lost connection" on the
 * client), while one chunk is only a few hundred bytes.
 * <p>
 * One message carries:
 * <ul>
 * <li>the topmost ore material per column of the chunk ({@link #cells}, 16x16, local coordinates, 0 = none),</li>
 * <li>the block count per material <em>inside this chunk</em> ({@link #counts}). The client sums these into the
 * totals of the ore list and keeps them per chunk for the chunk hover tooltip.</li>
 * </ul>
 */
public class ScanChunkResponse implements IMessage {
    /** 16 * 16 columns of one chunk. */
    public static final int CELLS = 256;

    /**
     * Whether a material id may be used as an index into {@link OreDictMaterial#MATERIAL_ARRAY}, which is what the
     * client does with every id a scan result carries. {@code W} (-1) is GT6's placeholder for an unset material - a
     * prefix block tile entity whose metadata was never initialised reports it - and ids that were never registered
     * have no entry in that array at all. Both would crash the map with an {@code ArrayIndexOutOfBoundsException},
     * so they are dropped on the server and filtered once more when the data arrives on the client.
     */
    public static boolean isValidMaterialId(short matID) {
        return matID >= 0 && matID < OreDictMaterial.MATERIAL_ARRAY.length
            && OreDictMaterial.MATERIAL_ARRAY[matID] != null;
    }

    /**
     * Whether an id may appear in the scan result of a mode: a fluid id in the fluid modes (see
     * {@link ScanMode#isFluid()}, resolved through {@code FL.fluid}), a material id everywhere else. Both are used
     * as an index on the client, so anything else would break the map there.
     */
    public static boolean isValidId(ScanMode mode, short id) {
        return mode.isFluid() ? FL.fluid(id) != null : isValidMaterialId(id);
    }

    /**
     * Materials remembered per column at most. Every material of a column list costs two bytes on the wire and a
     * column list only has to be sent for columns that hold more than one material, so even a chunk whose 256 columns
     * all hold this many ores stays at about 9 kB - far below what one packet may carry. A column with more different
     * ores than this keeps the highest ones (the deep end is dropped first).
     */
    public static final int MAX_COLUMN_MATS = 16;

    /** Index of the chunk in the scan grid, i.e. {@code (gridX >> 4) * chunkSize + (gridZ >> 4)}. */
    int chunkIndex;
    /** Topmost ore material per column, index = {@code k * 16 + l} (k/l are the in-chunk block coordinates). */
    short[] cells = new short[CELLS];
    /**
     * Materials of every column of this chunk that holds more than one, <em>highest first</em>, index =
     * {@code k * 16 + l} like {@link #cells}; {@code null} for columns with a single material (their one material is
     * already in {@code cells}). The list contains the topmost material as well, so the client can answer "which of
     * the materials I filtered for lies highest in this column?" by walking it in order - that is what lets the
     * filter find a vein another ore sits on top of, and the cell is drawn in the colour of the material found that
     * way.
     */
    short[][] columns = new short[CELLS][];
    /** Block count per material id of this chunk. */
    HashMap<Short, Integer> counts = new HashMap<>();

    public ScanChunkResponse(int chunkIndex, short[] cells, HashMap<Short, Integer> counts) {
        this.chunkIndex = chunkIndex;
        this.cells = cells;
        this.counts = counts;
    }

    public ScanChunkResponse() {
        // invalid
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteBufInputStream(buf)))) {
            chunkIndex = in.readInt();
            for (int i = 0; i < CELLS; i++) cells[i] = in.readShort();
            columns = readColumns(in);
            counts = readShortMap(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode a scanned chunk", e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new ByteBufOutputStream(buf)))) {
            out.writeInt(chunkIndex);
            for (int i = 0; i < CELLS; i++) out.writeShort(cells[i]);
            writeColumns(out, columns);
            writeShortMap(out, counts);
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode a scanned chunk", e);
        }
    }

    /** Writes only the columns holding more than one material, as (index, size, materials) triples. */
    private static void writeColumns(DataOutputStream out, short[][] columns) throws IOException {
        int multi = 0;
        if (columns != null) for (short[] column : columns) if (column != null) multi++;
        out.writeShort(multi);
        if (columns == null) return;
        for (int i = 0; i < CELLS; i++) {
            short[] column = columns[i];
            if (column == null) continue;
            out.writeShort(i);
            out.writeByte(column.length);
            for (short matID : column) out.writeShort(matID);
        }
    }

    private static short[][] readColumns(DataInputStream in) throws IOException {
        int multi = in.readUnsignedShort();
        short[][] columns = new short[CELLS][];
        for (int i = 0; i < multi; i++) {
            int index = in.readUnsignedShort();
            int size = in.readUnsignedByte();
            short[] column = new short[size];
            for (int k = 0; k < size; k++) column[k] = in.readShort();
            if (index >= 0 && index < CELLS) columns[index] = column;
        }
        return columns;
    }

    private static HashMap<Short, Integer> readShortMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        HashMap<Short, Integer> map = new HashMap<>(Math.max(2, size * 2));
        for (int i = 0; i < size; i++) map.put(in.readShort(), in.readInt());
        return map;
    }

    private static void writeShortMap(DataOutputStream out, Map<Short, Integer> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<Short, Integer> entry : map.entrySet()) {
            out.writeShort(entry.getKey());
            out.writeInt(entry.getValue());
        }
    }
}
