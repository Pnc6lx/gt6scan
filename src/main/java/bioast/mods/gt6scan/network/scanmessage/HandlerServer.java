package bioast.mods.gt6scan.network.scanmessage;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

import com.google.common.collect.MapMaker;

import bioast.mods.gt6scan.ScannerMod;
import bioast.mods.gt6scan.item.ScannerBehavior;
import bioast.mods.gt6scan.item.ScannerMultiTool;
import bioast.mods.gt6scan.network.ScanMode;
import bioast.mods.gt6scan.proxy.CommonProxy;
import bioast.mods.gt6scan.utils.ScanScheduler;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import gregapi.block.prefixblock.PrefixBlock;
import gregapi.block.prefixblock.PrefixBlockTileEntity;
import gregapi.code.TagData;
import gregapi.data.CS;
import gregapi.data.LH;
import gregapi.data.LH.Chat;
import gregapi.data.MT;
import gregapi.data.TD;
import gregapi.oredict.OreDictItemData;
import gregapi.oredict.OreDictMaterial;
import gregapi.oredict.OreDictPrefix;
import gregapi.util.OM;
import gregapi.util.UT;
import gregtech.blocks.BlockDiggable;
import gregtech.blocks.BlockSands;
import gregtech.blocks.stone.BlockCrystalOres;
import gregtech.blocks.stone.BlockRockOres;
import gregtech.blocks.stone.BlockVanillaOresA;
import gregtech.tileentity.misc.MultiTileEntityFluidSpring;
import gregtech.tileentity.placeables.MultiTileEntityRock;

import static bioast.mods.gt6scan.utils.HLPs.prefixBlock;

/**
 * The scan itself is no longer executed in one go (which stalled the server thread and, on big ranges, produced a
 * response larger than the ~2MB packet limit). Instead, like the detrav prospector, the work is spread over server
 * ticks by {@link ScanScheduler} and the response only carries the column grid + per material counts.
 */
public class HandlerServer implements IMessageHandler<ScanRequest, ScanResponse> {
    /** Pending job per player, so a new scan cancels the previous one, exactly like detrav's prospector. */
    private static final Map<EntityPlayerMP, ScanJob> PENDING = new MapMaker().weakKeys()
        .makeMap();

    @Override
    public ScanResponse onMessage(ScanRequest message, MessageContext ctx) {
        if (ctx.side != Side.SERVER) return null;
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (message.mode < 0 || message.mode >= ScanMode.values().length) return null;
        ScanMode mode = ScanMode.values()[message.mode];
        if (mode == ScanMode.NONE) return null;

        // the request must come from a scanner the player is actually holding; the mode and size are stored in the
        // item NBT and the energy is consumed here, so a modified client cannot get free scans
        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || held.getItem() != ScannerMod.tool) return null;
        int tier = held.getItemDamage();
        if (tier < 2 || tier > 5) return null;
        if (!tryConsumeEnergy(player, held, tier)) {
            UT.Entities.sendchat(player, Chat.RED + LH.get("gt6scan.chat.no_energy") + Chat.GRAY);
            return null;
        }
        UT.NBT.makeInt(UT.NBT.getOrCreate(held), "mode", mode.ordinal());

        // the range is a config value of the tier (see ScannerMultiTool.rangeOf); the value the client asked for is
        // only honoured up to that, so a modified client cannot scan a bigger area than the config allows while a
        // normal client still gets exactly the range its GUI displayed
        int chunkSize = Math.max(1, Math.min(message.chunkSize, ScannerMultiTool.rangeOf(tier)));
        World world = player.getEntityWorld();

        // chunk loading has to happen before the job starts, like detrav does it
        Chunk[][] chunks = getChunksAroundLoc(world, message.x, message.z, chunkSize);

