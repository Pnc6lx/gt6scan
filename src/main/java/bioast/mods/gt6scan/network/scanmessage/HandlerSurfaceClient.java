package bioast.mods.gt6scan.network.scanmessage;

import bioast.mods.gt6scan.gui.JourneyMapBridge;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Hands the position the server resolved for a clicked map cell over to {@link JourneyMapBridge}. */
@SideOnly(Side.CLIENT)
public class HandlerSurfaceClient implements IMessageHandler<SurfaceResponse, IMessage> {

    @Override
    public IMessage onMessage(SurfaceResponse message, MessageContext ctx) {
        if (ctx.side != Side.CLIENT) return null;
        JourneyMapBridge.onSurfaceResolved(message.x, message.y, message.z);
        return null;
    }
}
