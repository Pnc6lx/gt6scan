package bioast.mods.gt6scan.gui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import bioast.mods.gt6scan.ScannerMod;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.LH;
import gregapi.data.LH.Chat;
import gregapi.util.UT;
import journeymap.client.model.Waypoint;
import journeymap.client.ui.UIManager;
import journeymap.client.waypoint.WaypointStore;

/**
 * JourneyMap waypoint creation for the scan map, in the two flavours the map needs.
 * <p>
 * The editor flavour is what JourneyMap's own fullscreen map does on a double click: build
 * {@code Waypoint.at(x, y, z, Type.Normal, dimension)} (which already names the waypoint after the coordinates) and
 * hand it to {@code UIManager.openWaypointEditor(waypoint, true, parent)} with {@code isNew = true}. The map uses it
 * for every scan mode without its own naming rule.
 * <p>
 * The direct flavour writes one waypoint per entry straight into {@link WaypointStore} without opening anything,
 * skipping the entries JourneyMap already holds for that chunk. The map uses it for the bedrock modes and for
 * dense_and_normal, where one waypoint per material is wanted, each in the colour of that material.
 * <p>
 * Every JourneyMap type is kept inside this one class, so a client without JourneyMap only fails when a waypoint is
 * really created (and the failure is caught below) instead of while loading the map widget.
 */
@SideOnly(Side.CLIENT)
public final class JourneyMapBridge {

    private static final String JOURNEYMAP_MODID = "journeymap";

    /** True while a click waits for the server to report the position the waypoint(s) should get. */
    private static boolean pending;
    /** True to write the waypoints directly instead of opening JourneyMap's editor. */
    private static boolean direct;
    /** Names and colours of the waypoints to write, only set when {@link #direct} is true. */
    private static String[] pendingNames;
    private static int[] pendingColors;

    private JourneyMapBridge() {}

    /**
     * Called by the map widget when the server position should open JourneyMap's waypoint editor. The name is left
     * to JourneyMap, which fills in the coordinates, exactly like a double click on its fullscreen map.
     */
    static void expectEditor() {
        pending = true;
        direct = false;
        pendingNames = null;
        pendingColors = null;
    }

    /**
     * Called by the map widget when the waypoints should be written directly, without any editor. {@code names} and
     * {@code colors} are parallel and must have the same length.
     */
    static void expectWaypoints(String[] names, int[] colors) {
        pending = true;
        direct = true;
        pendingNames = names;
        pendingColors = colors;
    }

    /** Server answered with the resolved position: create the waypoint(s) there. */
    public static void onSurfaceResolved(int x, int y, int z) {
        if (!pending) return;
        pending = false;
        boolean directNow = direct;
        String[] names = pendingNames;
        int[] colors = pendingColors;
        // clear right away, a stray second response must not create anything again
        direct = false;
        pendingNames = null;
        pendingColors = null;

        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        if (!Loader.isModLoaded(JOURNEYMAP_MODID)) {
            // client side player, so UT.Entities.sendchat (EntityPlayerMP only) would silently drop the message
            UT.Entities.chat(player, Chat.RED + LH.get("gt6scan.chat.no_journeymap") + Chat.GRAY);
            return;
        }
        try {
            if (directNow) writeWaypoints(player, x, y, z, names, colors);
            else openEditor(player, x, y, z);
        } catch (Throwable t) {
            // Getting here means the direct write or the editor did not even start (a JourneyMap version that does
            // not have one of the methods this class calls, for example), so the editor is tried once more as the
            // last resort: it is JourneyMap's own way of creating a waypoint and therefore the one path that is
            // always in sync with the JourneyMap that is really running.
            ScannerMod.debug.warn("Could not create the JourneyMap waypoint", t);
            try {
                openEditor(player, x, y, z);
                UT.Entities.chat(player,
                    Chat.RED + String.format(LH.get("gt6scan.chat.waypoint_failed"), t.toString()) + Chat.GRAY);
            } catch (Throwable t2) {
                ScannerMod.debug.warn("Could not open the JourneyMap waypoint editor either", t2);
                UT.Entities.chat(player,
                    Chat.RED + String.format(LH.get("gt6scan.chat.waypoint_no_editor"), t.toString()) + Chat.GRAY);
            }
        }
    }

    /**
     * Opens JourneyMap's waypoint editor at the resolved position. Kept in its own method on purpose: a client
     * without JourneyMap never calls (and therefore never verifies/loads) a method that mentions JourneyMap types.
     */
    private static void openEditor(EntityPlayer player, int x, int y, int z) {
        UIManager.getInstance()
            .openWaypointEditor(Waypoint.at(x, y, z, Waypoint.Type.Normal, player.dimension), true, null);
    }

