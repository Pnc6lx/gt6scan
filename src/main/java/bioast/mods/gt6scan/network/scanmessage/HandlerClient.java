package bioast.mods.gt6scan.network.scanmessage;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.IThemeApi;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widgets.ListWidget;

import bioast.mods.gt6scan.gui.CJKTextFieldWidget;
import bioast.mods.gt6scan.gui.FilterRowWidget;
import bioast.mods.gt6scan.gui.ScanMapWidget;
import bioast.mods.gt6scan.gui.ScanScreen;
import bioast.mods.gt6scan.gui.ScanViewState;
import bioast.mods.gt6scan.gui.TextButtonWidget;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.LH;
import gregapi.oredict.OreDictMaterial;

@SideOnly(Side.CLIENT)
public class HandlerClient implements IMessageHandler<ScanResponse, IMessage>, IGuiHolder {
    /** Padding around the map and the material list. */
    private static final int PAD = 6;
    /** Row reserved above the map for the hover/status line. */
    private static final int STATUS_H = 11;
    private static final int MIN_LIST_W = 120;
    private static final int MAX_LIST_W = 230;
    /** Search field and tool button height. */
    private static final int SEARCH_H = 14;
    private static final int TOOL_H = 14;
    private static final int INVERT_W = 34;
    /** ModularUI2 ships a dark theme, the inverted mode switches the whole GUI over to it. */
    private static final String DARK_THEME = "vanilla_dark";

    private ScanViewState state;

    @Override
    public IMessage onMessage(ScanResponse message, MessageContext ctx) {
        if (ctx.side != Side.CLIENT) return null;

        Minecraft minecraft = Minecraft.getMinecraft();

        state = new ScanViewState(message.x, message.z, message.mode, message.chunkSize, message.teleportAllowed,
            message.topMat, message.counts, message.chunkCounts);
        state.recompute();
        GuiData data = new GuiData(minecraft.thePlayer);
        UISettings settings = new UISettings();
        ModularPanel panel = buildUI(data, new PanelSyncManager(new ModularSyncManager(true), true), settings);
        ClientGUI.open(createScreen(data, panel), settings);
        return null;
    }

    @Override
    public ModularScreen createScreen(GuiData data, ModularPanel mainPanel) {
        EntityPlayer player = data.getPlayer();
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        final ScanViewState view = state;
        final int mapPx = view.mapPx;
        final List<Map.Entry<Short, Integer>> entries = view.sortedTotals();

        // Material column is as wide as its longest entry (DetravScannerGUI sizes its list the same way).
        int longest = MIN_LIST_W;
        for (Map.Entry<Short, Integer> entry : entries) {
            String label = ScanViewState.materialName(OreDictMaterial.MATERIAL_ARRAY[entry.getKey()])
                + ": " + entry.getValue();
            longest = Math.max(longest, font.getStringWidth(label) + FilterRowWidget.ROW_H + 34);
        }
        final int listW = Math.min(longest, MAX_LIST_W);

        // The map is drawn 1:1 unless it does not fit, in which case it is scaled down. It must never overlap
        // the list nor leave the panel/screen.
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int maxMap = Math.min(mapPx, Math.min(resolution.getScaledWidth() - listW - 3 * PAD - 8,
            resolution.getScaledHeight() - STATUS_H - 2 * PAD - 8));
        final int mapArea = Math.max(48, maxMap);

        final int mapX = PAD;
        final int mapY = PAD + STATUS_H;
        final int listX = mapX + mapArea + PAD;
        final int toolY = mapY + SEARCH_H + 2;
        final int listY = toolY + TOOL_H + 2;
        final int listH = Math.max(16, mapY + mapArea - listY);

        mainPanel.flex().align(Alignment.Center).size(listX + listW + PAD, mapY + mapArea + PAD);
        mainPanel.themeOverride(themeId(view.isInverted()));

        // search field: only hides list rows, the map follows the ticked entries
        final StringValue search = new StringValue("");
        IWidget searchField = new CJKTextFieldWidget().hintText(LH.get("gt6scan.gui.search"))
            .value(search)
            .autoUpdateOnChange(true)
            .setMaxLength(24)
            .pos(listX, mapY)
            .size(listW - INVERT_W - 2, SEARCH_H);

        TextButtonWidget invertButton = new TextButtonWidget(
            () -> LH.get(view.isInverted() ? "gt6scan.gui.invert_on" : "gt6scan.gui.invert_off"),
            view::isInverted,
            () -> {
                view.setInverted(!view.isInverted());
                view.recompute();
                mainPanel.themeOverride(themeId(view.isInverted()));
            },
            INVERT_W,
            SEARCH_H).pos(listX + listW - INVERT_W, mapY);
        invertButton.tooltipAutoUpdate(true)
            .tooltipBuilder(tooltip -> tooltip.addLine(LH.get("gt6scan.gui.invert_hint")));

        TextButtonWidget clearButton = new TextButtonWidget(
            () -> view.isFiltering() ? String.format(LH.get("gt6scan.gui.clear_filter_n"), view.selectionSize())
                : LH.get("gt6scan.gui.clear_filter"),
            view::isFiltering,
            () -> {
                view.clearSelection();
                view.recompute();
            },
            listW,
            TOOL_H).pos(listX, toolY);
        clearButton.tooltipAutoUpdate(true)
            .tooltipBuilder(tooltip -> tooltip.addLine(LH.get("gt6scan.gui.filter_hint")));

        mainPanel.child(
            new ListWidget<>().scrollDirection(new VerticalScrollData())
                .collapseDisabledChild()
                .pos(listX, listY)
                .size(listW, listH)
                .children(entries.size(), i -> {
                    Map.Entry<Short, Integer> entry = entries.get(i);
                    FilterRowWidget row = new FilterRowWidget(view, entry.getKey(), entry.getValue(), listW);
                    row.setEnabledIf(r -> r.matches(search.getStringValue()));
                    return row;
                }));

        mainPanel.child(new ScanMapWidget(view, player, mapArea, STATUS_H).pos(mapX, mapY)
            .size(mapArea, mapArea));
        mainPanel.child(searchField);
        mainPanel.child(invertButton);
        mainPanel.child(clearButton);
        // openParentOnClose: ESC / Backspace in the map reopens the mode selection GUI it was opened from
        return new ScanScreen("gt6scan", mainPanel);
    }

    /** Theme id for the inverted mode, or null to use the default theme of the screen. */
    private static String themeId(boolean inverted) {
        return inverted && IThemeApi.get()
            .hasTheme(DARK_THEME) ? DARK_THEME : null;
    }

    @Override
    public ModularPanel buildUI(GuiData guiData, PanelSyncManager syncManager, UISettings settings) {
        return ModularPanel.defaultPanel("Scanner");
    }
}
