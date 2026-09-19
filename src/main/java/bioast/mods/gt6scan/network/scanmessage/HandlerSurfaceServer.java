package bioast.mods.gt6scan.network.scanmessage;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

import bioast.mods.gt6scan.ScannerMod;
import bioast.mods.gt6scan.item.ScannerMultiTool;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import gregapi.data.LH;
import gregapi.data.LH.Chat;
import gregapi.util.UT;

/**
 * Resolves the surface of a map cell (highest block that is neither air nor a fluid, and the free space on top of
 * it) and either teleports the player there or reports the position back for a JourneyMap waypoint.
 * <p>
 * The scan GUI only shows cells of the scanned area, which is centred on the player and as big as the configured
 * range, so a request from anywhere else cannot have come from the map - together with the "must hold the scanner"
 * check this keeps the teleport from being usable as a free, unbounded teleporter.
 */
public class HandlerSurfaceServer implements IMessageHandler<SurfaceRequest, SurfaceResponse> {
    private static final int BLOCKS_PER_CHUNK = 16;
    /** Reported height for a column without any block at all (void), used for waypoints only. */
    private static final int FALLBACK_Y = 64;

    @Override
    public SurfaceResponse onMessage(SurfaceRequest message, MessageContext ctx) {
        if (ctx.side != Side.SERVER) return null;
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || held.getItem() != ScannerMod.tool) return null;

        // same gate the map uses for its "press T" hint, enforced here too so a modified client cannot teleport
        // without the world allowing cheats and the config enabling it
        if (message.teleport && (!ScannerMod.allowTeleport || !player.getEntityWorld()
            .getWorldInfo()
            .areCommandsAllowed())) {
            return null;
        }

        int x = message.x, z = message.z;
        int reach = ScannerMultiTool.rangeOf(held.getItemDamage()) * BLOCKS_PER_CHUNK;
        if (Math.abs(x - player.posX) > reach || Math.abs(z - player.posZ) > reach) {
            UT.Entities.sendchat(player, Chat.RED + LH.get("gt6scan.chat.out_of_reach") + Chat.GRAY);
            return null;
        }

        int y = surfaceY(player.getEntityWorld(), x, z);
        if (!message.teleport) {
            // no teleport: the position is only used to place a waypoint, so a column without a block still gets one
            return new SurfaceResponse(x, y < 0 ? FALLBACK_Y : y, z);
        }
        if (y < 0) {
            // a column without a single block is void: there is nothing to stand on and no ground to fall onto, so
            // the teleport is refused instead of dropping the player into the void
            UT.Entities.sendchat(player, Chat.RED + LH.get("gt6scan.chat.no_surface") + Chat.GRAY);
            return null;
        }
        player.setPositionAndUpdate(x + 0.5D, y, z + 0.5D);
        player.fallDistance = 0.0F;
        player.motionX = player.motionY = player.motionZ = 0.0D;
        return null;
    }

    /**
     * Y of the free space on top of the highest block of a column that is neither air nor a fluid. Liquids (water,
     * lava and every Forge fluid block, which covers GT's own fluids) are skipped, so an ocean is crossed down to
     * the ground below it. The column is read down to Y 0, the lowest layer of the world, so only a column that
     * truly holds no block at all counts as void.
     *
     * @return the Y a player stands on, or -1 when the column has no such block
     */
    private static int surfaceY(World world, int x, int z) {
        // like a scan does it: make sure the chunk is there before the column is read
        world.getChunkFromBlockCoords(x, z);
        for (int y = world.getHeight() - 1; y >= 0; y--) {
            Block block = world.getBlock(x, y, z);
            if (block == null || block.isAir(world, x, y, z)) continue;
            if (block.getMaterial().isLiquid() || block instanceof IFluidBlock) continue;
            return y + 1;
        }
        return -1;
    }
}
