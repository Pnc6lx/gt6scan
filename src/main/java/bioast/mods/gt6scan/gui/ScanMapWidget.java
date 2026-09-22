package bioast.mods.gt6scan.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.MCHelper;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import bioast.mods.gt6scan.network.ScanMode;
import bioast.mods.gt6scan.network.scanmessage.BroadcastRequest;
import bioast.mods.gt6scan.network.scanmessage.SurfaceRequest;
import bioast.mods.gt6scan.proxy.CommonProxy;
import bioast.mods.gt6scan.utils.ModularUIUtils;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.LH;
import gregapi.util.UT;
import org.lwjgl.input.Keyboard;

/**
 * The map itself. Equal coloured cells are merged into rectangles before drawing (a frame needs a few dozen draw
 * calls instead of one per cell). Every cell is drawn as an opaque block of the material's own colour - no outlines,
 * no colour adjustment; a material that is hard to tell apart from the background is handled by the invert toggle,
 * which switches the map background. Hovering shows the chunk the cell belongs to as a tooltip.
 * <p>
 * The merge only ever sees material colours: the chunk grid is an overlay drawn on top, so a vein that fills a
 * chunk (a square) stays one rectangle instead of being sliced into strips by the grid. Rectangles are snapped to
 * whole pixels on both ends, so neighbouring rectangles always touch without sub-pixel seams or overlaps.
 * <p>
 * Left click is the bookmark action of this map: it puts the item or fluid the GUI shows for the hovered position
 * into NEI's bookmark panel (see {@link #bookmarkHoveredItem}). B creates the JourneyMap waypoint of that position
 * instead (see {@link #createWaypoint}) and T teleports there. Right click broadcasts the chunk under the cursor:
 * the report goes to the server, which puts it into the public chat in the name of the clicker, so every player on
 * the server sees who reported which chunk.
 */
@SideOnly(Side.CLIENT)
public class ScanMapWidget extends Widget<ScanMapWidget> implements Interactable {
    /** Materials listed in the chunk tooltip at most. */
    private static final int TOOLTIP_LINES = 7;

    private final ScanViewState state;
    private final EntityPlayer player;
    private final int mapArea;
    private final int statusH;
    private final float scale;
    private final int[][] mergeMask;
    private int mergeStamp;
    private int hoverX, hoverZ;
    private boolean hoverInside;
    private boolean hoverHasMat;
    private String hoverName = LH.get("gt6scan.gui.nan");
    private int hoverColor;

    public ScanMapWidget(ScanViewState state, EntityPlayer player, int mapArea, int statusH) {
        this.state = state;
        this.player = player;
        this.mapArea = mapArea;
        this.statusH = statusH;
        this.scale = mapArea / (float) state.mapPx;
        this.mergeMask = new int[state.mapPx][state.mapPx];
        // the status line is drawn in the theme's own text colour until a cell holding something is hovered
        this.hoverColor = state.textColor();
        tooltipAutoUpdate(true).tooltipBuilder(this::buildTooltip)
            .tooltipShowUpTimer(0);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        // opaque map background: empty cells are a defined colour instead of whatever the panel happens to look like,
        // and the invert toggle can switch it between the light and the dark variant to contrast the material colours
        GuiDraw.drawRect(0, 0, mapArea, mapArea, state.background());
        drawCells();
        // frame, so the map viewport is visible even when the scan found nothing
        int frame = state.frameColor();
        GuiDraw.drawRect(0, 0, mapArea, 1, frame);
        GuiDraw.drawRect(0, mapArea - 1, mapArea, 1, frame);
        GuiDraw.drawRect(0, 0, 1, mapArea, frame);
        GuiDraw.drawRect(mapArea - 1, 0, 1, mapArea, frame);

        updateHover(context);
        drawHoverMarks();

        // drawn in the row reserved above the map, so it cannot overlap the map or the panel border
        String status = String.format(LH.get("gt6scan.gui.status"), state.originX + hoverX, state.originZ + hoverZ,
            hoverName, player.worldObj.provider.getDimensionName(), state.mode()
                .localizedName());
        GuiDraw.drawText(status, 0, -(statusH - 1), 1f, hoverColor, true);
    }

