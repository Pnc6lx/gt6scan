package bioast.mods.gt6scan.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widgets.ButtonWidget;

import bioast.mods.gt6scan.utils.ModularUIUtils;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.LH;

/**
 * One entry of the right hand list: tick box, material icon, name and block count. Clicking it toggles the material
 * in the map filter, so the map only shows the materials that were explicitly selected.
 */
@SideOnly(Side.CLIENT)
public class FilterRowWidget extends ButtonWidget<FilterRowWidget> {
    public static final int ROW_H = 16;
    private static final int BOX = 9;

    private final ScanViewState state;
    private final short matID;
    private final String name;
    private final int count;
    private final MaterialIcon icon;
    private final int width;

    public FilterRowWidget(ScanViewState state, short matID, int count, int width) {
        this.state = state;
        this.matID = matID;
        this.count = count;
        // the name and the icon come from the view: in the fluid modes an entry is a fluid, not a material
        this.name = state.entryName(matID);
        this.icon = ModularUIUtils.icon(state.mode(), matID);
        this.width = width;
        size(width, ROW_H);
        onMousePressed(mouseButton -> {
            if (mouseButton != 0) return false;
            state.toggleSelection(matID);
            // the map renders a colour grid that is computed once per option change, without this the map would
            // keep showing every material until the filter is cleared
            state.recompute();
            return true;
        });
        tooltipAutoUpdate(true).tooltipBuilder(this::buildTooltip)
            .tooltipShowUpTimer(0);
    }

    /** Used by the search field to hide rows whose name does not match. */
    public boolean matches(String query) {
        return state.matchesSearch(matID, query);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        WidgetTheme theme = getActiveWidgetTheme(widgetTheme, isHovering());
        boolean selected = state.isSelected(matID);
        int textColor = state.entryListColor(matID);
        int line = theme.getTextColor();

        if (selected) {
            // the whole row is tinted as well: the filter state is then obvious at a glance and not only from the
            // tick box, and the accent bar keeps the colour of the entry itself
            GuiDraw.drawRect(0, 0, width, ROW_H, (line & 0x00FFFFFF) | 0x28000000);
            GuiDraw.drawRect(0, 0, 2, ROW_H, textColor);
        }
        int boxY = (ROW_H - BOX) / 2;
        GuiDraw.drawRect(2, boxY, BOX, 1, line);
        GuiDraw.drawRect(2, boxY + BOX - 1, BOX, 1, line);
        GuiDraw.drawRect(2, boxY, 1, BOX, line);
        GuiDraw.drawRect(2 + BOX - 1, boxY, 1, BOX, line);
        // the tick is filled in the colour of the box's own border: one solid block is unmistakably "ticked", while
        // the material coloured fill it used before was too close to the border to tell the two states apart
        if (selected) GuiDraw.drawRect(2 + 2, boxY + 2, BOX - 4, BOX - 4, line);

        int iconX = 2 + BOX + 3;
        icon.draw(context, iconX, 0, ROW_H, ROW_H, theme);

        int textX = iconX + ROW_H + 3;
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String label = font.trimStringToWidth(name + ": " + count, Math.max(8, width - textX - 2));
        GuiDraw.drawText(label, textX, (ROW_H - 8) / 2f, 1f, textColor, true);
    }

    private void buildTooltip(RichTooltip tooltip) {
        tooltip.addLine(IKey.str(name + ": " + count)
            .color(state.entryListColor(matID)));
        tooltip.addLine(
            IKey.str(LH.get(state.isSelected(matID) ? "gt6scan.gui.row_selected" : "gt6scan.gui.row_unselected"))
                .color(state.mutedColor()));
        tooltip.addLine(IKey.str(LH.get("gt6scan.gui.row_hint"))
            .color(state.mutedColor()));
        if (state.isFiltering()) {
            tooltip.addLine(
                IKey.str(String.format(LH.get("gt6scan.gui.filter_count"), state.selectionSize()))
                    .color(state.headerColor()));
        }
    }
}
