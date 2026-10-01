package dev.nottambok.gtnhaibot.client.crafting;

public final class StackKey {

    public static final int WILDCARD_DAMAGE = 32767;

    private final String itemId;
    private final int damage;
    private final String nbt;

    public StackKey(String itemId, int damage, String nbt) {
        if (itemId == null || itemId.trim()
            .length() == 0) throw new IllegalArgumentException("itemId is required");
        this.itemId = itemId.trim();
        this.damage = damage;
        this.nbt = nbt == null ? "" : nbt;
    }

    public String getItemId() {
        return itemId;
    }

    public int getDamage() {
        return damage;
    }

    public String getNbt() {
        return nbt;
    }

    public boolean matches(StackKey actual) {
        if (actual == null || !itemId.equals(actual.itemId)) return false;
        if (damage != WILDCARD_DAMAGE && actual.damage != WILDCARD_DAMAGE && damage != actual.damage) return false;
        return nbt.length() == 0 || nbt.equals(actual.nbt);
    }

    public String canonical() {
        return itemId + "@" + damage + (nbt.length() == 0 ? "" : "#" + nbt);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StackKey)) return false;
        StackKey key = (StackKey) other;
        return damage == key.damage && itemId.equals(key.itemId) && nbt.equals(key.nbt);
    }

    @Override
    public int hashCode() {
        int result = itemId.hashCode();
        result = 31 * result + damage;
        result = 31 * result + nbt.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return canonical();
    }
}