    /**
     * Writes the waypoints straight into JourneyMap's store. Every waypoint carries the packed {@code 0xFFRRGGBB}
     * colour {@link ScanViewState#rawColor} returns, handed to the waypoint's constructor rather than set afterwards -
     * see the loop below for why that is not the same thing.
     * <p>
     * Every waypoint is checked against what JourneyMap already holds first: a marker of the same name in the same
     * chunk of the same dimension is skipped, so clicking around one vein (or double clicking a cell) does not pile
     * up waypoints that are indistinguishable on the map. Only when nothing could be created at all does the player
     * get told, so a normal click stays silent.
     */
    private static void writeWaypoints(EntityPlayer player, int x, int y, int z, String[] names, int[] colors) {
        WaypointStore store = WaypointStore.instance();
        // Snapshot instead of iterating the store directly: its view is a live cache (save() writes into it) and the
        // snapshot also collects this batch, so a name repeated inside one click cannot slip through either.
        Collection<Waypoint> all = store == null ? null : store.getAll();
        List<Waypoint> existing = all == null ? new ArrayList<>() : new ArrayList<>(all);

        List<String> created = new ArrayList<>();
        boolean needsBulkSave = false;
        Throwable failure = null;
        for (int i = 0; i < names.length; i++) {
            if (existsAlready(existing, names[i], x, z, player.dimension)) continue;
            Waypoint waypoint;
            try {
                // Name and colour go into the constructor instead of being set afterwards. JourneyMap's setColor
                // takes an Integer up to 5.1.4 and an int from 5.2 on, so a call to it is bound to the version this
                // mod was compiled against and dies with a NoSuchMethodError on every other one. The constructor
                // exists unchanged in both versions and it takes the final name as well, which keeps the waypoint's
                // id in sync: the id is built from the name, so renaming a waypoint that Waypoint.at() created left
                // every waypoint of one cell sharing the id - and the file - of that coordinate name, and only the
                // last of them survived. The colour is 0xRRGGBB here, which is what java.awt.Color and JourneyMap
                // both use.
                waypoint = new Waypoint(names[i], x, y, z, new Color(colors[i] & 0xFFFFFF),
                    Waypoint.Type.Normal, player.dimension);
            } catch (Throwable t) {
                // inside the loop: a JourneyMap that cannot build one waypoint still gets all the others
                failure = t;
                continue;
            }
            boolean stored = false;
            try {
                // save() puts it into the store's cache and writes its file right away, which is what the direct
                // flavour needs - JourneyMap 5.x keeps the whole store in a cache and only writes out dirty entries
                store.save(waypoint);
                stored = true;
            } catch (Throwable t) {
                failure = t;
            }
            if (!stored) {
                try {
                    // add() is what the store uses for a batch (bulkSave() writes every dirty entry afterwards)
                    store.add(waypoint);
                    needsBulkSave = true;
                    stored = true;
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (!stored) continue;
            existing.add(waypoint);
            created.add(names[i]);
        }
        if (needsBulkSave) {
            try {
                store.bulkSave();
            } catch (Throwable t) {
                failure = t;
            }
        }
        // only report what the store really holds afterwards, so the player is never told about a waypoint that did
        // not make it (a version that silently ignores one of the two write calls would show up right here)
        Collection<Waypoint> after = store == null ? null : store.getAll();
        List<Waypoint> storedWaypoints = after == null ? new ArrayList<>() : new ArrayList<>(after);
        created.removeIf(name -> !existsAlready(storedWaypoints, name, x, z, player.dimension));

        if (!created.isEmpty()) {
            UT.Entities.chat(player,
                Chat.GREEN + String.format(LH.get("gt6scan.chat.waypoint_created"),
                    created.size(), String.join(Chat.GRAY + ", " + Chat.GREEN, created)) + Chat.GRAY);
            return;
        }
        if (failure == null) {
            UT.Entities.chat(player, Chat.YELLOW + LH.get("gt6scan.chat.waypoint_exists") + Chat.GRAY);
            return;
        }
        // the direct write did not work on this JourneyMap: fall back to its own editor, the flavour that always
        // works, so the marker can still be set by hand
        UT.Entities.chat(player,
            Chat.RED + String.format(LH.get("gt6scan.chat.waypoint_failed"), failure.toString()) + Chat.GRAY);
        ScannerMod.debug.warn("Could not write the JourneyMap waypoints directly", failure);
        openEditor(player, x, y, z);
    }

    /**
     * True when JourneyMap already holds a waypoint with that name in the chunk of the clicked position and in the
     * player's dimension. The chunk - not the exact block - is what identifies a marker here: the map bookmarks a
     * chunk (one marker per material the chunk holds), so clicking any cell of an already marked chunk is a repeat
     * of the same marker, while the same material name in another chunk is a marker of its own. The resolved
     * height is deliberately not compared, that way the check stays valid even if the surface moved since.
     */
    private static boolean existsAlready(List<Waypoint> waypoints, String name, int x, int z, int dimension) {
        int chunkX = x >> 4, chunkZ = z >> 4;
        for (Waypoint waypoint : waypoints) {
            if ((waypoint.getX() >> 4) != chunkX || (waypoint.getZ() >> 4) != chunkZ) continue;
            Collection<Integer> dimensions = waypoint.getDimensions();
            if (dimensions == null || !dimensions.contains(dimension)) continue;
            if (name.equals(waypoint.getName())) return true;
        }
        return false;
    }
}