    private void drawCells() {
        int mapPx = state.mapPx;
        int stamp = ++mergeStamp;
        for (int i = 0; i < mapPx; i++) {
            for (int j = 0; j < mapPx; j++) {
                if (mergeMask[i][j] == stamp) continue;
                int color = state.colorAt(i, j);
                mergeMask[i][j] = stamp;
                int i2 = i + 1;
                while (i2 < mapPx && mergeMask[i2][j] != stamp && state.colorAt(i2, j) == color) {
                    mergeMask[i2][j] = stamp;
                    i2++;
                }
                int j2 = j + 1;
                rows:
                while (j2 < mapPx) {
                    for (int k = i; k < i2; k++) {
                        if (mergeMask[k][j2] == stamp || state.colorAt(k, j2) != color) break rows;
                    }
                    for (int k = i; k < i2; k++) mergeMask[k][j2] = stamp;
                    j2++;
                }
                if (color == ScanViewState.EMPTY) continue; // nothing to draw, empty areas stay background
                // Whole pixel alignment: a rectangle runs from the rounded start of its first cell to the rounded
                // start of the cell after its last one, so adjacent rectangles always meet exactly - no sub-pixel
                // seams (that showed up as thin background lines / broken boxes) and no overlaps either.
                int x = Math.round(i * scale);
                int y = Math.round(j * scale);
                int w = Math.round(i2 * scale) - x;
                int h = Math.round(j2 * scale) - y;
                GuiDraw.drawRect(x, y, w, h, color);
            }
        }
        drawChunkGrid();
    }

    /**
     * Chunk grid as an overlay instead of painted into the cells: the merge works on pure material colours, so a
     * vein that fills a whole chunk (or a square of chunks) is drawn as one solid square with a thin grid on top.
     */
    private void drawChunkGrid() {
        int line = state.lineColor();
        for (int x = ScanViewState.CELL; x < state.mapPx; x += ScanViewState.CELL) {
            GuiDraw.drawRect(x * scale, 0, 1, mapArea, line);
        }
        for (int z = ScanViewState.CELL; z < state.mapPx; z += ScanViewState.CELL) {
            GuiDraw.drawRect(0, z * scale, mapArea, 1, line);
        }
    }

    private void updateHover(ModularGuiContext context) {
        int localX = (int) ((context.getAbsMouseX() - getArea().x) / scale);
        int localZ = (int) ((context.getAbsMouseY() - getArea().y) / scale);
        hoverInside = localX >= 0 && localZ >= 0 && localX < state.mapPx && localZ < state.mapPx;
        hoverX = Math.max(0, Math.min(state.mapPx - 1, localX));
        hoverZ = Math.max(0, Math.min(state.mapPx - 1, localZ));
        short matID = hoverInside ? state.shownMatAt(hoverX, hoverZ) : 0;
        hoverHasMat = matID != 0;
        hoverName = hoverHasMat ? state.entryName(matID) : LH.get("gt6scan.gui.nan");
        hoverColor = hoverHasMat ? state.entryListColor(matID) : state.textColor();
    }

    /** Marks the hovered cell and the 16x16 chunk it belongs to, which is what the tooltip describes. */
    private void drawHoverMarks() {
        if (!hoverInside) return;
        float chunkX = (hoverX >> 4) * ScanViewState.CELL * scale;
        float chunkZ = (hoverZ >> 4) * ScanViewState.CELL * scale;
        float chunkSize = ScanViewState.CELL * scale;
        int chunkMark = state.hoverChunkColor();
        GuiDraw.drawRect(chunkX, chunkZ, chunkSize, 1, chunkMark);
        GuiDraw.drawRect(chunkX, chunkZ + chunkSize - 1, chunkSize, 1, chunkMark);
        GuiDraw.drawRect(chunkX, chunkZ, 1, chunkSize, chunkMark);
        GuiDraw.drawRect(chunkX + chunkSize - 1, chunkZ, 1, chunkSize, chunkMark);
        if (scale < 2f) return; // a cell frame would cover the whole cell
        float x = hoverX * scale, y = hoverZ * scale;
        int cellMark = state.hoverCellColor();
        GuiDraw.drawRect(x, y, scale, 1, cellMark);
        GuiDraw.drawRect(x, y + scale - 1, scale, 1, cellMark);
        GuiDraw.drawRect(x, y, 1, scale, cellMark);
        GuiDraw.drawRect(x + scale - 1, y, 1, scale, cellMark);
    }

