package bioast.mods.gt6scan.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gregapi.data.LH;
import gregapi.data.MT;
import gregapi.oredict.OreDictMaterial;
import gregapi.util.UT;

import bioast.mods.gt6scan.network.ScanMode;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client side state of one scan result: the topmost material per column plus the counters coming from the server.
 * It also knows the current view options (material filter, search text and inverted colours) and turns the raw
 * material grid into the colour grid the map renders. Recomputing is O(cells) and only happens when an option
 * changes, never per frame.
 */
@SideOnly(Side.CLIENT)
public class ScanViewState {
    /** One map cell is one block column. */
    public static final int CELL = 16;
    /**
     * Marker for a cell that shows nothing: either the column holds no ore or the material was filtered out.
     * It is a colour no material can produce (a material colour always has a full alpha), which keeps the merge
     * from confusing "empty" with "a material whose colour happens to match the background".
     */
    public static final int EMPTY = 0x00000000;
    /** Map background, light for the normal theme and dark for the inverted (dark) theme. */
    public static final int LIGHT_BG = 0xFFFFFFFF;
    public static final int DARK_BG = 0xFF2B2B2B;
    private static final int LIGHT_LINE = 0xFFA8A8A8, DARK_LINE = 0xFF707070;

    public final int originX, originZ, chunkSize, mapPx;
    private final ScanMode mode;
    /** Whether the server let this scan offer the T teleport (cheat mode + config); never decided client side. */
    private final boolean teleportAllowed;
    private final short[][] mats;
    private final Map<Short, Integer> totals;
    private final Map<Short, Integer>[] chunkCounts;
    private final int[][] colors;
    /** Materials selected in the list; empty means "show everything". */
    private final Set<Short> selection = new LinkedHashSet<>();
    private boolean inverted;

    public ScanViewState(int x, int z, int modeOrdinal, int chunkSize, boolean teleportAllowed, short[] topMat,
        Map<Short, Integer> counts, Map<Integer, Integer> rawChunkCounts) {
        this.originX = x;
        this.originZ = z;
        this.chunkSize = chunkSize;
        this.mapPx = chunkSize * CELL;
        this.mode = ScanMode.values()[modeOrdinal];
        this.teleportAllowed = teleportAllowed;
        this.mats = new short[this.mapPx][this.mapPx];
        if (topMat != null && topMat.length == this.mapPx * this.mapPx) {
            for (int gridX = 0; gridX < this.mapPx; gridX++) {
                for (int gridZ = 0; gridZ < this.mapPx; gridZ++) {
                    this.mats[gridX][gridZ] = topMat[gridX * this.mapPx + gridZ];
                }
            }
        }
        this.totals = counts == null ? new HashMap<>() : counts;
        this.chunkCounts = new Map[chunkSize * chunkSize];
        if (rawChunkCounts != null) {
            for (Map.Entry<Integer, Integer> entry : rawChunkCounts.entrySet()) {
                int index = entry.getKey() >>> 16;
                int matID = entry.getKey() & 0xFFFF;
                if (index < 0 || index >= this.chunkCounts.length) continue;
                Map<Short, Integer> map = this.chunkCounts[index];
                if (map == null) this.chunkCounts[index] = map = new HashMap<>();
                map.put((short) matID, entry.getValue());
            }
        }
        this.colors = new int[this.mapPx][this.mapPx];
    }

    public ScanMode mode() {
        return mode;
    }

    /** True when the map may offer the T teleport: the world allowed cheats and the config did not disable it. */
    public boolean teleportAllowed() {
        return teleportAllowed;
    }

    public Map<Short, Integer> totals() {
        return totals;
    }

    public boolean isInverted() {
        return inverted;
    }

    public void setInverted(boolean inverted) {
        this.inverted = inverted;
    }

    // ---- filter -------------------------------------------------------------------------------------------------

    public boolean isFiltering() {
        return !selection.isEmpty();
    }

    public int selectionSize() {
        return selection.size();
    }

    public boolean isSelected(short matID) {
        return selection.contains(matID);
    }

    /** Selected materials are the only ones drawn on the map; an empty selection draws every material. */
    public boolean isVisible(short matID) {
        return selection.isEmpty() || selection.contains(matID);
    }

    public void toggleSelection(short matID) {
        if (!selection.remove(matID)) selection.add(matID);
    }

    public void clearSelection() {
        selection.clear();
    }

