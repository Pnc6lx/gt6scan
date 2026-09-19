package bioast.mods.gt6scan.network.scanmessage;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;

/**
 * Right click on the map: the chunk report becomes a public chat line sent in the name of the player who clicked,
 * so everyone on the server reads it as "<player> scanner report" instead of as an anonymous server announcement.
 * That is what makes it useful in multiplayer - the others see both the scan result and who found it, which a
 * client side chat message (visible to the clicker alone) can never do.
 * <p>
 * The text itself is built by the client, because the material and mode names are localised there and cannot be
 * recreated on the server. It is therefore checked here rather than trusted: formatting codes and line breaks are
 * removed and the length is capped, which is all a modified client could otherwise abuse this for. The player name
 * is taken from the server side entity, so that part cannot be forged.
 */
public class HandlerBroadcastServer implements IMessageHandler<BroadcastRequest, IMessage> {
    /** Chat wraps long reports on its own; this only stops absurd payloads from a modified client. */
    private static final int MAX_LENGTH = 1024;

    @Override
    public IMessage onMessage(BroadcastRequest message, MessageContext ctx) {
        if (ctx.side != Side.SERVER) return null;
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (player == null) return null;
        String text = clean(message.text);
        if (text.isEmpty()) return null;
        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server == null) return null;
        // the vanilla chat format is reused, so the report is displayed as a message the clicker sent ("<player>
        // report") rather than as an anonymous server broadcast, and every player (the clicker included) receives
        // it exactly like a typed chat message
        IChatComponent line = new ChatComponentTranslation("chat.type.text", player.getCommandSenderName(),
            new ChatComponentText(text));
        server.getConfigurationManager()
            .sendChatMsg(line);
        return null;
    }

    /** Drops the chat formatting section sign and any line break, and caps the length. */
    private static String clean(String text) {
        if (text == null) return "";
        StringBuilder builder = new StringBuilder(Math.min(text.length(), MAX_LENGTH));
        for (int i = 0; i < text.length() && builder.length() < MAX_LENGTH; i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' || c == '\n' || c == '\r' || c == '\t') continue;
            builder.append(c);
        }
        return builder.toString()
            .trim();
    }
}
