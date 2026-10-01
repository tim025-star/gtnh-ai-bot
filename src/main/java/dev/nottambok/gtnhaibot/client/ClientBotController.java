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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.nottambok.gtnhaibot.client.actions.TrackedAction;
import dev.nottambok.gtnhaibot.client.crafting.CraftPlan;
import dev.nottambok.gtnhaibot.client.crafting.CraftPlanner;
import dev.nottambok.gtnhaibot.client.crafting.IngredientRequirement;
import dev.nottambok.gtnhaibot.client.crafting.LiveRecipeIndex;
import dev.nottambok.gtnhaibot.client.crafting.RecipeDefinition;
import dev.nottambok.gtnhaibot.client.crafting.ResourceLedger;
import dev.nottambok.gtnhaibot.client.crafting.StackKey;

public class ClientBotController {

    private static final int PATH_RECALC_TICKS = 12;
    private static final int MAX_PATH_NODES = 4096;
    private static final int MAX_INTERACTION_MOVE_TICKS = 600;
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
    private final LiveRecipeIndex recipeIndex = new LiveRecipeIndex();
    private final Map<String, TrackedAction> trackedActions = new LinkedHashMap<String, TrackedAction>();
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

    public synchronized String enqueueFromWeb(String actionId, String actionType, String commandLine) {
        TrackedAction existing = trackedActions.get(actionId);
        if (existing != null) return actionResponse(existing);
        ParsedCommand parsed = parse(commandLine);
        if (parsed.error != null) throw new IllegalArgumentException(parsed.error);
        TrackedAction action = new TrackedAction(actionId, actionType);
        recordAction(action);
        if (parsed.task == null) {
            action.complete("Action completed");
        } else {
            parsed.task.actionId = actionId;
            tasks.add(parsed.task);
            controlSource = "web";
            controlRevision++;
        }
        return actionResponse(action);
    }

    public synchronized String completeImmediateAction(String actionId, String actionType, String result) {
        TrackedAction existing = trackedActions.get(actionId);
        if (existing != null) return actionResponse(existing);
        TrackedAction action = new TrackedAction(actionId, actionType);
        recordAction(action);
        if ("selectItem".equals(actionType) && !result.startsWith("HELD ")) action.fail(result);
        else action.complete(result);
        return actionResponse(action);
    }

    public synchronized String trackedActionJson(String actionId) {
        TrackedAction action = trackedActions.get(actionId);
        return action == null ? null : actionResponse(action);
    }

    public synchronized boolean hasControlRevision(long revision) {
        return controlRevision == revision;
    }

