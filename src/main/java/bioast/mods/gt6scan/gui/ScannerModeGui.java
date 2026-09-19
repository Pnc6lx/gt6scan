package bioast.mods.gt6scan.gui;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;

import bioast.mods.gt6scan.ScannerMod;
import bioast.mods.gt6scan.item.ScannerBehavior;
import bioast.mods.gt6scan.network.ScanMode;
import bioast.mods.gt6scan.network.scanmessage.ScanRequest;
import bioast.mods.gt6scan.proxy.CommonProxy;
import com.cleanroommc.modularui.theme.WidgetTheme;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregapi.data.LH;

/**
 * The mode selection GUI, opened by right clicking the scanner. One button per scan mode; clicking a button
 * requests the scan and the map GUI opens on top of this one once the result arrives, so ESC / Backspace in the
 * map returns here.
 */
@SideOnly(Side.CLIENT)
public final class ScannerModeGui {

    private static final int PAD = 8;
    /** Height one wrapped line of the title / range / hint block gets, so a block is {@code lines * itsHeight}. */
    private static final int TITLE_H = 12;
    private static final int RANGE_H = 11;
    private static final int HINT_H = 11;
    /** Distance between the baselines of two wrapped lines of the same block (one pixel more than the font itself). */
    private static final int LINE_STEP = 10;
    private static final int BTN_W = 150;
    private static final int BTN_H = 18;
    private static final int GAP = 2;
    /** Blocks per chunk, used to show the range the way the player sees it in the world. */
    private static final int BLOCKS_PER_CHUNK = 16;

    private ScannerModeGui() {}

    public static void open(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != ScannerMod.tool) return;
        final ScanMode current = ScannerBehavior.getMode(held);
        final int size = ScannerBehavior.getSize(held);
        // the mode the last scan was requested with, for the "scanning ..." feedback line
        final ScanMode[] requested = { null };

        ScanMode[] modes = ScanMode.values();
        int buttons = 0;
        for (ScanMode mode : modes) if (mode != ScanMode.NONE) buttons++;
        int width = PAD + BTN_W + PAD;

        // every header line is wrapped to the panel width, so a long translation makes the panel grow downwards
        // instead of running over the right border. The block heights come from the very same wrapping the drawing
        // uses, and the hint block is sized for the longest text it can ever show (the initial hint plus the
        // "scanning <mode>" feedback of every mode), so the buttons below can never be overlapped.
        int titleH = wrap(LH.get("gt6scan.gui.select_mode"), BTN_W).size() * TITLE_H;
        int rangeH = wrap(String.format(LH.get("gt6scan.gui.range"), size, size * BLOCKS_PER_CHUNK),
            BTN_W).size() * RANGE_H;
        int hintH = wrap(LH.get("gt6scan.gui.mode_hint"), BTN_W).size() * HINT_H;
        for (ScanMode mode : modes) {
            if (mode == ScanMode.NONE) continue;
            hintH = Math.max(hintH,
                wrap(String.format(LH.get("gt6scan.gui.scanning"), mode.localizedName()), BTN_W).size() * HINT_H);
        }
        int headerH = titleH + rangeH + hintH;
        int height = PAD + headerH + buttons * (BTN_H + GAP) + PAD;

        ModularPanel panel = ModularPanel.defaultPanel("ScannerModes", width, height);
        panel.flex()
            .align(Alignment.Center);

        panel.child(new TextLine(() -> LH.get("gt6scan.gui.select_mode"), BTN_W).pos(PAD, PAD)
            .size(BTN_W, titleH));
        // the range the scanner will really use: it is read from the config, so this line follows any config change
        // and always agrees with the item tooltip and with what the server scans
        panel.child(new TextLine(() -> String.format(LH.get("gt6scan.gui.range"), size, size * BLOCKS_PER_CHUNK),
            BTN_W).pos(PAD, PAD + titleH)
                .size(BTN_W, rangeH));
        panel.child(new TextLine(() -> requested[0] == null ? LH.get("gt6scan.gui.mode_hint")
            : String.format(LH.get("gt6scan.gui.scanning"), requested[0].localizedName()), BTN_W)
                .pos(PAD, PAD + titleH + rangeH)
                .size(BTN_W, hintH));

        int index = 0;
        for (ScanMode mode : modes) {
            if (mode == ScanMode.NONE) continue;
            final ScanMode m = mode;
            int y = PAD + headerH + index * (BTN_H + GAP);
            panel.child(new TextButtonWidget(
                m::localizedName,
                () -> requested[0] == m || (requested[0] == null && current == m),
                () -> {
                    requested[0] = m;
                    CommonProxy.simpleNetworkWrapper
                        .sendToServer(new ScanRequest(m, (int) player.posX, (int) player.posZ, size));
                },
                BTN_W,
                BTN_H).tooltipAutoUpdate(true)
                    .tooltipBuilder(tooltip -> tooltip.addLine(LH.get("gt6scan.gui.mode_hint")))
                    .pos(PAD, y));
            index++;
        }

        ClientGUI.open(new ModularScreen("gt6scan", panel));
    }

    /**
     * Breaks a text into the lines that fit the given pixel width, the same way vanilla tooltips and chat do: word
     * wrap at spaces, and a hard split when a single word (any run of CJK characters, for example) is already wider
     * than the width. The panel layout uses it too, so what is drawn can never be higher than the space reserved.
     */
    @SuppressWarnings("unchecked") // vanilla declares its result as a raw List, every element is a String
    private static List<String> wrap(String text, int maxWidth) {
        return Minecraft.getMinecraft().fontRenderer.listFormattedStringToWidth(text, maxWidth);
    }

    /** A block of plain text wrapped to the given width, drawn with the panel theme's text colour. */
    private static final class TextLine extends Widget<TextLine> {

        private final Supplier<String> text;
        private final int wrapWidth;

        TextLine(Supplier<String> text, int wrapWidth) {
            this.text = text;
            this.wrapWidth = wrapWidth;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
            WidgetTheme theme = getActiveWidgetTheme(widgetTheme, false);
            int color = theme.getTextColor();
            int y = 0;
            for (String line : wrap(text.get(), wrapWidth)) {
                GuiDraw.drawText(line, 0, y, 1f, color, true);
                y += LINE_STEP;
            }
        }
    }
}
