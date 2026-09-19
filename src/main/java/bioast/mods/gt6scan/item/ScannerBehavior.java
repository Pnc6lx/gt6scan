package bioast.mods.gt6scan.item;

import bioast.mods.gt6scan.ScannerMod;
import bioast.mods.gt6scan.network.ScanMode;
import gregapi.code.TagData;
import gregapi.data.LH;
import gregapi.data.LH.Chat;
import gregapi.item.multiitem.MultiItem;
import gregapi.item.multiitem.behaviors.IBehavior;
import gregapi.util.UT;
import gregapi.util.UT.NBT;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import java.util.List;

/**
 * Right click opens the mode selection GUI. The scan itself is requested by the GUI; energy is consumed server side
 * in {@code HandlerServer} and the chosen mode is persisted in the item NBT, so the GUI can show the current
 * selection. The scan range is not item state: it is a config value of the tier, see
 * {@link ScannerMultiTool#rangeOf(int)}.
 */
public class ScannerBehavior extends IBehavior.AbstractBehaviorDefault {

    /** Energy type shared by every tier (all tiers are constructed from the same config value). */
    public static TagData ENERGY_TYPE;

    public ScannerBehavior() {
        this(null);
    }

    public ScannerBehavior(TagData energyType) {
        ENERGY_TYPE = energyType;
    }

    @Override
    public ItemStack onItemRightClick(MultiItem aItem, ItemStack aStack, World aWorld, EntityPlayer aPlayer) {
        if (aWorld.isRemote) ScannerMod.proxy.openScannerGui(aPlayer, aStack);
        return super.onItemRightClick(aItem, aStack, aWorld, aPlayer);
    }

    @Override
    public List<String> getAdditionalToolTips(MultiItem aItem, List<String> aList, ItemStack aStack) {
        aList.add(Chat.GRAY + LH.get("gt6scan.tooltip.open_gui"));
        aList.add(
            Chat.BLINKING_ORANGE + getMode(aStack).localizedName() + Chat.GRAY + " " + LH.get("gt6scan.tooltip.mode_suffix"));
        aList.add(Chat.GRAY + String.format(LH.get("gt6scan.tooltip.size"), Chat.BLINKING_ORANGE + getSize(aStack)));
        return aList;
    }

    /** Current scan mode of a scanner item, written by the server when a scan is requested. */
    public static ScanMode getMode(ItemStack aStack) {
        NBTTagCompound tag = UT.NBT.getNBT(aStack);
        if (!tag.hasKey("mode")) {
            UT.NBT.makeInt(tag, "mode", 0);
            UT.NBT.set(aStack, tag);
            return ScanMode.NONE; // nothing selected yet, the mode GUI is opened on first use anyway
        }
        int ordinal = tag.getInteger("mode");
        ScanMode[] values = ScanMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ScanMode.NONE;
    }

    /**
     * Scan range (in chunks) of this scanner, a config value of its tier.
     * <p>
     * Deliberately not stored in the item any more: reading the config on every call keeps the tooltip, the mode
     * GUI and the scan in sync even when the config was edited after the item was crafted, and a stale NBT copy
     * can no longer make the GUI report a range the server would not scan.
     */
    public static int getSize(ItemStack aStack) {
        return ScannerMultiTool.rangeOf(aStack.getItemDamage());
    }
}
