package bioast.mods.gt6scan.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gregapi.data.FL;
import gregapi.data.LH;
import gregapi.data.MT;
import gregapi.oredict.OreDictMaterial;
import gregapi.util.UT;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import bioast.mods.gt6scan.network.ScanMode;
import bioast.mods.gt6scan.network.scanmessage.ScanChunkResponse;
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
    /** Luma below which a colour is too dark for the dark theme's panel and gets lifted by {@link #uiColor(int)}. */
    private static final int DARK_PANEL_MIN_LUMA = 125;

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

    /**
     * Materials of every column that holds more than one, highest first, key = {@code gridX * mapPx + gridZ}.
     * Single material columns are not stored: their material is already in {@link #mats}. It is a sparse map on
     * purpose, most columns of a scan hold nothing or one material and a dense array of column lists would cost
     * several megabytes on a big range.
     */
    private final Map<Integer, short[]> columnMats = new HashMap<>();
    /** Changes whenever the ore list has to be rebuilt: a material appeared or the scan finished. */
    private int revision;
    /** Set by {@link #markFinished()}: the server sent the last chunk of this scan. */
    private boolean finished;

    @SuppressWarnings("unchecked")
    public ScanViewState(int x, int z, int modeOrdinal, int chunkSize, boolean teleportAllowed) {
        this.originX = x;
        this.originZ = z;
        this.chunkSize = chunkSize;
        this.mapPx = chunkSize * CELL;
        this.mode = ScanMode.values()[modeOrdinal];
        this.teleportAllowed = teleportAllowed;
        this.mats = new short[this.mapPx][this.mapPx];
        this.colors = new int[this.mapPx][this.mapPx];
        this.totals = new HashMap<>();
        this.chunkCounts = new Map[chunkSize * chunkSize];
    }

    /**
     * Adds the result of one scanned chunk where it belongs in the grid and recolours exactly that part of the map,
     * so a map that the client opened on the begin message fills in chunk by chunk while the scan is still running.
     *
     * @param chunkIndex position of the chunk in the scan grid, {@code (gridX >> 4) * chunkSize + (gridZ >> 4)}
     * @param cells      topmost material per column of that chunk, {@code localX * 16 + localZ}, 0 = none
     * @param counts     block count per material inside that chunk
     * @param columns    materials of the columns holding more than one; {@code null} entries are single material
     *                   columns, the whole array may be null
     */
    public void applyChunk(int chunkIndex, short[] cells, Map<Short, Integer> counts, short[][] columns) {
        if (chunkIndex < 0 || chunkIndex >= chunkCounts.length) return;
        int gridX = (chunkIndex / chunkSize) * CELL;
        int gridZ = (chunkIndex % chunkSize) * CELL;
        if (gridX + CELL > mapPx || gridZ + CELL > mapPx) return;

        for (int k = 0; k < CELL; k++) {
            for (int l = 0; l < CELL; l++) {
                // every id on the wire is looked up in OreDictMaterial.MATERIAL_ARRAY on this side, so anything
                // that would not survive that lookup is dropped right where it enters the map
                short matID = cells == null ? 0 : cells[k * CELL + l];
                mats[gridX + k][gridZ + l] = ScanChunkResponse.isValidId(mode, matID) ? matID : 0;
                if (columns != null && columns[k * CELL + l] != null) {
                    columnMats.put((gridX + k) * mapPx + gridZ + l, columns[k * CELL + l]);
                }
                recolor(gridX + k, gridZ + l);
            }
        }
        if (counts == null) return;
        for (Map.Entry<Short, Integer> entry : counts.entrySet()) {
            short matID = entry.getKey();
            if (!ScanChunkResponse.isValidId(mode, matID)) continue;
            if (!totals.containsKey(matID)) revision++;
            totals.merge(matID, entry.getValue(), Integer::sum);
            Map<Short, Integer> perChunk = chunkCounts[chunkIndex];
            if (perChunk == null) chunkCounts[chunkIndex] = perChunk = new HashMap<>();
            perChunk.merge(matID, entry.getValue(), Integer::sum);
        }
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
     * Chinese input works) and the internal English name, so either language finds the entry - for a fluid in the
     * fluid modes that is the fluid's localised name and its registry name. */
    public boolean matchesSearch(short id, String query) {
        if (query == null || query.isEmpty()) return true;
        String q = query.toLowerCase();
        if (mode.isFluid()) {
            Fluid fluid = FL.fluid(id);
            return fluid != null && (fluidName(fluid).toLowerCase()
                .contains(q) || fluid.getName()
                    .toLowerCase()
                    .contains(q));
        }
        OreDictMaterial mat = OreDictMaterial.MATERIAL_ARRAY[id];
        return mat != null && (materialName(mat).toLowerCase()
            .contains(q) || mat.mNameInternal.toLowerCase()
                .contains(q));
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
                recolor(gridX, gridZ);
            }
        }
        int center = ((chunkSize - 1) / 2) * CELL + 7;
        colors[center][center] = 0xFFE03030;
    }

    /** Colours one cell with whatever {@link #shownMatAt} reports for it: a material or, in the fluid modes, a fluid. */
    private void recolor(int gridX, int gridZ) {
        short matID = shownMatAt(gridX, gridZ);
        colors[gridX][gridZ] = matID != 0 ? entryColor(matID) : EMPTY;
    }

    /**
     * Name of a scanned entry: the material's name, or the fluid's name in the fluid modes. The fluid modes need this
     * because GT6's heavy, light, medium and extra heavy oil are separate fluids without a material - they all share
     * the material "Oil", so only the fluid can tell them apart in the list and the tooltips.
     */
    public String entryName(short id) {
        if (!mode.isFluid()) return materialName(OreDictMaterial.MATERIAL_ARRAY[id]);
        Fluid fluid = FL.fluid(id);
        return fluid == null ? LH.get("gt6scan.gui.nan") : fluidName(fluid);
    }

    /** Map colour of a scanned entry: the material's solid colour, or the fluid's colour - see {@link FluidColour}. */
    public int entryColor(short id) {
        if (!mode.isFluid()) return rawColor(OreDictMaterial.MATERIAL_ARRAY[id]);
        return FluidColour.of(FL.fluid(id));
    }

    /** {@link #entryColor} as the label is drawn with it: theme aware, see {@link #uiColor(int)}. */
    public int entryListColor(short id) {
        return uiColor(entryColor(id));
    }

    /** Localised name of a fluid, with the vanilla fallback for fluids that only have an unlocalised name. */
    private static String fluidName(Fluid fluid) {
        String name = fluid.getLocalizedName(new FluidStack(fluid, 1));
        if (name != null && !name.isEmpty() && !name.equals(fluid.getUnlocalizedName())) return name;
        String key = "fluid." + fluid.getUnlocalizedName();
        String translated = LH.get(key);
        return translated.equals(key) ? name : translated;
    }

    /**
     * The material a cell shows. Without a filter that is the topmost material of the column. With a filter it is the
     * highest <em>selected</em> material of that column: a column holding material a on top and b and c below shows b
     * when b and c are filtered (and c when only c is), and it stays empty when none of the selected materials occurs
     * in it at all. So a vein another ore covers up can be found, and the cell still answers "which of the materials
     * I am looking for is closest to the surface here".
     */
    public short shownMatAt(int gridX, int gridZ) {
        short top = mats[gridX][gridZ];
        if (top == 0 || selection.isEmpty() || selection.contains(top)) return top;
        short[] column = columnMats.get(gridX * mapPx + gridZ);
        if (column == null) return 0;
        // the list is stored highest first, so the first selected material is the highest one that was selected
        for (short matID : column) if (selection.contains(matID)) return matID;
        return 0;
    }

    /**
     * Counter for the ore list: it changes when a material appears that was not in the list yet, and once more
     * when the scan is done. The list widget rebuilds its rows when it sees a new value, so the list next to a
     * still running map grows with it instead of being rebuilt on every chunk.
     */
    public int revision() {
        return revision;
    }

    /** Called with the last chunk: the list is rebuilt once more, now with the final block counts. */
    public void markFinished() {
        finished = true;
        revision++;
    }

    /** Whether the server already sent the last chunk of this scan. */
    public boolean isFinished() {
        return finished;
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
     * The single place every colour the interface draws itself goes through: the material and fluid colours of the
     * labels, the hover line, the tooltip headings and hints, the frame of the map and its hover marks.
     * <p>
     * The light theme draws on a medium grey panel and both dark and bright material colours stand out against it, so
     * nothing is adjusted there: an entry whose material is near white (natural gas, for example) is drawn in that
     * white, exactly like on the map. Darkening it, which used to happen, made it harder to read rather than easier -
     * on a grey panel a black label has less contrast than the white it started as.
     * <p>
     * Only the inverted mode changes anything. It switches the panel over to ModularUI2's dark theme, where a dark
     * material colour would disappear into the background, so those are mixed towards white. The hue is kept and a
     * colour is moved only as far as it has to go; everything that already contrasts with the dark panel is returned
     * unchanged.
     */
    public int uiColor(int argb) {
        if (!inverted) return argb;
        int luma = luma(argb);
        if (luma >= DARK_PANEL_MIN_LUMA) return argb;
        float mix = Math.min(0.85f, (DARK_PANEL_MIN_LUMA - luma) / (float) DARK_PANEL_MIN_LUMA);
        float r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (0xFF - r) * mix;
        g += (0xFF - g) * mix;
        b += (0xFF - b) * mix;
        return 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    /** Plain interface text, for example the hover line of a column that holds nothing. */
    public int textColor() {
        return uiColor(0xFF404040);
    }

    /** Secondary text: units, "nothing found" lines, the less important hints. */
    public int mutedColor() {
        return uiColor(0xFF909090);
    }

    /** Headings of the tooltip sections and of the cell info line. */
    public int headerColor() {
        return uiColor(0xFFFFD070);
    }

    /** The click and key hints of the tooltip (bookmark, broadcast, teleport, waypoint). */
    public int hintColor() {
        return uiColor(0xFF80C0FF);
    }

    /** Frame of the map viewport. */
    public int frameColor() {
        return uiColor(0xFF8B8B8B);
    }

    /** Hover mark of the chunk the tooltip describes. */
    public int hoverChunkColor() {
        return uiColor(0xFFFFC040);
    }

    /**
     * Hover mark of the cell under the cursor. It is drawn on the map, whose background switches with the invert
     * toggle, so the mark is white on the dark map and dark on the light one.
     */
    public int hoverCellColor() {
        return inverted ? 0xFFFFFFFF : 0xFF404040;
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