    /** Cell info plus everything the scan found inside the chunk the cell belongs to. */
    private void buildTooltip(RichTooltip tooltip) {
        if (!hoverInside) return; // empty tooltip, nothing is drawn
        tooltip.addLine(IKey.str(LH.get("gt6scan.gui.cell_info"))
            .color(state.headerColor()));
        tooltip.addLine(
            IKey.str(String.format("%d , %d : ", state.originX + hoverX, state.originZ + hoverZ) + hoverName)
                .color(hoverColor));

        int chunkWorldX = state.originX + (hoverX >> 4) * ScanViewState.CELL;
        int chunkWorldZ = state.originZ + (hoverZ >> 4) * ScanViewState.CELL;
        tooltip.spaceLine();
        tooltip.addLine(IKey.str(LH.get("gt6scan.gui.chunk_info"))
            .color(state.headerColor()));
        tooltip.addLine(IKey.str(String.format("x %d - %d , z %d - %d", chunkWorldX, chunkWorldX + 15, chunkWorldZ,
                chunkWorldZ + 15))
            .color(state.mutedColor()));

        List<Map.Entry<Short, Integer>> entries = chunkEntries(hoverX, hoverZ);
        if (entries.isEmpty()) {
            tooltip.addLine(IKey.str(LH.get("gt6scan.gui.chunk_empty"))
                .color(state.mutedColor()));
        } else {
            for (int i = 0; i < entries.size() && i < TOOLTIP_LINES; i++) {
                Map.Entry<Short, Integer> entry = entries.get(i);
                tooltip.addLine(
                    IKey.str(state.entryName(entry.getKey()) + ": " + entry.getValue())
                        .color(state.entryListColor(entry.getKey())));
            }
            if (entries.size() > TOOLTIP_LINES) {
                tooltip.addLine(IKey.str("... +" + (entries.size() - TOOLTIP_LINES))
                    .color(state.mutedColor()));
            }
        }
        tooltip.spaceLine();
        // the teleport line only shows when the server said the teleport is available (cheat mode + config)
        if (state.teleportAllowed()) {
            tooltip.addLine(IKey.str(LH.get("gt6scan.gui.teleport_hint"))
                .color(state.hintColor()));
        }
        tooltip.addLine(IKey.str(LH.get("gt6scan.gui.waypoint_hint"))
            .color(state.hintColor()));
        tooltip.addLine(IKey.str(LH.get("gt6scan.gui.hover_hint"))
            .color(state.mutedColor()));
    }

    /**
     * Left click bookmarks the item/fluid of the hovered position in NEI, right click broadcasts the chunk it
     * belongs to. The JourneyMap waypoint moved to the B key, see {@link #onKeyPressed}.
     */
    @Override
    public Interactable.Result onMousePressed(int mouseButton) {
        updateHover(getContext());
        if (!hoverInside) return Interactable.Result.IGNORE;
        if (mouseButton == 0) {
            bookmarkHoveredItem();
            return Interactable.Result.SUCCESS;
        }
        if (mouseButton == 1) {
            broadcastChunk();
            return Interactable.Result.SUCCESS;
        }
        return Interactable.Result.IGNORE;
    }

    /**
     * Left click: puts the item or fluid of the hovered position into NEI's bookmark panel - the very stack the right
     * hand list shows as the icon of that material (a fluid mode bookmarks the item form of the fluid, its bucket,
     * because NEI bookmarks are item stacks). The cell decides which material is meant; an empty cell inside a vein
     * falls back to the most common material of its chunk, exactly like the tooltip describes the whole chunk there.
     * <p>
     * The click only ever adds: an entry that is already bookmarked stays, and the player is told instead of getting
     * NEI's bookmarks toggle behaviour (which would remove it again). Nothing is sent to the server, the map is
     * purely client side.
     */
    private void bookmarkHoveredItem() {
        short id = chunkEntry(hoverX, hoverZ);
        if (id == 0) return; // nothing scanned in that chunk, so there is nothing to bookmark
        ItemStack stack = ModularUIUtils.stackFor(state.mode(), id);
        if (stack == null) {
            notifyPlayer(LH.get("gt6scan.chat.no_bookmark_item"));
            return;
        }
        switch (NeiBookmarkBridge.add(stack)) {
            case ADDED -> {}
            case EXISTS -> notifyPlayer(LH.get("gt6scan.chat.bookmark_exists"));
            case UNAVAILABLE -> notifyPlayer(LH.get("gt6scan.chat.no_nei"));
        }
    }

