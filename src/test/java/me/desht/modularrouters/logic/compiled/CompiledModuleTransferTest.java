package me.desht.modularrouters.logic.compiled;

import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.item.augment.ItemAugment;
import me.desht.modularrouters.logic.filter.Filter;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.junit.BeforeClass;
import org.junit.Test;
import sun.misc.Unsafe;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CompiledModuleTransferTest {
    private static final Item TEST_ITEM = new Item();

    @BeforeClass
    public static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    public void transferUsesStableSlotCountWhenHandlerBecomesEmpty() throws Exception {
        TestCompiledModule module = createCompiledModule();
        TestRouter router = new TestRouter();
        SingleSlotHandler handler = new SingleSlotHandler(1, true);

        assertEquals(1, module.transferToRouter(handler, router));
        assertEquals(0, handler.getSlots());
        assertTrue(handler.getStackInSlot(0).isEmpty());
        assertEquals(1, router.peekBuffer(1).getCount());
    }

    @Test
    public void zeroSlotHandlerIsIgnoredWithoutSlotAccess() throws Exception {
        TestCompiledModule module = createCompiledModule();
        TestRouter router = new TestRouter();
        ZeroSlotHandler handler = new ZeroSlotHandler();

        assertEquals(0, module.transferToRouter(handler, router));
        assertEquals(0, handler.slotAccesses);
        assertTrue(router.peekBuffer(1).isEmpty());
    }

    @Test
    public void staleSearchPositionIsNormalizedAfterInventoryShrinks() throws Exception {
        TestCompiledModule module = createCompiledModule();
        setField(module, "lastMatchPos", 5);
        TestRouter router = new TestRouter();
        SingleSlotHandler handler = new SingleSlotHandler(1, false);

        assertEquals(1, module.transferToRouter(handler, router));
        assertEquals(0, handler.lastAccessedSlot);
    }

    @Test
    public void fixedSlotHandlerRetainsNormalTransferBehaviour() throws Exception {
        TestCompiledModule module = createCompiledModule();
        TestRouter router = new TestRouter();
        SingleSlotHandler handler = new SingleSlotHandler(3, false);

        assertEquals(1, module.transferToRouter(handler, router));
        assertEquals(2, handler.getStackInSlot(0).getCount());
        assertEquals(1, router.peekBuffer(1).getCount());
    }

    private static TestCompiledModule createCompiledModule() throws Exception {
        TestCompiledModule module = (TestCompiledModule) getUnsafe().allocateInstance(TestCompiledModule.class);
        ItemAugment.AugmentCounter augmentCounter =
                (ItemAugment.AugmentCounter) getUnsafe().allocateInstance(ItemAugment.AugmentCounter.class);
        setField(augmentCounter, ItemAugment.AugmentCounter.class, "counts",
                new int[ItemAugment.AugmentType.values().length]);
        setField(module, "filter", new Filter());
        setField(module, "augmentCounter", augmentCounter);
        return module;
    }

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        setField(target, CompiledModule.class, name, value);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class TestCompiledModule extends CompiledModule {
        private TestCompiledModule() {
            super(null, ItemStack.EMPTY);
        }

        @Override
        public boolean execute(TileEntityItemRouter router) {
            return false;
        }
    }

    private static class TestRouter extends TileEntityItemRouter {
        private ItemStack buffer = ItemStack.EMPTY;

        @Override
        public int getItemsPerTick() {
            return 1;
        }

        @Override
        public boolean isBufferFull() {
            return !buffer.isEmpty() && buffer.getCount() >= buffer.getMaxStackSize();
        }

        @Override
        public ItemStack peekBuffer(int amount) {
            return buffer.isEmpty() ? ItemStack.EMPTY : ItemHandlerHelper.copyStackWithSize(buffer, amount);
        }

        @Override
        public ItemStack insertBuffer(ItemStack stack) {
            if (!buffer.isEmpty() && !ItemHandlerHelper.canItemStacksStack(buffer, stack)) {
                return stack;
            }

            int current = buffer.isEmpty() ? 0 : buffer.getCount();
            int inserted = Math.min(stack.getCount(), stack.getMaxStackSize() - current);
            if (inserted <= 0) {
                return stack;
            }

            if (buffer.isEmpty()) {
                buffer = ItemHandlerHelper.copyStackWithSize(stack, inserted);
            } else {
                buffer.grow(inserted);
            }

            ItemStack remainder = stack.copy();
            remainder.shrink(inserted);
            return remainder;
        }
    }

    private static class SingleSlotHandler implements IItemHandler {
        private ItemStack stack;
        private final boolean hideSlotWhenEmpty;
        private int lastAccessedSlot = -1;

        private SingleSlotHandler(int count, boolean hideSlotWhenEmpty) {
            stack = new ItemStack(TEST_ITEM, count);
            this.hideSlotWhenEmpty = hideSlotWhenEmpty;
        }

        @Override
        public int getSlots() {
            return hideSlotWhenEmpty && stack.isEmpty() ? 0 : 1;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            checkSlot(slot);
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            checkSlot(slot);
            return stack;
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            checkSlot(slot);
            if (stack.isEmpty() || amount <= 0) {
                return ItemStack.EMPTY;
            }

            int extracted = Math.min(amount, stack.getCount());
            ItemStack result = ItemHandlerHelper.copyStackWithSize(stack, extracted);
            if (!simulate) {
                stack.shrink(extracted);
            }
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            checkSlot(slot);
            return 64;
        }

        private void checkSlot(int slot) {
            if (slot != 0) {
                throw new IndexOutOfBoundsException("slot " + slot);
            }
            lastAccessedSlot = slot;
        }
    }

    private static class ZeroSlotHandler implements IItemHandler {
        private int slotAccesses;

        @Override
        public int getSlots() {
            return 0;
        }

        @Nonnull
        @Override
        public ItemStack getStackInSlot(int slot) {
            slotAccesses++;
            throw new AssertionError("zero-slot handler was accessed");
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            slotAccesses++;
            throw new AssertionError("zero-slot handler was accessed");
        }

        @Nonnull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            slotAccesses++;
            throw new AssertionError("zero-slot handler was accessed");
        }

        @Override
        public int getSlotLimit(int slot) {
            slotAccesses++;
            throw new AssertionError("zero-slot handler was accessed");
        }
    }
}