    /** Entries of the list, biggest block count first. */
    public List<Map.Entry<Short, Integer>> sortedTotals() {
        List<Map.Entry<Short, Integer>> entries = new ArrayList<>(totals.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries;
    }

    /** Name search only hides list rows, it never filters the map on its own. Matches the localised name (so
     * Chinese input works) and the internal English name, so either language finds the material. */
    public boolean matchesSearch(OreDictMaterial mat, String query) {
        if (query == null || query.isEmpty()) return true;
        String q = query.toLowerCase();
        return materialName(mat).toLowerCase()
            .contains(q) || mat.mNameInternal.toLowerCase()
                .contains(q);
    }

    // ---- map data -----------------------------------------------------------------------------------------------

    public short matAt(int gridX, int gridZ) {
        return mats[gridX][gridZ];
    }

    public int colorAt(int gridX, int gridZ) {
        return colors[gridX][gridZ];
    }

    public int background() {
        return inverted ? DARK_BG : LIGHT_BG;
    }

    /** Colour of the chunk grid overlay the map draws on top of the cells. */
    public int lineColor() {
        return inverted ? DARK_LINE : LIGHT_LINE;
    }

    public int chunkIndexAt(int gridX, int gridZ) {
        return (gridX >> 4) * chunkSize + (gridZ >> 4);
    }

    /** Material counts of one chunk, for the hover tooltip. */
    public Map<Short, Integer> chunkCountsAt(int gridX, int gridZ) {
        int index = chunkIndexAt(gridX, gridZ);
        if (index < 0 || index >= chunkCounts.length) return null;
        return chunkCounts[index];
    }

    /**
     * Colour grid rebuild: called when the filter or the inverted mode changes, not per frame.
     * <p>
     * Only the plain material colours end up in here - no brightening, darkening or outlining. A material that is
     * close to the background simply becomes hard to see on that background, which is what the invert toggle is
     * for: it switches the map between a light and a dark background while the material colours stay untouched.
     * <p>
     * The chunk grid is drawn as a separate overlay by the map widget, so the merge in there sees the real material
     * colours and a filled vein of any shape (a full square, for example) becomes one single rectangle instead of
     * being cut apart by the grid lines.
     */
    public void recompute() {
        for (int gridX = 0; gridX < mapPx; gridX++) {
            for (int gridZ = 0; gridZ < mapPx; gridZ++) {
                short matID = mats[gridX][gridZ];
                colors[gridX][gridZ] = matID != 0 && isVisible(matID)
                    ? rawColor(OreDictMaterial.MATERIAL_ARRAY[matID])
                    : EMPTY;
            }
        }
        int center = ((chunkSize - 1) / 2) * CELL + 7;
        colors[center][center] = 0xFFE03030;
    }

    // ---- colours and names --------------------------------------------------------------------------------------

    /**
     * Map colour of a material: exactly the material's own solid colour, forced opaque.
     * <p>
     * The alpha channel is dropped on purpose. GT6 stores gases almost transparent (Air has alpha 15, for example)
     * and {@code getRGBaInt} happily returns that alpha, so those blocks used to be drawn blended with - or
     * practically invisible on - the map background: a colour deviation for everything semi transparent and a
     * missing block for everything near alpha 0. A map cell is a flat block of colour, opacity has no meaning here,
     * so every material is drawn fully opaque. The material's colour itself is never modified any more, the invert
     * toggle provides the contrasting background instead.
     */
    public static int rawColor(OreDictMaterial mat) {
        if (mat == null) return 0xFF404040;
        return UT.Code.getRGBaInt(mat.mRGBaSolid) | 0xFF000000;
    }

    /**
     * List colour, adjusted so the label stays readable on the current panel background. Dark colours are lifted on
     * the light theme and vice versa; bright colours on a light panel (natural gas is nearly white) are darkened,
     * which used to make the entry invisible.
     */
    public int listColor(OreDictMaterial mat) {
        int argb = rawColor(mat);
        int luma = luma(argb);
        int target = -1;
        float mix = 0f;
        if (!inverted) {
            if (luma < 120) { // light panel: lift dark colours
                target = 0xFF;
                mix = (120 - luma) / 120f;
            } else if (luma > 200) { // light panel: darken almost white colours
                target = 0x40;
                mix = 0.6f;
            }
        } else {
            if (luma > 150) { // dark panel: darken bright colours
                target = 0x30;
                mix = (luma - 150) / 150f;
            } else if (luma < 70) { // dark panel: lift almost black colours
                target = 0xD0;
                mix = 0.6f;
            }
        }
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        if (target >= 0) {
            mix = Math.min(mix, 0.85f);
            r = (int) (r + (target - r) * mix);
            g = (int) (g + (target - g) * mix);
            b = (int) (b + (target - b) * mix);
        }
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** Localised display name of a scanned material ({@code mNameLocal} only holds the English default). */
    public static String materialName(OreDictMaterial mat) {
        if (mat == null) return LH.get("gt6scan.gui.nan");
        return mat == MT.MethaneIce ? LH.get("gt6scan.gui.natural_gas") : mat.getLocal();
    }

    private static int luma(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
    }
}
