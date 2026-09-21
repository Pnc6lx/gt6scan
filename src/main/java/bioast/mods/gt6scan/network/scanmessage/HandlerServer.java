package bioast.mods.gt6scan.network.scanmessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

import com.google.common.collect.MapMaker;

import bioast.mods.gt6scan.ScannerMod;
import bioast.mods.gt6scan.item.ScannerBehavior;
import bioast.mods.gt6scan.item.ScannerMultiTool;
import bioast.mods.gt6scan.network.ScanMode;
import bioast.mods.gt6scan.proxy.CommonProxy;
import bioast.mods.gt6scan.utils.ScanScheduler;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
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
 * response larger than the ~2MB packet limit of {@code S3FPacketCustomPayload} - the server then closed the
 * connection and the client reported a lost connection). Instead the work is spread over server ticks by
 * {@link ScanScheduler} and the result is <em>streamed</em>, one chunk at a time:
 * <ul>
 * <li>{@link ScanBeginResponse} sets up the result grid on the client,</li>
 * <li>every scanned chunk immediately follows as its own {@link ScanChunkResponse} (a few hundred bytes),</li>
 * <li>{@link ScanDoneResponse} tells the client that it has everything and may open the map.</li>
 * </ul>
 * Chunks are also loaded lazily, one per scanned chunk, instead of loading the whole range up front. One tick never
 * has to do more than one chunk, so a scan of other mods' ores (ore dictionary mode: every block of every column of
 * the range) can neither stall the server nor flood the connection anymore.
 */
public class HandlerServer implements IMessageHandler<ScanRequest, IMessage> {
    /** Pending job per player, so a new scan cancels the previous one, exactly like detrav's prospector. */
    private static final Map<EntityPlayerMP, ScanJob> PENDING = new MapMaker().weakKeys()
        .makeMap();

