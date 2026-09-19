package bioast.mods.gt6scan.item;

import bioast.mods.gt6scan.ScannerMod;
import gregapi.code.TagData;
import gregapi.data.CS;
import gregapi.data.TD;
import gregapi.item.multiitem.MultiItemRandom;
import gregapi.item.multiitem.energy.EnergyStat;
import gregapi.util.ST;
import gregapi.util.UT;

import static gregapi.data.CS.V;

public class ScannerMultiTool extends MultiItemRandom {
    public static int consumptionRate;
    /** Tiers the scanner exists for; the item meta is the tier. */
    public static final int MIN_TIER = 2, MAX_TIER = 5;
    /** Hard upper bound for every scan range, in chunks. Read from the config. */
    public static int maxRange = 64;
    /** Configured scan range in chunks per tier (index = item meta); the config loader fills this in. */
    private static final int[] RANGE = new int[MAX_TIER + 1];
    static {
        for (int tier = 0; tier < RANGE.length; tier++) RANGE[tier] = defaultRange(tier);
    }

    public String mainConsumptionEnergyType = "EU";
    public int storage_multiplier = 8000;

    public ScannerMultiTool() {
        super(ScannerMod.MODID, "scannertool");
        consumptionRate = 50;
    }

    /** Range a tier falls back to when the config holds no usable value: 9, 11, 13 and 15 chunks for tiers 2 to 5. */
    public static int defaultRange(int tier) {
        return tier * 2 + 5;
    }

    /** Stores the configured range of one tier; reading clamps it to [1, maxRange]. */
    public static void setRange(int tier, int range) {
        if (tier >= 0 && tier < RANGE.length) RANGE[tier] = range;
    }

    /**
     * Scan range of a tier, in chunks.
     * <p>
     * This is the single source of truth for the item tooltip, the mode GUI and the scan itself, so what the GUI
     * shows always matches the area the server really scans - there is no second copy in the item NBT that could
     * go stale when the config changes.
     */
    public static int rangeOf(int tier) {
        int range = tier >= 0 && tier < RANGE.length ? RANGE[tier] : defaultRange(tier);
        return Math.max(1, Math.min(range, maxRange));
    }

    @Override
    public void addItems() {
        for (int i = MIN_TIER; i <= MAX_TIER; i++) {
            TagData energy = (TagData) UT.Reflection.getFieldContent(TD.Energy.class,
                mainConsumptionEnergyType,
                false,
                false);
            if (energy == null) {
                ScannerMod.debug.warn(
                    "mainConsumptionEnergyType was not set right, defaulting to NULL (no energy consumption)");
            }
            addItem(i,
                String.format("%s Scanner (%s)", CS.VOLTAGE_NAMES[i], CS.VN[i]),
                "Scan For Ores, Fluids and Rocks (when in Gui you can click on any tile and bookmark it)",
                new ScannerBehavior(energy)
            );
            if (energy != null) {
                setElectricStats(i,
                    EnergyStat.makeTool(energy,
                        V[i] * storage_multiplier,
                        V[i],
                        1,
                        ST.make(this, 1, i)));
            }
        }
    }
}
