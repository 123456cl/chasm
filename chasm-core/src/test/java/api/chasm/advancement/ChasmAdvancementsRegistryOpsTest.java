
package api.chasm.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;

import net.minecraft.SharedConstants;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **{@code ChasmAdvancements} 的 registry-aware ops 回归**（2026-09-23 事故）。
 *
 * <h2>它守的 bug</h2>
 * <p>1.21.1 的 {@link ItemPredicate} 的 {@code items} 是 {@code HolderSet}，解析必须带注册表；
 * 旧实现固定用 {@code JsonOps.INSTANCE}，于是任何带 {@code items} 的
 * {@code tetra:craft_module}/{@code craft_improvement}/{@code block_use} 条件都会
 * {@code Can't access registry ... minecraft:item}，导致整条 criterion（进而整个成就）不加载。</p>
 *
 * <h2>它守的两条修法</h2>
 * <ol>
 *   <li><b>透传</b>：loader 传来的 {@link RegistryOps} 必须被解码器看到 —— 用
 *       {@code Codec.PASSTHROUGH} 的 Dynamic 取回。测试用一张<b>数据驱动</b>注册表
 *       （自定义 {@code minecraft:enchantment}）证明"确实是透传的 ops"，因为兜底 ops 只包
 *       {@code BuiltInRegistries}，根本看不见这张表。</li>
 *   <li><b>兜底</b>：调用方没有 ops 上下文（离线单测）时回落
 *       {@code BuiltInRegistries} 的 RegistryOps，仍能解析 {@code minecraft:stick}。</li>
 * </ol>
 */
class ChasmAdvancementsRegistryOpsTest {

	/**
	 * 与 tetra-port 里的真实用法同形：用 {@link ChasmAdvancements#jsonCodec} 包一个 ItemPredicate 解析。
	 *
	 * <p><b>刻意不是 {@code final} 静态初始化</b>（2026-09-23 收口实测）：类初始化发生在
	 * {@code @BeforeAll} **之前**，而这个 codec 的构造会碰到 {@code ChasmAdvancements} 的静态状态
	 * （兜底 ops 要读 {@code BuiltInRegistries}）—— 那时 {@code Bootstrap.bootStrap()} 还没跑，
	 * 会让 {@code BuiltInRegistries} 的静态初始化抛 "Not bootstrapped" 并**永久毒化**整个测试 JVM
	 * （一次挂掉 20+ 个无关测试类）。所以改成在 {@link #bootstrap()} 里、bootStrap() 之后赋值。</p>
	 */
	private static Codec<ItemPredicate> PREDICATE_CODEC;

	private static MappedRegistry<Enchantment> enchantments;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// 必须在 bootStrap() **之后**（见 PREDICATE_CODEC 的 javadoc：类初始化先于 @BeforeAll）
		PREDICATE_CODEC = ChasmAdvancements.jsonCodec(
			json -> ChasmAdvancements.itemPredicate(json),
			predicate -> ItemPredicate.CODEC.encodeStart(JsonOps.INSTANCE, predicate)
				.getOrThrow(message -> new IllegalStateException(message)));
		enchantments = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
		Registry.register(enchantments, ResourceLocation.fromNamespaceAndPath("chasm_probe", "test_power"), enchantment());
		enchantments.freeze();
	}

	@SuppressWarnings("unchecked")
	private static Enchantment enchantment() {
		Enchantment.EnchantmentDefinition definition = Enchantment.definition(HolderSet.empty(), 1, 1,
			Enchantment.constantCost(1), Enchantment.constantCost(1), 1, EquipmentSlotGroup.ANY);
		return new Enchantment(Component.literal("chasm-probe"), definition, HolderSet.empty(), DataComponentMap.EMPTY);
	}

	/** 成功路径：带 items 的谓词在 loader 透传的 RegistryOps 下解析出 HolderSet（旧实现必红）。 */
	@Test
	void itemsHolderSetParsesWithLoaderRegistryOps() {
		RegistryOps<JsonElement> ops = loaderOps(registry(false));
		JsonObject json = JsonParser.parseString("{\"items\":[\"minecraft:stick\"]}").getAsJsonObject();
		ItemPredicate predicate = require(PREDICATE_CODEC.parse(ops, json), "items 形状");
		assertTrue(predicate.items().isPresent(), "items 必须被解析出来（不是静默忽略）");
		assertTrue(predicate.items().get().contains(Items.STICK.builtInRegistryHolder()),
			"items 必须指到 minecraft:stick");
		assertTrue(predicate.test(new ItemStack(Items.STICK)), "谓词应命中木棍");
	}

	/** 兜底：调用方直接给 JsonOps（没有注册表上下文）时，仍能从 BuiltInRegistries 解析 items。 */
	@Test
	void itemsHolderSetParsesWithBuiltInFallback() {
		JsonObject json = JsonParser.parseString("{\"items\":[\"minecraft:stick\"]}").getAsJsonObject();
		ItemPredicate predicate = require(PREDICATE_CODEC.parse(JsonOps.INSTANCE, json), "兜底 items 形状");
		assertTrue(predicate.items().isPresent(), "兜底 ops 也要把 items 解析出来");
		assertTrue(predicate.items().get().contains(Items.STICK.builtInRegistryHolder()));
	}

	/**
	 * **透传的证明**：谓词只约束一个"数据驱动注册表"里的组件值
	 * （附魔在 1.21 不在 {@code BuiltInRegistries} 里）。如果实现没有透传 loader 的 ops，
	 * 而是用了只包 {@code BuiltInRegistries} 的兜底 ops，这里必然失败。
	 */
	@Test
	void passthroughSeesDataDrivenRegistries() {
		RegistryOps<JsonElement> ops = loaderOps(registry(true));
		JsonObject json = JsonParser.parseString(
			"{\"components\":{\"minecraft:stored_enchantments\":{\"levels\":{\"chasm_probe:test_power\":1}}}}")
			.getAsJsonObject();
		ItemPredicate predicate = require(PREDICATE_CODEC.parse(ops, json), "数据驱动注册表透传");
		// 1.21.1 的 DataComponentPredicate **没有** stream()（javap 实证）：它只有 alwaysMatches()/test()/asPatch()。
		// 有约束时 alwaysMatches() 为 false，所以"解出来了"= 不是空谓词。
		assertFalse(predicate.components().alwaysMatches(), "stored_enchantments 组件必须解出来（不是空谓词）");
	}

	/** 1.20 旧写法 {@code {item: ...}} 不是合法约束：codec 会静默忽略，测试把这个事实钉住。 */
	@Test
	void legacyItemKeyIsNotTreatedAsAConstraint() {
		RegistryOps<JsonElement> ops = loaderOps(registry(false));
		JsonObject json = JsonParser.parseString("{\"item\":\"minecraft:stick\"}").getAsJsonObject();
		ItemPredicate predicate = require(PREDICATE_CODEC.parse(ops, json), "旧 item 形状");
		assertTrue(predicate.items().isEmpty(),
			"1.20 的 item 键在 1.21 codec 里被静默忽略 —— 所以迁移必须显式改成 items");
	}

	// ------------------------------------------------------------------ 工具

	/** {@code withEnchantments} = 本测试自己造的数据驱动附魔表是否放进 provider。 */
	private static HolderLookup.Provider registry(boolean withEnchantments) {
		Stream<HolderLookup.RegistryLookup<?>> lookups = Stream.of(new Lookup<>(BuiltInRegistries.ITEM));
		if (withEnchantments) {
			lookups = Stream.concat(lookups, Stream.of(new Lookup<>(enchantments)));
		}
		return HolderLookup.Provider.create(lookups);
	}

	private static RegistryOps<JsonElement> loaderOps(HolderLookup.Provider provider) {
		return RegistryOps.create(JsonOps.INSTANCE, provider);
	}

	private static ItemPredicate require(DataResult<ItemPredicate> result, String what) {
		return result.result().orElseThrow(() -> new AssertionError(
			what + " 解析失败: " + result.error().map(DataResult.Error::message).orElse("?")));
	}

	private static final class Lookup<T> implements HolderLookup.RegistryLookup<T> {
		private final Registry<T> registry;

		private Lookup(Registry<T> registry) { this.registry = registry; }

		@Override public ResourceKey<? extends Registry<? extends T>> key() { return registry.key(); }
		@Override public Lifecycle registryLifecycle() { return Lifecycle.stable(); }
		@Override public Optional<Holder.Reference<T>> get(ResourceKey<T> key) { return registry.getHolder(key); }
		@Override public Optional<HolderSet.Named<T>> get(TagKey<T> tag) { return registry.getTag(tag); }
		@Override public Stream<Holder.Reference<T>> listElements() { return registry.holders(); }
		@Override public Stream<HolderSet.Named<T>> listTags() { return registry.getTags().map(Pair::getSecond); }
	}
}
