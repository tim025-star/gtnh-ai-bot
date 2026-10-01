package dev.nottambok.gtnhaibot.client.crafting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

public final class LiveRecipeIndex {

    public enum State {
        NOT_STARTED,
        CRAFTING,
        FURNACE,
        GREGTECH,
        READY,
        FAILED
    }

    private static final long TICK_BUDGET_NANOS = 2_000_000L;
    private static final int MAX_RECIPES_PER_TICK = 250;

    private final RecipeCatalog catalog = new RecipeCatalog();
    private State state = State.NOT_STARTED;
    private List craftingRecipes = Collections.emptyList();
    private int craftingCursor;
    private Iterator<Map.Entry> furnaceRecipes = Collections.<Map.Entry>emptyList()
        .iterator();
    private Iterator<Map.Entry> gregTechMaps = Collections.<Map.Entry>emptyList()
        .iterator();
    private Iterator<?> gregTechRecipes = Collections.emptyList()
        .iterator();
    private String currentGregTechMap = "";
    private MessageDigest fingerprintDigest;
    private String fingerprint = "";
    private String error = "";

    public void tick() {
        if (state == State.READY || state == State.FAILED) return;
        if (state == State.NOT_STARTED) begin();
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int processed = 0;
        try {
            while (processed < MAX_RECIPES_PER_TICK && System.nanoTime() - deadline < 0L) {
                if (state == State.CRAFTING) {
                    if (craftingCursor < craftingRecipes.size()) {
                        addCraftingRecipe((IRecipe) craftingRecipes.get(craftingCursor), craftingCursor);
                        craftingCursor++;
                        processed++;
                        continue;
                    }
                    state = State.FURNACE;
                }
                if (state == State.FURNACE) {
                    if (furnaceRecipes.hasNext()) {
                        addFurnaceRecipe(furnaceRecipes.next());
                        processed++;
                        continue;
                    }
                    state = State.GREGTECH;
                }
                if (state == State.GREGTECH) {
                    if (gregTechRecipes.hasNext()) {
                        addGregTechRecipe(gregTechRecipes.next());
                        processed++;
                        continue;
                    }
                    if (beginNextGregTechMap()) continue;
                    finish();
                    return;
                }
            }
        } catch (Exception ex) {
            state = State.FAILED;
            error = ex.getClass()
                .getSimpleName() + ": "
                + safeMessage(ex);
        }
    }

    public RecipeCatalog getCatalog() {
        return catalog;
    }

    public State getState() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isReadyForCrafting() {
        return state == State.GREGTECH || state == State.READY;
    }