    @Override
    public IMessage onMessage(ScanRequest message, MessageContext ctx) {
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
        // the scan is paid for up front, before the job starts and therefore before the client opens the map: the
        // client never gets a map it did not pay for, and a scan cancelled halfway is not refunded
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

        ScanJob old = PENDING.remove(player);
        if (old != null && ScanScheduler.cancel(old)) {
            UT.Entities.sendchat(player, Chat.YELLOW + LH.get("gt6scan.chat.cancelled") + Chat.GRAY);
        }
        UT.Entities.sendchat(player, Chat.YELLOW + LH.get("gt6scan.chat.scanning") + Chat.GRAY);

        ScanJob job = new ScanJob(mode, message.mode, world, message.x, message.z, chunkSize, player);
        PENDING.put(player, job);
        ScanScheduler.submit(job);
        return null; // the response is sent chunk by chunk while the job runs
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

    /**
     * One scan, spread over several server ticks and sent out chunk by chunk. It only ever holds the data of the
     * chunk it is currently scanning (three small reused buffers), the client accumulates the rest.
     */
    private static final class ScanJob implements ScanScheduler.Job {
        private final ScanMode mode;
        private final int modeOrdinal;
        private final World world;
        private final int chunkSize;
        private final EntityPlayerMP player;
        /** Corner chunk of the scan grid; the grid is not exactly centred for even sizes, like detrav's prospector. */
        private final int baseChunkX, baseChunkZ;
        private final int xOrigin, zOrigin;
        /**
         * Whether the map may offer the T teleport for this scan: the world has to allow cheats and the config must
         * not have turned it off (checked here so a modified client cannot bypass it).
         */
        private final boolean teleportAllowed;
        /** Topmost ore per column of the chunk being scanned, index = {@code localX * 16 + localZ}. */
        private final short[] cells = new short[ScanChunkResponse.CELLS];
        /** y of the entry in {@link #cells}, so the topmost ore per column wins. */
        private final int[] cellY = new int[ScanChunkResponse.CELLS];
        /** Block count per material of the chunk being scanned - a dense vein contributes every one of its blocks. */
        private final HashMap<Short, Integer> chunkCount = new HashMap<>();
        /**
         * Materials seen in each column of the chunk being scanned, {@link #columnCount} says how many of the
         * {@link ScanChunkResponse#MAX_COLUMN_MATS} slots belong to a column (index = {@code localX * 16 + localZ}).
         * The client needs this to find a material that another ore sits on top of; only the columns holding more
         * than one material are put on the wire.
         */
        private final short[] columnMats = new short[ScanChunkResponse.CELLS * ScanChunkResponse.MAX_COLUMN_MATS];
        /** y of the entry at the same position in {@link #columnMats}, so the materials can be sent highest first. */
        private final short[] columnY = new short[ScanChunkResponse.CELLS * ScanChunkResponse.MAX_COLUMN_MATS];
        private final byte[] columnCount = new byte[ScanChunkResponse.CELLS];
        /**
         * Cache of {@link #oreDictOreID}, key = (block id << 16) | (meta & 0xFFFF). One ore dictionary lookup per
         * block type instead of one per block - the same stone or ore block is hit thousands of times per scan.
         */
        private final HashMap<Integer, Short> oreDictCache = new HashMap<>();
        private final int totalChunks;
        /** Chunk indices of the grid in scan order, the chunks around the player first - see {@link #scanOrder}. */
        private final int[] order;
        private int nextChunk = 0;
        private boolean began = false;
        /** The map warning is logged once per scan, not once per chunk. */
        private boolean warnedAboutTileEntityMap = false;

        ScanJob(ScanMode mode, int modeOrdinal, World world, int posX, int posZ, int chunkSize,
            EntityPlayerMP player) {
            this.mode = mode;
            this.modeOrdinal = modeOrdinal;
            this.world = world;
            this.chunkSize = chunkSize;
            this.player = player;
            this.totalChunks = chunkSize * chunkSize;
            int half = (chunkSize - 1) / 2;
            this.baseChunkX = (posX >> 4) - half;
            this.baseChunkZ = (posZ >> 4) - half;
            this.xOrigin = baseChunkX << 4;
            this.zOrigin = baseChunkZ << 4;
            this.teleportAllowed = ScannerMod.allowTeleport && world.getWorldInfo()
                .areCommandsAllowed();
            this.order = scanOrder(posX >> 4, posZ >> 4);
        }

        /**
         * The chunks of the grid in the order they are scanned: outward from the chunk the player stands in (Chebyshev
         * distance, so a square around the player grows evenly). The scan takes just as long as before, but the area
         * the player is looking at appears first instead of the top left corner of the range. A chunk keeps its own
         * grid index, only the visit order changes, and the client places every chunk by that index - it does not
         * care in which order they arrive.
         */
        private int[] scanOrder(int playerChunkX, int playerChunkZ) {
            int px = Math.max(0, Math.min(chunkSize - 1, playerChunkX - baseChunkX));
            int pz = Math.max(0, Math.min(chunkSize - 1, playerChunkZ - baseChunkZ));
            int[] result = new int[totalChunks];
            for (int i = 0; i < chunkSize; i++) {
                for (int j = 0; j < chunkSize; j++) {
                    int distance = Math.max(Math.abs(i - px), Math.abs(j - pz));
                    // packed as distance * totalChunks + gridIndex, so sorting puts the nearest chunk first
                    result[i * chunkSize + j] = distance * totalChunks + i * chunkSize + j;
                }
            }
            Arrays.sort(result);
            return result;
        }

        @Override
        public boolean run(long deadline) {
            if (!began) {
                began = true;
                if (!sendBegin()) {
                    finish();
                    return true;
                }
            }
            boolean didWork = false;
            while (nextChunk < totalChunks) {
                // one chunk is the smallest unit of work (loading it may generate terrain), so the budget is checked
                // between chunks only - and at least one chunk per tick is always done, or the scan could stall
                if (didWork && System.nanoTime() >= deadline) return false;
                if (!isPlayerValid()) {
                    finish();
                    return true;
                }
                scanAndSendChunk(order[nextChunk] % totalChunks);
                nextChunk++;
                didWork = true;
            }
            sendDone();
            return true;
        }

        /**
         * Loads and scans exactly one chunk of the range and sends its result. Chunks are loaded here, one per
         * scanned chunk, instead of loading the whole range before the job starts: a big range no longer blocks the
         * server thread while it loads (or even generates) hundreds of chunks inside a single tick.
         */
        private void scanAndSendChunk(int chunkIndex) {
            int i = chunkIndex / chunkSize, j = chunkIndex % chunkSize;
            Chunk chunk = world.getChunkFromChunkCoords(baseChunkX + i, baseChunkZ + j);
            if (chunk == null) return; // nothing to scan, those columns simply stay empty on the client
            Arrays.fill(cells, (short) 0);
            Arrays.fill(cellY, 0);
            Arrays.fill(columnCount, (byte) 0);
            chunkCount.clear();
            if (mode.isTE()) scanTileEntities(chunk);
            else scanBlocks(chunk);
            // the buffers are reused for the next chunk, so the message gets its own copy of them. Only the columns
            // holding more than one material are added, a single material column is already in the topmost grid.
            ScanChunkResponse response = new ScanChunkResponse(chunkIndex, cells.clone(), new HashMap<>(chunkCount));
            for (int k = 0; k < ScanChunkResponse.CELLS; k++) {
                int count = columnCount[k];
                if (count < 2) continue;
                response.columns[k] = columnTopFirst(k, count);
            }
            CommonProxy.simpleNetworkWrapper.sendTo(response, player);
        }

        /**
         * Records every block that {@link #scanBlocks} / {@link #scanTileEntities} reports, for every layer it
         * appears in. The per-material {@link #chunkCount} is a full-layer total - a dense ore vein that fills a
         * column contributes every one of its blocks - while the map only keeps the topmost ore per column.
         *
         * @param localX x inside the chunk, 0..15
         * @param localZ z inside the chunk, 0..15
         */
        private void record(int localX, int y, int localZ, short matID) {
            if (localX < 0 || localZ < 0 || localX >= 16 || localZ >= 16) return;
            // only ids the client can resolve may go onto the wire: a material id in the block modes and a fluid id
            // in the fluid modes. A prefix block tile entity whose metadata was never initialised reports W (-1).
            if (!ScanChunkResponse.isValidId(mode, matID)) return;
            int index = localX * 16 + localZ;
            if (cells[index] == 0 || y > cellY[index]) {
                cells[index] = matID;
                cellY[index] = y;
            }
            chunkCount.merge(matID, 1, Integer::sum);
            addColumnMaterial(index, matID, y);
        }

        /**
         * Remembers that a material occurs in a column and how high it was found there: the client colours a cell the
         * player filtered for with the highest <em>selected</em> material of that column, so the depth order has to
         * travel with the data (see {@link #columnTopFirst}). A material found several times keeps its highest y, and
         * a column holding more different ores than {@link ScanChunkResponse#MAX_COLUMN_MATS} drops its deepest one
         * in favour of a higher one.
         */
        private void addColumnMaterial(int index, short matID, int y) {
            int base = index * ScanChunkResponse.MAX_COLUMN_MATS;
            int count = columnCount[index];
            int deepest = 0;
            for (int i = 0; i < count; i++) {
                if (columnMats[base + i] == matID) {
                    if (y > columnY[base + i]) columnY[base + i] = (short) y;
                    return;
                }
                if (columnY[base + i] < columnY[base + deepest]) deepest = i;
            }
            if (count < ScanChunkResponse.MAX_COLUMN_MATS) {
                columnMats[base + count] = matID;
                columnY[base + count] = (short) y;
                columnCount[index] = (byte) (count + 1);
                return;
            }
            if (y > columnY[base + deepest]) {
                columnMats[base + deepest] = matID;
                columnY[base + deepest] = (short) y;
            }
        }

        /** Materials of one column, the highest one first - the order tells the client which one to draw. */
        private short[] columnTopFirst(int index, int count) {
            int base = index * ScanChunkResponse.MAX_COLUMN_MATS;
            short[] mats = new short[count];
            short[] ys = new short[count];
            for (int i = 0; i < count; i++) {
                mats[i] = columnMats[base + i];
                ys[i] = columnY[base + i];
            }
            // insertion sort by y, at most MAX_COLUMN_MATS entries, so this is a handful of moves
            for (int i = 1; i < count; i++) {
                short mat = mats[i], y = ys[i];
                int j = i - 1;
                while (j >= 0 && ys[j] < y) {
                    mats[j + 1] = mats[j];
                    ys[j + 1] = ys[j];
                    j--;
                }
                mats[j + 1] = mat;
                ys[j + 1] = y;
            }
            return mats;
        }

        /**
         * Scans one chunk block by block, every mode through the whole column down to y = 0: the counters are real
         * full-layer totals, a vein that fills a column adds every one of its blocks and not just the topmost one.
         * The map itself only needs the topmost entry per column, which {@link #record} picks up while counting the
         * rest.
         * <p>
         * The fluid mode runs the column down as well. It used to stop at the first water layer of a column, which
         * made everything under an ocean invisible - an oil seep under the sea floor was never scanned at all. Now it
         * is counted, and because every column keeps the list of what it holds (see {@link #columnMats}) the filter
         * finds it even though the water above is what the unfiltered map draws. Tile-entity based ore modes
         * ({@link ScanMode#LARGE}, {@link ScanMode#SMALL}, {@link ScanMode#BEDROCK}, {@link ScanMode#ROCK},
         * {@link ScanMode#FLUID_BEDROCK}) are handled by {@link #scanTileEntities} and are full-layer by construction
         * because every placed block is its own tile entity.
         */
        private void scanBlocks(Chunk chunk) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    int highestY = chunk.getHeightValue(k, l);
                    for (int y = highestY; y >= 0; y--) {
                        Block block = chunk.getBlock(k, y, l);
                        int meta = chunk.getBlockMetadata(k, y, l);
                        short matID = oreIDForMode(block, meta);
                        if (matID != 0) {
                            record(k, y, l, matID);
                            continue;
                        }
                        if (mode == ScanMode.FLUID) scanFluidBlock(block, k, y, l);
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
         * Records the fluid of this block if the FLUID mode reports it. The scan keeps going down the column after
         * this (see {@link #scanBlocks}), so a fluid underneath another one - oil below the water of an ocean - is
         * found as well instead of being hidden by the water above it.
         * <p>
         * The id that goes onto the wire is the <em>fluid</em> id, not a material id: GT6's heavy, light and medium
         * oil (and the extra heavy one) are separate fluids without a material of their own, so a material id could
         * not tell them apart - the fluid carries the name the player wants to see ("Heavy Oil", "Raw Oil", ...).
         */
        private void scanFluidBlock(Block block, int localX, int y, int localZ) {
            if (block == Blocks.lava) {
                record(localX, y, localZ, fluidID(FluidRegistry.LAVA));
                return;
            }
            if (!(block instanceof IFluidBlock) && block != Blocks.water) return;
            if (!(block instanceof IFluidBlock fluidBlock)) {
                record(localX, y, localZ, fluidID(FluidRegistry.WATER)); // plain water, the only fluid without a block
                return;
            }
            Fluid fluid = fluidBlock.getFluid();
            String fluidName = fluid.getName();
            // water always counts (every water variant reports its own fluid), everything else only when it is one of
            // the fluids this mode is about - without that every modded fluid would end up in the list
            if (block == CS.BlocksGT.WaterGeothermal || fluidName.contains("water") || isInterestingFluid(fluidName)) {
                record(localX, y, localZ, fluidID(fluid));
            }
        }

        /** The fluid kinds the FLUID mode reports; without this the map would fill up with every modded fluid. */
        private static boolean isInterestingFluid(String fluidName) {
            return fluidName.contains("oil") || fluidName.contains("natural") || fluidName.contains("honey")
                || fluidName.contains("sulfuric") || fluidName.contains("acid") || fluidName.contains("poison")
                || fluidName.contains("infused") || fluidName.contains("mana");
        }

        /**
         * Fluid id of a fluid, the id the client resolves back to it (name, colour, icon). GT6's oils are distinct
         * fluids without a material of their own, so the fluid is what identifies a fluid scan result.
         */
        private static short fluidID(Fluid fluid) {
            return fluid == null ? 0 : (short) fluid.getID();
        }

        /**
         * Scans the tile entities of one chunk. Every placed ore block of the GT6 prefix block system is its own
         * tile entity, so counting them is a full-layer total by construction. The tile coordinates are turned into
         * chunk local ones, because the result of a chunk is sent as a 16x16 grid of its own.
         * <p>
         * The map is read through {@link Map#values()} and deliberately <em>not</em> through {@link Map#forEach}:
         * a mod that replaces the chunk tile entity map may return a subclass that delegates every {@code Map}
         * method to another map and so inherits {@code HashMap.forEach}, which iterates the internal table of the
         * never used {@code HashMap} superclass - the loop body then never runs and every tile entity mode silently
         * reports an empty map. {@code values()} is part of the {@code Map} contract, so it stays correct with and
         * without such a mod (Angelica installs a {@code ConcurrentTileEntityMap} exactly like that).
         * <p>
         * Every tile entity gets its own try/catch as well: one broken tile entity must not throw away the rest of
         * the chunk, and an {@code Error} (an incompatible GregTech build, for example) is caught too instead of
         * killing the whole scan job without a single line in the log.
         */
        private void scanTileEntities(Chunk chunk) {
            var tMap = chunk.chunkTileEntityMap;
            if (tMap == null) return;
            final Map<ChunkPosition, TileEntity> map = (Map<ChunkPosition, TileEntity>) tMap;
            final int chunkX = chunk.xPosition * 16, chunkZ = chunk.zPosition * 16;
            List<TileEntity> tiles;
            try {
                // a snapshot, so a map that defers its removals to the running iteration cannot break the loop
                tiles = new ArrayList<>(map.values());
            } catch (Throwable t) {
                ScannerMod.debug.error("Failed to read the tile entities of the chunk " + chunk.xPosition + " "
                    + chunk.zPosition, t);
                return;
            }
            if (tiles.isEmpty() && map.size() > 0 && !warnedAboutTileEntityMap) {
                warnedAboutTileEntityMap = true;
                // without this line the symptom of a broken map implementation is a map that stays empty for no
                // visible reason, so tell the player's log what is going on instead of reporting "nothing found"
                ScannerMod.debug.warn("The chunk tile entity map reports " + map.size()
                    + " entries but iterating it returned none (a mod replaced the map implementation, Angelica does)"
                    + " - the tile entity scan modes cannot find anything like this");
            }
            for (TileEntity tile : tiles) {
                if (tile == null) continue;
                try {
                    scanTileEntity(tile, chunkX, chunkZ);
                } catch (Throwable t) {
                    ScannerMod.debug.error("Skipping the tile entity " + tile.getClass()
                        .getName() + " at " + tile.xCoord + " " + tile.yCoord + " " + tile.zCoord, t);
                }
            }
        }

        /**
         * Records what the current mode reports for one tile entity, if anything.
         * <p>
         * Each mode has its own method: a mode then only ever loads and verifies the GregTech classes it really
         * needs, so a GregTech build whose rock or fluid spring class moved or vanished can only break the one mode
         * that uses it instead of every tile entity mode at once.
         */
        private void scanTileEntity(TileEntity tile, int chunkX, int chunkZ) {
            switch (mode) {
                case FLUID_BEDROCK -> scanFluidSpring(tile, chunkX, chunkZ);
                case ROCK -> scanRock(tile, chunkX, chunkZ);
                case LARGE, SMALL, BEDROCK -> scanPrefixBlock(tile, chunkX, chunkZ);
                default -> { }
            }
        }

        /** Ores of the prefix block system: large, small and bedrock ores are all one tile entity per block. */
        private void scanPrefixBlock(TileEntity tile, int chunkX, int chunkZ) {
            if (!(tile instanceof PrefixBlockTileEntity pTile)) return;
            PrefixBlock pBlock = prefixBlock(pTile);
            // a prefix block whose tile entity is somehow not backed by a prefix block any more
            if (pBlock == null) return;
            boolean isBedrock = mode == ScanMode.BEDROCK && pBlock.mNameInternal.contains("bedrock");
            if (isBedrock || pBlock.mPrefix.mFamiliarPrefixes.contains(mode.PREFIX)) {
                record(tile.xCoord - chunkX, tile.yCoord, tile.zCoord - chunkZ, pTile.mMetaData);
            }
        }

        /** Bedrock fluid springs - see {@link #scanFluidBlock} for why the fluid and not the material is recorded. */
        private void scanFluidSpring(TileEntity tile, int chunkX, int chunkZ) {
            if (!(tile instanceof MultiTileEntityFluidSpring spring)) return;
            // the spring keeps its fluid in the NBT (gt.spring -> fluidname), GT6 reads it back into mFluid. The
            // fluid's own id is recorded, so heavy, light and medium oil stay apart in the GUI instead of all
            // being shown as "Oil".
            FluidStack fluidStack = spring.mFluid;
            if (fluidStack == null || fluidStack.getFluid() == null) return;
            record(tile.xCoord - chunkX, tile.yCoord, tile.zCoord - chunkZ, fluidID(fluidStack.getFluid()));
        }

        /** Rocks, both the ones carrying an item and the plain ones - see {@link #rockMaterial}. */
        private void scanRock(TileEntity tile, int chunkX, int chunkZ) {
            if (!(tile instanceof MultiTileEntityRock rock)) return;
            record(tile.xCoord - chunkX, tile.yCoord, tile.zCoord - chunkZ, rockMaterial(rock));
        }

        /**
         * Material of a rock, which is what the rock shows and drops.
         * <p>
         * A rock without an item is a perfectly normal rock: GT6 shows and drops it as the stone of the dimension it
         * lies in, so a plain rock is counted as that stone (see {@code MultiTileEntityRock.getDefaultRock}, which
         * gives {@code MT.Stone} in the overworld, Netherrack in the nether, Endstone in the end and so on) instead
         * of being dropped from the result. Reading the item of a rock that has none is what used to abort the scan
         * of the whole chunk with a NullPointerException.
         */
        private static short rockMaterial(MultiTileEntityRock aRock) {
            // getRock already answers with the default of the dimension when the rock carries no item
            short matID = oreMaterial(aRock.getRock(1));
            if (matID == 0) matID = oreMaterial(aRock.getDefaultRock(1));
            return matID == 0 ? MT.Stone.mID : matID;
        }

        /** Material id of an item GT6 knows, or 0 when there is no usable material on it. */
        private static short oreMaterial(ItemStack aStack) {
            if (aStack == null) return 0;
            OreDictItemData data = OM.anydata_(aStack);
            if (data == null || data.mMaterial == null || data.mMaterial.mMaterial == null) return 0;
            return data.mMaterial.mMaterial.mID;
        }

        /** Tells the client the grid it has to set up; without it the per chunk messages could not be placed. */
        private boolean sendBegin() {
            if (!isPlayerValid()) return false;
            CommonProxy.simpleNetworkWrapper.sendTo(
                new ScanBeginResponse(xOrigin, zOrigin, modeOrdinal, chunkSize, teleportAllowed),
                player);
            return true;
        }

        /** Last message of the scan: everything is on the client, it may open the map now. */
        private void sendDone() {
            finish();
            if (!isPlayerValid()) return;
            CommonProxy.simpleNetworkWrapper.sendTo(new ScanDoneResponse(), player);
        }

        private void finish() {
            PENDING.remove(player, this);
        }

        /** The scan is dropped as soon as the player is gone or left the world it was started in. */
        private boolean isPlayerValid() {
            return player.playerNetServerHandler != null && player.playerNetServerHandler.netManager != null
                && !player.isDead
                && player.worldObj == world;
        }
    }
}
