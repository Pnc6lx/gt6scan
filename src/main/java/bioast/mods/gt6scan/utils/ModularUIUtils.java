package bioast.mods.gt6scan.utils;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.widget.Widget;

import bioast.mods.gt6scan.gui.MaterialIcon;
import bioast.mods.gt6scan.network.ScanMode;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.FL;
import gregapi.data.OP;
import gregapi.oredict.OreDictMaterial;
import gregapi.oredict.OreDictPrefix;

@SideOnly(Side.CLIENT)
public class ModularUIUtils {
    /** Same item choice as before, but null safe: prefixes are not necessarily registered for every material. */
    public static ItemStack stackFor(OreDictMaterial mat, ScanMode mode) {
        OreDictPrefix prefix = OP.oreRaw;
        if (mode == ScanMode.SMALL) prefix = OP.crushed;
        if (mode.PREFIX == OP.bucket) prefix = OP.bucket;
        if (mode == ScanMode.ROCK) prefix = OP.rockGt;
        if (mat.mNameInternal.contains("Peat")) prefix = OP.ingot;
        if (mat.mNameInternal.contains("Clay")) prefix = OP.dust;
        try {
            return prefix.dat(mat)
                .getStack(1);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fluid form of a scanned material for the fluid scan modes. GT6 keeps the fluid states on the material itself,
     * which is exactly the Forge registered fluid, so any fluid (also from other mods) can be displayed.
     *
     * @return the fluid to display or null if the material has no fluid state
     */
    public static FluidStack fluidFor(OreDictMaterial mat, ScanMode mode) {
        if (mode != ScanMode.FLUID && mode != ScanMode.FLUID_BEDROCK) return null;
        // the two special cases keep the fluid the scanner used to show for them
        if (mat.mNameInternal.contains("Methan")) return new FluidStack(FL.Gas_Natural.fluid(), 1);
        if (mat.mNameInternal.contains("Water")) return new FluidStack(FL.Water_Geothermal.fluid(), 1);
        if (mat.mNameInternal.contains("Lava")) return new FluidStack(FL.Lava.fluid(), 1);
        if (mat.mGas != null) return mat.mGas;
        if (mat.mLiquid != null) return mat.mLiquid;
        if (mat.mPlasma != null) return mat.mPlasma;
        return null;
    }

    /** Icon for a list entry: fluid texture when the material has a fluid, otherwise the item form. */
    public static MaterialIcon icon(OreDictMaterial mat, ScanMode mode) {
        FluidStack fluid = fluidFor(mat, mode);
        if (fluid != null) return new MaterialIcon(null, fluid);
        return new MaterialIcon(stackFor(mat, mode), null);
    }

    /** Kept for callers that want the old widget behaviour. */
    public static Widget<?> wItem(OreDictMaterial mat, ScanMode mode, int size) {
        return icon(mat, mode).asWidget()
            .size(size);
    }
}
