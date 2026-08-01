package dev.nottambok.gtnhaibot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.WorldServer;

final class MachineAdapters {

    private final List<MachineAdapter> adapters = new ArrayList<MachineAdapter>();

    MachineAdapters() {
        adapters.add(new FurnaceAdapter());
        adapters.add(new SidedInventoryAdapter());
        adapters.add(new GenericInventoryAdapter());
    }

    boolean tryInteract(EntityPlayerMP actor, WorldServer world, int x, int y, int z, int side) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) return false;
        Block block = world.getBlock(x, y, z);
        InteractionContext context = new InteractionContext(actor, world, tile, block, side);
        for (int i = 0; i < adapters.size(); i++) {
            MachineAdapter adapter = adapters.get(i);
            if (adapter.matches(context) && adapter.interact(context)) {
                context.markDirty();
                return true;
            }
        }
        return false;
    }

    private interface MachineAdapter {

        boolean matches(InteractionContext context);

        boolean interact(InteractionContext context);
    }

    private static class InteractionContext {

        private final EntityPlayerMP actor;
        private final WorldServer world;
        private final TileEntity tile;
        private final Block block;
        private final int side;
        private boolean dirty;

        private InteractionContext(EntityPlayerMP actor, WorldServer world, TileEntity tile, Block block, int side) {
            this.actor = actor;
            this.world = world;
            this.tile = tile;
            this.block = block;
            this.side = side;
        }

        private ItemStack held() {
            return actor.getCurrentEquippedItem();
        }

        private boolean isInsertMode() {
            ItemStack held = held();
            return held != null && held.stackSize > 0;
        }

        private void markDirty() {
            if (dirty) return;
            dirty = true;
            if (tile != null) tile.markDirty();
            actor.inventoryContainer.detectAndSendChanges();
            world.markBlockForUpdate(tile.xCoord, tile.yCoord, tile.zCoord);
        }
    }

    private static class FurnaceAdapter implements MachineAdapter {

        @Override
        public boolean matches(InteractionContext context) {
            return context.tile instanceof TileEntityFurnace;
        }

        @Override
        public boolean interact(InteractionContext context) {
            TileEntityFurnace furnace = (TileEntityFurnace) context.tile;
            if (context.isInsertMode()) {
                ItemStack held = context.held();
                boolean isFuel = TileEntityFurnace.isItemFuel(held);
                boolean isSmeltable = FurnaceRecipes.smelting()
                    .getSmeltingResult(held) != null;
                if (isFuel && tryInsert(furnace, 1, held, false, context.side)) return true;
                if (isSmeltable && tryInsert(furnace, 0, held, false, context.side)) return true;
                if (tryInsert(furnace, 0, held, false, context.side)) return true;
                return tryInsert(furnace, 1, held, false, context.side);
            }
            return tryExtract(context.actor, furnace, 2, false, context.side);
        }
    }

    private static class SidedInventoryAdapter implements MachineAdapter {

        @Override
        public boolean matches(InteractionContext context) {
            return context.tile instanceof ISidedInventory;
        }

        @Override
        public boolean interact(InteractionContext context) {
            ISidedInventory inv = (ISidedInventory) context.tile;
            int[] slots = inv.getAccessibleSlotsFromSide(context.side);
            if (slots == null || slots.length == 0) return false;
            if (context.isInsertMode()) {
                ItemStack held = context.held();
                for (int i = 0; i < slots.length; i++) {
                    if (tryInsert(inv, slots[i], held, true, context.side)) return true;
                }
                return false;
            }
            for (int i = 0; i < slots.length; i++) {
                if (tryExtract(context.actor, inv, slots[i], true, context.side)) return true;
            }
            return false;
        }
    }

    private static class GenericInventoryAdapter implements MachineAdapter {

        @Override
        public boolean matches(InteractionContext context) {
            return context.tile instanceof IInventory;
        }

        @Override
        public boolean interact(InteractionContext context) {
            IInventory inv = (IInventory) context.tile;
            if (context.isInsertMode()) {
                ItemStack held = context.held();
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    if (tryInsert(inv, i, held, false, context.side)) return true;
                }
                return false;
            }
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                if (tryExtract(context.actor, inv, i, false, context.side)) return true;
            }
            return false;
        }
    }

    private static boolean tryInsert(IInventory inv, int slot, ItemStack held, boolean sided, int side) {
        if (held == null || held.stackSize <= 0) return false;
        if (slot < 0 || slot >= inv.getSizeInventory()) return false;
        if (sided && inv instanceof ISidedInventory && !((ISidedInventory) inv).canInsertItem(slot, held, side)) {
            return false;
        }
        if (!inv.isItemValidForSlot(slot, held)) return false;
        ItemStack existing = inv.getStackInSlot(slot);
        int slotLimit = Math.min(inv.getInventoryStackLimit(), held.getMaxStackSize());
        if (existing == null) {
            int move = Math.min(slotLimit, held.stackSize);
            if (move <= 0) return false;
            ItemStack moved = held.copy();
            moved.stackSize = move;
            inv.setInventorySlotContents(slot, moved);
            held.stackSize -= move;
            if (held.stackSize <= 0) held.stackSize = 0;
            return true;
        }
        if (!canStack(existing, held)) return false;
        int stackLimit = Math.min(inv.getInventoryStackLimit(), existing.getMaxStackSize());
        int room = stackLimit - existing.stackSize;
        if (room <= 0) return false;
        int move = Math.min(room, held.stackSize);
        existing.stackSize += move;
        inv.setInventorySlotContents(slot, existing);
        held.stackSize -= move;
        if (held.stackSize <= 0) held.stackSize = 0;
        return move > 0;
    }

    private static boolean tryExtract(EntityPlayerMP actor, IInventory inv, int slot, boolean sided, int side) {
        if (slot < 0 || slot >= inv.getSizeInventory()) return false;
        ItemStack inSlot = inv.getStackInSlot(slot);
        if (inSlot == null || inSlot.stackSize <= 0) return false;
        if (sided && inv instanceof ISidedInventory && !((ISidedInventory) inv).canExtractItem(slot, inSlot, side)) {
            return false;
        }
        ItemStack hand = actor.getCurrentEquippedItem();
        if (hand == null) {
            ItemStack moved = inSlot.copy();
            int limit = moved.getMaxStackSize();
            if (moved.stackSize > limit) moved.stackSize = limit;
            actor.inventory.mainInventory[actor.inventory.currentItem] = moved;
            inSlot.stackSize -= moved.stackSize;
            if (inSlot.stackSize <= 0) {
                inv.setInventorySlotContents(slot, null);
            } else {
                inv.setInventorySlotContents(slot, inSlot);
            }
            return true;
        }
        if (!canStack(hand, inSlot)) return false;
        int room = hand.getMaxStackSize() - hand.stackSize;
        if (room <= 0) return false;
        int move = Math.min(room, inSlot.stackSize);
        hand.stackSize += move;
        actor.inventory.mainInventory[actor.inventory.currentItem] = hand;
        inSlot.stackSize -= move;
        if (inSlot.stackSize <= 0) {
            inv.setInventorySlotContents(slot, null);
        } else {
            inv.setInventorySlotContents(slot, inSlot);
        }
        return move > 0;
    }

    private static boolean canStack(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getItem() != b.getItem()) return false;
        if (a.getItemDamage() != b.getItemDamage()) return false;
        return ItemStack.areItemStackTagsEqual(a, b);
    }
}
