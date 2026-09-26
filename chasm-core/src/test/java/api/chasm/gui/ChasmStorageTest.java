package api.chasm.gui;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存储来源：临时归还 / 外部容器 / 物品自带容器（写回与清空）。 */
class ChasmStorageTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void transientStorageKeepsLegacyBehavior() {
		ChasmStorage storage = ChasmStorage.transientStorage(3);
		assertEquals(3, storage.container().getContainerSize());
		assertTrue(storage.returnsItemsOnClose(), "临时存储仍应把物品归还玩家（向后兼容）");
	}

	@Test
	void externalContainerIsUsedDirectly() {
		SimpleContainer backend = new SimpleContainer(2);
		ChasmStorage storage = ChasmStorage.of(backend);
		assertSame(backend, storage.container(), "必须直接用调用方给的容器（方块实体等）");
		assertFalse(storage.returnsItemsOnClose(), "外部容器的内容不归还玩家");
		storage.container().setItem(0, new ItemStack(Items.DIAMOND, 4));
		assertEquals(4, backend.getItem(0).getCount(), "界面写入即后端写入（持久化由后端负责）");
	}

	@Test
	void itemStorageLoadsExistingContents() {
		ItemStack box = new ItemStack(Items.CHEST);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
			List.of(new ItemStack(Items.DIAMOND, 3), ItemStack.EMPTY)));
		ChasmStorage storage = ChasmStorage.ofItem(box, 2);
		assertEquals(3, storage.container().getItem(0).getCount(), "应载入物品里已有的内容");
		assertTrue(storage.container().getItem(1).isEmpty());
		assertFalse(storage.returnsItemsOnClose());
	}

	@Test
	void itemStorageWritesBackOnClose() {
		ItemStack box = new ItemStack(Items.CHEST);
		ChasmStorage storage = ChasmStorage.ofItem(box, 2);
		storage.container().setItem(0, new ItemStack(Items.DIAMOND, 5));
		storage.container().setItem(1, new ItemStack(Items.EMERALD, 2));
		storage.onClosed(null);   // null 玩家 = 只落盘，跳过"是否还在身上"的判断

		// 重新打开：内容必须还在（这就是"关窗后物品不再跑回背包"）
		ChasmStorage reopened = ChasmStorage.ofItem(box, 2);
		assertEquals(Items.DIAMOND, reopened.container().getItem(0).getItem());
		assertEquals(5, reopened.container().getItem(0).getCount());
		assertEquals(2, reopened.container().getItem(1).getCount());
	}

	@Test
	void itemStorageRemovesComponentWhenEmpty() {
		ItemStack box = new ItemStack(Items.CHEST);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
		ChasmStorage storage = ChasmStorage.ofItem(box, 1);
		storage.container().setItem(0, ItemStack.EMPTY);
		storage.onClosed(null);
		assertNull(box.get(DataComponents.CONTAINER), "空容器不应留下空组件（物品保持干净/可堆叠）");
	}

	@Test
	void itemStorageSkipsWriteWhenUnchanged() {
		ItemStack box = new ItemStack(Items.CHEST);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 7))));
		ItemContainerContents before = box.get(DataComponents.CONTAINER);
		ChasmStorage storage = ChasmStorage.ofItem(box, 1);
		storage.onClosed(null);   // 没有任何修改
		assertSame(before, box.get(DataComponents.CONTAINER), "内容没变就不该写组件（避免无谓的物品同步）");
	}

	@Test
	void invalidInputsFailFast() {
		assertThrows(IllegalArgumentException.class, () -> ChasmStorage.of(null));
		assertThrows(IllegalArgumentException.class, () -> ChasmStorage.ofItem(ItemStack.EMPTY, 3));
	}
}
