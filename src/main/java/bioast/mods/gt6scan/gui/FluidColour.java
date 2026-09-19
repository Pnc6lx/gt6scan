package bioast.mods.gt6scan.gui;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.MT;
import gregapi.oredict.OreDictMaterial;

/**
 * Colour of a fluid in the fluid scan modes, taken from its own texture.
 * <p>
 * GT6 gives the oils and natural gas no colour at all: their {@code BlockBaseFluid} renders the fluid's texture
 * tinted with {@code UNCOLOURED} (white), so the texture is the only place their colour exists - the average of its
 * pixels is what such a fluid looks like in the world, and that is what the map draws for it. Fluids GT6 did not
 * register that way still have a still icon in Forge's fluid API, which is averaged exactly the same way. Only a
 * fluid without any texture falls back to the colour of the material it belongs to, and finally to the colour Forge
 * reports for the fluid itself.
 */
@SideOnly(Side.CLIENT)
public final class FluidColour {
    private static final int UNKNOWN = 0xFF404040;
    /** Average colour per fluid, so a texture is only ever read once; 0 marks "no readable texture". */
    private static final Map<Fluid, Integer> CACHE = new HashMap<>();

    private FluidColour() {}

    /** The colour a fluid is drawn with in the list, in the tooltips and on the map. */
    public static int of(Fluid fluid) {
        if (fluid == null) return UNKNOWN;
        int texture = textureAverage(fluid);
        if (texture != 0) return texture;
        OreDictMaterial source = materialColourSource(fluid.getName());
        if (source != null) return ScanViewState.rawColor(source);
        return fluid.getColor() | 0xFF000000;
    }

    /** Whether the fluid has a texture of its own - then the list shows that texture instead of a colour. */
    public static boolean hasTexture(Fluid fluid) {
        return fluid != null && fluid.getStillIcon() != null;
    }

    /** Average colour of the fluid's still texture, or 0 when it has no readable texture. */
    public static int textureAverage(Fluid fluid) {
        Integer cached = CACHE.get(fluid);
        if (cached != null) return cached;
        int argb = read(fluid);
        CACHE.put(fluid, argb);
        return argb;
    }

    private static int read(Fluid fluid) {
        IIcon icon = fluid.getStillIcon();
        if (icon == null || icon.getIconName() == null) return 0;
        // block icons are named without the "textures/blocks/" part, e.g. "gregtech:fluids/liquid_heavy_oil"
        String name = icon.getIconName();
        int split = name.indexOf(':');
        String domain = split < 0 ? "minecraft" : name.substring(0, split);
        String path = split < 0 ? name : name.substring(split + 1);
        try (InputStream in = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(new ResourceLocation(domain, "textures/blocks/" + path + ".png"))
            .getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) return 0;
            long r = 0, g = 0, b = 0, weight = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    int pixel = image.getRGB(x, y);
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha < 8) continue; // a transparent pixel is not part of the fluid
                    r += ((pixel >> 16) & 0xFF) * alpha;
                    g += ((pixel >> 8) & 0xFF) * alpha;
                    b += (pixel & 0xFF) * alpha;
                    weight += alpha;
                }
            }
            if (weight == 0) return 0;
            return 0xFF000000 | ((int) (r / weight) << 16) | ((int) (g / weight) << 8) | (int) (b / weight);
        } catch (Exception e) {
            // a generated icon or a mod fluid that draws itself: there is no texture to average, so fall back
            return 0;
        }
    }

    /**
     * Material providing the colour of a fluid without a texture, picked by the fluid's name - the mapping the mod
     * used for the fluid modes before they carried fluid ids, so those colours did not change.
     */
    private static OreDictMaterial materialColourSource(String fluidName) {
        if (fluidName.contains("oil")) return MT.Oil;
        if (fluidName.contains("natural")) return MT.MethaneIce;
        if (fluidName.contains("honey")) return MT.Honey;
        if (fluidName.contains("sulfuric") || fluidName.contains("acid")) return MT.H2SO4;
        if (fluidName.contains("poison")) return MT.DirtyWater;
        if (fluidName.contains("infused")) return MT.InfusedWater;
        if (fluidName.contains("mana")) return MT.Magic;
        if (fluidName.contains("water")) return MT.Water;
        if (fluidName.contains("lava")) return MT.Lava;
        return null;
    }
}
