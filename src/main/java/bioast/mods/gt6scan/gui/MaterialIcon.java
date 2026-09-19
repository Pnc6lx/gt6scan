package bioast.mods.gt6scan.gui;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Icon of a scanned material. If the material has a fluid state the fluid texture is used, which is obtained from the
 * Forge registered fluid (see {@code ModularUIUtils}). That way fluids without an item form no longer render as
 * missing texture, they show their actual fluid sprite instead.
 */
@SideOnly(Side.CLIENT)
public class MaterialIcon implements IDrawable {
    /** Thin frame for fluid icons; light fluids such as natural gas would otherwise blend into the panel. */
    private static final int EDGE = 0x70000000;
    private static final int UNKNOWN = 0x50808080;

    private final ItemStack item;
    private final FluidStack fluid;
    /** Drawn when neither the item nor the fluid can be shown: the entry's colour, or 0 for the neutral marker. */
    private final int fallbackColour;

    public MaterialIcon(ItemStack item, FluidStack fluid) {
        this(item, fluid, 0);
    }

    public MaterialIcon(ItemStack item, FluidStack fluid, int fallbackColour) {
        this.item = item;
        this.fluid = fluid;
        this.fallbackColour = fallbackColour;
    }

    @Override
    public void draw(GuiContext context, int x, int y, int width, int height, WidgetTheme widgetTheme) {
        if (this.fluid != null) {
            GuiDraw.drawFluidTexture(this.fluid, x, y, width, height, 0);
            GuiDraw.drawRect(x, y, width, 1, EDGE);
            GuiDraw.drawRect(x, y + height - 1, width, 1, EDGE);
            GuiDraw.drawRect(x, y, 1, height, EDGE);
            GuiDraw.drawRect(x + width - 1, y, 1, height, EDGE);
        } else if (this.item != null) {
            GuiDraw.drawItem(this.item, x, y, width, height, 0);
        } else if (this.fallbackColour != 0) {
            // no texture for that fluid: a solid block of the colour the entry is drawn with everywhere else
            GuiDraw.drawRect(x, y, width, height, this.fallbackColour);
        } else {
            // nothing known for this material: neutral marker instead of a broken/missing texture
            GuiDraw.drawRect(x, y, width, height, UNKNOWN);
        }
    }
}
