package bioast.mods.gt6scan.gui;

import net.minecraftforge.common.MinecraftForge;

import com.cleanroommc.modularui.ModularUI;
import com.cleanroommc.modularui.api.event.KeyboardInputEvent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * Delivers input method (Chinese, Japanese, Korean) text to the search box on plain 1.7.10.
 * <p>
 * ModularUI2 posts {@link KeyboardInputEvent.Pre} for every LWJGL keyboard event and handles them itself: key
 * presses (event key state true) go to the focused widget as {@code onKeyPressed}, while character events (key code
 * 0, no key state) are only checked against the screen wide shortcuts and, when nothing matches, left to vanilla
 * {@code GuiScreen.handleKeyboardInput()} - which for a ModularUI2 screen is {@code GuiScreenWrapper.keyTyped},
 * a method that does nothing. The committed IME characters are exactly those character events, so they died there.
 * <p>
 * Listening at the lowest priority (after ModularUI2 and other mods, and also for events they cancelled) lets this
 * bridge hand the character to the focused {@link CJKTextFieldWidget} before it is lost.
 */
@SideOnly(Side.CLIENT)
public class ImeBridge {

    private static boolean registered;

    /** Called once from the client proxy. */
    public static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new ImeBridge());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onKeyboardInput(KeyboardInputEvent.Pre event) {
        if (ModularUI.Mods.LWJGL3IFY.isLoaded()) return; // text input events already reach the field there
        if (Keyboard.getEventKeyState()) return; // key presses are routed to the focused widget by ModularUI2
        if (Keyboard.getEventKey() != 0) return; // only character events carry no key code
        char character = Keyboard.getEventCharacter();
        if (character < ' ' || !Character.isDefined(character)) return; // control/non printable, not typed text
        if (!CJKTextFieldWidget.isTargetOf(event.gui)) return; // the search box is not the one being typed in
        if (CJKTextFieldWidget.insertImeChar(character)) event.setCanceled(true);
    }
}