    /** Client side notice of a click action, in the colours the JourneyMap notices already use. */
    private void notifyPlayer(String message) {
        UT.Entities.chat(player, LH.Chat.RED + message + LH.Chat.GRAY);
    }

    /**
     * Right click: reports the hovered chunk as "mode name, dimension, chunk (coordinate range), what the scan found
     * in it". The text is not shown locally but handed to the server, which broadcasts it to every player in the
     * public chat - the report is meant to tell the other players in multiplayer, so a message only the clicker can
     * see would defeat it. The list is read through the same method as the tooltip and the dense_and_normal
     * bookmarks, so the broadcast can never disagree with what the player sees on the map, but unlike the tooltip it
     * is not capped - the chat wraps long messages on its own. The chunk is reported even when the hovered cell
     * itself is empty, because the tooltip describes the whole chunk in that case too.
     */
    private void broadcastChunk() {
        int chunkWorldX = state.originX + (hoverX >> 4) * ScanViewState.CELL;
        int chunkWorldZ = state.originZ + (hoverZ >> 4) * ScanViewState.CELL;
        List<Map.Entry<Short, Integer>> entries = chunkEntries(hoverX, hoverZ);
        StringBuilder found = new StringBuilder();
        if (entries.isEmpty()) {
            found.append(LH.get("gt6scan.gui.chunk_empty"));
        } else {
            for (Map.Entry<Short, Integer> entry : entries) {
                if (found.length() > 0) found.append(", ");
                found.append(state.entryName(entry.getKey()))
                    .append(": ")
                    .append(entry.getValue());
            }
        }
        // the report is not written into the local chat (a client side message only the clicker would see) but sent
        // to the server, which puts it into the public chat in the name of the clicker, so the other players on the
        // server read who found what
        CommonProxy.simpleNetworkWrapper.sendToServer(
            new BroadcastRequest(String.format(LH.get("gt6scan.chat.broadcast"), state.mode()
                .localizedName(), dimensionLabel(), chunkWorldX, chunkWorldX + 15, chunkWorldZ, chunkWorldZ + 15,
                found)));
    }

    /**
     * Dimension of the broadcast as "name (id)", placed in front of the chunk so a broadcast that is copied out of
     * the chat still says where it came from. The status row above the map shows the bare provider name; the id
     * comes along because a modded world provider may report a missing name, and because two dimensions of a pack
     * can share one - with the id next to it the line is unambiguous either way.
     */
    private String dimensionLabel() {
        String name = player.worldObj.provider.getDimensionName();
        return name == null || name.trim()
            .isEmpty() ? String.valueOf(player.dimension)
                : name + " (" + player.dimension + ")";
    }

