package dev.nottambok.gtnhaibot.client.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class CraftPlannerTest {

    private static final StackKey LOG = key("minecraft:log");
    private static final StackKey PLANK = key("minecraft:planks");
    private static final StackKey STICK = key("minecraft:stick");

    @Test
    void resolvesInventoryThenChestThenRecursiveRecipes() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("planks", PLANK, 4, ingredient(LOG, 1)));
        catalog.add(recipe("sticks", STICK, 4, ingredient(PLANK, 2)));
        Map<StackKey, Integer> inventory = stock(PLANK, 1);
        Map<StackKey, Integer> chests = stock(LOG, 1);

        CraftPlan plan = planner(catalog).plan(STICK, 4, new ResourceLedger(inventory, chests));

        assertTrue(plan.isFeasible());
        assertEquals(
            2,
            plan.getSteps()
                .size());
        assertEquals(
            "planks",
            plan.getSteps()
                .get(0)
                .getRecipe()
                .getId());
        assertEquals(
            "sticks",
            plan.getSteps()
                .get(1)
                .getRecipe()
                .getId());
        assertTrue(hasChestWithdrawal(plan, LOG, 1));
    }

    @Test
    void usesRecipeOutputCountsForMultipleCrafts() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("sticks", STICK, 4, ingredient(PLANK, 2)));

        CraftPlan plan = planner(catalog).plan(STICK, 8, new ResourceLedger(stock(PLANK, 4), emptyStock()));

        assertTrue(plan.isFeasible());
        assertEquals(
            2,
            plan.getSteps()
                .get(0)
                .getCrafts());
    }

    @Test
    void failedCandidateDoesNotConsumeResourcesUsedByNextCandidate() {
        StackKey missing = key("test:missing");
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("bad", STICK, 1, ingredient(PLANK, 1), ingredient(missing, 1)));
        catalog.add(recipe("good", STICK, 1, ingredient(PLANK, 1)));

        CraftPlan plan = planner(catalog).plan(STICK, 1, new ResourceLedger(stock(PLANK, 1), emptyStock()));

        assertTrue(plan.isFeasible());
        assertEquals(
            "good",
            plan.getSteps()
                .get(0)
                .getRecipe()
                .getId());
    }

    @Test
    void disabledServerRejectedRecipeIsSkippedOnReplan() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("rejected", STICK, 1, ingredient(PLANK, 1)));
        catalog.add(recipe("accepted", STICK, 1, ingredient(PLANK, 1)));
        catalog.disable("rejected");

        CraftPlan plan = planner(catalog).plan(STICK, 1, new ResourceLedger(stock(PLANK, 1), emptyStock()));

        assertTrue(plan.isFeasible());
        assertEquals(
            "accepted",
            plan.getSteps()
                .get(0)
                .getRecipe()
                .getId());
    }

    @Test
    void detectsRecipeCyclesWithoutMutatingAnything() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("plank-from-stick", PLANK, 1, ingredient(STICK, 1)));
        catalog.add(recipe("stick-from-plank", STICK, 1, ingredient(PLANK, 1)));

        CraftPlan plan = planner(catalog).plan(STICK, 1, new ResourceLedger(emptyStock(), emptyStock()));

        assertFalse(plan.isFeasible());
        assertTrue(
            plan.getFailure()
                .contains("cycle"));
        assertTrue(
            plan.getSteps()
                .isEmpty());
    }

    @Test
    void retrievesFinishedTargetFromChestInsteadOfCraftingAnother() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("sticks", STICK, 4, ingredient(PLANK, 2)));

        CraftPlan plan = planner(catalog).plan(STICK, 1, new ResourceLedger(emptyStock(), stock(STICK, 1)));

        assertTrue(plan.isFeasible());
        assertTrue(
            plan.getSteps()
                .isEmpty());
        assertTrue(hasChestWithdrawal(plan, STICK, 1));
    }

    @Test
    void reportsMissingIngredientInsteadOfLaterUnsupportedAlternative() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("manual", STICK, 1, ingredient(PLANK, 1)));
        catalog.add(
            new RecipeDefinition(
                "machine",
                "test",
                RecipeStation.GREGTECH,
                "gt.recipe.assembler",
                STICK,
                1,
                Arrays.asList(ingredient(LOG, 1)),
                1,
                1,
                false,
                false));

        CraftPlan plan = planner(catalog).plan(STICK, 1, new ResourceLedger(emptyStock(), emptyStock()));

        assertFalse(plan.isFeasible());
        assertEquals(PLANK, plan.getMissing());
        assertFalse(
            plan.getFailure()
                .contains("unsupported executor"));
    }

    @Test
    void retrievesOneReusableToolForMultipleCrafts() {
        StackKey tool = key("test:tool");
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(
            recipe("sticks", STICK, 1, ingredient(PLANK, 1), new IngredientRequirement(Arrays.asList(tool), 1, false)));

        CraftPlan plan = planner(catalog).plan(STICK, 3, new ResourceLedger(stock(PLANK, 3), stock(tool, 1)));

        assertTrue(plan.isFeasible());
        assertTrue(hasChestWithdrawal(plan, tool, 1));
        assertEquals(
            3,
            plan.getSteps()
                .get(0)
                .getCrafts());
    }

    @Test
    void countsSearchBudgetAcrossRejectedBranches() {
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("bad-one", STICK, 1, ingredient(key("test:missing-one"), 1)));
        catalog.add(recipe("bad-two", STICK, 1, ingredient(key("test:missing-two"), 1)));
        catalog.add(recipe("good", STICK, 1, ingredient(PLANK, 1)));

        CraftPlan plan = new CraftPlanner(catalog, 2, 10, TimeUnit.SECONDS.toNanos(1))
            .plan(STICK, 1, new ResourceLedger(stock(PLANK, 1), emptyStock()));

        assertFalse(plan.isFeasible());
        assertTrue(
            plan.getFailure()
                .contains("budget"));
    }

    @Test
    void doesNotUseAnOutputWithTheWrongNbt() {
        StackKey taggedStick = new StackKey("minecraft:stick", 0, "{variant:1}");
        RecipeCatalog catalog = new RecipeCatalog();
        catalog.add(recipe("plain", STICK, 1, ingredient(PLANK, 1)));

        CraftPlan plan = planner(catalog).plan(taggedStick, 1, new ResourceLedger(stock(PLANK, 1), emptyStock()));

        assertFalse(plan.isFeasible());
    }

    private CraftPlanner planner(RecipeCatalog catalog) {
        return new CraftPlanner(catalog, 100, 10, TimeUnit.SECONDS.toNanos(1));
    }

    private static RecipeDefinition recipe(String id, StackKey output, int outputCount,
        IngredientRequirement... ingredients) {
        List<IngredientRequirement> slots = new ArrayList<IngredientRequirement>();
        slots.addAll(Arrays.asList(ingredients));
        return new RecipeDefinition(
            id,
            "test",
            RecipeStation.CRAFTING_TABLE,
            "minecraft:crafting_table",
            output,
            outputCount,
            slots,
            3,
            3,
            false,
            true);
    }

    private static IngredientRequirement ingredient(StackKey key, int count) {
        return new IngredientRequirement(Arrays.asList(key), count, true);
    }

    private static StackKey key(String itemId) {
        return new StackKey(itemId, 0, "");
    }

    private static Map<StackKey, Integer> stock(StackKey key, int count) {
        Map<StackKey, Integer> stock = new HashMap<StackKey, Integer>();
        stock.put(key, Integer.valueOf(count));
        return stock;
    }

    private static Map<StackKey, Integer> emptyStock() {
        return new HashMap<StackKey, Integer>();
    }

    private static boolean hasChestWithdrawal(CraftPlan plan, StackKey key, int count) {
        for (ResourceLedger.Withdrawal withdrawal : plan.getWithdrawals()) {
            if (withdrawal.getSource() == ResourceLedger.Source.CHEST && withdrawal.getKey()
                .equals(key) && withdrawal.getCount() == count) return true;
        }
        return false;
    }
}
