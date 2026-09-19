package bioast.mods.gt6scan.gui;

import net.minecraft.item.ItemStack;

import bioast.mods.gt6scan.ScannerMod;
import codechicken.nei.BookmarkPanel;
import codechicken.nei.LayoutManager;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * NEI bookmark creation for the scan map, i.e. the left click on a cell.
 * <p>
 * The stack is added through {@code BookmarkPanel.addItem}, which is the very call NEI's own {@code /nei bookmark add}
 * command makes: it appends the stack to the bookmark grid - the page the player currently has open, which is NEI's
 * own behaviour - and leaves the list completely untouched when that stack is already bookmarked. NEI's bookmarks key
 * ({@code addOrRemoveItem}) is deliberately not used: it removes an entry that is already there, so using it would
 * make a second click delete the bookmark instead of doing nothing.
 * <p>
 * Every NEI type is kept inside this one class, so a client without NEI only fails when a bookmark is really added
 * (and the failure is caught below) instead of while loading the map widget.
 */
@SideOnly(Side.CLIENT)
public final class NeiBookmarkBridge {

    private static final String NEI_MODID = "NotEnoughItems";

    /** Outcome of one bookmark click, so the map can tell the player what happened. */
    public enum Result {
        /** The stack was appended to the bookmark panel. */
        ADDED,
        /** That stack is already bookmarked, NEI left the list as it was. */
        EXISTS,
        /** No NEI, no bookmark panel yet, or NEI refused - never an exception to the caller. */
        UNAVAILABLE
    }

    private NeiBookmarkBridge() {}

    /**
     * Adds the stack to NEI's bookmark panel, without ever removing an entry that is already there.
     *
     * @return what NEI did with the stack, see {@link Result}
     */
    public static Result add(ItemStack stack) {
        if (stack == null || !Loader.isModLoaded(NEI_MODID)) return Result.UNAVAILABLE;
        try {
            return addToPanel(stack);
        } catch (Throwable t) {
            ScannerMod.debug.warn("Could not add the NEI bookmark", t);
            return Result.UNAVAILABLE;
        }
    }

    /**
     * Kept in its own method on purpose: a client without NEI never calls (and therefore never verifies/loads) a
     * method that mentions NEI types.
     */
    private static Result addToPanel(ItemStack stack) {
        BookmarkPanel panel = LayoutManager.bookmarkPanel;
        // the panel is created with NEI's layout, so it is simply not there while NEI is disabled or still loading
        if (panel == null) return Result.UNAVAILABLE;
        return panel.addItem(stack) ? Result.ADDED : Result.EXISTS;
    }
}