        ScanJob old = PENDING.remove(player);
        if (old != null && ScanScheduler.cancel(old)) {
            UT.Entities.sendchat(player, Chat.YELLOW + LH.get("gt6scan.chat.cancelled") + Chat.GRAY);
        }
        UT.Entities.sendchat(player, Chat.YELLOW + LH.get("gt6scan.chat.scanning") + Chat.GRAY);

        ScanJob job = new ScanJob(mode, message.mode, world, chunks, chunkSize, player);
        PENDING.put(player, job);
        ScanScheduler.submit(job);
        return null; // the response is sent once the scan finished
    }

    /**
     * Energy for one scan, consumed server side (moved out of the item behaviour together with the GUI rework:
     * the client only asks for a scan, the server decides if it is paid for). Creative players pay nothing.
     */
    private static boolean tryConsumeEnergy(EntityPlayerMP player, ItemStack held, int tier) {
        if (UT.Entities.isCreative(player)) return true;
        TagData energy = ScannerBehavior.ENERGY_TYPE;
        if (energy == null) return true; // config disabled energy consumption
        return ScannerMod.tool.useEnergy(energy,
            held,
            ScannerMultiTool.consumptionRate * CS.V[tier],
            player,
            player.inventory,
            player.getEntityWorld(),
            (int) player.posX,
            (int) player.posY,
            (int) player.posZ,
            true);
    }

    /** One scan, spread over several server ticks. Collects only what the GUI needs. */
    private static final class ScanJob implements ScanScheduler.Job {
        private final ScanMode mode;
        private final int modeOrdinal;
        private final World world;
        private final Chunk[][] chunks;
        private final int chunkSize;
        private final int mapPx;
        private final int xOrigin, zOrigin;
        private final EntityPlayerMP player;
        /** Same layout as the response grid: index = gridX * mapPx + gridZ. */
        private final short[] topMat;
        /** y of the entry in {@link #topMat}, so the topmost ore per column wins. */
        private final int[] topY;
        private final HashMap<Short, Integer> counts = new HashMap<>();
        /** Per chunk block count, key = (chunkIndex << 16) | (matID & 0xFFFF), used by the chunk hover tooltip. */
        private final HashMap<Integer, Integer> chunkCounts = new HashMap<>();
        /**
         * Cache of {@link #oreDictOreID}, key = (block id << 16) | (meta & 0xFFFF). One ore dictionary lookup per
         * block type instead of one per block - the same stone or ore block is hit thousands of times per scan.
         */
        private final HashMap<Integer, Short> oreDictCache = new HashMap<>();
        private int nextChunk = 0;

        ScanJob(ScanMode mode, int modeOrdinal, World world, Chunk[][] chunks, int chunkSize, EntityPlayerMP player) {
            this.mode = mode;
            this.modeOrdinal = modeOrdinal;
            this.world = world;
            this.chunks = chunks;
            this.chunkSize = chunkSize;
            this.player = player;
            this.mapPx = chunkSize * 16;
            this.xOrigin = chunks[0][0].xPosition << 4;
            this.zOrigin = chunks[0][0].zPosition << 4;
            this.topMat = new short[mapPx * mapPx];
            this.topY = new int[mapPx * mapPx];
        }

        @Override
        public boolean run(long deadline) {
            while (nextChunk < chunkSize * chunkSize) {
                scanChunk(chunks[nextChunk / chunkSize][nextChunk % chunkSize]);
                nextChunk++;
                if (System.nanoTime() >= deadline) return false;
            }
            sendResponse();
            return true;
        }

        /**
         * Records every block that {@link #scanBlocks} / {@link #scanTileEntities} reports, for every layer it
         * appears in. The per-material {@link #counts} and per-chunk {@link #chunkCounts} are full-layer totals -
         * a dense ore vein that fills a column contributes every one of its blocks. The map only needs the
         * topmost ore per column and is filled in here too.
         */
        private void record(int x, int y, int z, short matID) {
            int gridX = x - xOrigin, gridZ = z - zOrigin;
            if (gridX < 0 || gridZ < 0 || gridX >= mapPx || gridZ >= mapPx) return;
            int index = gridX * mapPx + gridZ;
            if (topMat[index] == 0 || y > topY[index]) {
                topMat[index] = matID;
                topY[index] = y;
            }
            Integer count = counts.get(matID);
            counts.put(matID, count == null ? 1 : count + 1);
            int key = (((gridX >> 4) * chunkSize + (gridZ >> 4)) << 16) | (matID & 0xFFFF);
            Integer chunkCount = chunkCounts.get(key);
            chunkCounts.put(key, chunkCount == null ? 1 : chunkCount + 1);
        }

        private void scanChunk(Chunk chunk) {
            if (mode.isTE()) scanTileEntities(chunk);
            else scanBlocks(chunk);
        }

        /**
         * Scans one chunk block by block. Every block-scanning ore mode runs through the whole column without
         * breaking, so the counters are real full-layer totals - a dense ore vein that fills a column adds every one
         * of its blocks, not just the topmost one. The map only needs the topmost material per column, which
         * {@link #record} picks up while still counting every block.
         * <p>
         * Fluid modes ({@link ScanMode#FLUID}) intentionally stop at the first fluid layer of each column - an ocean
         * column would otherwise add ~60 water blocks per column and drown the interesting totals. Tile-entity based
         * ore modes ({@link ScanMode#LARGE}, {@link ScanMode#SMALL}, {@link ScanMode#BEDROCK}, {@link ScanMode#ROCK},
         * {@link ScanMode#FLUID_BEDROCK}) are handled by {@link #scanTileEntities} and are full-layer by construction
         * because every placed ore block is its own tile entity.
         */
        private void scanBlocks(Chunk chunk) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    int highestY = chunk.getHeightValue(k, l);
                    int x = chunk.xPosition * 16 + k;
                    int z = chunk.zPosition * 16 + l;
                    for (int y = highestY; y >= 0; y--) {
                        Block block = chunk.getBlock(k, y, l);
                        int meta = chunk.getBlockMetadata(k, y, l);
                        short matID = oreIDForMode(block, meta);
                        if (matID != 0) {
                            record(x, y, z, matID);
                            continue;
                        }
                        if (mode == ScanMode.FLUID && scanFluidBlock(block, x, y, z)) break;
                    }
                }
            }
        }

        /** Translates one block to the material id counted by the current scan mode, or 0 if the block is not an ore. */
        private short oreIDForMode(Block block, int meta) {
            if (mode == ScanMode.OREDICT) return oreDictOreID(block, meta);
            if (mode != ScanMode.DENSE_AND_NORMAL) return 0;
            if (block instanceof BlockRockOres) return BlockRockOres.ORE_MATERIALS[meta].mID;
            if (block instanceof BlockCrystalOres) return BlockCrystalOres.ORE_MATERIALS[meta].mID;
            if (block instanceof BlockVanillaOresA) return BlockVanillaOresA.ORE_MATERIALS[meta].mID;
            if (block instanceof BlockDiggable) {
                return switch (meta) {
                    case 1 -> MT.ClayBrown.mID;
                    case 2 -> MT.Peat.mID;
                    case 3 -> MT.ClayRed.mID;
                    case 4 -> MT.Bentonite.mID;
                    case 5 -> MT.Palygorskite.mID;
                    case 6 -> MT.Kaolinite.mID;
                    default -> 0;
                };
            }
            if (block instanceof BlockSands) {
                return switch (meta) {
                    case 0 -> MT.OREMATS.Magnetite.mID;
                    case 1 -> MT.OREMATS.BasalticMineralSand.mID;
                    case 2 -> MT.OREMATS.GraniticMineralSand.mID;
                    default -> 0;
                };
            }
            return 0;
        }

        /**
         * Ores of other mods, found the way detrav's prospector does it: through the ore dictionary instead of
         * through the block class. When an ore gets registered, GT6 resolves its "ore..." name into a prefix plus
         * material (see OreDictManager.onOreRegistration2), so asking GT6 for the data of the block's item also
         * finds the ores of other mods, placed by whichever mod.
         * <p>
         * Materials GT6 could not resolve are dropped, they are auto created placeholders without any items or
         * textures and so could not be drawn in the list. Everything counted here is a real GT6 material, which
         * means the client can always show it as the GT6 raw ore of that material.
         * <p>
         * Blocks with a tile entity are skipped: their metadata does not identify the material (that is stored in
         * the tile entity), so they would be resolved to the wrong material here. Those are exactly the ores the
         * tile entity modes already scan.
         */
        private short oreDictOreID(Block block, int meta) {
            int key = (Block.getIdFromBlock(block) << 16) | (meta & 0xFFFF);
            Short cached = oreDictCache.get(key);
            if (cached != null) return cached;
            short matID = 0;
            Item item = block.hasTileEntity(meta) ? null : Item.getItemFromBlock(block);
            if (item != null) {
                OreDictItemData data = OM.anydata(new ItemStack(item, 1, meta));
                if (data != null && isOrePrefix(data.mPrefix) && data.mMaterial != null) {
                    OreDictMaterial material = data.mMaterial.mMaterial;
                    if (material != null && !material.contains(TD.Properties.AUTO_MATERIAL)) matID = material.mID;
                }
            }
            oreDictCache.put(key, matID);
            return matID;
        }

        /** GT6 names every one of its ore prefixes with "ore": ore, oreSmall, oreDense, oreNether, orePoor, ... */
        private static boolean isOrePrefix(OreDictPrefix prefix) {
            return prefix != null && prefix.mNameInternal.startsWith("ore");
        }

        /**
         * Records the fluid block of this column if it is one we recognise, and reports whether the scan should
         * stop at this layer (the FLUID mode always stops at the first fluid block, the FLUID_BEDROCK mode is
         * tile-entity based and handled elsewhere).
         */
        private boolean scanFluidBlock(Block block, int x, int y, int z) {
            if (block == Blocks.lava) {
                record(x, y, z, MT.Lava.mID);
                if (world.provider.isHellWorld) return true;
            }
            if (!(block instanceof IFluidBlock) && block != Blocks.water) return false;
            if (!(block instanceof IFluidBlock fluid)) return true;
            String fluidName = fluid.getFluid()
                .getName();
            short matID = MT.Air.mID;
            if (fluidName.contains("natural")) matID = MT.MethaneIce.mID;
            else if (fluidName.contains("oil")) matID = MT.Oil.mID;
            else if (fluidName.contains("honey")) matID = MT.Honey.mID;
            else if (fluidName.contains("sulfuric")) matID = MT.H2SO4.mID;
            else if (fluidName.contains("acid")) matID = MT.H2SO4.mID;
            else if (fluidName.contains("poison")) matID = MT.DirtyWater.mID;
            else if (fluidName.contains("infused")) matID = MT.InfusedWater.mID;
            else if (fluidName.contains("mana")) matID = MT.Magic.mID;
            if (block == CS.BlocksGT.WaterGeothermal) matID = MT.DistWater.mID;
            if (fluidName.contains("water")) {
                // the special "water" variants (geothermal/oil/...) were handled above; plain water is recorded and
                // the column stops here - everything below is still water
                if (matID == MT.Air.mID) record(x, y, z, MT.Water.mID);
                return true;
            }
            if (matID != MT.Air.mID) record(x, y, z, matID);
            return false;
        }

        private void scanTileEntities(Chunk chunk) {
            var tMap = chunk.chunkTileEntityMap;
            if (tMap == null) return;
            try {
                ((Map<ChunkPosition, TileEntity>) (tMap)).forEach((chunkPos, tile) -> {
                    if (tile instanceof PrefixBlockTileEntity pTile && (mode == ScanMode.LARGE || mode == ScanMode.SMALL
                        || mode == ScanMode.BEDROCK)) {
                        PrefixBlock pBlock = prefixBlock(pTile);
                        boolean isBedrock = false;
                        if (mode == ScanMode.BEDROCK) {
                            isBedrock = pBlock.mNameInternal.contains("bedrock");
                        }
                        if (isBedrock || pBlock.mPrefix.mFamiliarPrefixes.contains(mode.PREFIX)) {
                            record(pTile.getX(), pTile.getY(), pTile.getZ(), pTile.mMetaData);
                        }
                    }
                    if (mode == ScanMode.FLUID_BEDROCK && tile instanceof MultiTileEntityFluidSpring) {
                        FluidStack fluidStack = ((MultiTileEntityFluidSpring) tile).mFluid;
                        if (fluidStack == null || fluidStack.getFluid() == null) return;
                        String name = fluidStack.getFluid()
                            .getName();
                        short matID = 0;
                        if (name.contains("oil")) matID = MT.Oil.mID;
                        if (name.contains("water")) matID = MT.Water.mID;
                        if (name.contains("lava")) matID = MT.Lava.mID;
                        // MethaneIce is what the client displays as "natural gas" (lang key gt6scan.gui.natural_gas),
                        // CH4 would show up as "methane" instead
                        if (name.contains("natural")) matID = MT.MethaneIce.mID;
                        if (matID != 0) {
                            record(tile.xCoord, tile.yCoord, tile.zCoord, matID);
                        }
                    }
                    if (tile instanceof MultiTileEntityRock && mode == ScanMode.ROCK) {
                        short matID = OM.anydata_(((MultiTileEntityRock) tile).mRock).mMaterial.mMaterial.mID;
                        record(tile.xCoord, tile.yCoord, tile.zCoord, matID);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void sendResponse() {
            PENDING.remove(player, this);
            if (player.playerNetServerHandler == null || player.playerNetServerHandler.netManager == null) return; // player left
            // the client only shows/uses the T teleport when the server says it may: the world must allow cheats
            // and the config must not have turned it off (checked here so a modified client cannot bypass it)
            boolean teleportAllowed = ScannerMod.allowTeleport && world.getWorldInfo()
                .areCommandsAllowed();
            CommonProxy.simpleNetworkWrapper.sendTo(
                new ScanResponse(xOrigin, zOrigin, modeOrdinal, chunkSize, teleportAllowed, topMat, counts,
                    chunkCounts),
                player);
        }
    }

    public static Chunk[][] getChunksAroundLoc(World aWorld, int posX, int posZ, int chunkSize) {
        Chunk[][] chunks = new Chunk[chunkSize][chunkSize];
        final int CENTER_CHUNK_INDEX = (chunkSize - 1) / 2;
        chunks[CENTER_CHUNK_INDEX][CENTER_CHUNK_INDEX] = aWorld.getChunkFromBlockCoords(posX, posZ);
        final Chunk PLAYER_CHUNK = chunks[CENTER_CHUNK_INDEX][CENTER_CHUNK_INDEX];
        chunks[0][0] = aWorld.getChunkFromChunkCoords(PLAYER_CHUNK.xPosition - ((chunkSize - 1) / 2),
                PLAYER_CHUNK.zPosition - ((chunkSize - 1) / 2));
        for (int i = 0; i < chunkSize; i++) {
            for (int j = 0; j < chunkSize; j++) {
                if (i == 0 && j == 0) continue;
                if (i == CENTER_CHUNK_INDEX && j == CENTER_CHUNK_INDEX) continue;
                chunks[i][j] = aWorld.getChunkFromChunkCoords(chunks[0][0].xPosition + i, chunks[0][0].zPosition + j);
            }
        }
        return chunks;
    }
}