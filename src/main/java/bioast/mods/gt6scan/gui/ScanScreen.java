package bioast.mods.gt6scan.gui;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The screen of the scan map. It is opened on top of the mode selection GUI, so closing it (ESC is handled by
 * ModularUI2 and calls the same code path) reopens the mode selection GUI instead of returning to the game.
 * <p>
 * Backspace does the same, unless a text field (the search box) is focused and needs the key to delete text.
 */
@SideOnly(Side.CLIENT)
public class ScanScreen extends ModularScreen {

    public ScanScreen(String owner, ModularPanel mainPanel) {
        super(owner, mainPanel);
        // closing the map goes back to the mode selection GUI (the screen below it in the mui stack)
        openParentOnClose(true);
    }

    @Override
    public boolean onKeyPressed(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_BACK && !textFieldFocused()) {
            close();
            return true;
        }
        return super.onKeyPressed(typedChar, keyCode);
    }

    private boolean textFieldFocused() {
        LocatedWidget focused = getContext().getFocusedWidget();
        return focused != null && focused.getElement() instanceof TextFieldWidget;
    }
}
