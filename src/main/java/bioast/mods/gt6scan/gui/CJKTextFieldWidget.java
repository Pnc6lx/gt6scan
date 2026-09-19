package bioast.mods.gt6scan.gui;

import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.ModularUI;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Search box that also takes CJK text on plain 1.7.10 (LWJGL2).
 * <p>
 * 1.7.10 never handles input method composition itself: the IME commits a finished word and LWJGL turns it into a
 * plain character event - either as a key event carrying the character, or as a "character only" event (no key
 * code, no key state). ModularUI2 forwards the first kind to the focused widget, the second kind ends up in
 * {@code GuiScreenWrapper.keyTyped}, which is not implemented, so the text was simply dropped. That is the reason
 * Chinese input did nothing here (InputFix, which patches {@code GuiScreen.handleKeyboardInput} for exactly those
 * events, only made the text reach that dead end instead of being stopped earlier).
 * <p>
 * {@link ImeBridge} now picks up those character events and hands them to {@link #insertImeChar(char)}, so both
 * delivery paths end up in the field because both belong to the same widget.
 * <p>
 * With lwjgl3ify installed ModularUI2 uses real text input events ({@code InputEvents.beginTextInput()}), the
 * characters arrive through {@code onTextInput} and this widget stays out of the way.
 */
@SideOnly(Side.CLIENT)
public class CJKTextFieldWidget extends TextFieldWidget {

    /** The focused field, so {@link ImeBridge} has a target for the events ModularUI2 does not route to widgets. */
    private static CJKTextFieldWidget focusedField;

    public CJKTextFieldWidget() {
        super();
    }

    @Override
    public Result onKeyPressed(char character, int keyCode) {
        if (!isFocused()) return Result.IGNORE;
        // lwjgl3ify delivers input method text through ModularUI2's own text events, nothing to add here
        if (ModularUI.Mods.LWJGL3IFY.isLoaded()) return super.onKeyPressed(character, keyCode);
        // IME committed character riding on a normal key event: insert it and keep the key from being handled as a
        // command (return / backspace would otherwise move the cursor or deselect the field)
        if (isImeChar(character)) {
            insertImeCharInternal(character);
            return Result.SUCCESS;
        }
        return super.onKeyPressed(character, keyCode);
    }

    @Override
    public void onFocus(ModularGuiContext context) {
        super.onFocus(context);
        focusedField = this;
    }

    @Override
    public void onRemoveFocus(ModularGuiContext context) {
        if (focusedField == this) focusedField = null;
        super.onRemoveFocus(context);
    }

    /**
     * Inserts one character that came in as a ModularUI2 character event, see {@link ImeBridge}.
     *
     * @return false when this field is not the one being typed in, so the caller leaves the event alone
     */
    static boolean insertImeChar(char character) {
        CJKTextFieldWidget field = focusedField;
        if (field == null || !field.isFocused()) return false;
        field.insertImeCharInternal(character);
        return true;
    }

    /** True when the focused field lives in the given screen, so a character event is actually meant for it. */
    static boolean isTargetOf(GuiScreen gui) {
        CJKTextFieldWidget field = focusedField;
        if (field == null || !field.isValid() || !field.isFocused()) return false;
        return field.getScreen()
            .getScreenWrapper()
            .getGuiScreen() == gui;
    }

    private void insertImeCharInternal(char character) {
        String text = String.valueOf(character);
        if (!this.handler.test(text)) return;
        this.handler.deleteMarked(); // same contract as the parent: replace the selection, do not append to it
        this.handler.insert(text, canScrollHorizontally());
    }

    /** Printable non ASCII: the output of an input method. ASCII keys go through the parent's key handling. */
    private static boolean isImeChar(char c) {
        return c > 127 && !Character.isISOControl(c) && Character.isDefined(c);
    }
}