    public int size() {
        return catalog.size();
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getError() {
        return error;
    }

    public String describe(String itemId, int maxLength) {
        if (itemId == null || itemId.trim()
            .length() == 0) return "RECIPE item ID required";
        StackKey target = new StackKey(itemId.trim(), StackKey.WILDCARD_DAMAGE, "");
        List<RecipeDefinition> matches = catalog.find(target);
        StringBuilder out = new StringBuilder("RECIPE ").append(itemId.trim())
            .append(" candidates=")
            .append(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            RecipeDefinition recipe = matches.get(i);
            StringBuilder candidate = new StringBuilder(" | ").append(recipe.getId())
                .append(" station=")
                .append(recipe.getStationId())
                .append(" grid=")
                .append(recipe.getWidth())
                .append('x')
                .append(recipe.getHeight())
                .append(" shaped=")
                .append(recipe.isShaped())
                .append(" output=")
                .append(recipe.getOutputCount())
                .append(" inputs=");
            List<IngredientRequirement> slots = recipe.getSlots();
            for (int slot = 0; slot < slots.size(); slot++) {
                IngredientRequirement ingredient = slots.get(slot);
                if (ingredient == null) continue;
                if (candidate.charAt(candidate.length() - 1) != '=') candidate.append(',');
                candidate.append(slot)
                    .append(':')
                    .append(
                        ingredient.getAlternatives()
                            .get(0)
                            .canonical())
                    .append('x')
                    .append(ingredient.getCount());
            }
            if (out.length() + candidate.length() > maxLength) {
                out.append(" | truncated");
                break;
            }
            out.append(candidate);
        }
        return out.toString();
    }

    private void begin() {
        try {
            fingerprintDigest = MessageDigest.getInstance("SHA-256");
            craftingRecipes = CraftingManager.getInstance()
                .getRecipeList();
            Map furnace = FurnaceRecipes.smelting()
                .getSmeltingList();
            furnaceRecipes = furnace.entrySet()
                .iterator();
            gregTechMaps = loadGregTechMaps();
            state = State.CRAFTING;
        } catch (Exception ex) {
            state = State.FAILED;
            error = ex.getClass()
                .getSimpleName() + ": "
                + safeMessage(ex);
        }
    }

    private void addCraftingRecipe(IRecipe recipe, int index) {
        if (recipe == null) return;
        ItemStack recipeOutput = recipe.getRecipeOutput();
        if (!isUsableOutput(recipeOutput)) return;
        List<IngredientRequirement> slots = new ArrayList<IngredientRequirement>();
        int width;
        int height;
        boolean shaped;
        if (recipe instanceof ShapedRecipes) {
            ShapedRecipes shapedRecipe = (ShapedRecipes) recipe;
            width = shapedRecipe.recipeWidth;
            height = shapedRecipe.recipeHeight;
            shaped = true;
            addSlots(slots, shapedRecipe.recipeItems);
        } else if (recipe instanceof ShapelessRecipes) {
            ShapelessRecipes shapeless = (ShapelessRecipes) recipe;
            width = Math.min(3, shapeless.recipeItems.size());
            height = (shapeless.recipeItems.size() + 2) / 3;
            shaped = false;
            addSlots(slots, shapeless.recipeItems.toArray());
        } else if (recipe instanceof ShapedOreRecipe) {
            ShapedOreRecipe shapedOre = (ShapedOreRecipe) recipe;
            width = privateIntField(shapedOre, "width", 3);
            height = privateIntField(shapedOre, "height", 3);
            shaped = true;
            addSlots(slots, shapedOre.getInput());
        } else if (recipe instanceof ShapelessOreRecipe) {
            List input = ((ShapelessOreRecipe) recipe).getInput();
            width = Math.min(3, input.size());
            height = (input.size() + 2) / 3;
            shaped = false;
            addSlots(slots, input.toArray());
        } else {
            return;
        }
        ItemStack output = recipeOutput.copy();
        RecipeStation station = (!shaped && slots.size() <= 4) || (shaped && width <= 2 && height <= 2)
            ? RecipeStation.PLAYER_2X2
            : RecipeStation.CRAFTING_TABLE;
        RecipeDefinition definition = new RecipeDefinition(
            "crafting:" + index + ":" + stackKey(output).canonical(),
            recipe.getClass()
                .getName(),
            station,
            station == RecipeStation.PLAYER_2X2 ? "player_inventory" : "minecraft:crafting_table",
            stackKey(output),
            output.stackSize,
            slots,
            width,
            height,
            shaped,
            true);
        add(definition);
    }

    private void addFurnaceRecipe(Map.Entry entry) {
        if (!(entry.getKey() instanceof ItemStack) || !(entry.getValue() instanceof ItemStack)) return;
        ItemStack input = ((ItemStack) entry.getKey()).copy();
        ItemStack output = ((ItemStack) entry.getValue()).copy();
        if (!isUsableOutput(output)) return;
        IngredientRequirement ingredient = ingredient(input);
        RecipeDefinition definition = new RecipeDefinition(
            "furnace:" + stackKey(input).canonical() + ":" + stackKey(output).canonical(),
            "minecraft_furnace",
            RecipeStation.FURNACE,
            "minecraft:furnace",
            stackKey(output),
            output.stackSize,
            Arrays.asList(ingredient),
            1,
            1,
            false,
            false);
        add(definition);
    }

    private Iterator<Map.Entry> loadGregTechMaps() {
        try {
            Class<?> recipeMap = Class.forName("gregtech.api.recipe.RecipeMap");
            Object value = recipeMap.getField("ALL_RECIPE_MAPS")
                .get(null);
            if (value instanceof Map) return ((Map) value).entrySet()
                .iterator();
        } catch (Exception ignored) {}
        return Collections.<Map.Entry>emptyList()
            .iterator();
    }

    private boolean beginNextGregTechMap() {
        while (gregTechMaps.hasNext()) {
            Map.Entry entry = gregTechMaps.next();
            currentGregTechMap = String.valueOf(entry.getKey());
            try {
                Method getAllRecipes = entry.getValue()
                    .getClass()
                    .getMethod("getAllRecipes");
                Object value = getAllRecipes.invoke(entry.getValue());
                if (value instanceof Collection) {
                    gregTechRecipes = ((Collection) value).iterator();
                    if (gregTechRecipes.hasNext()) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void addGregTechRecipe(Object recipe) {
        if (recipe == null || booleanField(recipe, "mFakeRecipe") || !booleanField(recipe, "mEnabled")) return;
        ItemStack[] inputs = stackArrayField(recipe, "mInputs");
        ItemStack[] outputs = stackArrayField(recipe, "mOutputs");
        if (outputs.length == 0) return;
        List<IngredientRequirement> ingredients = new ArrayList<IngredientRequirement>();
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] != null) ingredients.add(ingredient(inputs[i]));
        }
        boolean hasFluidInputs = arrayLengthField(recipe, "mFluidInputs") > 0;
        int duration = intField(recipe, "mDuration");
        int eut = intField(recipe, "mEUt");
        for (int i = 0; i < outputs.length; i++) {
            ItemStack output = outputs[i];
            if (!isUsableOutput(output)) continue;
            String id = "gregtech:" + currentGregTechMap
                + ":"
                + stackKey(output).canonical()
                + ":"
                + duration
                + ":"
                + eut
                + ":"
                + i;
            RecipeDefinition definition = new RecipeDefinition(
                id,
                "gregtech_recipe_map",
                RecipeStation.GREGTECH,
                currentGregTechMap + (hasFluidInputs ? ":fluid_inputs" : ""),
                stackKey(output),
                output.stackSize,
                ingredients,
                inputs.length,
                1,
                false,
                false);
            add(definition);
        }
    }

    private void addSlots(List<IngredientRequirement> slots, Object[] raw) {
        for (int i = 0; i < raw.length; i++) slots.add(ingredient(raw[i]));
    }

    private IngredientRequirement ingredient(Object raw) {
        if (raw == null) return null;
        List<StackKey> alternatives = new ArrayList<StackKey>();
        List<ItemStack> stacks = new ArrayList<ItemStack>();
        if (raw instanceof ItemStack) stacks.add(((ItemStack) raw).copy());
        else if (raw instanceof Item) stacks.add(new ItemStack((Item) raw));
        else if (raw instanceof Block) stacks.add(new ItemStack((Block) raw));
        else if (raw instanceof List) {
            List list = (List) raw;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof ItemStack) stacks.add(((ItemStack) list.get(i)).copy());
            }
        }
        if (stacks.isEmpty()) return null;
        int count = 1;
        boolean allReusable = true;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            alternatives.add(stackKey(stack));
            count = Math.max(count, stack.stackSize);
            if (!isReusableIngredient(stack)) allReusable = false;
        }
        return new IngredientRequirement(alternatives, count, !allReusable);
    }

    static boolean isReusableIngredient(ItemStack stack) {
        try {
            if (!stack.getItem()
                .hasContainerItem(stack)) return false;
            ItemStack returned = stack.getItem()
                .getContainerItem(stack);
            if (!isUsableOutput(returned) || returned.getItem() != stack.getItem()) return false;
            return (stack.getItemDamage() == StackKey.WILDCARD_DAMAGE
                || returned.getItemDamage() == stack.getItemDamage())
                && ItemStack.areItemStackTagsEqual(stack, returned);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isUsableOutput(ItemStack stack) {
        return stack != null && stack.getItem() != null && stack.stackSize > 0;
    }

    private StackKey stackKey(ItemStack stack) {
        Object key = Item.itemRegistry.getNameForObject(stack.getItem());
        String itemId = key == null ? stack.getDisplayName() : String.valueOf(key);
        String nbt = stack.hasTagCompound() ? stack.getTagCompound()
            .toString() : "";
        return new StackKey(itemId, stack.getItemDamage(), nbt);
    }

    private void add(RecipeDefinition definition) {
        catalog.add(definition);
        fingerprintDigest.update(
            definition.getId()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void finish() {
        byte[] digest = fingerprintDigest.digest();
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) out.append(String.format("%02x", digest[i] & 0xff));
        fingerprint = out.toString();
        state = State.READY;
    }

    private ItemStack[] stackArrayField(Object object, String name) {
        try {
            Field field = object.getClass()
                .getField(name);
            Object value = field.get(object);
            return value instanceof ItemStack[] ? (ItemStack[]) value : new ItemStack[0];
        } catch (Exception ignored) {
            return new ItemStack[0];
        }
    }

    private int arrayLengthField(Object object, String name) {
        try {
            Object value = object.getClass()
                .getField(name)
                .get(object);
            return value == null ? 0 : java.lang.reflect.Array.getLength(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int intField(Object object, String name) {
        try {
            return object.getClass()
                .getField(name)
                .getInt(object);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int privateIntField(Object object, String name, int fallback) {
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(object);
            } catch (Exception ignored) {
                type = type.getSuperclass();
            }
        }
        return fallback;
    }

    private boolean booleanField(Object object, String name) {
        try {
            return object.getClass()
                .getField(name)
                .getBoolean(object);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? "unknown"
            : message.replace('\r', ' ')
                .replace('\n', ' ');
    }
}
