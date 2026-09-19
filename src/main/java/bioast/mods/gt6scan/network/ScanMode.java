package bioast.mods.gt6scan.network;

import gregapi.data.LH;
import gregapi.oredict.OreDictPrefix;

public enum ScanMode {
    NONE(null),
    //CUSTOM(null), /*TODO add custom mode*/
    LARGE(gregapi.data.OP.ore), SMALL(gregapi.data.OP.oreSmall), DENSE_AND_NORMAL(gregapi.data.OP.oreDense), BEDROCK(
        gregapi.data.OP.oreBedrock), FLUID_BEDROCK(gregapi.data.OP.bucket), ROCK(gregapi.data.OP.rockGt), FLUID(gregapi.data.OP.bucket),
    /**
     * Ores of every mod, the way detrav's prospector finds them: not bound to one GT6 prefix but resolved through the
     * ore dictionary, so a block of another mod counts for its GT6 material. Added at the end of the enum on purpose,
     * so the ordinals stored in the item NBT keep their meaning.
     */
    OREDICT(null);
    public final OreDictPrefix PREFIX;

    ScanMode(OreDictPrefix op) {
        PREFIX = op;
    }

    public String local() {
        return PREFIX == null ? localizedName() : PREFIX.mNameLocal;
    }

    public String localizedName() {
        return LH.get("gt6scan.mode." + name().toLowerCase());
    }

    public boolean isTE() {
        switch (this) {
            case LARGE, ROCK, BEDROCK, SMALL, FLUID_BEDROCK -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
