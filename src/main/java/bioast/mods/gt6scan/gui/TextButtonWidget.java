package bioast.mods.gt6scan.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widgets.ButtonWidget;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Small labelled button used for the invert and clear filter buttons of the toolbar. */
@SideOnly(Side.CLIENT)
public class TextButtonWidget extends ButtonWidget<TextButtonWidget> {
    private final Supplier<String> label;
    private final BooleanSupplier selected;
    private final Runnable onClick;
    private final int width, height;

    public TextButtonWidget(Supplier<String> label, BooleanSupplier selected, Runnable onClick, int width, int height) {
        this.label = label;
        this.selected = selected;
        this.onClick = onClick;
        this.width = width;
        this.height = height;
        size(width, height);
        onMousePressed(mouseButton -> {
            if (mouseButton != 0) return false;
            onClick.run();
            return true;
        });
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        WidgetTheme theme = getActiveWidgetTheme(widgetTheme, isHovering());
        if (selected != null && selected.getAsBoolean()) {
            GuiDraw.drawRect(0, 0, width, height, 0x6000A0FF);
        }
        String text = label.get();
        int textW = Minecraft.getMinecraft().fontRenderer.getStringWidth(text);
        GuiDraw.drawText(text, (width - textW) / 2f, (height - 8) / 2f, 1f, theme.getTextColor(), true);
    }
}
