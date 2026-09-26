package api.chasm.item;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 创造模式标签页：命名空间收录、去重、图标回退、冻结注册表下的安全性。 */
class ChasmCreativeTabBuilderTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void collectsItemsByNamespace() {
		List<Item> vanilla = ChasmCreativeTabBuilder.itemsOfNamespace("minecraft");
		assertFalse(vanilla.isEmpty());
		assertTrue(vanilla.contains(Items.DIAMOND));
		assertTrue(vanilla.contains(Items.DIAMOND_SWORD));
		for (Item item : vanilla) {
			assertEquals("minecraft", BuiltInRegistries.ITEM.getKey(item).getNamespace());
		}
		// 排序稳定（按路径），便于玩家查找
		String first = BuiltInRegistries.ITEM.getKey(vanilla.get(0)).getPath();
		String second = BuiltInRegistries.ITEM.getKey(vanilla.get(1)).getPath();
		assertTrue(first.compareTo(second) <= 0, "收录顺序应按 id 排序");
	}

	@Test
	void unknownNamespaceIsEmpty() {
		assertTrue(ChasmCreativeTabBuilder.itemsOfNamespace("no_such_mod_namespace").isEmpty());
	}

	@Test
	void autoAllAndExplicitAddsAreDeduplicated() {
		ChasmCreativeTabBuilder builder = new ChasmCreativeTabBuilder("minecraft", "test_tab")
			.autoAll()
			.add(Items.DIAMOND);   // 已在 autoAll 里 → 不应重复
		long diamonds = builder.collected().stream()
			.filter(s -> s.getItem() == Items.DIAMOND).count();
		assertEquals(1, diamonds, "重复物品应去重");
		assertEquals(ChasmCreativeTabBuilder.itemsOfNamespace("minecraft").size(), builder.collected().size());
	}

	@Test
	void explicitOnlyTabWorks() {
		List<net.minecraft.world.item.ItemStack> stacks = new ChasmCreativeTabBuilder("mymod", "tools")
			.add(Items.IRON_PICKAXE, Items.IRON_AXE)
			.collected();
		assertEquals(2, stacks.size());
	}

	@Test
	void nullArgumentsFailFast() {
		assertThrows(IllegalArgumentException.class, () -> new ChasmCreativeTabBuilder(null, "x"));
		assertThrows(IllegalArgumentException.class, () -> new ChasmCreativeTabBuilder("mymod", " "));
	}

	@Test
	void registerOnFrozenRegistryIsSafe() {
		// Bootstrap 之后创造模式注册表已冻结：注册应只记 WARN，不抛异常（否则 DataGen 会崩）
		ChasmCreativeTabBuilder builder = new ChasmCreativeTabBuilder("mymod", "test_tab").add(Items.DIAMOND);
		builder.register();
		assertEquals(1, builder.collected().size());
	}
}