    private void recordAction(TrackedAction action) {
        Iterator<TrackedAction> cached = trackedActions.values()
            .iterator();
        while (trackedActions.size() >= 500 && cached.hasNext()) {
            if (cached.next()
                .isTerminal()) cached.remove();
        }
        if (trackedActions.size() >= 500) throw new IllegalStateException("Too many pending Minecraft actions");
        trackedActions.put(action.getId(), action);
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
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null && minecraft.thePlayer != null) {
            clearOpenCraftingGrid(minecraft, minecraft.thePlayer);
            if (minecraft.thePlayer.openContainer != minecraft.thePlayer.inventoryContainer)
                minecraft.thePlayer.closeScreen();
        }
        for (ClientTask task : tasks) {
            TrackedAction action = trackedAction(task);
            if (action != null) action.cancel("Stopped by user");
        }
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
        try {
            return task.get(5L, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception) throw (Exception) ex.getCause();
            throw ex;
        } finally {
            if (!task.isDone()) task.cancel(false);
            apiCalls.remove(task);
        }
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
        JsonObject index = new JsonObject();
        index.addProperty(
            "state",
            recipeIndex.getState()
                .name()
                .toLowerCase());
        index.addProperty("recipes", recipeIndex.size());
        index.addProperty("fingerprint", recipeIndex.getFingerprint());
        if (recipeIndex.getError()
            .length() > 0) index.addProperty("error", recipeIndex.getError());
        root.add("recipeIndex", index);
        ClientTask activeTask = tasks.peek();
        if (activeTask != null) {
            TrackedAction activeAction = trackedAction(activeTask);
            if (activeAction != null) root.add("activeAction", activeAction.toJson());
        }
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
        if (player.openContainer != player.inventoryContainer) player.closeScreen();
        int hotbarSlot = player.inventory.currentItem;
        mc.playerController.windowClick(player.inventoryContainer.windowId, found, hotbarSlot, 2, player);
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
        String diagnosticArg = targetArg == null ? "" : targetArg.trim();
        if (diagnosticArg.toLowerCase(Locale.ROOT)
            .startsWith("recipe ")) return recipeIndex.describe(diagnosticArg.substring(7), 450);
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
        processApiCalls();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            synchronized (this) {
                for (ClientTask pending : tasks) {
                    TrackedAction action = trackedAction(pending);
                    if (action != null) action.fail("Minecraft world disconnected");
                }
                stopAll();
            }
            return;
        }
        tickBlockCache(mc);
        tickChestCache(mc);
        recipeIndex.tick();
        ClientTask task;
        synchronized (this) {
            task = tasks.peek();
        }
        if (task == null) {
            return;
        }
        TrackedAction tracked = trackedAction(task);
        if (tracked != null && tracked.getStatus() == TrackedAction.Status.ACCEPTED) tracked.running(
            task.kind.name()
                .toLowerCase(),
            "Minecraft is executing the action");
        boolean keep;
        try {
            keep = runTask(mc, task);
        } catch (Exception ex) {
            keep = false;
            if (tracked != null) tracked.fail(
                ex.getMessage() == null ? ex.getClass()
                    .getSimpleName() : ex.getMessage());
        }
        if (!keep) {
            if (task.kind == TaskKind.CRAFT) {
                clearOpenCraftingGrid(mc, mc.thePlayer);
                if (mc.thePlayer.openContainer != mc.thePlayer.inventoryContainer) mc.thePlayer.closeScreen();
            }
            if (tracked != null && !tracked.isTerminal()) tracked.complete("Action completed in Minecraft");
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

    void processApiCalls() {
        FutureTask<String> call;
        while ((call = apiCalls.poll()) != null) call.run();
    }

    private boolean runTask(Minecraft mc, ClientTask task) {
        EntityClientPlayerMP player = mc.thePlayer;
        if (task.kind == TaskKind.GOTO) {
            BlockPos target = new BlockPos(task.x, task.y, task.z);
            if (moveTo(mc, player, target, 0.9D)) {
                if (interactionMovementTimedOut(task, player))
                    return failTask(player, task, "Navigation timed out before reaching the requested position");
                return true;
            }
            resetInteractionMovement(task);
            return distSq(player, target) <= 0.9D ? false
                : failTask(player, task, "No navigable path to the requested position");
        }
        if (task.kind == TaskKind.FOLLOW) {
            EntityPlayer target = findPlayer(mc, task.targetPlayer);
            if (target == null) return failTask(player, task, "The player to follow is not present");
            BlockPos targetPosition = new BlockPos(
                MathHelper.floor_double(target.posX),
                MathHelper.floor_double(target.posY),
                MathHelper.floor_double(target.posZ));
            if (moveTo(mc, player, targetPosition, 2.35D)) {
                if (interactionMovementTimedOut(task, player))
                    return failTask(player, task, "Navigation timed out while following the player");
                return true;
            }
            resetInteractionMovement(task);
            return distSq(player, targetPosition) <= 2.35D ? false
                : failTask(player, task, "No navigable path to the player being followed");
        }
        if (task.kind == TaskKind.BREAK) {
            BlockPos target = new BlockPos(task.x, task.y, task.z);
            if (moveToInteraction(mc, player, target, 1.35D)) {
                if (interactionMovementTimedOut(task, player))
                    return failTask(player, task, "Navigation timed out before reaching the block to break");
                return true;
            }
            resetInteractionMovement(task);
            if (!isAtInteractionPosition(mc.theWorld, player, target, 1.35D))
                return failTask(player, task, "No navigable path to the block to break");
            return !breakBlock(mc, task.x, task.y, task.z);
        }
        if (task.kind == TaskKind.USE || task.kind == TaskKind.PLACE) {
            BlockPos target = new BlockPos(task.x, task.y, task.z);
            if (moveToInteraction(mc, player, target, 1.6D)) {
                if (interactionMovementTimedOut(task, player))
                    return failTask(player, task, "Navigation timed out before reaching the target block");
                return true;
            }
            resetInteractionMovement(task);
            if (!isAtInteractionPosition(mc.theWorld, player, target, 1.6D))
                return failTask(player, task, "No navigable path to the target block");
            if (!useOrPlace(mc, player, task.x, task.y, task.z, task.side))
                return failTask(player, task, "Minecraft rejected the block interaction");
            TrackedAction action = trackedAction(task);
            if (action != null) action.complete("Block interaction accepted by Minecraft");
            return false;
        }
        if (task.kind == TaskKind.CRAFT) {
            return runCraft(mc, player, task);
        }
        return false;
    }

    private boolean runCraft(Minecraft mc, EntityClientPlayerMP player, ClientTask task) {
        if (countByQuery(player, task.itemQuery) >= task.targetCount) {
            return false;
        }
        if (recipeIndex.getState() == LiveRecipeIndex.State.FAILED)
            return failCraft(player, task, "Recipe indexing failed: " + recipeIndex.getError());
        if (!recipeIndex.isReadyForCrafting()) {
            updateAction(task, "indexing_recipes", "Indexing loaded recipes: " + recipeIndex.size() + " found so far");
            return true;
        }
        if (task.craftPhase == CraftPhase.NEW || task.craftPhase == CraftPhase.PLANNING) {
            return planCraft(player, task);
        }
        if (task.craftPhase == CraftPhase.SCANNING_STORAGE) return scanStorageForCraft(mc, player, task);
        if (task.craftPhase == CraftPhase.RETRIEVING) return retrieveCraftResources(mc, player, task);
        if (task.craftPhase == CraftPhase.EXECUTING) return executeCraftPlan(mc, player, task);
        return failCraft(player, task, "Unknown crafting state");
    }

    private boolean planCraft(EntityClientPlayerMP player, ClientTask task) {
        if (task.phaseWaitTicks > 0) {
            task.phaseWaitTicks--;
            return true;
        }
        if (task.itemQuery == null || task.itemQuery.indexOf(':') < 1)
            return failCraft(player, task, "Crafting requires an exact registry item ID");
        updateAction(task, "planning", "Resolving inventory, storage, and loaded recipe dependencies");
        StackKey target = new StackKey(task.itemQuery, StackKey.WILDCARD_DAMAGE, "");
        ResourceLedger resources = resourceLedger(player);
        CraftPlanner planner = new CraftPlanner(recipeIndex.getCatalog(), 2000, 16, TimeUnit.MILLISECONDS.toNanos(20L));
        CraftPlan plan = planner.plan(target, task.targetCount, resources);
        if (!plan.isFeasible()) {
            List<ChestKey> unknown = unknownNearbyChests(player);
            if (!unknown.isEmpty() && !task.storageScanCompleted) {
                task.storageScanQueue = new ArrayDeque<ChestKey>(unknown);
                task.craftPhase = CraftPhase.SCANNING_STORAGE;
                task.craftMissing = plan.getMissing() == null ? "unknown"
                    : plan.getMissing()
                        .canonical();
                updateAction(
                    task,
                    "scanning_storage",
                    craftPlanningContext(task) + "; opening nearby storage to find " + task.craftMissing);
                return true;
            }
            return failCraft(
                player,
                task,
                plan.getFailure() + (plan.getMissing() == null ? ""
                    : ": " + plan.getMissing()
                        .canonical()));
        }
        acceptCraftPlan(task, plan);
        return true;
    }

    private void acceptCraftPlan(ClientTask task, CraftPlan plan) {
        task.craftPlan = plan;
        task.requiredChestItems = requiredChestItems(plan);
        if (!task.requiredChestItems.isEmpty()) {
            task.craftPhase = CraftPhase.RETRIEVING;
            updateAction(task, "retrieving", "Retrieving planned ingredients from nearby storage");
        } else {
            task.craftPhase = CraftPhase.EXECUTING;
            updateAction(
                task,
                "crafting",
                "Executing " + plan.getSteps()
                    .size() + " verified recipe steps");
        }
    }

    private ResourceLedger resourceLedger(EntityClientPlayerMP player) {
        Map<StackKey, Integer> inventory = new HashMap<StackKey, Integer>();
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null && stack.stackSize > 0) addStock(inventory, craftingKey(stack), stack.stackSize);
        }
        Map<StackKey, Integer> chests = new HashMap<StackKey, Integer>();
        for (ChestSnapshot snapshot : chestCache.values()) {
            if (!snapshot.trusted) continue;
            for (Map.Entry<StackKey, Integer> entry : snapshot.exactItems.entrySet()) {
                addStock(
                    chests,
                    entry.getKey(),
                    entry.getValue()
                        .intValue());
            }
        }
        return new ResourceLedger(inventory, chests);
    }

    private void addStock(Map<StackKey, Integer> stock, StackKey key, int count) {
        Integer current = stock.get(key);
        stock.put(key, Integer.valueOf((current == null ? 0 : current.intValue()) + count));
    }

    private StackKey craftingKey(ItemStack stack) {
        String nbt = stack.hasTagCompound() ? stack.getTagCompound()
            .toString() : "";
        return new StackKey(stackKey(stack), stack.getItemDamage(), nbt);
    }

    private List<ChestKey> unknownNearbyChests(final EntityClientPlayerMP player) {
        List<ChestKey> unknown = new ArrayList<ChestKey>();
        for (Map.Entry<ChestKey, ChestSnapshot> entry : chestCache.entrySet()) {
            if (!entry.getValue().trusted) unknown.add(entry.getKey());
        }
        unknown.sort(new Comparator<ChestKey>() {

            @Override
            public int compare(ChestKey left, ChestKey right) {
                return Double.compare(
                    distSq(player, new BlockPos(left.x, left.y, left.z)),
                    distSq(player, new BlockPos(right.x, right.y, right.z)));
            }
        });
        return unknown;
    }

    private Map<StackKey, Integer> requiredChestItems(CraftPlan plan) {
        Map<StackKey, Integer> required = new HashMap<StackKey, Integer>();
        for (ResourceLedger.Withdrawal withdrawal : plan.getWithdrawals()) {
            if (withdrawal.getSource() == ResourceLedger.Source.CHEST)
                addStock(required, withdrawal.getKey(), withdrawal.getCount());
        }
        return required;
    }

    private boolean scanStorageForCraft(Minecraft mc, EntityClientPlayerMP player, ClientTask task) {
        if (task.activeChest == null) {
            if (player.openContainer != player.inventoryContainer) player.closeScreen();
            task.activeChest = task.storageScanQueue.poll();
            task.phaseWaitTicks = 0;
            resetInteractionMovement(task);
            if (task.activeChest == null) {
                task.storageScanCompleted = true;
                task.craftPhase = CraftPhase.PLANNING;
                updateAction(task, "planning", "Storage scan completed; rebuilding the dependency plan");
                return true;
            }
        }
        ChestKey chest = task.activeChest;
        updateAction(
            task,
            "scanning_storage",
            craftPlanningContext(task) + "; missing "
                + task.craftMissing
                + "; inspecting chest at "
                + chest.x
                + ","
                + chest.y
                + ","
                + chest.z);
        if (moveToInteraction(mc, player, new BlockPos(chest.x, chest.y, chest.z), 2.5D)) {
            if (interactionMovementTimedOut(task, player)) {
                updateAction(
                    task,
                    "scanning_storage",
                    "Skipping chest after navigation timed out at " + chest.x + "," + chest.y + "," + chest.z);
                task.activeChest = null;
                resetInteractionMovement(task);
                currentPath.clear();
            }
            return true;
        }
        resetInteractionMovement(task);
        if (!isAtInteractionPosition(mc.theWorld, player, new BlockPos(chest.x, chest.y, chest.z), 2.5D)) {
            updateAction(
                task,
                "scanning_storage",
                "Skipping unreachable chest at " + chest.x + "," + chest.y + "," + chest.z);
            task.activeChest = null;
            return true;
        }
        IInventory inventory = chestInventory(mc, chest);
        if (inventory == null) {
            task.activeChest = null;
            return true;
        }
        if (!hasOpenStorageContainer(player)) {
            if (task.phaseWaitTicks % 20 == 0) useOrPlace(mc, player, chest.x, chest.y, chest.z, 1);
            task.phaseWaitTicks++;
            if (task.phaseWaitTicks > 80) task.activeChest = null;
            return true;
        }
        captureOpenStorage(mc, player, chest);
        player.closeScreen();
        task.activeChest = null;
        task.phaseWaitTicks = 0;
        StackKey target = new StackKey(task.itemQuery, StackKey.WILDCARD_DAMAGE, "");
        CraftPlan updated = new CraftPlanner(recipeIndex.getCatalog(), 2000, 16, TimeUnit.MILLISECONDS.toNanos(20L))
            .plan(target, task.targetCount, resourceLedger(player));
        if (updated.isFeasible()) acceptCraftPlan(task, updated);
        return true;
    }

    private boolean retrieveCraftResources(Minecraft mc, EntityClientPlayerMP player, ClientTask task) {
        if (task.pendingRetrieval != null) {
            if (task.phaseWaitTicks-- > 0) return true;
            StackKey retrieved = task.pendingRetrieval;
            int moved = countCraftingKey(player, retrieved) - task.retrievalCountBefore;
            if (moved <= 0) return failCraft(player, task, "Storage transfer was not confirmed; inventory may be full");
            int remaining = task.requiredChestItems.get(retrieved)
                .intValue() - moved;
            if (remaining <= 0) task.requiredChestItems.remove(retrieved);
            else task.requiredChestItems.put(retrieved, Integer.valueOf(remaining));
            captureOpenStorage(mc, player, task.activeChest);
            task.pendingRetrieval = null;
            task.phaseWaitTicks = 0;
            return true;
        }
        StackKey needed = firstRequiredItem(task.requiredChestItems);
        if (needed == null) {
            if (player.openContainer != player.inventoryContainer) player.closeScreen();
            task.craftPlan = null;
            task.craftPhase = CraftPhase.PLANNING;
            task.phaseWaitTicks = 5;
            updateAction(task, "verifying_retrieval", "Verifying retrieved ingredients with the server");
            return true;
        }
        if (task.activeChest == null) {
            if (player.openContainer != player.inventoryContainer) player.closeScreen();
            task.activeChest = findTrustedChestWith(needed);
            task.phaseWaitTicks = 0;
            resetInteractionMovement(task);
            if (task.activeChest == null)
                return failCraft(player, task, "Planned chest item is no longer available: " + needed.canonical());
        }
        ChestKey chest = task.activeChest;
        updateAction(
            task,
            "retrieving",
            "Retrieving " + needed.canonical() + " from " + chest.x + "," + chest.y + "," + chest.z);
        if (moveToInteraction(mc, player, new BlockPos(chest.x, chest.y, chest.z), 2.5D)) {
            if (interactionMovementTimedOut(task, player)) return failCraft(
                player,
                task,
                "Navigation timed out while approaching required storage at " + chest.x
                    + ","
                    + chest.y
                    + ","
                    + chest.z);
            return true;
        }
        resetInteractionMovement(task);
        if (!isAtInteractionPosition(mc.theWorld, player, new BlockPos(chest.x, chest.y, chest.z), 2.5D))
            return failCraft(
                player,
                task,
                "No navigable path to required storage at " + chest.x + "," + chest.y + "," + chest.z);
        IInventory inventory = chestInventory(mc, chest);
        if (inventory == null)
            return failCraft(player, task, "Storage disappeared at " + chest.x + "," + chest.y + "," + chest.z);
        if (!hasOpenStorageContainer(player)) {
            if (task.phaseWaitTicks % 20 == 0) useOrPlace(mc, player, chest.x, chest.y, chest.z, 1);
            task.phaseWaitTicks++;
            if (task.phaseWaitTicks > 80)
                return failCraft(player, task, "Could not open storage at " + chest.x + "," + chest.y + "," + chest.z);
            return true;
        }
        task.retrievalCountBefore = countCraftingKey(player, needed);
        if (!shiftClickMatchingChestStack(mc, player, needed)) {
            captureOpenStorage(mc, player, chest);
            task.activeChest = null;
            return true;
        }
        task.pendingRetrieval = needed;
        task.phaseWaitTicks = 4;
        return true;
    }

    private IInventory chestInventory(Minecraft mc, ChestKey key) {
        if (mc == null || mc.theWorld == null || mc.theWorld.provider.dimensionId != key.dim) return null;
        TileEntity tile = mc.theWorld.getTileEntity(key.x, key.y, key.z);
        return tile instanceof IInventory ? (IInventory) tile : null;
    }

    private boolean hasOpenStorageContainer(EntityClientPlayerMP player) {
        if (player == null || player.openContainer == null || player.openContainer == player.inventoryContainer)
            return false;
        return !(player.openContainer instanceof ContainerWorkbench);
    }

    private void captureOpenStorage(Minecraft mc, EntityClientPlayerMP player, ChestKey key) {
        chestCache.put(key, snapshotOpenStorage(player, mc.theWorld.getTotalWorldTime()));
        chestCacheDirty = true;
        recomputeCachedTotals();
    }

    private ChestSnapshot snapshotOpenStorage(EntityClientPlayerMP player, long when) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<StackKey, Integer> exactCounts = new HashMap<StackKey, Integer>();
        List slots = player.openContainer.inventorySlots;
        for (int i = 0; i < slots.size(); i++) {
            Object object = slots.get(i);
            if (!(object instanceof Slot)) continue;
            Slot slot = (Slot) object;
            if (slot.inventory == player.inventory) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.stackSize <= 0) continue;
            String itemKey = stackKey(stack) + ":" + stack.getItemDamage();
            Integer current = counts.get(itemKey);
            counts.put(itemKey, Integer.valueOf((current == null ? 0 : current.intValue()) + stack.stackSize));
            addStock(exactCounts, craftingKey(stack), stack.stackSize);
        }
        return new ChestSnapshot(counts, exactCounts, when, true);
    }

    private StackKey firstRequiredItem(Map<StackKey, Integer> required) {
        for (Map.Entry<StackKey, Integer> entry : required.entrySet()) {
            if (entry.getValue()
                .intValue() > 0) return entry.getKey();
        }
        return null;
    }

    private ChestKey findTrustedChestWith(StackKey needed) {
        for (Map.Entry<ChestKey, ChestSnapshot> entry : chestCache.entrySet()) {
            if (!entry.getValue().trusted) continue;
            for (Map.Entry<StackKey, Integer> item : entry.getValue().exactItems.entrySet()) {
                if (needed.matches(item.getKey()) && item.getValue()
                    .intValue() > 0) return entry.getKey();
            }
        }
        return null;
    }

    private boolean shiftClickMatchingChestStack(Minecraft mc, EntityClientPlayerMP player, StackKey needed) {
        List slots = player.openContainer.inventorySlots;
        for (int i = 0; i < slots.size(); i++) {
            Object object = slots.get(i);
            if (!(object instanceof Slot)) continue;
            Slot slot = (Slot) object;
            ItemStack stack = slot.getStack();
            if (slot.inventory == player.inventory || stack == null || !needed.matches(craftingKey(stack))) continue;
            mc.playerController.windowClick(player.openContainer.windowId, slot.slotNumber, 0, 1, player);
            return true;
        }
        return false;
    }

    private boolean executeCraftPlan(Minecraft mc, EntityClientPlayerMP player, ClientTask task) {
        if (task.craftPlan == null) return failCraft(player, task, "Craft plan was lost");
        if (task.craftStepIndex >= task.craftPlan.getSteps()
            .size()) {
            clearOpenCraftingGrid(mc, player);
            if (player.openContainer != player.inventoryContainer) player.closeScreen();
            return countByQuery(player, task.itemQuery) >= task.targetCount ? false
                : failCraft(player, task, "Recipe steps finished but the requested output was not confirmed");
        }
        CraftPlan.Step step = task.craftPlan.getSteps()
            .get(task.craftStepIndex);
        RecipeDefinition recipe = step.getRecipe();
        if (recipe.getStation() != dev.nottambok.gtnhaibot.client.crafting.RecipeStation.PLAYER_2X2
            && recipe.getStation() != dev.nottambok.gtnhaibot.client.crafting.RecipeStation.CRAFTING_TABLE)
            return failCraft(player, task, "No executor for recipe station " + recipe.getStationId());
        if (!prepareCraftingContainer(mc, player, task, recipe)) {
            TrackedAction action = trackedAction(task);
            return action == null || !action.isTerminal();
        }
        if (task.executionStage == CraftExecutionStage.CLEAR_GRID) {
            if (shiftClickFirstGridStack(mc, player)) return true;
            task.craftSlotIndex = 0;
            task.executionStage = CraftExecutionStage.PLACE_INGREDIENTS;
        }
        if (task.executionStage == CraftExecutionStage.PLACE_INGREDIENTS) {
            List<IngredientRequirement> slots = recipe.getSlots();
            while (task.craftSlotIndex < slots.size() && slots.get(task.craftSlotIndex) == null) task.craftSlotIndex++;
            if (task.craftSlotIndex < slots.size()) {
                IngredientRequirement ingredient = slots.get(task.craftSlotIndex);
                int targetSlot = targetGridSlot(
                    recipe,
                    task.craftSlotIndex,
                    player.openContainer instanceof ContainerPlayer);
                if (!placeIngredient(mc, player, ingredient, targetSlot)) return failCraft(
                    player,
                    task,
                    "Planned ingredient was not present in inventory for " + recipe.getId());
                task.craftSlotIndex++;
                return true;
            }
            task.phaseWaitTicks = 0;
            task.executionStage = CraftExecutionStage.WAIT_OUTPUT;
        }
        if (task.executionStage == CraftExecutionStage.WAIT_OUTPUT) {
            ItemStack output = outputSlot(player).getStack();
            if (output == null || !recipe.getOutput()
                .matches(craftingKey(output))) {
                task.phaseWaitTicks++;
                if (task.phaseWaitTicks > 20) return rejectRecipeAndReplan(mc, player, task, recipe);
                return true;
            }
            task.outputCountBefore = countCraftingKey(player, recipe.getOutput());
            mc.playerController.windowClick(player.openContainer.windowId, outputSlot(player).slotNumber, 0, 1, player);
            task.phaseWaitTicks = 4;
            task.executionStage = CraftExecutionStage.VERIFY_OUTPUT;
            return true;
        }
        if (task.executionStage == CraftExecutionStage.VERIFY_OUTPUT) {
            if (task.phaseWaitTicks-- > 0) return true;
            if (countCraftingKey(player, recipe.getOutput()) <= task.outputCountBefore)
                return failCraft(player, task, "Server did not confirm crafted output for " + recipe.getId());
            task.craftRepeatIndex++;
            if (task.craftRepeatIndex >= step.getCrafts()) {
                task.craftStepIndex++;
                task.craftRepeatIndex = 0;
            }
            task.executionStage = CraftExecutionStage.PREPARE_STATION;
            task.craftSlotIndex = 0;
            updateAction(
                task,
                "crafting",
                "Completed recipe step " + task.craftStepIndex
                    + " of "
                    + task.craftPlan.getSteps()
                        .size());
        }
        return true;
    }

    private boolean rejectRecipeAndReplan(Minecraft mc, EntityClientPlayerMP player, ClientTask task,
        RecipeDefinition recipe) {
        task.rejectedRecipeCount++;
        if (task.rejectedRecipeCount > 8) return failCraft(
            player,
            task,
            "Too many loaded crafting recipes were rejected by the server; last recipe " + recipe.getId()
                + " from "
                + recipe.getProvider());
        recipeIndex.getCatalog()
            .disable(recipe.getId());
        task.lastRejectedRecipeId = recipe.getId();
        clearOpenCraftingGrid(mc, player);
        if (player.openContainer != player.inventoryContainer) player.closeScreen();
        task.craftPlan = null;
        task.craftPhase = CraftPhase.PLANNING;
        task.phaseWaitTicks = 4;
        task.craftStepIndex = 0;
        task.craftRepeatIndex = 0;
        task.craftSlotIndex = 0;
        task.executionStage = CraftExecutionStage.PREPARE_STATION;
        updateAction(
            task,
            "replanning",
            "Server rejected " + recipe.getId() + " from " + recipe.getProvider() + "; trying the next loaded recipe");
        return true;
    }

    private String craftPlanningContext(ClientTask task) {
        return task.lastRejectedRecipeId == null ? "Planning recipe" : "Rejected " + task.lastRejectedRecipeId;
    }

    private boolean prepareCraftingContainer(Minecraft mc, EntityClientPlayerMP player, ClientTask task,
        RecipeDefinition recipe) {
        if (recipe.getStation() == dev.nottambok.gtnhaibot.client.crafting.RecipeStation.PLAYER_2X2) {
            if (player.openContainer != player.inventoryContainer) player.closeScreen();
            if (!(player.openContainer instanceof ContainerPlayer)) return false;
            if (task.executionStage == CraftExecutionStage.PREPARE_STATION)
                task.executionStage = CraftExecutionStage.CLEAR_GRID;
            return true;
        }
        if (player.openContainer instanceof ContainerWorkbench) {
            if (task.executionStage == CraftExecutionStage.PREPARE_STATION)
                task.executionStage = CraftExecutionStage.CLEAR_GRID;
            return true;
        }
        if (task.craftingTable == null) {
            task.craftingTable = nearestBlock(player, "minecraft:crafting_table");
            resetInteractionMovement(task);
            if (task.craftingTable == null) {
                failCraft(player, task, "No nearby crafting table is available for " + recipe.getId());
                return false;
            }
        }
        updateAction(
            task,
            "opening_crafting_table",
            "Opening crafting table at " + task.craftingTable.x
                + ","
                + task.craftingTable.y
                + ","
                + task.craftingTable.z);
        if (moveToInteraction(mc, player, task.craftingTable, 2.5D)) {
            if (interactionMovementTimedOut(task, player))
                failCraft(player, task, "Navigation timed out while approaching the nearby crafting table");
            return false;
        }
        resetInteractionMovement(task);
        if (!isAtInteractionPosition(mc.theWorld, player, task.craftingTable, 2.5D)) {
            failCraft(player, task, "No navigable path to nearby crafting table");
            return false;
        }
        if (task.phaseWaitTicks % 20 == 0)
            useOrPlace(mc, player, task.craftingTable.x, task.craftingTable.y, task.craftingTable.z, 1);
        task.phaseWaitTicks++;
        if (task.phaseWaitTicks > 80) {
            failCraft(player, task, "Could not open nearby crafting table");
            return false;
        }
        return false;
    }

    private BlockPos nearestBlock(EntityClientPlayerMP player, String registryId) {
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        Map<String, List<BlockPos>> blocks = mergedBlockIndex();
        for (Map.Entry<String, List<BlockPos>> entry : blocks.entrySet()) {
            if (!entry.getKey()
                .startsWith(registryId)) continue;
            for (int i = 0; i < entry.getValue()
                .size(); i++) {
                BlockPos position = entry.getValue()
                    .get(i);
                double distance = distSq(player, position);
                if (distance < closestDistance) {
                    closest = position;
                    closestDistance = distance;
                }
            }
        }
        return closest;
    }

    private boolean shiftClickFirstGridStack(Minecraft mc, EntityClientPlayerMP player) {
        int size = player.openContainer instanceof ContainerPlayer ? 4 : 9;
        for (int i = 1; i <= size; i++) {
            Slot slot = (Slot) player.openContainer.inventorySlots.get(i);
            if (slot.getStack() == null) continue;
            mc.playerController.windowClick(player.openContainer.windowId, slot.slotNumber, 0, 1, player);
            return true;
        }
        return false;
    }

    private void clearOpenCraftingGrid(Minecraft mc, EntityClientPlayerMP player) {
        if (player == null || player.openContainer == null) return;
        if (!(player.openContainer instanceof ContainerPlayer) && !(player.openContainer instanceof ContainerWorkbench))
            return;
        int size = player.openContainer instanceof ContainerPlayer ? 4 : 9;
        for (int i = 1; i <= size; i++) {
            Slot slot = (Slot) player.openContainer.inventorySlots.get(i);
            if (slot.getStack() != null)
                mc.playerController.windowClick(player.openContainer.windowId, slot.slotNumber, 0, 1, player);
        }
    }

    private int targetGridSlot(RecipeDefinition recipe, int recipeSlot, boolean playerGrid) {
        int gridWidth = playerGrid ? 2 : 3;
        if (!recipe.isShaped()) return 1 + recipeSlot;
        int row = recipeSlot / recipe.getWidth();
        int column = recipeSlot % recipe.getWidth();
        return 1 + row * gridWidth + column;
    }

    private boolean placeIngredient(Minecraft mc, EntityClientPlayerMP player, IngredientRequirement ingredient,
        int targetSlotNumber) {
        Slot source = findPlayerInventorySlot(player, ingredient);
        if (source == null) return false;
        Slot target = (Slot) player.openContainer.inventorySlots.get(targetSlotNumber);
        mc.playerController.windowClick(player.openContainer.windowId, source.slotNumber, 0, 0, player);
        for (int i = 0; i < ingredient.getCount(); i++)
            mc.playerController.windowClick(player.openContainer.windowId, target.slotNumber, 1, 0, player);
        mc.playerController.windowClick(player.openContainer.windowId, source.slotNumber, 0, 0, player);
        return true;
    }

    private Slot findPlayerInventorySlot(EntityClientPlayerMP player, IngredientRequirement ingredient) {
        List slots = player.openContainer.inventorySlots;
        for (int i = 0; i < slots.size(); i++) {
            Object object = slots.get(i);
            if (!(object instanceof Slot)) continue;
            Slot slot = (Slot) object;
            ItemStack stack = slot.getStack();
            if (slot.inventory != player.inventory || stack == null || stack.stackSize < ingredient.getCount())
                continue;
            StackKey actual = craftingKey(stack);
            for (int j = 0; j < ingredient.getAlternatives()
                .size(); j++) {
                if (ingredient.getAlternatives()
                    .get(j)
                    .matches(actual)) return slot;
            }
        }
        return null;
    }

    private Slot outputSlot(EntityClientPlayerMP player) {
        return (Slot) player.openContainer.inventorySlots.get(0);
    }

    private int countCraftingKey(EntityClientPlayerMP player, StackKey key) {
        int total = 0;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null && key.matches(craftingKey(stack))) total += stack.stackSize;
        }
        return total;
    }

    private boolean failCraft(EntityClientPlayerMP player, ClientTask task, String message) {
        TrackedAction action = trackedAction(task);
        if (action != null) action.fail(message);
        player.addChatComponentMessage(new ChatComponentText("[GTNH AI Bot] Craft failed: " + message));
        return false;
    }

    private boolean failTask(EntityClientPlayerMP player, ClientTask task, String message) {
        TrackedAction action = trackedAction(task);
        if (action != null) action.fail(message);
        player.addChatComponentMessage(new ChatComponentText("[GTNH AI Bot] Action failed: " + message));
        return false;
    }

    private void updateAction(ClientTask task, String phase, String message) {
        TrackedAction action = trackedAction(task);
        if (action != null) action.running(phase, message);
    }

    private boolean matchesQuery(ItemStack stack, String queryRaw) {
        String query = norm(queryRaw);
        if (queryRaw != null && queryRaw.indexOf(':') >= 0) return norm(stackKey(stack)).equals(query);
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
            ChestKey key = canonicalChestKey(world, te, dim);
            if (next.containsKey(key)) continue;
            IInventory inventory = (IInventory) te;
            boolean trusted = isOpenInventory(mc.thePlayer, inventory);
            ChestSnapshot previous = chestCache.get(key);
            ChestSnapshot snapshot = trusted ? snapshotOpenStorage(mc.thePlayer, world.getTotalWorldTime())
                : previous != null && previous.trusted ? previous
                    : snapshotInventory(inventory, world.getTotalWorldTime(), false);
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

    private ChestKey canonicalChestKey(World world, TileEntity tile, int dimension) {
        int x = tile.xCoord;
        int z = tile.zCoord;
        if (tile instanceof TileEntityChest) {
            int[][] neighbours = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
            for (int[] offset : neighbours) {
                TileEntity neighbour = world
                    .getTileEntity(tile.xCoord + offset[0], tile.yCoord, tile.zCoord + offset[1]);
                if (neighbour instanceof TileEntityChest
                    && world.getBlock(neighbour.xCoord, neighbour.yCoord, neighbour.zCoord)
                        == world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord)
                    && (neighbour.xCoord < x || neighbour.xCoord == x && neighbour.zCoord < z)) {
                    x = neighbour.xCoord;
                    z = neighbour.zCoord;
                }
            }
        }
        return new ChestKey(dimension, x, tile.yCoord, z);
    }

    private boolean isOpenInventory(EntityClientPlayerMP player, IInventory inventory) {
        if (player == null || player.openContainer == null) return false;
        List slots = player.openContainer.inventorySlots;
        for (int i = 0; i < slots.size(); i++) {
            Object slot = slots.get(i);
            if (slot instanceof Slot) {
                IInventory open = ((Slot) slot).inventory;
                if (open == inventory || open instanceof InventoryLargeChest
                    && ((InventoryLargeChest) open).isPartOfLargeChest(inventory)) return true;
            }
        }
        return false;
    }

    private ChestSnapshot snapshotInventory(IInventory inv, long when, boolean trusted) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            String key = stackKey(s) + ":" + s.getItemDamage();
            Integer seen = counts.get(key);
            counts.put(key, Integer.valueOf((seen == null ? 0 : seen.intValue()) + s.stackSize));
        }
        return new ChestSnapshot(counts, when, trusted);
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
                    snap = new ChestSnapshot(new HashMap<String, Integer>(), 0L, false);
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

    private boolean useOrPlace(Minecraft mc, EntityClientPlayerMP player, int x, int y, int z, int side) {
        if (mc.playerController == null) {
            return false;
        }
        Block beforeTarget = mc.theWorld.getBlock(x, y, z);
        int s = clampSide(side);
        boolean accepted = mc.playerController.onPlayerRightClick(
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
        return accepted;
    }

    private boolean moveTo(Minecraft mc, EntityClientPlayerMP p, BlockPos goal, double arriveSq) {
        if (distSq(p, goal) <= arriveSq) return false;
        BlockPos start = navigationPosition(mc.theWorld, p);
        pathRecalcCounter--;
        boolean changed = lastGoal == null || !lastGoal.equals(goal);
        if (currentPath.isEmpty() || pathRecalcCounter <= 0 || changed) {
            currentPath = AStarPathfinder.findPath(mc.theWorld, start, goal, MAX_PATH_NODES);
            pathRecalcCounter = PATH_RECALC_TICKS;
            lastGoal = goal;
        }
        BlockPos next = currentPath.peek();
        if (next == null) return false;
        if (isAtPathNode(p, next)) {
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
        if (next.y > start.y) p.motionY = 0.42D;
        return true;
    }

    private boolean moveToInteraction(Minecraft mc, EntityClientPlayerMP player, BlockPos target, double arriveSq) {
        BlockPos start = navigationPosition(mc.theWorld, player);
        if (isAtInteractionPosition(player, start, target, arriveSq)) return false;
        pathRecalcCounter--;
        boolean changed = lastGoal == null || !lastGoal.equals(target);
        if (currentPath.isEmpty() || pathRecalcCounter <= 0 || changed) {
            currentPath = AStarPathfinder.findPathToInteraction(mc.theWorld, start, target, MAX_PATH_NODES);
            pathRecalcCounter = PATH_RECALC_TICKS;
            lastGoal = target;
        }
        BlockPos next = currentPath.peek();
        if (next == null) return false;
        if (isAtPathNode(player, next)) {
            currentPath.poll();
            return true;
        }
        double dx = next.x + 0.5D - player.posX;
        double dz = next.z + 0.5D - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.0001D) {
            player.motionX = (dx / horizontal) * 0.19D;
            player.motionZ = (dz / horizontal) * 0.19D;
            player.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        }
        if (next.y > start.y) player.motionY = 0.42D;
        return true;
    }

    private BlockPos navigationPosition(World world, EntityClientPlayerMP player) {
        BlockPos raw = new BlockPos(
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ));
        BlockPos normalized = AStarPathfinder.normalizeStandPosition(world, raw);
        return normalized == null ? raw : normalized;
    }

    private boolean isAtPathNode(EntityClientPlayerMP player, BlockPos node) {
        double dx = player.posX - (node.x + 0.5D);
        double dz = player.posZ - (node.z + 0.5D);
        return dx * dx + dz * dz < 0.35D && Math.abs(player.posY - node.y) <= 1.25D;
    }

    private boolean isAtInteractionPosition(World world, EntityClientPlayerMP player, BlockPos target,
        double arriveSq) {
        BlockPos position = navigationPosition(world, player);
        return isAtInteractionPosition(player, position, target, arriveSq);
    }

    private boolean interactionMovementTimedOut(ClientTask task, EntityClientPlayerMP player) {
        task.interactionMoveTicks++;
        if (Double.isNaN(task.lastInteractionX)) task.interactionStallTicks = 0;
        else {
            double dx = player.posX - task.lastInteractionX;
            double dy = player.posY - task.lastInteractionY;
            double dz = player.posZ - task.lastInteractionZ;
            if (dx * dx + dy * dy + dz * dz > 0.0025D) task.interactionStallTicks = 0;
            else task.interactionStallTicks++;
        }
        task.lastInteractionX = player.posX;
        task.lastInteractionY = player.posY;
        task.lastInteractionZ = player.posZ;
        return task.interactionMoveTicks > MAX_INTERACTION_MOVE_TICKS || task.interactionStallTicks > 60;
    }

    private void resetInteractionMovement(ClientTask task) {
        task.interactionMoveTicks = 0;
        task.interactionStallTicks = 0;
        task.lastInteractionX = Double.NaN;
        task.lastInteractionY = Double.NaN;
        task.lastInteractionZ = Double.NaN;
    }

    private boolean isAtInteractionPosition(EntityClientPlayerMP player, BlockPos position, BlockPos target,
        double arriveSq) {
        return distSq(player, target) <= arriveSq
            || isInteractionBlock(position.x, position.y, position.z, target.x, target.y, target.z);
    }

    static boolean isInteractionBlock(int positionX, int positionY, int positionZ, int targetX, int targetY,
        int targetZ) {
        int horizontal = Math.abs(positionX - targetX) + Math.abs(positionZ - targetZ);
        return horizontal == 1 && Math.abs(positionY - targetY) <= 1;
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

    private TrackedAction trackedAction(ClientTask task) {
        return task == null || task.actionId == null ? null : trackedActions.get(task.actionId);
    }

    private String actionResponse(TrackedAction action) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("action", action.toJson());
        return response.toString();
    }

    private String norm(String in) {
        if (in == null) return "";
        String lower = in.toLowerCase();
        StringBuilder out = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':') {
                if (pendingSpace && out.length() > 0) out.append(' ');
                out.append(c);
                pendingSpace = false;
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
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

    private enum CraftPhase {
        NEW,
        PLANNING,
        SCANNING_STORAGE,
        RETRIEVING,
        EXECUTING
    }

    private enum CraftExecutionStage {
        PREPARE_STATION,
        CLEAR_GRID,
        PLACE_INGREDIENTS,
        WAIT_OUTPUT,
        VERIFY_OUTPUT
    }

    private static class ClientTask {

        private final TaskKind kind;
        private final int x, y, z, side, targetCount;
        private final String targetPlayer, itemQuery;
        private String actionId;
        private CraftPhase craftPhase = CraftPhase.NEW;
        private CraftPlan craftPlan;
        private Queue<ChestKey> storageScanQueue = new ArrayDeque<ChestKey>();
        private Map<StackKey, Integer> requiredChestItems = new HashMap<StackKey, Integer>();
        private boolean storageScanCompleted;
        private ChestKey activeChest;
        private StackKey pendingRetrieval;
        private int retrievalCountBefore;
        private int phaseWaitTicks;
        private int craftStepIndex;
        private int craftRepeatIndex;
        private int craftSlotIndex;
        private CraftExecutionStage executionStage = CraftExecutionStage.PREPARE_STATION;
        private BlockPos craftingTable;
        private int outputCountBefore;
        private int rejectedRecipeCount;
        private String lastRejectedRecipeId;
        private String craftMissing;
        private int interactionMoveTicks;
        private int interactionStallTicks;
        private double lastInteractionX = Double.NaN;
        private double lastInteractionY = Double.NaN;
        private double lastInteractionZ = Double.NaN;

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
        private final Map<StackKey, Integer> exactItems;
        private final long updatedAtTick;
        private final boolean trusted;

        private ChestSnapshot(Map<String, Integer> items, long updatedAtTick, boolean trusted) {
            this(items, new HashMap<StackKey, Integer>(), updatedAtTick, trusted);
        }

        private ChestSnapshot(Map<String, Integer> items, Map<StackKey, Integer> exactItems, long updatedAtTick,
            boolean trusted) {
            this.items = items;
            this.exactItems = exactItems;
            this.updatedAtTick = updatedAtTick;
            this.trusted = trusted;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChestSnapshot)) return false;
            ChestSnapshot s = (ChestSnapshot) o;
            return trusted == s.trusted && items.equals(s.items) && exactItems.equals(s.exactItems);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * items.hashCode() + exactItems.hashCode()) + (trusted ? 1 : 0);
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
            return findPath(world, start, goal, maxNodes, false);
        }

        private static Queue<BlockPos> findPathToInteraction(World world, BlockPos start, BlockPos target,
            int maxNodes) {
            return findPath(world, start, target, maxNodes, true);
        }

        private static Queue<BlockPos> findPath(World world, BlockPos start, BlockPos goal, int maxNodes,
            boolean interactionTarget) {
            BlockPos normalizedStart = normalizeStandPosition(world, start);
            if (normalizedStart == null) return new ArrayDeque<BlockPos>();
            start = normalizedStart;
            if (!interactionTarget) {
                BlockPos normalizedGoal = normalizeStandPosition(world, goal);
                if (normalizedGoal == null) return new ArrayDeque<BlockPos>();
                goal = normalizedGoal;
            }
            if (reached(start, goal, interactionTarget)) return new ArrayDeque<BlockPos>();
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
                if (reached(cur.pos, goal, interactionTarget)) return build(cur);
                if (closed.contains(cur.pos)) continue;
                closed.add(cur.pos);
                expanded++;
                int[][] dirs = new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                for (int i = 0; i < dirs.length; i++) {
                    int[] levels = new int[] { cur.pos.y, cur.pos.y + 1, cur.pos.y - 1 };
                    for (int j = 0; j < levels.length; j++) {
                        BlockPos next = new BlockPos(cur.pos.x + dirs[i][0], levels[j], cur.pos.z + dirs[i][1]);
                        if (!canStand(world, next) || closed.contains(next)) continue;
                        int g = cur.g + 1 + Math.abs(next.y - cur.pos.y);
                        PathNode seen = nodes.get(next);
                        if (seen == null || g < seen.g) {
                            PathNode n = new PathNode(next, cur, g, h(next, goal));
                            nodes.put(next, n);
                            open.add(n);
                        }
                    }
                }
            }
            return new ArrayDeque<BlockPos>();
        }

        private static BlockPos normalizeStandPosition(World world, BlockPos requested) {
            int[] offsets = new int[] { 0, -1, 1, -2, 2 };
            for (int i = 0; i < offsets.length; i++) {
                BlockPos candidate = new BlockPos(requested.x, requested.y + offsets[i], requested.z);
                if (canStand(world, candidate)) return candidate;
            }
            return null;
        }

        private static boolean reached(BlockPos position, BlockPos target, boolean interactionTarget) {
            if (!interactionTarget) return position.equals(target);
            return isInteractionBlock(position.x, position.y, position.z, target.x, target.y, target.z);
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
