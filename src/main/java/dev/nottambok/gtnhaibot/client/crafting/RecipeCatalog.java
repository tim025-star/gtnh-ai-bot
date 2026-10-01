package dev.nottambok.gtnhaibot.client.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecipeCatalog {

    private final Map<String, List<RecipeDefinition>> recipesByItem = new HashMap<String, List<RecipeDefinition>>();
    private final Set<String> disabledRecipeIds = new HashSet<String>();

    public void add(RecipeDefinition recipe) {
        String itemId = recipe.getOutput()
            .getItemId();
        List<RecipeDefinition> recipes = recipesByItem.get(itemId);
        if (recipes == null) {
            recipes = new ArrayList<RecipeDefinition>();
            recipesByItem.put(itemId, recipes);
        }
        recipes.add(recipe);
    }

    public List<RecipeDefinition> find(StackKey output) {
        List<RecipeDefinition> candidates = recipesByItem.get(output.getItemId());
        if (candidates == null) return Collections.emptyList();
        List<RecipeDefinition> matches = new ArrayList<RecipeDefinition>();
        for (int i = 0; i < candidates.size(); i++) {
            RecipeDefinition recipe = candidates.get(i);
            if (disabledRecipeIds.contains(recipe.getId())) continue;
            if (output.matches(recipe.getOutput())) matches.add(recipe);
        }
        return matches;
    }

    public void disable(String recipeId) {
        if (recipeId != null) disabledRecipeIds.add(recipeId);
    }

    public int size() {
        int total = 0;
        for (List<RecipeDefinition> recipes : recipesByItem.values()) total += recipes.size();
        return total;
    }
}
