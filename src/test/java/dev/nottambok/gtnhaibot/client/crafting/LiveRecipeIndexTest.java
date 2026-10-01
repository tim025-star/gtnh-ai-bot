package dev.nottambok.gtnhaibot.client.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

class LiveRecipeIndexTest {

    @Test
    void rejectsPlaceholderOutputsWithoutFailingTheCatalog() {
        ItemStack placeholder = new ItemStack(new Item(), 0);
        ItemStack output = new ItemStack(new Item(), 2);

        assertFalse(LiveRecipeIndex.isUsableOutput(null));
        assertFalse(LiveRecipeIndex.isUsableOutput(placeholder));
        assertTrue(LiveRecipeIndex.isUsableOutput(output));
    }

    @Test
    void aContainerItemIsReusableOnlyWhenItReturnsTheSameIngredient() {
        final Item empty = new Item();
        Item filled = new Item() {

            @Override
            public boolean hasContainerItem(ItemStack stack) {
                return true;
            }

            @Override
            public ItemStack getContainerItem(ItemStack stack) {
                return new ItemStack(empty);
            }
        };
        Item tool = new Item() {

            @Override
            public boolean hasContainerItem(ItemStack stack) {
                return true;
            }

            @Override
            public ItemStack getContainerItem(ItemStack stack) {
                return stack.copy();
            }
        };

        assertFalse(LiveRecipeIndex.isReusableIngredient(new ItemStack(filled)));
        assertTrue(LiveRecipeIndex.isReusableIngredient(new ItemStack(tool)));
    }
}
