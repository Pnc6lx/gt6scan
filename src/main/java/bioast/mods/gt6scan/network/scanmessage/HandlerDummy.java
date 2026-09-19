package bioast.mods.gt6scan.network.scanmessage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * This MessageHandler does nothing; it is only used because the dedicated server must register the message types it
 * sends with Forge, so that the channel knows the discriminator id of each of them (the real handling happens on the
 * client). It stays generic over the message type, one instance of it is registered per server to client message:
 * {@link ScanBeginResponse}, {@link ScanChunkResponse} and {@link ScanDoneResponse}.
 */
public class HandlerDummy implements IMessageHandler<IMessage, IMessage> {
    @Override
    public IMessage onMessage(IMessage message, MessageContext ctx) {
        System.err.println("Scan message received on wrong side:" + ctx.side + " " + message.getClass());
        return null;
    }
}
