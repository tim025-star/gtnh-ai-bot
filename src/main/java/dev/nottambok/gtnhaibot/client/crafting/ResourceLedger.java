package dev.nottambok.gtnhaibot.client.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ResourceLedger {

    public enum Source {
        INVENTORY,
        CHEST
    }

    public static final class Withdrawal {

        private final Source source;
        private final StackKey key;
        private final int count;

        Withdrawal(Source source, StackKey key, int count) {
            this.source = source;
            this.key = key;
            this.count = count;
        }

        public Source getSource() {
            return source;
        }

        public StackKey getKey() {
            return key;
        }

        public int getCount() {
            return count;
        }
    }

    private final Map<StackKey, Integer> inventory;
    private final Map<StackKey, Integer> chests;
    private final List<Withdrawal> withdrawals;

    public ResourceLedger(Map<StackKey, Integer> inventory, Map<StackKey, Integer> chests) {
        this(
            new HashMap<StackKey, Integer>(inventory),
            new HashMap<StackKey, Integer>(chests),
            new ArrayList<Withdrawal>());
    }

    private ResourceLedger(Map<StackKey, Integer> inventory, Map<StackKey, Integer> chests,
        List<Withdrawal> withdrawals) {
        this.inventory = inventory;
        this.chests = chests;
        this.withdrawals = withdrawals;
    }

    public ResourceLedger copy() {
        return new ResourceLedger(
            new HashMap<StackKey, Integer>(inventory),
            new HashMap<StackKey, Integer>(chests),
            new ArrayList<Withdrawal>(withdrawals));
    }

    public int count(List<StackKey> alternatives) {
        return countIn(inventory, alternatives) + countIn(chests, alternatives);
    }

    public int count(StackKey key) {
        List<StackKey> one = new ArrayList<StackKey>();
        one.add(key);
        return count(one);
    }

    public int countInventory(StackKey key) {
        List<StackKey> one = new ArrayList<StackKey>();
        one.add(key);
        return countIn(inventory, one);
    }

    public int moveFromChestToInventory(StackKey key, int requested) {
        List<StackKey> one = new ArrayList<StackKey>();
        one.add(key);
        int remaining = consumeFrom(chests, one, requested, Source.CHEST);
        int moved = requested - remaining;
        if (moved > 0) addInventory(key, moved);
        return moved;
    }

    public int retainInInventory(List<StackKey> alternatives, int requested) {
        int remaining = requested - countIn(inventory, alternatives);
        if (remaining <= 0) return requested;
        List<StackKey> keys = new ArrayList<StackKey>(chests.keySet());
        for (StackKey key : keys) {
            if (remaining <= 0) break;
            if (matchesAny(alternatives, key)) remaining -= moveFromChestToInventory(key, remaining);
        }
        return requested - remaining;
    }

    public int consume(List<StackKey> alternatives, int requested) {
        int remaining = requested;
        remaining = consumeFrom(inventory, alternatives, remaining, Source.INVENTORY);
        remaining = consumeFrom(chests, alternatives, remaining, Source.CHEST);
        return requested - remaining;
    }

    public void addInventory(StackKey key, int count) {
        Integer current = inventory.get(key);
        inventory.put(key, Integer.valueOf((current == null ? 0 : current.intValue()) + count));
    }

    public List<Withdrawal> getWithdrawals() {
        return new ArrayList<Withdrawal>(withdrawals);
    }

    private int countIn(Map<StackKey, Integer> source, List<StackKey> alternatives) {
        int count = 0;
        for (Map.Entry<StackKey, Integer> entry : source.entrySet()) {
            if (matchesAny(alternatives, entry.getKey())) count += entry.getValue()
                .intValue();
        }
        return count;
    }

    private int consumeFrom(Map<StackKey, Integer> source, List<StackKey> alternatives, int requested,
        Source sourceKind) {
        int remaining = requested;
        List<StackKey> keys = new ArrayList<StackKey>(source.keySet());
        for (int i = 0; i < keys.size() && remaining > 0; i++) {
            StackKey key = keys.get(i);
            if (!matchesAny(alternatives, key)) continue;
            int available = source.get(key)
                .intValue();
            int taken = Math.min(available, remaining);
            if (taken == available) source.remove(key);
            else source.put(key, Integer.valueOf(available - taken));
            withdrawals.add(new Withdrawal(sourceKind, key, taken));
            remaining -= taken;
        }
        return remaining;
    }

    private boolean matchesAny(List<StackKey> alternatives, StackKey actual) {
        for (int i = 0; i < alternatives.size(); i++) {
            if (alternatives.get(i)
                .matches(actual)) return true;
        }
        return false;
    }
}
