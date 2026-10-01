package dev.nottambok.gtnhaibot.client.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CraftPlan {

    public static final class Step {

        private final RecipeDefinition recipe;
        private final int crafts;

        Step(RecipeDefinition recipe, int crafts) {
            this.recipe = recipe;
            this.crafts = crafts;
        }

        public RecipeDefinition getRecipe() {
            return recipe;
        }

        public int getCrafts() {
            return crafts;
        }
    }

    private final boolean feasible;
    private final List<Step> steps;
    private final List<ResourceLedger.Withdrawal> withdrawals;
    private final StackKey missing;
    private final String failure;

    private CraftPlan(boolean feasible, List<Step> steps, List<ResourceLedger.Withdrawal> withdrawals, StackKey missing,
        String failure) {
        this.feasible = feasible;
        this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
        this.withdrawals = Collections.unmodifiableList(new ArrayList<ResourceLedger.Withdrawal>(withdrawals));
        this.missing = missing;
        this.failure = failure;
    }

    static CraftPlan feasible(List<Step> steps, List<ResourceLedger.Withdrawal> withdrawals) {
        return new CraftPlan(true, steps, withdrawals, null, null);
    }

    static CraftPlan failed(StackKey missing, String failure) {
        return new CraftPlan(
            false,
            Collections.<Step>emptyList(),
            Collections.<ResourceLedger.Withdrawal>emptyList(),
            missing,
            failure);
    }

    public boolean isFeasible() {
        return feasible;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public List<ResourceLedger.Withdrawal> getWithdrawals() {
        return withdrawals;
    }

    public StackKey getMissing() {
        return missing;
    }

    public String getFailure() {
        return failure;
    }
}
