package dev.nottambok.gtnhaibot.client.crafting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CraftPlanner {

    private final RecipeCatalog recipes;
    private final int maxNodes;
    private final int maxDepth;
    private final long maxNanos;

    public CraftPlanner(RecipeCatalog recipes, int maxNodes, int maxDepth, long maxNanos) {
        this.recipes = recipes;
        this.maxNodes = maxNodes;
        this.maxDepth = maxDepth;
        this.maxNanos = maxNanos;
    }

    public CraftPlan plan(StackKey target, int count, ResourceLedger resources) {
        Search search = new Search(resources.copy(), new Budget(System.nanoTime() + maxNanos));
        int inventoryShortage = count - search.ledger.countInventory(target);
        if (inventoryShortage > 0) search.ledger.moveFromChestToInventory(target, inventoryShortage);
        Failure failure = produce(target, count, search, new HashSet<String>(), 0);
        if (failure != null) return CraftPlan.failed(failure.missing, failure.message);
        return CraftPlan.feasible(search.steps, search.ledger.getWithdrawals());
    }

    private Failure produce(StackKey target, int required, Search search, Set<String> visiting, int depth) {
        int shortage = required - search.ledger.count(target);
        if (shortage <= 0) return null;
        if (depth > maxDepth) return new Failure(target, "dependency depth exceeded");
        if (!search.visit()) return new Failure(target, "planning budget exhausted");
        String visitKey = target.canonical();
        if (!visiting.add(visitKey)) return new Failure(target, "recipe cycle detected");
        try {
            List<RecipeDefinition> candidates = recipes.find(target);
            Failure lastFailure = new Failure(target, "no loaded recipe and insufficient inventory or chest stock");
            Failure unsupportedFailure = null;
            boolean sawExecutable = false;
            for (int i = 0; i < candidates.size(); i++) {
                if (!search.budget.available()) return new Failure(target, "planning budget exhausted");
                RecipeDefinition recipe = candidates.get(i);
                if (!recipe.isExecutable()) {
                    if (unsupportedFailure == null) unsupportedFailure = new Failure(
                        target,
                        "loaded recipe requires unsupported executor " + recipe.getStationId());
                    continue;
                }
                if (!search.visit()) return new Failure(target, "planning budget exhausted");
                sawExecutable = true;
                Search branch = search.copy();
                int crafts = (shortage + recipe.getOutputCount() - 1) / recipe.getOutputCount();
                Failure failure = satisfyRecipe(recipe, crafts, branch, visiting, depth + 1);
                if (failure == null) {
                    branch.ledger.addInventory(recipe.getOutput(), recipe.getOutputCount() * crafts);
                    branch.steps.add(new CraftPlan.Step(recipe, crafts));
                    search.replaceWith(branch);
                    return null;
                }
                lastFailure = failure;
            }
            return sawExecutable ? lastFailure : unsupportedFailure == null ? lastFailure : unsupportedFailure;
        } finally {
            visiting.remove(visitKey);
        }
    }

    private Failure satisfyRecipe(RecipeDefinition recipe, int crafts, Search search, Set<String> visiting, int depth) {
        List<IngredientRequirement> slots = recipe.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            IngredientRequirement ingredient = slots.get(i);
            if (ingredient == null) continue;
            int needed = ingredient.getCount() * (ingredient.isConsumed() ? crafts : 1);
            int available = search.ledger.count(ingredient.getAlternatives());
            if (available < needed) {
                Failure failure = produceAlternative(ingredient, needed - available, search, visiting, depth);
                if (failure != null) return failure;
            }
            if (ingredient.isConsumed()) {
                int consumed = search.ledger.consume(ingredient.getAlternatives(), needed);
                if (consumed < needed) return new Failure(
                    ingredient.getAlternatives()
                        .get(0),
                    "resource reservation failed");
            } else if (search.ledger.retainInInventory(ingredient.getAlternatives(), needed) < needed) {
                return new Failure(
                    ingredient.getAlternatives()
                        .get(0),
                    "reusable ingredient reservation failed");
            }
        }
        return null;
    }

    private Failure produceAlternative(IngredientRequirement ingredient, int shortage, Search search,
        Set<String> visiting, int depth) {
        Failure lastFailure = new Failure(
            ingredient.getAlternatives()
                .get(0),
            "missing ingredient");
        for (int i = 0; i < ingredient.getAlternatives()
            .size(); i++) {
            StackKey alternative = ingredient.getAlternatives()
                .get(i);
            Search branch = search.copy();
            int current = branch.ledger.count(alternative);
            Failure failure = produce(alternative, current + shortage, branch, visiting, depth);
            if (failure == null) {
                search.replaceWith(branch);
                return null;
            }
            lastFailure = failure;
        }
        return lastFailure;
    }

    private final class Search {

        private ResourceLedger ledger;
        private List<CraftPlan.Step> steps;
        private final Budget budget;

        private Search(ResourceLedger ledger, Budget budget) {
            this(ledger, new ArrayList<CraftPlan.Step>(), budget);
        }

        private Search(ResourceLedger ledger, List<CraftPlan.Step> steps, Budget budget) {
            this.ledger = ledger;
            this.steps = steps;
            this.budget = budget;
        }

        private Search copy() {
            return new Search(ledger.copy(), new ArrayList<CraftPlan.Step>(steps), budget);
        }

        private boolean visit() {
            return budget.visit();
        }

        private void replaceWith(Search branch) {
            ledger = branch.ledger;
            steps = branch.steps;
        }
    }

    private final class Budget {

        private final long deadline;
        private int nodes;

        private Budget(long deadline) {
            this.deadline = deadline;
        }

        private boolean available() {
            return nodes <= maxNodes && System.nanoTime() - deadline < 0L;
        }

        private boolean visit() {
            nodes++;
            return available();
        }
    }

    private static final class Failure {

        private final StackKey missing;
        private final String message;

        private Failure(StackKey missing, String message) {
            this.missing = missing;
            this.message = message;
        }
    }
}
