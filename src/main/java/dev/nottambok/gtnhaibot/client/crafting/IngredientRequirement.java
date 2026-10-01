package dev.nottambok.gtnhaibot.client.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IngredientRequirement {

    private final List<StackKey> alternatives;
    private final int count;
    private final boolean consumed;

    public IngredientRequirement(List<StackKey> alternatives, int count, boolean consumed) {
        if (alternatives == null || alternatives.isEmpty()) throw new IllegalArgumentException("alternatives required");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        this.alternatives = Collections.unmodifiableList(new ArrayList<StackKey>(alternatives));
        this.count = count;
        this.consumed = consumed;
    }

    public List<StackKey> getAlternatives() {
        return alternatives;
    }

    public int getCount() {
        return count;
    }

    public boolean isConsumed() {
        return consumed;
    }
}