    /**
     * The two keyboard actions of the map: T teleports to the hovered cell, B creates the JourneyMap waypoint there -
     * the latter is the action the left click used to have. Both act on the hovered cell only, and ModularUI2 hands
     * key events to the hovered widget after the focused one, so typing in the search box never triggers them.
     * <p>
     * T closes the scan GUI right away: the map shows a scan of the area the player is leaving, and closing also
     * drops the mode selection GUI underneath it. Only offered when the server allowed the teleport for this scan
     * (cheat mode + config), which is also what the tooltip line follows.
     */
    @Override
    public Interactable.Result onKeyPressed(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_T && state.teleportAllowed()) {
            updateHover(getContext());
            if (!hoverInside) return Interactable.Result.IGNORE;
            CommonProxy.simpleNetworkWrapper.sendToServer(
                new SurfaceRequest(state.originX + hoverX, state.originZ + hoverZ, true));
            MCHelper.closeScreen();
            return Interactable.Result.SUCCESS;
        }
        if (keyCode == Keyboard.KEY_B) {
            updateHover(getContext());
            if (!hoverInside) return Interactable.Result.IGNORE;
            createWaypoint(state.originX + hoverX, state.originZ + hoverZ);
            return Interactable.Result.SUCCESS;
        }
        return Interactable.Result.IGNORE;
    }

    /**
     * The JourneyMap action of the map, i.e. the B key (it used to be the left click, which now bookmarks in NEI
     * instead): it marks this position in JourneyMap, which is what the key hint in the tooltip promises. The bedrock
     * modes and dense_and_normal write their waypoints straight into JourneyMap - one per material the scan found in
     * the chunk, in that material's own colour - while every other mode opens JourneyMap's waypoint editor, exactly
     * like a double click on its fullscreen map (its default name is then the coordinates of the cell). The exit
     * position is resolved by the server, so the waypoints sit on the very block a teleport would use even when the
     * client has not loaded the chunk.
     */
    private void createWaypoint(int worldX, int worldZ) {
        ScanMode mode = state.mode();
        if (mode == ScanMode.BEDROCK || mode == ScanMode.FLUID_BEDROCK) {
            // a bedrock vein is one material per chunk; without a scan result there is nothing to mark at all
            short id = chunkEntry(hoverX, hoverZ);
            if (id == 0) return;
            JourneyMapBridge.expectWaypoints(new String[]{bedrockWaypointName(mode, id)},
                new int[]{state.entryColor(id)});
        } else if (mode == ScanMode.DENSE_AND_NORMAL) {
            // one waypoint per material the chunk holds, so every kind the tooltip lists gets its own marker
            List<Map.Entry<Short, Integer>> entries = chunkEntries(hoverX, hoverZ);
            if (entries.isEmpty()) return; // nothing scanned in that chunk, do not create anything
            String[] names = new String[entries.size()];
            int[] colors = new int[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                short id = entries.get(i)
                    .getKey();
                names[i] = state.entryName(id);
                colors[i] = state.entryColor(id);
            }
            JourneyMapBridge.expectWaypoints(names, colors);
        } else {
            JourneyMapBridge.expectEditor();
        }
        CommonProxy.simpleNetworkWrapper.sendToServer(new SurfaceRequest(worldX, worldZ, false));
    }

    /** Name of a bedrock mode waypoint: "bedrock ore (X)" / "fluid bedrock (X)" with the material or fluid of the chunk. */
    private String bedrockWaypointName(ScanMode mode, short id) {
        return String.format(LH.get("gt6scan.waypoint.name"),
            LH.get(mode == ScanMode.BEDROCK ? "gt6scan.waypoint.bedrock_ore" : "gt6scan.waypoint.fluid_bedrock"),
            state.entryName(id));
    }

    /**
     * Materials of the chunk a cell belongs to, biggest block count first, filtered down to what the map currently
     * shows. The hover tooltip and the dense_and_normal waypoints both read the chunk through this, so the list of
     * markers always agrees with the list the player sees.
     */
    private List<Map.Entry<Short, Integer>> chunkEntries(int gridX, int gridZ) {
        Map<Short, Integer> counts = state.chunkCountsAt(gridX, gridZ);
        List<Map.Entry<Short, Integer>> entries = new ArrayList<>();
        if (counts != null) {
            for (Map.Entry<Short, Integer> entry : counts.entrySet()) {
                if (state.isVisible(entry.getKey())) entries.add(entry);
            }
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        }
        return entries;
    }

    /** Scanned entry of the clicked cell (a material or a fluid), or the most common one of its chunk when empty. */
    private short chunkEntry(int gridX, int gridZ) {
        short id = state.shownMatAt(gridX, gridZ);
        if (id != 0) return id;
        Map<Short, Integer> counts = state.chunkCountsAt(gridX, gridZ);
        int best = 0;
        if (counts != null) {
            for (Map.Entry<Short, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > best) {
                    best = entry.getValue();
                    id = entry.getKey();
                }
            }
        }
        return id;
    }
}
