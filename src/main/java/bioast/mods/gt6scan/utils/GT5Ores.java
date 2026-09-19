package bioast.mods.gt6scan.utils;

import java.lang.reflect.Method;

import net.minecraft.block.Block;

import gregapi.data.TD;
import gregapi.oredict.OreDictMaterial;

/**
 * Reserved compatibility with GregTech 5, for the extreme case of GT5 and GT6 being installed side by side.
 * <p>
 * GT5 keeps its ores in the metadata of plain blocks ({@code gregtech.common.blocks.GTBlockOre}), so the tile entity
 * based ore scan never sees them, and the ore dictionary route does not cover them either: GT5 registers its other
 * stone types as "oreNetherrack...", "oreBlackgranite...", ..., names whose prefixes GT6 does not know anything
 * about, so GT6 resolves those names to nothing and no material comes out. detrav's prospector reads GT5's blocks
 * directly instead, and that is what is mirrored here: {@code LARGE} counts GT5's normal ores, {@code SMALL} its
 * small ores, both as the GT6 material behind them.
 * <p>
 * Everything below is reflection on purpose. Both GregTechs ship the very same class names (for example
 * {@code gregtech.api.interfaces.tileentity.IGregTechTileEntity}, which exists in both of them), so gt6scan can
 * never be compiled against GT5 and referencing its classes directly would blow up on every normal GT6-only install.
 * With reflection the whole thing is inert when GT5 is missing - {@link #isPresent()} is false, the normal case - and
 * only the two scans that use it pay for it when GT5 really is there.
 * <p>
 * Only GT5's own ore blocks are covered. Its legacy ore blocks and the ores of its addons (Bartworks, GT++) are
 * different classes or even different mods and are left alone; the ore dictionary scan still covers their overlworld
 * (plain stone) variants.
 */
public final class GT5Ores {
    /** GT5's ore block, the only thing that tells GT5's ores apart from every other block in the world. */
    private static final Class<?> BLOCK = load("gregtech.common.blocks.GTBlockOre");
    /** {@code boolean isSmallOre(int meta)} - small ores are the "SMALL" half of the split, the rest is "LARGE". */
    private static final Method IS_SMALL_ORE = method(BLOCK, "isSmallOre", int.class);
    /** {@code boolean isNatural(int meta)} - the world generation marker, the same filter detrav's prospect uses. */
    private static final Method IS_NATURAL = method(BLOCK, "isNatural", int.class);
    /** {@code Materials getMaterial(int meta)} - null for a metadata that is not a material index. */
    private static final Method GET_MATERIAL = method(BLOCK, "getMaterial", int.class);
    /** {@code String getInternalName()} - GT5's material name, and GT6's material map is keyed by exactly that. */
    private static final Method GET_INTERNAL_NAME = GET_MATERIAL == null ? null
        : method(GET_MATERIAL.getReturnType(), "getInternalName");

    private GT5Ores() {}

    /** Whether GT5 and its ore blocks are there, i.e. whether {@link #oreID} can return anything but 0. */
    public static boolean isPresent() {
        return IS_SMALL_ORE != null && IS_NATURAL != null && GET_MATERIAL != null && GET_INTERNAL_NAME != null;
    }

    /**
     * The GT6 material of a GT5 ore block, or 0 if it is not a GT5 ore, if it is the other kind of ore (a small one
     * when {@code small} is false or a normal one when it is true), if it was not placed by world generation, or if
     * GT6 does not know that material - a GT5 material without a GT6 counterpart has no GT6 raw ore to draw, and
     * showing it as some other material would be worse than not showing it at all.
     */
    public static short oreID(Block block, int meta, boolean small) {
        if (BLOCK == null || !BLOCK.isInstance(block)) return 0;
        try {
            if (((Boolean) IS_SMALL_ORE.invoke(block, meta)).booleanValue() != small) return 0;
            if (!((Boolean) IS_NATURAL.invoke(block, meta)).booleanValue()) return 0;
            Object material = GET_MATERIAL.invoke(block, meta);
            if (material == null) return 0;
            // MT.NULL (unknown material) has the id -1, MT.Empty (id 0) means "no ore" in the response as well
            OreDictMaterial gt6 = OreDictMaterial.get((String) GET_INTERNAL_NAME.invoke(material));
            if (gt6.mID <= 0 || gt6.contains(TD.Properties.AUTO_MATERIAL)) return 0;
            return gt6.mID;
        } catch (Throwable e) {
            // a partially loaded or otherwise conflicting GT5 may not take the scan down with it
            return 0;
        }
    }

    /** Loads GT5's ore block without initialising it, or returns null when GT5 is not installed at all. */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, GT5Ores.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    /** The public method of that GT5 class, or null if it does not have it the way detrav's code assumes. */
    private static Method method(Class<?> owner, String name, Class<?>... args) {
        if (owner == null) return null;
        try {
            return owner.getMethod(name, args);
        } catch (Throwable e) {
            return null;
        }
    }
}
