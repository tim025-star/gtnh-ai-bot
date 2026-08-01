package dev.nottambok.gtnhaibot.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ClientBotController {

    private static final int PATH_RECALC_TICKS = 12;
    private static final int MAX_PATH_NODES = 4096;
    private static final String GOAL_FILE = "config/gtnh-ai-bot-goals.properties";
    private static final String CHEST_CACHE_FILE = "config/gtnh-ai-bot-chest-cache.tsv";
    private static final int CHEST_SCAN_RADIUS_CHUNKS = 3;
    private static final int CHEST_DISCOVERY_INTERVAL_TICKS = 200;
    private static final int CHEST_OPEN_REFRESH_INTERVAL_TICKS = 8;
    private static final int CHEST_CACHE_SAVE_INTERVAL_TICKS = 200;
    private static final boolean LOAD_CHEST_CACHE_FROM_DISK = false;
    private static final int BLOCK_SCAN_RADIUS_CHUNKS = 3;
    private static final int BLOCK_SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_BLOCK_QUERY_RESULTS = 40;
    private static final String COLD_CHUNK_CACHE_FILE = "config/gtnh-ai-bot-chunk-cold-cache.tsv";
    private static final int COLD_CHUNK_CACHE_SAVE_INTERVAL_TICKS = 200;
    private static final int MAX_COLD_CONTEXT_LINES = 40;

    private final Queue<ClientTask> tasks = new ArrayDeque<ClientTask>();
    private Queue<BlockPos> currentPath = new ArrayDeque<BlockPos>();
    private final Map<ChestKey, ChestSnapshot> chestCache = new HashMap<ChestKey, ChestSnapshot>();
    private final Map<String, Integer> cachedItemTotals = new HashMap<String, Integer>();
    private final Map<ChunkKey, ChunkBlockCache> nearbyChunkBlockCache = new HashMap<ChunkKey, ChunkBlockCache>();
    private final Map<ChunkKey, ChunkBlockCache> coldChunkBlockCache = new HashMap<ChunkKey, ChunkBlockCache>();
    private final Map<String, List<BlockPos>> nearbyBlockIndex = new HashMap<String, List<BlockPos>>();
    private final Map<String, List<BlockPos>> coldBlockIndex = new HashMap<String, List<BlockPos>>();
    private final Set<ChunkKey> pendingChunkRefresh = new HashSet<ChunkKey>();
    private final Queue<FutureTask<String>> apiCalls = new ConcurrentLinkedQueue<FutureTask<String>>();
    private BlockPos lastGoal;
    private int pathRecalcCounter;
    private int chestScanCooldown;
    private int chestOpenRefreshCooldown;
    private int chestCacheSaveCooldown;
    private int blockScanCooldown;
    private int coldChunkCacheSaveCooldown;
    private int lastBlockCacheDim = Integer.MIN_VALUE;
    private boolean chestCacheLoaded;
    private boolean chestCacheDirty;
    private boolean coldChunkCacheLoaded;
    private boolean coldChunkCacheDirty;
    private final Properties goals = new Properties();
    private boolean goalsLoaded;
    private String controlSource = "idle";
    private long controlRevision;

    public synchronized String enqueue(String commandLine) {
        ParsedCommand parsed = parse(commandLine);
        if (parsed.error != null) {
            return parsed.error;
        }
        if (parsed.task != null) {
            tasks.add(parsed.task);
        }
        return "QUEUED";
    }

    public synchronized String enqueueFromWeb(String commandLine) {
        controlSource = "web";
        controlRevision++;
        return enqueue(commandLine);
    }

    public synchronized String enqueueFromGame(String commandLine) {
        stopAll();
        controlSource = "game";
        controlRevision++;
        return enqueue(commandLine);
    }

    public synchronized String prompt(String prompt) {
        loadGoalsIfNeeded();
        String key = prompt.toLowerCase()
            .trim();
        String script = goals.getProperty(key);
        if (script == null || script.trim()
            .length() == 0) {
            return "No local macro available";
        }
        int queued = 0;
        String[] steps = script.split(";");
        for (int i = 0; i < steps.length; i++) {
            ParsedCommand parsed = parse(steps[i].trim());
            if (parsed.task != null && parsed.error == null) {
                tasks.add(parsed.task);
                queued++;
            }
        }
        return "QUEUED " + queued + " local steps";
    }

    public synchronized void stopAll() {
        tasks.clear();
        currentPath.clear();
        lastGoal = null;
        pathRecalcCounter = 0;
        controlSource = "idle";
        controlRevision++;
    }

    public String callOnClientThread(Callable<String> call) throws Exception {
        FutureTask<String> task = new FutureTask<String>(call);
        apiCalls.add(task);
        return task.get(5L, TimeUnit.SECONDS);
    }

    public synchronized String snapshotJson() {
        JsonObject root = new JsonObject();
        Minecraft mc = Minecraft.getMinecraft();
        boolean connected = mc != null && mc.thePlayer != null && mc.theWorld != null;
        root.addProperty("ok", connected);
        root.addProperty("controlSource", controlSource);
        root.addProperty("controlRevision", controlRevision);
        root.addProperty("queuedTasks", tasks.size());
        root.addProperty("tasks", listTasks());
        if (!connected) return root.toString();
        root.addProperty("player", mc.thePlayer.getCommandSenderName());
        root.addProperty("x", MathHelper.floor_double(mc.thePlayer.posX));
        root.addProperty("y", MathHelper.floor_double(mc.thePlayer.posY));
        root.addProperty("z", MathHelper.floor_double(mc.thePlayer.posZ));
        root.addProperty("dimension", mc.theWorld.provider.dimensionId);
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < mc.thePlayer.inventory.mainInventory.length; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("slot", i);
            item.addProperty("item", stackKey(stack));
            item.addProperty("damage", stack.getItemDamage());
            item.addProperty("count", stack.stackSize);
            inventory.add(item);
        }
        root.add("inventory", inventory);
        return root.toString();
    }

    public synchronized String listTasks() {
        List<String> out = new ArrayList<String>();
        for (ClientTask task : tasks) {
            out.add(task.describe());
        }
        return out.toString();
    }

    public synchronized String cacheStatus(String query) {
        loadChestCacheIfNeeded();
        if (query == null || query.trim()
            .length() == 0) {
            return "CHESTS " + chestCache.size() + " ITEMS " + cachedItemTotals.size();
        }
        String q = norm(query);
        List<String> hits = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : cachedItemTotals.entrySet()) {
            if (norm(e.getKey()).contains(q)) {
                hits.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (hits.isEmpty()) {
            return "CACHE MISS";
        }
        return hits.toString();
    }

    public synchronized String blockStatus(Minecraft mc, String query) {
        loadColdChunkCacheIfNeeded();
        if (query == null || query.trim()
            .length() == 0) {
            return "LIVE_CHUNKS " + nearbyChunkBlockCache.size()
                + " COLD_CHUNKS "
                + coldChunkBlockCache.size()
                + " LIVE_TYPES "
                + nearbyBlockIndex.size()
                + " COLD_TYPES "
                + coldBlockIndex.size();
        }
        String q = norm(query);
        List<String> out = new ArrayList<String>();
        BlockPos playerPos = null;
        if (mc != null && mc.thePlayer != null) {
            playerPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ));
        }
        Map<String, List<BlockPos>> merged = mergedBlockIndex();
        for (Map.Entry<String, List<BlockPos>> e : merged.entrySet()) {
            if (!norm(e.getKey()).contains(q)) continue;
            List<BlockPos> positions = new ArrayList<BlockPos>(e.getValue());
            if (playerPos != null) {
                final BlockPos p = playerPos;
                positions.sort(new Comparator<BlockPos>() {

                    @Override
                    public int compare(BlockPos a, BlockPos b) {
                        double da = distSq(p, a);
                        double db = distSq(p, b);
                        return da < db ? -1 : da > db ? 1 : 0;
                    }
                });
            }
            int limit = Math.min(3, positions.size());
            StringBuilder sb = new StringBuilder();
            sb.append(e.getKey())
                .append(" count=")
                .append(positions.size());
            for (int i = 0; i < limit; i++) {
                BlockPos pos = positions.get(i);
                sb.append(" @")
                    .append(pos.x)
                    .append(",")
                    .append(pos.y)
                    .append(",")
                    .append(pos.z);
            }
            out.add(sb.toString());
        }
        if (out.isEmpty()) return "BLOCK MISS";
        if (out.size() > MAX_BLOCK_QUERY_RESULTS) {
            out = out.subList(0, MAX_BLOCK_QUERY_RESULTS);
        }
        return out.toString();
    }

    public synchronized String refreshChunkData(Minecraft mc, String arg) {
        loadColdChunkCacheIfNeeded();
        if (mc == null || mc.theWorld == null) {
            return "NO_WORLD";
        }
        String trimmed = arg == null ? "" : arg.trim();
        if (trimmed.length() == 0 || "nearby".equalsIgnoreCase(trimmed)) {
            refreshNearbyBlockCache(mc);
            scanNearbyChests(mc);
            return "REFRESHED nearby live_chunks=" + nearbyChunkBlockCache.size()
                + " cold_chunks="
                + coldChunkBlockCache.size();
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 2) {
            try {
                int chunkX = Integer.parseInt(parts[0]);
                int chunkZ = Integer.parseInt(parts[1]);
                ChunkKey key = new ChunkKey(mc.theWorld.provider.dimensionId, chunkX, chunkZ);
                forceRefreshChunk(mc, key, "manual");
                return "REFRESH_QUEUED chunk=" + chunkX + "," + chunkZ;
            } catch (Exception ignored) {}
        }
        return "Usage: refresh | refresh nearby | refresh <chunkX> <chunkZ>";
    }

    public synchronized String whereStatus() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return "NO_WORLD";
        int x = MathHelper.floor_double(mc.thePlayer.posX);
        int y = MathHelper.floor_double(mc.thePlayer.posY);
        int z = MathHelper.floor_double(mc.thePlayer.posZ);
        return "POS " + x + " " + y + " " + z + " DIM " + mc.theWorld.provider.dimensionId;
    }

    public synchronized String holdItem(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() == 0) return "Usage: hold <item query>";
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return "NO_PLAYER";
        EntityClientPlayerMP player = mc.thePlayer;
        int found = -1;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack s = player.inventory.mainInventory[i];
            if (s != null && matchesQuery(s, trimmed)) {
                found = i;
                break;
            }
        }
        if (found < 0) return "HOLD MISS " + trimmed;
        if (found < 9) {
            player.inventory.currentItem = found;
            player.inventoryContainer.detectAndSendChanges();
            return "HELD " + stackKey(player.inventory.mainInventory[found]) + " slot=" + found;
        }
        int hotbarSlot = player.inventory.currentItem;
        ItemStack inHand = player.inventory.mainInventory[hotbarSlot];
        ItemStack selected = player.inventory.mainInventory[found];
        player.inventory.mainInventory[hotbarSlot] = selected;
        player.inventory.mainInventory[found] = inHand;
        player.inventoryContainer.detectAndSendChanges();
        ItemStack now = player.inventory.mainInventory[hotbarSlot];
        return now == null ? "HOLD FAIL " + trimmed : "HELD " + stackKey(now) + " slot=" + hotbarSlot;
    }

    public synchronized String status(String targetArg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return "STATUS NO_WORLD";
        DiagnosticTarget target = resolveDiagnosticTarget(mc, targetArg);
        if (target == null) {
            return "STATUS TARGET_MISS arg=\"" + (targetArg == null ? "" : targetArg.trim()) + "\"";
        }
        return buildStatusForTarget(mc, target);
    }

    public synchronized String diagnose(String targetArg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return "DIAG code=NO_WORLD";
        DiagnosticTarget target = resolveDiagnosticTarget(mc, targetArg);
        if (target == null) {
            return "DIAG code=TARGET_MISS recommend=\"refresh nearby; blocks <machine>\"";
        }
        World world = mc.theWorld;
        Block block = world.getBlock(target.x, target.y, target.z);
        if (block == null || block.isAir(world, target.x, target.y, target.z)) {
            return "DIAG code=TARGET_AIR pos=" + target.x
                + ","
                + target.y
                + ","
                + target.z
                + " recommend=\"refresh nearby; blocks "
                + safeQueryToken(target.queryHint)
                + "\"";
        }
        TileEntity tile = world.getTileEntity(target.x, target.y, target.z);
        if (tile == null) {
            return "DIAG code=NOT_A_MACHINE pos=" + target.x
                + ","
                + target.y
                + ","
                + target.z
                + " block="
                + blockKey(block, world.getBlockMetadata(target.x, target.y, target.z))
                + " recommend=\"use "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + " 1; status "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + "\"";
        }

        InventoryStats inv = readInventoryStats(tile);
        EnergyProbe energy = probeEnergy(tile);
        String key = blockKey(block, world.getBlockMetadata(target.x, target.y, target.z));

        if (inv != null && inv.slots > 0 && inv.used >= inv.slots) {
            return "DIAG code=OUTPUT_OR_BUFFER_FULL pos=" + target.x
                + ","
                + target.y
                + ","
                + target.z
                + " block="
                + key
                + " inv_used="
                + inv.used
                + "/"
                + inv.slots
                + " recommend=\"use "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + " 1; use "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + " 2; status "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + "\"";
        }

        if (energy.hasNumeric && !energy.hasPositiveNumeric) {
            return "DIAG code=NO_POWER pos=" + target.x
                + ","
                + target.y
                + ","
                + target.z
                + " block="
                + key
                + " energy=\""
                + energy.summary()
                + "\" recommend=\"blocks cable; blocks generator; cache coal; prompt make fuel for "
                + safeQueryToken(target.queryHint)
                + "\"";
        }

        if (energy.activeKnown && !energy.active && (inv == null || inv.used > 0)) {
            return "DIAG code=WRONG_SIDE_OR_RECIPE pos=" + target.x
                + ","
                + target.y
                + ","
                + target.z
                + " block="
                + key
                + " energy=\""
                + energy.summary()
                + "\" recommend=\"use "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + " 1; use "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + " 2; use "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + " 3; status "
                + target.x
                + " "
                + target.y
                + " "
                + target.z
                + "\"";
        }

        if (inv != null && inv.used == 0) {
            return "DIAG code=NO_INPUT pos=" + target.x
                + ","
                + target.y
                + ","
                + target.z
                + " block="
                + key
                + " recommend=\"cache "
                + safeQueryToken(target.queryHint)
                + "; prompt make "
                + safeQueryToken(target.queryHint)
                + "\"";
        }

        return "DIAG code=STALLED_UNKNOWN pos=" + target.x
            + ","
            + target.y
            + ","
            + target.z
            + " block="
            + key
            + " energy=\""
            + energy.summary()
            + "\" recommend=\"status "
            + target.x
            + " "
            + target.y
            + " "
            + target.z
            + "; refresh nearby; blocks "
            + safeQueryToken(target.queryHint)
            + "\"";
    }

    public synchronized String haveStatus(String query, int neededCount) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() == 0) return "Usage: have <item query> [count]";
        int needed = Math.max(1, neededCount);
        loadChestCacheIfNeeded();
        int chest = 0;
        String q = norm(trimmed);
        for (Map.Entry<String, Integer> e : cachedItemTotals.entrySet()) {
            if (norm(e.getKey()).contains(q)) {
                chest += e.getValue();
            }
        }
        Minecraft mc = Minecraft.getMinecraft();
        int inv = 0;
        if (mc != null && mc.thePlayer != null) {
            inv = countByQuery(mc.thePlayer, trimmed);
        }
        int total = inv + chest;
        if (total >= needed) {
            return "HAVE query=\"" + trimmed
                + "\" total="
                + total
                + " need="
                + needed
                + " inv="
                + inv
                + " chest="
                + chest;
        }
        return "MISSING query=\"" + trimmed
            + "\" total="
            + total
            + " need="
            + needed
            + " inv="
            + inv
            + " chest="
            + chest;
    }

    private DiagnosticTarget resolveDiagnosticTarget(Minecraft mc, String rawArg) {
        String arg = rawArg == null ? "" : rawArg.trim();
        if (arg.length() == 0) {
            if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                int x = mc.objectMouseOver.blockX;
                int y = mc.objectMouseOver.blockY;
                int z = mc.objectMouseOver.blockZ;
                return new DiagnosticTarget(x, y, z, blockQueryHint(mc.theWorld, x, y, z));
            }
            return null;
        }
        String[] parts = arg.split("\\s+");
        if (parts.length == 3) {
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                return new DiagnosticTarget(x, y, z, blockQueryHint(mc.theWorld, x, y, z));
            } catch (Exception ignored) {}
        }
        Map<String, List<BlockPos>> merged = mergedBlockIndex();
        BlockPos nearest = null;
        String matchedKey = null;
        String query = norm(arg);
        BlockPos playerPos = new BlockPos(
            MathHelper.floor_double(mc.thePlayer.posX),
            MathHelper.floor_double(mc.thePlayer.posY),
            MathHelper.floor_double(mc.thePlayer.posZ));
        for (Map.Entry<String, List<BlockPos>> e : merged.entrySet()) {
            if (!norm(e.getKey()).contains(query)) continue;
            List<BlockPos> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                BlockPos p = list.get(i);
                if (nearest == null || distSq(playerPos, p) < distSq(playerPos, nearest)) {
                    nearest = p;
                    matchedKey = e.getKey();
                }
            }
        }
        if (nearest == null) {
            return null;
        }
        return new DiagnosticTarget(nearest.x, nearest.y, nearest.z, matchedKey == null ? arg : matchedKey);
    }

    private String buildStatusForTarget(Minecraft mc, DiagnosticTarget target) {
        World world = mc.theWorld;
        Block block = world.getBlock(target.x, target.y, target.z);
        if (block == null || block.isAir(world, target.x, target.y, target.z)) {
            return "STATUS TARGET_AIR pos=" + target.x + "," + target.y + "," + target.z;
        }
        int meta = world.getBlockMetadata(target.x, target.y, target.z);
        String key = blockKey(block, meta);
        TileEntity tile = world.getTileEntity(target.x, target.y, target.z);
        StringBuilder sb = new StringBuilder();
        sb.append("STATUS pos=")
            .append(target.x)
            .append(",")
            .append(target.y)
            .append(",")
            .append(target.z)
            .append(" block=")
            .append(key);
        if (tile == null) {
            sb.append(" tile=none");
            return sb.toString();
        }
        sb.append(" tile=")
            .append(
                tile.getClass()
                    .getName());
        InventoryStats inv = readInventoryStats(tile);
        if (inv != null) {
            sb.append(" inv_used=")
                .append(inv.used)
                .append("/")
                .append(inv.slots)
                .append(" inv_stacks=")
                .append(inv.totalStacks);
            if (inv.slots > 0 && inv.used >= inv.slots) {
                sb.append(" inv_full=true");
            }
            if (tile instanceof ISidedInventory) {
                int[] top = ((ISidedInventory) tile).getAccessibleSlotsFromSide(1);
                int[] bottom = ((ISidedInventory) tile).getAccessibleSlotsFromSide(0);
                sb.append(" sided_top=")
                    .append(top == null ? 0 : top.length)
                    .append(" sided_bottom=")
                    .append(bottom == null ? 0 : bottom.length);
            }
        } else {
            sb.append(" inv=none");
        }
        EnergyProbe energy = probeEnergy(tile);
        sb.append(" energy=\"")
            .append(energy.summary())
            .append("\"");
        return sb.toString();
    }

    private InventoryStats readInventoryStats(TileEntity tile) {
        if (!(tile instanceof IInventory)) return null;
        IInventory inv = (IInventory) tile;
        int size = inv.getSizeInventory();
        int used = 0;
        int stacks = 0;
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.stackSize > 0) {
                used++;
                stacks += s.stackSize;
            }
        }
        return new InventoryStats(size, used, stacks);
    }

    private EnergyProbe probeEnergy(TileEntity tile) {
        Map<String, String> signals = new HashMap<String, String>();
        boolean hasNumeric = false;
        boolean hasPositiveNumeric = false;
        boolean activeKnown = false;
        boolean active = false;

        String[] methodNames = new String[] { "isActive", "isRunning", "isPowered", "isEnabled", "getActive",
            "getStoredEU", "getEUVar", "getEnergyStored", "getUniversalEnergyStored", "getPower", "getStoredEnergy",
            "getEnergy", "getVoltage", "getInputVoltage", "getOutputVoltage" };
        for (int i = 0; i < methodNames.length; i++) {
            String name = methodNames[i];
            try {
                Method m = tile.getClass()
                    .getMethod(name);
                if (m.getParameterTypes().length != 0) continue;
                Object v = m.invoke(tile);
                if (v == null) continue;
                String lower = name.toLowerCase();
                if (v instanceof Number) {
                    double n = ((Number) v).doubleValue();
                    signals.put(name, String.valueOf(n));
                    hasNumeric = true;
                    if (n > 0.0001D) hasPositiveNumeric = true;
                } else if (v instanceof Boolean) {
                    boolean b = ((Boolean) v).booleanValue();
                    signals.put(name, String.valueOf(b));
                    if (lower.contains("active") || lower.contains("running")
                        || lower.contains("powered")
                        || lower.contains("enabled")) {
                        activeKnown = true;
                        active = b;
                    }
                }
            } catch (Exception ignored) {}
        }

        Field[] fields = tile.getClass()
            .getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            if (signals.size() >= 10) break;
            Field f = fields[i];
            String n = f.getName()
                .toLowerCase();
            if (!(n.contains("energy") || n.contains("eu")
                || n.contains("power")
                || n.contains("voltage")
                || n.contains("active")
                || n.contains("running")
                || n.contains("burn"))) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(tile);
                if (v == null) continue;
                if (v instanceof Number) {
                    double num = ((Number) v).doubleValue();
                    signals.put(f.getName(), String.valueOf(num));
                    hasNumeric = true;
                    if (num > 0.0001D) hasPositiveNumeric = true;
                } else if (v instanceof Boolean) {
                    boolean b = ((Boolean) v).booleanValue();
                    signals.put(f.getName(), String.valueOf(b));
                    if (n.contains("active") || n.contains("running") || n.contains("powered")) {
                        activeKnown = true;
                        active = b;
                    }
                }
            } catch (Exception ignored) {}
        }
        return new EnergyProbe(signals, hasNumeric, hasPositiveNumeric, activeKnown, active);
    }

    private String blockQueryHint(World world, int x, int y, int z) {
        if (world == null) return "machine";
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return "machine";
        int meta = world.getBlockMetadata(x, y, z);
        String key = blockKey(block, meta);
        int colon = key.indexOf(':');
        return colon > 0 ? key.substring(0, colon) : key;
    }

    private String safeQueryToken(String in) {
        String n = norm(in);
        if (n.length() == 0) return "machine";
        String[] parts = n.split("\\s+");
        return parts[0];
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        FutureTask<String> apiCall;
        while ((apiCall = apiCalls.poll()) != null) {
            apiCall.run();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        tickBlockCache(mc);
        tickChestCache(mc);
        ClientTask task;
        synchronized (this) {
            task = tasks.peek();
        }
        if (task == null) {
            return;
        }
        boolean keep = runTask(mc, task);
        if (!keep) {
            synchronized (this) {
                tasks.poll();
                currentPath.clear();
                lastGoal = null;
                pathRecalcCounter = 0;
                if (tasks.isEmpty()) controlSource = "idle";
            }
            mc.thePlayer.addChatComponentMessage(new ChatComponentText("[GTNH AI Bot] Task finished."));
        }
    }

    private boolean runTask(Minecraft mc, ClientTask task) {
        EntityClientPlayerMP player = mc.thePlayer;
        if (task.kind == TaskKind.GOTO) {
            return moveTo(mc, player, new BlockPos(task.x, task.y, task.z), 0.9D);
        }
        if (task.kind == TaskKind.FOLLOW) {
            EntityPlayer target = findPlayer(mc, task.targetPlayer);
            if (target == null) return false;
            return moveTo(
                mc,
                player,
                new BlockPos(
                    MathHelper.floor_double(target.posX),
                    MathHelper.floor_double(target.posY),
                    MathHelper.floor_double(target.posZ)),
                2.35D);
        }
        if (task.kind == TaskKind.BREAK) {
            if (moveTo(mc, player, new BlockPos(task.x, task.y, task.z), 1.35D)) return true;
            return !breakBlock(mc, task.x, task.y, task.z);
        }
        if (task.kind == TaskKind.USE || task.kind == TaskKind.PLACE) {
            if (moveTo(mc, player, new BlockPos(task.x, task.y, task.z), 1.6D)) return true;
            useOrPlace(mc, player, task.x, task.y, task.z, task.side);
            return false;
        }
        if (task.kind == TaskKind.CRAFT) {
            return runCraft(player, task);
        }
        return false;
    }

    private boolean runCraft(EntityClientPlayerMP player, ClientTask task) {
        if (countByQuery(player, task.itemQuery) >= task.targetCount) {
            return false;
        }
        if (!craftOneRecursive(player, task.itemQuery, new HashSet<String>(), 0)) {
            task.missingCooldown--;
            if (task.missingCooldown <= 0) {
                task.missingCooldown = 80;
                player.addChatComponentMessage(
                    new ChatComponentText("[GTNH AI Bot] Missing ingredients for " + task.itemQuery));
            }
        }
        return true;
    }

    private boolean craftOneRecursive(EntityClientPlayerMP player, String query, Set<String> visiting, int depth) {
        if (depth > 6) return false;
        String key = query.toLowerCase();
        if (visiting.contains(key)) return false;
        visiting.add(key);
        try {
            List<RecipePlan> plans = findRecipePlans(query);
            for (int i = 0; i < plans.size(); i++) {
                if (tryCraft(player, plans.get(i), visiting, depth)) return true;
            }
            return false;
        } finally {
            visiting.remove(key);
        }
    }

    private boolean tryCraft(EntityClientPlayerMP player, RecipePlan plan, Set<String> visiting, int depth) {
        for (int i = 0; i < plan.ingredients.size(); i++) {
            Ingredient need = plan.ingredients.get(i);
            while (countTemplate(player, need.template) < need.count) {
                String sub = stackKey(need.template);
                if (!craftOneRecursive(player, sub, visiting, depth + 1)) return false;
            }
        }
        for (int i = 0; i < plan.ingredients.size(); i++) {
            Ingredient need = plan.ingredients.get(i);
            for (int j = 0; j < need.count; j++) {
                if (!removeOne(player, need.template)) return false;
            }
        }
        ItemStack out = plan.output.copy();
        if (!player.inventory.addItemStackToInventory(out)) {
            player.dropPlayerItemWithRandomChoice(out, false);
        }
        return true;
    }

    private List<RecipePlan> findRecipePlans(String query) {
        List<RecipePlan> out = new ArrayList<RecipePlan>();
        List recipes = CraftingManager.getInstance()
            .getRecipeList();
        for (int i = 0; i < recipes.size(); i++) {
            Object o = recipes.get(i);
            if (!(o instanceof IRecipe)) continue;
            IRecipe r = (IRecipe) o;
            ItemStack result = r.getRecipeOutput();
            if (result == null || !matchesQuery(result, query)) continue;
            RecipePlan plan = planFromRecipe(r, result);
            if (plan != null && !plan.ingredients.isEmpty()) out.add(plan);
        }
        return out;
    }

    private RecipePlan planFromRecipe(IRecipe recipe, ItemStack out) {
        List<Ingredient> ingredients = new ArrayList<Ingredient>();
        if (recipe instanceof ShapedRecipes) {
            ItemStack[] items = ((ShapedRecipes) recipe).recipeItems;
            for (int i = 0; i < items.length; i++) addIngredient(ingredients, items[i]);
            return new RecipePlan(out, ingredients);
        }
        if (recipe instanceof ShapelessRecipes) {
            List items = ((ShapelessRecipes) recipe).recipeItems;
            for (int i = 0; i < items.size(); i++) addIngredient(ingredients, items.get(i));
            return new RecipePlan(out, ingredients);
        }
        if (recipe instanceof ShapedOreRecipe) {
            Object[] items = ((ShapedOreRecipe) recipe).getInput();
            for (int i = 0; i < items.length; i++) addIngredient(ingredients, items[i]);
            return new RecipePlan(out, ingredients);
        }
        if (recipe instanceof ShapelessOreRecipe) {
            List items = ((ShapelessOreRecipe) recipe).getInput();
            for (int i = 0; i < items.size(); i++) addIngredient(ingredients, items.get(i));
            return new RecipePlan(out, ingredients);
        }
        return null;
    }

    private void addIngredient(List<Ingredient> list, Object obj) {
        ItemStack stack = null;
        if (obj instanceof ItemStack) stack = ((ItemStack) obj).copy();
        if (obj instanceof Item) stack = new ItemStack((Item) obj);
        if (obj instanceof Block) stack = new ItemStack((Block) obj);
        if (obj instanceof List) {
            List items = (List) obj;
            if (!items.isEmpty() && items.get(0) instanceof ItemStack) stack = ((ItemStack) items.get(0)).copy();
        }
        if (stack != null) list.add(new Ingredient(stack, 1));
    }

    private boolean matchesQuery(ItemStack stack, String queryRaw) {
        String query = norm(queryRaw);
        return norm(stack.getDisplayName()).contains(query) || norm(stackKey(stack)).contains(query);
    }

    private String stackKey(ItemStack stack) {
        Object key = Item.itemRegistry.getNameForObject(stack.getItem());
        return key == null ? stack.getDisplayName() : String.valueOf(key);
    }

    private int countByQuery(EntityClientPlayerMP player, String query) {
        int total = 0;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack s = player.inventory.mainInventory[i];
            if (s != null && matchesQuery(s, query)) total += s.stackSize;
        }
        return total;
    }

    private int countTemplate(EntityClientPlayerMP player, ItemStack template) {
        int total = 0;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack s = player.inventory.mainInventory[i];
            if (s != null && sameItem(s, template)) total += s.stackSize;
        }
        return total;
    }

    private boolean removeOne(EntityClientPlayerMP player, ItemStack template) {
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack s = player.inventory.mainInventory[i];
            if (s != null && sameItem(s, template)) {
                s.stackSize--;
                if (s.stackSize <= 0) player.inventory.mainInventory[i] = null;
                return true;
            }
        }
        return false;
    }

    private boolean sameItem(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem()) return false;
        int dmg = b.getItemDamage();
        return dmg == OreDictionary.WILDCARD_VALUE || a.getItemDamage() == dmg;
    }

    private void loadGoalsIfNeeded() {
        if (goalsLoaded) return;
        goalsLoaded = true;
        File file = new File(GOAL_FILE);
        try {
            if (!file.exists()) {
                if (file.getParentFile() != null) file.getParentFile()
                    .mkdirs();
                Properties defaults = new Properties();
                defaults.setProperty("make a motor", "craft electric motor lv 1");
                FileOutputStream out = new FileOutputStream(file);
                defaults.store(out, "Fallback goals");
                out.close();
            }
            FileInputStream in = new FileInputStream(file);
            goals.load(in);
            in.close();
        } catch (Exception ignored) {}
    }

    private void tickBlockCache(Minecraft mc) {
        loadColdChunkCacheIfNeeded();
        blockScanCooldown--;
        coldChunkCacheSaveCooldown--;
        int dim = mc.theWorld.provider.dimensionId;
        if (dim != lastBlockCacheDim) {
            nearbyChunkBlockCache.clear();
            nearbyBlockIndex.clear();
            pendingChunkRefresh.clear();
            lastBlockCacheDim = dim;
            rebuildColdBlockIndex();
            blockScanCooldown = 0;
        }
        processPendingChunkRefreshes(mc);
        if (blockScanCooldown <= 0) {
            refreshNearbyBlockCache(mc);
            blockScanCooldown = BLOCK_SCAN_INTERVAL_TICKS;
        }
        if (coldChunkCacheDirty && coldChunkCacheSaveCooldown <= 0) {
            saveColdChunkCache();
            coldChunkCacheSaveCooldown = COLD_CHUNK_CACHE_SAVE_INTERVAL_TICKS;
        }
    }

    private synchronized void refreshNearbyBlockCache(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;
        World world = mc.theWorld;
        int playerChunkX = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
        int playerChunkZ = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        Set<ChunkKey> seen = new HashSet<ChunkKey>();
        for (int cx = playerChunkX - BLOCK_SCAN_RADIUS_CHUNKS; cx <= playerChunkX + BLOCK_SCAN_RADIUS_CHUNKS; cx++) {
            for (int cz = playerChunkZ - BLOCK_SCAN_RADIUS_CHUNKS; cz
                <= playerChunkZ + BLOCK_SCAN_RADIUS_CHUNKS; cz++) {
                if (!world.getChunkProvider()
                    .chunkExists(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                ChunkKey key = new ChunkKey(world.provider.dimensionId, cx, cz);
                seen.add(key);
                ChunkBlockCache cache = scanChunk(world, chunk, world.getTotalWorldTime());
                nearbyChunkBlockCache.put(key, cache);
                coldChunkBlockCache.put(key, cache.copy());
                coldChunkCacheDirty = true;
                pendingChunkRefresh.remove(key);
            }
        }
        Iterator<Map.Entry<ChunkKey, ChunkBlockCache>> it = nearbyChunkBlockCache.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkKey, ChunkBlockCache> e = it.next();
            if (!seen.contains(e.getKey())) {
                coldChunkBlockCache.put(
                    e.getKey(),
                    e.getValue()
                        .copy());
                coldChunkCacheDirty = true;
                it.remove();
            }
        }
        rebuildNearbyBlockIndex();
        rebuildColdBlockIndex();
    }

    private ChunkBlockCache scanChunk(World world, Chunk chunk, long scannedAt) {
        Map<String, List<BlockPos>> byBlock = new HashMap<String, List<BlockPos>>();
        int baseX = chunk.xPosition << 4;
        int baseZ = chunk.zPosition << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = 0; y < 256; y++) {
                    Block b = chunk.getBlock(lx, y, lz);
                    if (b == null || b.isAir(world, baseX + lx, y, baseZ + lz)) continue;
                    int meta = chunk.getBlockMetadata(lx, y, lz);
                    if (!shouldTrackBlock(b, meta)) continue;
                    String key = blockKey(b, meta);
                    List<BlockPos> list = byBlock.get(key);
                    if (list == null) {
                        list = new ArrayList<BlockPos>();
                        byBlock.put(key, list);
                    }
                    list.add(new BlockPos(baseX + lx, y, baseZ + lz));
                }
            }
        }
        return new ChunkBlockCache(byBlock, scannedAt);
    }

    private boolean shouldTrackBlock(Block block, int meta) {
        if (block == null) return false;
        if (block.hasTileEntity(meta)) return true;
        Object keyObj = Block.blockRegistry.getNameForObject(block);
        if (keyObj == null) return false;
        String key = String.valueOf(keyObj);
        return key.contains("crafting_table") || key.contains("workbench")
            || key.contains("chest")
            || key.contains("furnace");
    }

    private String blockKey(Block block, int meta) {
        Object keyObj = Block.blockRegistry.getNameForObject(block);
        String key = keyObj == null ? block.getLocalizedName() : String.valueOf(keyObj);
        return key + ":" + meta;
    }

    private synchronized void rebuildNearbyBlockIndex() {
        nearbyBlockIndex.clear();
        for (ChunkBlockCache chunk : nearbyChunkBlockCache.values()) {
            for (Map.Entry<String, List<BlockPos>> e : chunk.byBlock.entrySet()) {
                List<BlockPos> list = nearbyBlockIndex.get(e.getKey());
                if (list == null) {
                    list = new ArrayList<BlockPos>();
                    nearbyBlockIndex.put(e.getKey(), list);
                }
                list.addAll(e.getValue());
            }
        }
    }

    private synchronized void rebuildColdBlockIndex() {
        coldBlockIndex.clear();
        for (Map.Entry<ChunkKey, ChunkBlockCache> ce : coldChunkBlockCache.entrySet()) {
            if (lastBlockCacheDim != Integer.MIN_VALUE && ce.getKey().dim != lastBlockCacheDim) continue;
            ChunkBlockCache chunk = ce.getValue();
            for (Map.Entry<String, List<BlockPos>> e : chunk.byBlock.entrySet()) {
                List<BlockPos> list = coldBlockIndex.get(e.getKey());
                if (list == null) {
                    list = new ArrayList<BlockPos>();
                    coldBlockIndex.put(e.getKey(), list);
                }
                list.addAll(e.getValue());
            }
        }
    }

    private synchronized Map<String, List<BlockPos>> mergedBlockIndex() {
        Map<String, List<BlockPos>> merged = new HashMap<String, List<BlockPos>>();
        mergeIndexInto(merged, coldBlockIndex);
        mergeIndexInto(merged, nearbyBlockIndex);
        return merged;
    }

    private void mergeIndexInto(Map<String, List<BlockPos>> out, Map<String, List<BlockPos>> from) {
        for (Map.Entry<String, List<BlockPos>> e : from.entrySet()) {
            List<BlockPos> list = out.get(e.getKey());
            if (list == null) {
                list = new ArrayList<BlockPos>();
                out.put(e.getKey(), list);
            }
            list.addAll(e.getValue());
        }
    }

    private synchronized void applySingleBlockUpdate(World world, int x, int y, int z) {
        if (world == null || y < 0 || y > 255) return;
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkKey key = new ChunkKey(world.provider.dimensionId, chunkX, chunkZ);
        ChunkBlockCache cache = nearbyChunkBlockCache.get(key);
        if (cache == null) {
            if (world.getChunkProvider()
                .chunkExists(chunkX, chunkZ)) {
                Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
                cache = scanChunk(world, chunk, world.getTotalWorldTime());
                nearbyChunkBlockCache.put(key, cache);
                coldChunkBlockCache.put(key, cache.copy());
                coldChunkCacheDirty = true;
                rebuildNearbyBlockIndex();
                rebuildColdBlockIndex();
            }
            return;
        }
        cache.removeAt(x, y, z);
        Block block = world.getBlock(x, y, z);
        if (block != null && !block.isAir(world, x, y, z)) {
            int meta = world.getBlockMetadata(x, y, z);
            if (shouldTrackBlock(block, meta)) {
                cache.add(blockKey(block, meta), new BlockPos(x, y, z));
            }
        }
        cache.lastScannedTick = world.getTotalWorldTime();
        coldChunkBlockCache.put(key, cache.copy());
        coldChunkCacheDirty = true;
        rebuildNearbyBlockIndex();
        rebuildColdBlockIndex();
    }

    private synchronized void appendNearbyBlockContext(Minecraft mc, StringBuilder body) {
        if (mc == null || mc.thePlayer == null) return;
        BlockPos p = new BlockPos(
            MathHelper.floor_double(mc.thePlayer.posX),
            MathHelper.floor_double(mc.thePlayer.posY),
            MathHelper.floor_double(mc.thePlayer.posZ));
        int emitted = 0;
        for (Map.Entry<String, List<BlockPos>> e : nearbyBlockIndex.entrySet()) {
            if (emitted >= 120) break;
            List<BlockPos> positions = new ArrayList<BlockPos>(e.getValue());
            final BlockPos playerPos = p;
            positions.sort(new Comparator<BlockPos>() {

                @Override
                public int compare(BlockPos a, BlockPos b) {
                    double da = distSq(playerPos, a);
                    double db = distSq(playerPos, b);
                    return da < db ? -1 : da > db ? 1 : 0;
                }
            });
            int limit = Math.min(3, positions.size());
            for (int i = 0; i < limit && emitted < 120; i++) {
                BlockPos pos = positions.get(i);
                body.append("block ")
                    .append(e.getKey())
                    .append(" @ ")
                    .append(pos.x)
                    .append(" ")
                    .append(pos.y)
                    .append(" ")
                    .append(pos.z)
                    .append('\n');
                emitted++;
            }
        }
        emitted += appendColdBlockContext(mc, body, MAX_COLD_CONTEXT_LINES);
    }

    private synchronized int appendColdBlockContext(Minecraft mc, StringBuilder body, int maxLines) {
        if (mc == null || mc.thePlayer == null || maxLines <= 0) return 0;
        if (coldBlockIndex.isEmpty()) return 0;
        BlockPos p = new BlockPos(
            MathHelper.floor_double(mc.thePlayer.posX),
            MathHelper.floor_double(mc.thePlayer.posY),
            MathHelper.floor_double(mc.thePlayer.posZ));
        int emitted = 0;
        for (Map.Entry<String, List<BlockPos>> e : coldBlockIndex.entrySet()) {
            if (emitted >= maxLines) break;
            if (nearbyBlockIndex.containsKey(e.getKey())) continue;
            List<BlockPos> positions = new ArrayList<BlockPos>(e.getValue());
            final BlockPos playerPos = p;
            positions.sort(new Comparator<BlockPos>() {

                @Override
                public int compare(BlockPos a, BlockPos b) {
                    double da = distSq(playerPos, a);
                    double db = distSq(playerPos, b);
                    return da < db ? -1 : da > db ? 1 : 0;
                }
            });
            int limit = Math.min(1, positions.size());
            for (int i = 0; i < limit && emitted < maxLines; i++) {
                BlockPos pos = positions.get(i);
                body.append("coldblock ")
                    .append(e.getKey())
                    .append(" @ ")
                    .append(pos.x)
                    .append(" ")
                    .append(pos.y)
                    .append(" ")
                    .append(pos.z)
                    .append(" stale 1")
                    .append('\n');
                emitted++;
            }
        }
        return emitted;
    }

    private void processPendingChunkRefreshes(Minecraft mc) {
        if (mc == null || mc.theWorld == null) return;
        if (pendingChunkRefresh.isEmpty()) return;
        List<ChunkKey> copy = new ArrayList<ChunkKey>(pendingChunkRefresh);
        for (int i = 0; i < copy.size(); i++) {
            forceRefreshChunk(mc, copy.get(i), "pending");
        }
    }

    private synchronized void forceRefreshChunk(Minecraft mc, ChunkKey key, String reason) {
        if (mc == null || mc.theWorld == null) return;
        World world = mc.theWorld;
        if (world.provider.dimensionId != key.dim) {
            return;
        }
        if (!world.getChunkProvider()
            .chunkExists(key.chunkX, key.chunkZ)) {
            pendingChunkRefresh.add(key);
            return;
        }
        Chunk chunk = world.getChunkFromChunkCoords(key.chunkX, key.chunkZ);
        ChunkBlockCache refreshed = scanChunk(world, chunk, world.getTotalWorldTime());
        EntityClientPlayerMP player = mc.thePlayer;
        int playerChunkX = player == null ? key.chunkX : (MathHelper.floor_double(player.posX) >> 4);
        int playerChunkZ = player == null ? key.chunkZ : (MathHelper.floor_double(player.posZ) >> 4);
        if (Math.abs(key.chunkX - playerChunkX) <= BLOCK_SCAN_RADIUS_CHUNKS
            && Math.abs(key.chunkZ - playerChunkZ) <= BLOCK_SCAN_RADIUS_CHUNKS) {
            nearbyChunkBlockCache.put(key, refreshed);
            rebuildNearbyBlockIndex();
        } else {
            nearbyChunkBlockCache.remove(key);
            rebuildNearbyBlockIndex();
        }
        coldChunkBlockCache.put(key, refreshed.copy());
        rebuildColdBlockIndex();
        coldChunkCacheDirty = true;
        pendingChunkRefresh.remove(key);
        if ("unexpected".equals(reason) && mc.thePlayer != null) {
            mc.thePlayer.addChatComponentMessage(
                new ChatComponentText("[GTNH AI Bot] Refreshed stale chunk " + key.chunkX + "," + key.chunkZ));
        }
    }

    private void markChunkUnexpected(Minecraft mc, int x, int z) {
        if (mc == null || mc.theWorld == null) return;
        ChunkKey key = new ChunkKey(mc.theWorld.provider.dimensionId, x >> 4, z >> 4);
        if (!coldChunkBlockCache.containsKey(key)) return;
        forceRefreshChunk(mc, key, "unexpected");
    }

    private void tickChestCache(Minecraft mc) {
        loadChestCacheIfNeeded();
        chestScanCooldown--;
        chestOpenRefreshCooldown--;
        chestCacheSaveCooldown--;

        if (chestScanCooldown <= 0) {
            scanNearbyChests(mc);
            chestScanCooldown = CHEST_DISCOVERY_INTERVAL_TICKS;
        }

        if (mc.currentScreen instanceof GuiChest && chestOpenRefreshCooldown <= 0) {
            scanNearbyChests(mc);
            chestOpenRefreshCooldown = CHEST_OPEN_REFRESH_INTERVAL_TICKS;
        }

        if (chestCacheDirty && chestCacheSaveCooldown <= 0) {
            saveChestCache();
            chestCacheSaveCooldown = CHEST_CACHE_SAVE_INTERVAL_TICKS;
        }
    }

    private synchronized void appendChestContext(StringBuilder body) {
        for (Map.Entry<String, Integer> e : cachedItemTotals.entrySet()) {
            body.append("base ")
                .append(e.getKey())
                .append("=")
                .append(e.getValue())
                .append('\n');
        }
    }

    private synchronized void scanNearbyChests(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;
        World world = mc.theWorld;
        int dim = world.provider.dimensionId;
        int playerChunkX = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
        int playerChunkZ = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        Map<ChestKey, ChestSnapshot> next = new HashMap<ChestKey, ChestSnapshot>();
        List loaded = world.loadedTileEntityList;
        for (int i = 0; i < loaded.size(); i++) {
            Object o = loaded.get(i);
            if (!(o instanceof TileEntity)) continue;
            TileEntity te = (TileEntity) o;
            if (!(te instanceof IInventory)) continue;
            if (!isChestBlock(world, te.xCoord, te.yCoord, te.zCoord)) continue;
            int chunkX = te.xCoord >> 4;
            int chunkZ = te.zCoord >> 4;
            if (Math.abs(chunkX - playerChunkX) > CHEST_SCAN_RADIUS_CHUNKS
                || Math.abs(chunkZ - playerChunkZ) > CHEST_SCAN_RADIUS_CHUNKS) {
                continue;
            }
            ChestKey key = new ChestKey(dim, te.xCoord, te.yCoord, te.zCoord);
            ChestSnapshot snapshot = snapshotInventory((IInventory) te, world.getTotalWorldTime());
            next.put(key, snapshot);
        }
        if (!next.equals(chestCache)) {
            chestCache.clear();
            chestCache.putAll(next);
            chestCacheDirty = true;
            recomputeCachedTotals();
        }
    }

    private boolean isChestBlock(World world, int x, int y, int z) {
        Block b = world.getBlock(x, y, z);
        if (b == null) return false;
        Object keyObj = Block.blockRegistry.getNameForObject(b);
        if (keyObj == null) return false;
        String name = String.valueOf(keyObj);
        return name.contains("chest");
    }

    private ChestSnapshot snapshotInventory(IInventory inv, long when) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            String key = stackKey(s) + ":" + s.getItemDamage();
            Integer seen = counts.get(key);
            counts.put(key, Integer.valueOf((seen == null ? 0 : seen.intValue()) + s.stackSize));
        }
        return new ChestSnapshot(counts, when);
    }

    private synchronized void recomputeCachedTotals() {
        cachedItemTotals.clear();
        for (ChestSnapshot snap : chestCache.values()) {
            for (Map.Entry<String, Integer> e : snap.items.entrySet()) {
                Integer seen = cachedItemTotals.get(e.getKey());
                cachedItemTotals.put(
                    e.getKey(),
                    Integer.valueOf(
                        (seen == null ? 0 : seen.intValue()) + e.getValue()
                            .intValue()));
            }
        }
    }

    private synchronized void loadChestCacheIfNeeded() {
        if (chestCacheLoaded) return;
        chestCacheLoaded = true;
        if (!LOAD_CHEST_CACHE_FROM_DISK) return;
        File file = new File(CHEST_CACHE_FILE);
        if (!file.exists()) return;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\t");
                if (parts.length != 6) continue;
                int dim = Integer.parseInt(parts[0]);
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                String itemKey = parts[4];
                int count = Integer.parseInt(parts[5]);
                ChestKey key = new ChestKey(dim, x, y, z);
                ChestSnapshot snap = chestCache.get(key);
                if (snap == null) {
                    snap = new ChestSnapshot(new HashMap<String, Integer>(), 0L);
                    chestCache.put(key, snap);
                }
                Integer seen = snap.items.get(itemKey);
                snap.items.put(itemKey, Integer.valueOf((seen == null ? 0 : seen.intValue()) + count));
            }
            recomputeCachedTotals();
        } catch (Exception ignored) {
            chestCache.clear();
            cachedItemTotals.clear();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
        }
    }

    private synchronized void saveChestCache() {
        File file = new File(CHEST_CACHE_FILE);
        BufferedWriter out = null;
        try {
            if (file.getParentFile() != null) file.getParentFile()
                .mkdirs();
            out = new BufferedWriter(new FileWriter(file, false));
            out.write("# dim\tx\ty\tz\titem\tcount");
            out.newLine();
            for (Map.Entry<ChestKey, ChestSnapshot> e : chestCache.entrySet()) {
                ChestKey key = e.getKey();
                ChestSnapshot snap = e.getValue();
                for (Map.Entry<String, Integer> item : snap.items.entrySet()) {
                    out.write(
                        key.dim + "\t"
                            + key.x
                            + "\t"
                            + key.y
                            + "\t"
                            + key.z
                            + "\t"
                            + item.getKey()
                            + "\t"
                            + item.getValue());
                    out.newLine();
                }
            }
            chestCacheDirty = false;
        } catch (Exception ignored) {} finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {}
        }
    }

    private synchronized void loadColdChunkCacheIfNeeded() {
        if (coldChunkCacheLoaded) return;
        coldChunkCacheLoaded = true;
        File file = new File(COLD_CHUNK_CACHE_FILE);
        if (!file.exists()) return;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\t");
                if (parts.length != 8) continue;
                int dim = Integer.parseInt(parts[0]);
                int chunkX = Integer.parseInt(parts[1]);
                int chunkZ = Integer.parseInt(parts[2]);
                long scannedAt = Long.parseLong(parts[3]);
                String blockKey = parts[4];
                int x = Integer.parseInt(parts[5]);
                int y = Integer.parseInt(parts[6]);
                int z = Integer.parseInt(parts[7]);
                ChunkKey key = new ChunkKey(dim, chunkX, chunkZ);
                ChunkBlockCache cache = coldChunkBlockCache.get(key);
                if (cache == null) {
                    cache = new ChunkBlockCache(new HashMap<String, List<BlockPos>>(), scannedAt);
                    coldChunkBlockCache.put(key, cache);
                }
                cache.add(blockKey, new BlockPos(x, y, z));
                cache.lastScannedTick = Math.max(cache.lastScannedTick, scannedAt);
            }
            rebuildColdBlockIndex();
        } catch (Exception ignored) {
            coldChunkBlockCache.clear();
            coldBlockIndex.clear();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
        }
    }

    private synchronized void saveColdChunkCache() {
        File file = new File(COLD_CHUNK_CACHE_FILE);
        BufferedWriter out = null;
        try {
            if (file.getParentFile() != null) file.getParentFile()
                .mkdirs();
            out = new BufferedWriter(new FileWriter(file, false));
            out.write("# dim\tchunkX\tchunkZ\tscannedAt\tblock\tx\ty\tz");
            out.newLine();
            for (Map.Entry<ChunkKey, ChunkBlockCache> e : coldChunkBlockCache.entrySet()) {
                ChunkKey key = e.getKey();
                ChunkBlockCache cache = e.getValue();
                for (Map.Entry<String, List<BlockPos>> byType : cache.byBlock.entrySet()) {
                    List<BlockPos> positions = byType.getValue();
                    for (int i = 0; i < positions.size(); i++) {
                        BlockPos p = positions.get(i);
                        out.write(
                            key.dim + "\t"
                                + key.chunkX
                                + "\t"
                                + key.chunkZ
                                + "\t"
                                + cache.lastScannedTick
                                + "\t"
                                + byType.getKey()
                                + "\t"
                                + p.x
                                + "\t"
                                + p.y
                                + "\t"
                                + p.z);
                        out.newLine();
                    }
                }
            }
            coldChunkCacheDirty = false;
        } catch (Exception ignored) {} finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {}
        }
    }

    private boolean breakBlock(Minecraft mc, int x, int y, int z) {
        Block block = mc.theWorld.getBlock(x, y, z);
        if (block == null || block.isAir(mc.theWorld, x, y, z)) {
            applySingleBlockUpdate(mc.theWorld, x, y, z);
            return true;
        }
        if (mc.playerController == null) {
            return false;
        }
        mc.thePlayer.swingItem();
        mc.playerController.onPlayerDamageBlock(x, y, z, 1);
        Block after = mc.theWorld.getBlock(x, y, z);
        applySingleBlockUpdate(mc.theWorld, x, y, z);
        if (after != null && !after.isAir(mc.theWorld, x, y, z)) {
            markChunkUnexpected(mc, x, z);
        }
        return after == null || after.isAir(mc.theWorld, x, y, z);
    }

    private void useOrPlace(Minecraft mc, EntityClientPlayerMP player, int x, int y, int z, int side) {
        if (mc.playerController == null) {
            return;
        }
        Block beforeTarget = mc.theWorld.getBlock(x, y, z);
        int s = clampSide(side);
        mc.playerController.onPlayerRightClick(
            player,
            mc.theWorld,
            player.getCurrentEquippedItem(),
            x,
            y,
            z,
            s,
            Vec3.createVectorHelper(0.5D, 0.5D, 0.5D));
        player.swingItem();
        applySingleBlockUpdate(mc.theWorld, x, y, z);
        BlockPos placed = offsetBySide(x, y, z, s);
        applySingleBlockUpdate(mc.theWorld, placed.x, placed.y, placed.z);
        Block afterTarget = mc.theWorld.getBlock(x, y, z);
        if (beforeTarget == afterTarget && s != 1) {
            markChunkUnexpected(mc, x, z);
        }
    }

    private boolean moveTo(Minecraft mc, EntityClientPlayerMP p, BlockPos goal, double arriveSq) {
        if (distSq(p, goal) <= arriveSq) return false;
        BlockPos start = new BlockPos(
            MathHelper.floor_double(p.posX),
            MathHelper.floor_double(p.posY),
            MathHelper.floor_double(p.posZ));
        pathRecalcCounter--;
        boolean changed = lastGoal == null || !lastGoal.equals(goal);
        if (currentPath.isEmpty() || pathRecalcCounter <= 0 || changed) {
            currentPath = AStarPathfinder.findPath(mc.theWorld, start, goal, MAX_PATH_NODES);
            pathRecalcCounter = PATH_RECALC_TICKS;
            lastGoal = goal;
        }
        BlockPos next = currentPath.peek();
        if (next == null) return false;
        if (distSq(p, next) < 0.35D) {
            currentPath.poll();
            return true;
        }
        double tx = next.x + 0.5D;
        double tz = next.z + 0.5D;
        double dx = tx - p.posX;
        double dz = tz - p.posZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.0001D) {
            p.motionX = (dx / d) * 0.19D;
            p.motionZ = (dz / d) * 0.19D;
            p.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        }
        return true;
    }

    private EntityPlayer findPlayer(Minecraft mc, String name) {
        List players = mc.theWorld.playerEntities;
        for (int i = 0; i < players.size(); i++) {
            Object o = players.get(i);
            if (o instanceof EntityPlayer) {
                EntityPlayer p = (EntityPlayer) o;
                if (p.getCommandSenderName()
                    .equalsIgnoreCase(name)) {
                    return p;
                }
            }
        }
        return null;
    }

    private double distSq(EntityClientPlayerMP p, BlockPos pos) {
        double dx = p.posX - (pos.x + 0.5D);
        double dy = p.posY - pos.y;
        double dz = p.posZ - (pos.z + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private int clampSide(int side) {
        if (side < 0) return 1;
        if (side > 5) return 1;
        return side;
    }

    private BlockPos offsetBySide(int x, int y, int z, int side) {
        if (side == 0) return new BlockPos(x, y - 1, z);
        if (side == 1) return new BlockPos(x, y + 1, z);
        if (side == 2) return new BlockPos(x, y, z - 1);
        if (side == 3) return new BlockPos(x, y, z + 1);
        if (side == 4) return new BlockPos(x - 1, y, z);
        if (side == 5) return new BlockPos(x + 1, y, z);
        return new BlockPos(x, y, z);
    }

    private ParsedCommand parse(String input) {
        if (input == null || input.trim()
            .length() == 0) {
            return ParsedCommand.error("Empty command");
        }
        String[] args = input.trim()
            .split("\\s+");
        String sub = args[0].toLowerCase();
        try {
            if ("goto".equals(sub) && args.length == 4) {
                return ParsedCommand.ok(ClientTask.gotoPos(parseInt(args[1]), parseInt(args[2]), parseInt(args[3])));
            }
            if ("follow".equals(sub) && args.length >= 2) {
                return ParsedCommand.ok(ClientTask.follow(join(args, 1, args.length - 1)));
            }
            if (("break".equals(sub) || "mine".equals(sub)) && args.length == 4) {
                return ParsedCommand.ok(ClientTask.breakBlock(parseInt(args[1]), parseInt(args[2]), parseInt(args[3])));
            }
            if ("use".equals(sub) && args.length == 5) {
                return ParsedCommand.ok(
                    ClientTask.useBlock(parseInt(args[1]), parseInt(args[2]), parseInt(args[3]), parseInt(args[4])));
            }
            if ("place".equals(sub) && args.length == 5) {
                return ParsedCommand.ok(
                    ClientTask.placeBlock(parseInt(args[1]), parseInt(args[2]), parseInt(args[3]), parseInt(args[4])));
            }
            if ("craft".equals(sub) && args.length >= 2) {
                int count = 1;
                int end = args.length - 1;
                if (args.length >= 3) {
                    try {
                        count = parseInt(args[args.length - 1]);
                        end = args.length - 2;
                    } catch (Exception ignored) {}
                }
                return ParsedCommand.ok(ClientTask.craft(join(args, 1, end), Math.max(1, count)));
            }
            if ("refresh".equals(sub)) {
                Minecraft mc = Minecraft.getMinecraft();
                String arg = args.length > 1 ? join(args, 1, args.length - 1) : "";
                return ParsedCommand.error(refreshChunkData(mc, arg));
            }
            if ("status".equals(sub)) {
                String arg = args.length > 1 ? join(args, 1, args.length - 1) : "";
                return ParsedCommand.error(status(arg));
            }
            if ("diagnose".equals(sub)) {
                String arg = args.length > 1 ? join(args, 1, args.length - 1) : "";
                return ParsedCommand.error(diagnose(arg));
            }
            if ("stop".equals(sub)) {
                stopAll();
                return ParsedCommand.ok(null);
            }
            return ParsedCommand.error("Unknown command");
        } catch (Exception ex) {
            return ParsedCommand.error(ex.getMessage());
        }
    }

    private String join(String[] args, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end && i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private int parseInt(String raw) {
        return Integer.parseInt(raw);
    }

    private String norm(String in) {
        return in == null ? ""
            : in.toLowerCase()
                .replace('_', ' ')
                .replaceAll("[^a-z0-9: ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static class ParsedCommand {

        private final ClientTask task;
        private final String error;

        private ParsedCommand(ClientTask task, String error) {
            this.task = task;
            this.error = error;
        }

        private static ParsedCommand ok(ClientTask task) {
            return new ParsedCommand(task, null);
        }

        private static ParsedCommand error(String e) {
            return new ParsedCommand(null, e);
        }
    }

    private enum TaskKind {
        GOTO,
        FOLLOW,
        BREAK,
        USE,
        PLACE,
        CRAFT
    }

    private static class ClientTask {

        private final TaskKind kind;
        private final int x, y, z, side, targetCount;
        private final String targetPlayer, itemQuery;
        private int missingCooldown = 1;

        private ClientTask(TaskKind k, int x, int y, int z, int side, String tp, String iq, int tc) {
            kind = k;
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
            targetPlayer = tp;
            itemQuery = iq;
            targetCount = tc;
        }

        private static ClientTask gotoPos(int x, int y, int z) {
            return new ClientTask(TaskKind.GOTO, x, y, z, 1, null, null, 0);
        }

        private static ClientTask follow(String n) {
            return new ClientTask(TaskKind.FOLLOW, 0, 0, 0, 1, n, null, 0);
        }

        private static ClientTask breakBlock(int x, int y, int z) {
            return new ClientTask(TaskKind.BREAK, x, y, z, 1, null, null, 0);
        }

        private static ClientTask useBlock(int x, int y, int z, int s) {
            return new ClientTask(TaskKind.USE, x, y, z, s, null, null, 0);
        }

        private static ClientTask placeBlock(int x, int y, int z, int s) {
            return new ClientTask(TaskKind.PLACE, x, y, z, s, null, null, 0);
        }

        private static ClientTask craft(String q, int c) {
            return new ClientTask(TaskKind.CRAFT, 0, 0, 0, 1, null, q, c);
        }

        private String describe() {
            if (kind == TaskKind.CRAFT) return "CRAFT(" + itemQuery + "," + targetCount + ")";
            if (kind == TaskKind.FOLLOW) return "FOLLOW(" + targetPlayer + ")";
            return kind.name() + "(" + x + "," + y + "," + z + ")";
        }
    }

    private static class Ingredient {

        private final ItemStack template;
        private final int count;

        private Ingredient(ItemStack t, int c) {
            template = t;
            count = c;
        }
    }

    private static class DiagnosticTarget {

        private final int x;
        private final int y;
        private final int z;
        private final String queryHint;

        private DiagnosticTarget(int x, int y, int z, String queryHint) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.queryHint = queryHint == null ? "machine" : queryHint;
        }
    }

    private static class InventoryStats {

        private final int slots;
        private final int used;
        private final int totalStacks;

        private InventoryStats(int slots, int used, int totalStacks) {
            this.slots = slots;
            this.used = used;
            this.totalStacks = totalStacks;
        }
    }

    private static class EnergyProbe {

        private final Map<String, String> signals;
        private final boolean hasNumeric;
        private final boolean hasPositiveNumeric;
        private final boolean activeKnown;
        private final boolean active;

        private EnergyProbe(Map<String, String> signals, boolean hasNumeric, boolean hasPositiveNumeric,
            boolean activeKnown, boolean active) {
            this.signals = signals;
            this.hasNumeric = hasNumeric;
            this.hasPositiveNumeric = hasPositiveNumeric;
            this.activeKnown = activeKnown;
            this.active = active;
        }

        private String summary() {
            if (signals == null || signals.isEmpty()) return "unknown";
            StringBuilder sb = new StringBuilder();
            int emitted = 0;
            for (Map.Entry<String, String> e : signals.entrySet()) {
                if (emitted >= 6) break;
                if (sb.length() > 0) sb.append(',');
                sb.append(e.getKey())
                    .append('=')
                    .append(e.getValue());
                emitted++;
            }
            return sb.toString();
        }
    }

    private static class ChestKey {

        private final int dim;
        private final int x;
        private final int y;
        private final int z;

        private ChestKey(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChestKey)) return false;
            ChestKey c = (ChestKey) o;
            return dim == c.dim && x == c.x && y == c.y && z == c.z;
        }

        @Override
        public int hashCode() {
            int h = dim;
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }
    }

    private static class ChestSnapshot {

        private final Map<String, Integer> items;
        private final long updatedAtTick;

        private ChestSnapshot(Map<String, Integer> items, long updatedAtTick) {
            this.items = items;
            this.updatedAtTick = updatedAtTick;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChestSnapshot)) return false;
            ChestSnapshot s = (ChestSnapshot) o;
            return items.equals(s.items);
        }

        @Override
        public int hashCode() {
            return items.hashCode();
        }
    }

    private static class ChunkKey {

        private final int dim;
        private final int chunkX;
        private final int chunkZ;

        private ChunkKey(int dim, int chunkX, int chunkZ) {
            this.dim = dim;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChunkKey)) return false;
            ChunkKey c = (ChunkKey) o;
            return dim == c.dim && chunkX == c.chunkX && chunkZ == c.chunkZ;
        }

        @Override
        public int hashCode() {
            int h = dim;
            h = 31 * h + chunkX;
            h = 31 * h + chunkZ;
            return h;
        }
    }

    private static class ChunkBlockCache {

        private final Map<String, List<BlockPos>> byBlock;
        private long lastScannedTick;

        private ChunkBlockCache(Map<String, List<BlockPos>> byBlock, long lastScannedTick) {
            this.byBlock = byBlock;
            this.lastScannedTick = lastScannedTick;
        }

        private void add(String key, BlockPos pos) {
            List<BlockPos> list = byBlock.get(key);
            if (list == null) {
                list = new ArrayList<BlockPos>();
                byBlock.put(key, list);
            }
            list.add(pos);
        }

        private void removeAt(int x, int y, int z) {
            Iterator<Map.Entry<String, List<BlockPos>>> byType = byBlock.entrySet()
                .iterator();
            while (byType.hasNext()) {
                Map.Entry<String, List<BlockPos>> e = byType.next();
                List<BlockPos> positions = e.getValue();
                for (int i = positions.size() - 1; i >= 0; i--) {
                    BlockPos p = positions.get(i);
                    if (p.x == x && p.y == y && p.z == z) {
                        positions.remove(i);
                    }
                }
                if (positions.isEmpty()) {
                    byType.remove();
                }
            }
        }

        private ChunkBlockCache copy() {
            Map<String, List<BlockPos>> copy = new HashMap<String, List<BlockPos>>();
            for (Map.Entry<String, List<BlockPos>> e : byBlock.entrySet()) {
                copy.put(e.getKey(), new ArrayList<BlockPos>(e.getValue()));
            }
            return new ChunkBlockCache(copy, lastScannedTick);
        }
    }

    private static class RecipePlan {

        private final ItemStack output;
        private final List<Ingredient> ingredients;

        private RecipePlan(ItemStack o, List<Ingredient> i) {
            output = o;
            ingredients = i;
        }
    }

    private static class BlockPos {

        private final int x, y, z;

        private BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BlockPos)) return false;
            BlockPos p = (BlockPos) o;
            return x == p.x && y == p.y && z == p.z;
        }

        @Override
        public int hashCode() {
            int h = x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }
    }

    private static class AStarPathfinder {

        private static Queue<BlockPos> findPath(World world, BlockPos start, BlockPos goal, int maxNodes) {
            if (start.equals(goal)) return new ArrayDeque<BlockPos>();
            PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(64, new Comparator<PathNode>() {

                @Override
                public int compare(PathNode a, PathNode b) {
                    return a.f - b.f;
                }
            });
            Set<BlockPos> closed = new HashSet<BlockPos>();
            Map<BlockPos, PathNode> nodes = new HashMap<BlockPos, PathNode>();
            PathNode first = new PathNode(start, null, 0, h(start, goal));
            open.add(first);
            nodes.put(start, first);
            int expanded = 0;
            while (!open.isEmpty() && expanded < maxNodes) {
                PathNode cur = open.poll();
                if (cur.pos.equals(goal)) return build(cur);
                if (closed.contains(cur.pos)) continue;
                closed.add(cur.pos);
                expanded++;
                int[][] dirs = new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                for (int i = 0; i < dirs.length; i++) {
                    BlockPos next = new BlockPos(cur.pos.x + dirs[i][0], cur.pos.y, cur.pos.z + dirs[i][1]);
                    if (!canStand(world, next) || closed.contains(next)) continue;
                    int g = cur.g + 1;
                    PathNode seen = nodes.get(next);
                    if (seen == null || g < seen.g) {
                        PathNode n = new PathNode(next, cur, g, h(next, goal));
                        nodes.put(next, n);
                        open.add(n);
                    }
                }
            }
            return new ArrayDeque<BlockPos>();
        }

        private static boolean canStand(World world, BlockPos p) {
            return isPass(world, p.x, p.y, p.z) && isPass(world, p.x, p.y + 1, p.z)
                && !isPass(world, p.x, p.y - 1, p.z);
        }

        private static boolean isPass(World world, int x, int y, int z) {
            Block b = world.getBlock(x, y, z);
            return b == null || b.isAir(world, x, y, z) || b.getBlocksMovement(world, x, y, z);
        }

        private static int h(BlockPos a, BlockPos b) {
            return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
        }

        private static Queue<BlockPos> build(PathNode end) {
            ArrayDeque<BlockPos> q = new ArrayDeque<BlockPos>();
            PathNode c = end;
            while (c != null) {
                q.addFirst(c.pos);
                c = c.parent;
            }
            if (!q.isEmpty()) q.pollFirst();
            return q;
        }

        private static class PathNode {

            private final BlockPos pos;
            private final PathNode parent;
            private final int g, f;

            private PathNode(BlockPos pos, PathNode parent, int g, int h) {
                this.pos = pos;
                this.parent = parent;
                this.g = g;
                this.f = g + h;
            }
        }
    }
}
