package com.example.chasm;

import api.chasm.Chasm;
import api.chasm.ChasmMod;
import api.chasm.data.ChasmCodecs;
import api.chasm.data.ChasmData;
import api.chasm.data.DataComponent;
import api.chasm.log.ChasmLogger;
import api.chasm.contrib.ChasmContributors;
import api.chasm.contrib.ItemContributor;
import api.chasm.contrib.socket.ChasmGems;
import api.chasm.contrib.socket.ChasmSockets;
import api.chasm.item.event.ChasmItemEventKeys;
import api.chasm.item.event.ChasmItemEvents;
import api.chasm.item.event.ChasmItemHookAdapters;
import api.chasm.item.event.ItemEventKey;
import api.chasm.item.event.ItemStackSources;
import api.chasm.item.event.ResultProtocol;
import api.chasm.spi.MethodPoint;
import api.chasm.spi.Spis;
import api.chasm.random.WeightedPool;
import api.chasm.registry.ChasmRegistrar;
import api.chasm.registry.Register;
import api.chasm.template.ArmorSetSpec;
import api.chasm.template.FoodTemplate;
import api.chasm.template.WeaponTemplate;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 魔法剑示例 —— 验证 Chasm 2.0 声明式玩法 API 全链路：
 * 声明式注册 + 数据组件（自动 Codec）+ 配方声明 + DataGen 自动化 + 模组隔离 + SPI 扩展。
 *
 * <p>物品、数据、配方都是一次声明；模型/语言文件/配方 json 由 DataGen 自动生成。</p>
 */
@ChasmMod(id = "mymod")
public class ExampleMod implements ModInitializer {

	/** 魔法值数据组件：record + 注解，Codec 自动生成。 */
	@DataComponent("mana")
	public static final ManaData MANA = ManaData.EMPTY;

	/**
	 * 纯 Codec 型数据组件（不依赖 record 类）：静态初始化先于物品字段执行，
	 * 因此 MANA_SWORD 声明时可直接引用。演示 {@code ChasmData.register(modId,name,codec)}
	 * + {@link ChasmCodecs} 常用 Codec 辅助。
	 */
	public static final DataComponentType<Integer> SOULS =
		ChasmData.register("mymod", "souls", ChasmCodecs.INT);

	/**
	 * 魔法杖右键特质：消耗玩家 5 点法力，治疗 1 心。
	 *
	 * <p>法力来自玩家持久化变量（{@link PlayerMana}）。客户端仅播放挥臂动画，
	 * 真实的法力结算与治疗效果通过 {@code serverOnly} 在服务端完成。</p>
	 */
	public static final ResourceLocation MANA_USE_TRAIT = Chasm.traits().registerUse(
		"mymod", "mana_use", ctx -> {
			Player player = ctx.player();
			if (player == null) {
				return InteractionResult.PASS;
			}
			ctx.swingArm(); // 双方都播放挥臂动画
			final InteractionResult[] resultHolder = { InteractionResult.PASS };
			ctx.serverOnly(() -> {
				if (!PlayerMana.tryConsume(player, 5)) {
					player.sendSystemMessage(Component.literal("The Mana Staff is depleted!"));
					resultHolder[0] = InteractionResult.PASS;
				} else {
					player.heal(1.0f);
					player.sendSystemMessage(Component.literal(
						"The Mana Staff heals you! [" + PlayerMana.get(player) + "/" + PlayerMana.MAX + "]"));
					resultHolder[0] = InteractionResult.SUCCESS;
				}
			});
			return resultHolder[0];
		});

	/** 魔法杖攻击特质：命中实体时恢复玩家 5 点法力（法力收集杖）。 */
	public static final ResourceLocation MANA_ATTACK_TRAIT = Chasm.traits().registerAttack(
		"mymod", "mana_attack", ctx -> {
			if (ctx.attacker() instanceof Player player) {
				ctx.serverOnly(() -> {
					PlayerMana.add(player, 5);
					ctx.sendMessage("The Mana Staff collects energy! Mana: "
						+ PlayerMana.get(player) + "/" + PlayerMana.MAX);
				});
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

	/**
	 * 自定义魔法伤害类型：奥术灼烧（魔法来源，无视护甲，可聚合到 "magic"/"fire" 标签）。
	 * 经 {@link ChasmDamageTypes} 注册表声明，可跨模组查询。
	 */
	public static final api.chasm.damage.DamageTypeInfo ARCANE_BURN = Chasm.damageTypes()
		.register("mymod", "arcane_burn", api.chasm.damage.DamageTypeKind.MAGIC)
		.message("was consumed by arcane fire")
		.bypassesArmor(true)
		.tag("magic")
		.tag("fire")
		.build();

	/**
	 * 魔法剑攻击特质 —— <b>满蓝额外魔法加成</b> 模式。
	 *
	 * <p>基础物理伤害<b>始终为原生 10</b>（由 {@code .attackDamage(10)} 的属性修饰符驱动，
	 * 原版 {@code Player.attack} 结算，工具提示正确显示、整合包可读取 +9 修饰符）。</p>
	 *
	 * <p>法力层：满蓝时消耗 10 蓝，在物理 10 之上<b>额外</b>附加一层魔法伤害（奥术灼烧，
	 * 无视护甲），并演示 {@link DamageContext} 的暴击判定；无蓝时退化为普通 10 伤之剑。</p>
	 */
	public static final ResourceLocation MANA_SWORD_ATTACK_TRAIT = Chasm.traits().registerAttack(
		"mymod", "mana_sword_attack", ctx -> {
			if (!(ctx.attacker() instanceof Player player)) {
				return InteractionResult.PASS;
			}
			final InteractionResult[] resultHolder = { InteractionResult.PASS };
			ctx.serverOnly(() -> {
				if (!PlayerMana.tryConsume(player, 10)) {
					player.sendSystemMessage(Component.literal("The Mana Sword has no power! (out of mana)"));
					return; // 无蓝：仅保留下方原生物理 10 伤（由 Player.attack 结算），不附加魔法层
				}
				// 满蓝：消耗 1 点耐久，附加魔法伤害层（奥术灼烧，无视护甲）
				ctx.damageItem(1);
				if (!ctx.target().isRemoved()) {
					float bonus = new api.chasm.damage.DamageContext(ARCANE_BURN)
						.base(8.0f)                     // 魔法加成层名义值
						.critChance(0.3f, 2.0f)         // 30% 概率翻倍（演示波动/暴击机制）
						.apply(player, ctx.target(), ctx.level().getRandom());
					ChasmLogger.call("mymod", "ManaSword", "attack",
						"满蓝附加魔法伤害 {} 到目标 {}", bonus, ctx.target());
				}
				resultHolder[0] = InteractionResult.SUCCESS;
			});
			return resultHolder[0];
		});

	/**
	 * 玛瑙水晶物品栏 tick 特质：持有水晶时每秒恢复 10 法力（服务端）。
	 *
	 * <p>每 20 tick（1 秒）触发一次回蓝，替代原 ServerTickEvents 手写逻辑。</p>
	 */
	public static final ResourceLocation CRYSTAL_TICK = Chasm.traits().registerInventoryTick(
		"mymod", "crystal_tick", ctx -> {
			ctx.serverOnly(() -> {
				if (ctx.level().getGameTime() % 20 == 0) {
					PlayerMana.add(ctx.player(), PlayerMana.REGEN_PER_SECOND);
				}
			});
		});

	// ==================== 开放事件层演示：自定义协议 + 自定义事件种类 ====================

	/** 自定义结果协议（示范：乘法合并，初始 1.0 —— 协议完全由开发者定义）。 */
	public static final ResultProtocol<Float> MULTIPLIED = new ResultProtocol<>() {
		@Override public Float initial() { return 1.0F; }
		@Override public Float merge(Float current, Float incoming) {
			return incoming == null ? current : current * incoming;
		}
	};

	/** 自定义事件种类（示范：第三方注册自己的事件，机制与内置事件完全一致）。 */
	public static final ItemEventKey<Float> ON_GREED_TICK =
		ChasmItemEvents.key(ResourceLocation.fromNamespaceAndPath("mymod", "on_greed_tick"), MULTIPLIED);

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("mymod", path);
	}

	/** 宝石贡献者 id（宝石 = 一条贡献：属性 + 事件行为，走统一管线）。 */
	public static final ResourceLocation RUBY_CONTRIBUTOR = id("gem/ruby");

	/** 宝石抽样池（演示 ④ WeightedPool：alias O(1) 抽样 + 派生种子可复现）。 */
	public static final WeightedPool<String> GEM_POOL = WeightedPool.<String>builder()
		.seed(20260908L)
		.group("rarity")
		.add("mymod:gem/ruby", 70.0, 1.0)
		.add("mymod:gem/sapphire", 30.0, 2.0)
		.build();

	@Register("mana_sword")
	public static final Item MANA_SWORD = Chasm.item()
		.type(api.chasm.item.ChasmTypes.SWORD) // ★ 类型系统：实例化为真正的 SwordItem 子类，获得横扫/剑类附魔池
		.maxStackSize(1)
		.durability(1200)
		.attackDamage(10.0f)   // 原生 10 攻击伤害：真实 base=2.0 → 写主手 +8 修饰符，面板显示"攻击伤害10"、整合包可读数
		.name("Mana Sword")
		.texture("minecraft:item/diamond_sword")
		.component(SOULS, 100)
		// 攻击特质：满蓝时在物理 10 之上额外附加魔法伤害层
		.attackTrait(MANA_SWORD_ATTACK_TRAIT)
		.register();

	/**
	 * 魔法杖 —— 演示第三步 Trait 系统：不再用 List&lt;Consumer&gt; 回调列表，
	 * 而是把「右键使用/攻击」绑定到全局注册的特质 id，运行时 O(1) 查回并执行。
	 */
	@Register("mana_staff")
	public static final Item MANA_STAFF = Chasm.item()
		.maxStackSize(1)
		.name("Mana Staff")
		.texture("minecraft:item/blaze_rod")
		.useTrait(MANA_USE_TRAIT)                     // 右键：耗 5 蓝治疗 1 心
		.attackTrait(MANA_ATTACK_TRAIT)               // 攻击：命中回 5 蓝
		.register();

	@Register("mana_crystal")
	public static final Item MANA_CRYSTAL = Chasm.item()
		.maxStackSize(64)
		.name("Mana Crystal")
		.texture("minecraft:item/amethyst_shard")
		// 物品栏 tick 特质：持有水晶时每秒恢复 10 法力（替代原 ServerTickEvents 手写逻辑）
		.inventoryTrait(CRYSTAL_TICK)
		.register();

	/**
	 * 魔法矿镐 —— 演示内置 PICKAXE 类型：实例化为真正的 {@code ChasmPickaxeItem}，
	 * 获得原版镐语义（挖掘等级/破坏 tag/耐久消耗），命中实体时恢复法力，并叠加类型默认攻速模板。
	 */
	@Register("mana_pickaxe")
	public static final Item MANA_PICKAXE = Chasm.item()
		.type(api.chasm.item.ChasmTypes.PICKAXE)
		.tier(Tiers.DIAMOND)                          // 覆盖默认铁级 → 钻石级挖掘
		.maxStackSize(1)
		.durability(1200)
		.attackDamage(3.0f)                          // 覆盖类型默认攻击力 1.0 → 3.0
		.name("Mana Pickaxe")
		.texture("minecraft:item/diamond_pickaxe")
		.attackTrait(MANA_ATTACK_TRAIT)              // 复用：命中回蓝
		.eventHandler(ChasmItemEventKeys.ON_BLOCK_BREAK_AFTER, id("mana_pickaxe_mining"))
		.register();

	/**
	 * 火焰斧 —— 演示内置 AXE 类型：真正的 {@code ChasmAxeItem}，右键可剥皮。
	 */
	@Register("flame_axe")
	public static final Item FLAME_AXE = Chasm.item()
		.type(api.chasm.item.ChasmTypes.AXE)
		.maxStackSize(1)
		.name("Flame Axe")
		.texture("minecraft:item/diamond_axe")
		.attackTrait(MANA_ATTACK_TRAIT)
		.register();

	/**
	 * 自定义附魔：魔力汲取（每次命中按附魔等级吸血）。
	 *
	 * <p>1.21.1 附魔为数据驱动动态注册表。经 {@code Chasm.enchantments().register(...)}
	 * 声明一个 {@code EnchantmentDeclaration}（静态期安全引用，携带 DataGen 元数据），
	 * DataGen 自动输出 {@code data/<ns>/enchantment/mana_vampire.json} 配套物品标签。
	 * 声明顺序在 WAND 类型之前：类型附魔池要引用它。</p>
	 */
	public static final api.chasm.enchantment.EnchantmentDeclaration MANA_VAMPIRE =
		Chasm.enchantments().register("mymod", "mana_vampire")
			.weight(10).maxLevel(3).anvilCost(1)
			.minCost(5, 10).maxCost(15, 10)
			.slot("mainhand")
			.build();

	/**
	 * 魔力汲取攻击特质 —— 演示 Trait 与附魔联动：运行时从物品栈读取附魔等级，
	 * 等级 &gt; 0 则按等级治疗攻击者。零事件监听、零查找开销（O(1) 查回 Holder）。
	 */
	public static final ResourceLocation MANA_VAMPIRE_TRAIT = Chasm.traits().registerAttack(
		"mymod", "mana_vampire", ctx -> {
			if (!(ctx.attacker() instanceof Player player)) {
				return InteractionResult.PASS;
			}
			ctx.serverOnly(() -> {
				// 动态注册表运行时捕获后即可解析 Holder
				var holder = api.chasm.enchantment.ChasmEnchantments.INSTANCE
					.holderFor(MANA_VAMPIRE.key());
				int level = holder.isPresent() ? ctx.stack().getEnchantments().getLevel(holder.get()) : 0;
				if (level > 0) {
					player.heal(1.0f * level);
					PlayerMana.add(player, 3); // 附魔命中额外回蓝，演示与法力联动
					ctx.sendMessage("Mana Vampire drains the foe! (+" + level
						+ " HP) Mana: " + PlayerMana.get(player) + "/" + PlayerMana.MAX);
				}
			});
			return InteractionResult.PASS; // 保留原版物理伤害
		});

	/**
	 * 奥术法杖 —— 演示自定义类型：注册 wand 类型（基于普通物品类，打标签 + 默认属性模板），
	 * 物品一行声明即可复用类型语义。
	 *
	 * <p>类型在静态初始化时注册（先于物品 {@code @Register} 扫描），因此物品声明可直接引用 WAND。</p>
	 * <p>此处在类型上声明专属附魔池：{@code .enchantment(MANA_VAMPIRE, 1, 3, 10)} —— 所有
	 * 绑定 WAND 类型的物品在附魔台都能刷出魔力汲取 I-III（权重 10），由 DataGen 聚合进
	 * {@code mymod:enchantable/mana_vampire} 标签驱动原版附魔台。</p>
	 */
	public static final api.chasm.item.ItemType WAND = Chasm.types()
		.register("mymod", "wand",
			ctx -> new api.chasm.item.ChasmItem(ctx.behavior(), ctx.properties()))
		.tag("mymod:magic")
		.tag("chasm:staffs")
		.defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 1.0f, EquipmentSlotGroup.MAINHAND)
		.enchantment(MANA_VAMPIRE, 1, 3, 10)
		.build();

	/**
	 * 第十二步：自定义按键 —— 魔法弹键（O，GLFW 码 79）。
	 *
	 * <p>在共享环境声明（不触碰客户端类）；客户端 init 会自动把它挂到原版
	 * 「选项 -> 按键控制」列表，玩家可自由改键。displayName 由 DataGen 自动生成翻译。</p>
	 */
	public static final api.chasm.item.ChasmKeyBinding MAGIC_KEY =
		Chasm.keys().register("mymod", "magic_blast", 79, "Magic Blast (O)");

	/** 奥术法杖：使用自定义 WAND 类型，自带 1 级魔力汲取，命中按附魔等级吸血（复用 MANA_USE_TRAIT 回蓝）。 */
	@Register("arcane_wand")
	public static final Item ARCANE_WAND = Chasm.item()
		.type(WAND)
		.maxStackSize(1)
		.name("Arcane Wand")
		.texture("minecraft:item/blaze_rod")
		.useTrait(MANA_USE_TRAIT)
		.attackTrait(MANA_VAMPIRE_TRAIT)
		.enchant(MANA_VAMPIRE, 1)   // 自带附魔：默认实例携带 1 级魔力汲取
		// 第十二步：自定义按键 —— 手持法杖按下 O 键发射一道"魔法弹"（服务端执行）。
		// 按键会自动出现在原版「选项 -> 按键控制」列表（分类 Chasm），玩家可自由改键。
		.onKeyPress(MAGIC_KEY, ctx -> ctx.player().sendSystemMessage(
			Component.literal("[Chasm.keys] 按 O：魔法弹射出！（自定义按键触发，服务端）")))
		.register();

	/**
	 * 声明式界面（第九步 / Chasm.gui）—— "魔法盒"。
	 *
	 * <p>9x3 网格；左上 2x2 存储区（打开时预填 4 件物品，演示数据绑定 L4）；三个虚拟按钮
	 * （传送 / 治疗 / 关闭，演示按钮交互 L2，回调在服务端主线程执行）。布局在服务端与客户端
	 * 共用同一份描述，仅按钮点击走一个轻量 C2S 包。</p>
	 */
	public static final api.chasm.gui.ChasmGui MAGIC_BOX = Chasm.gui("mymod", "magic_box")
		.title("魔法盒")
		.size(11, 4)                                    // 加宽：按钮按文字长度占位后，9 列放不下（会重叠）
		.theme(api.chasm.gui.ChasmGuiTheme.DARK_ID)     // 配深色皮肤 + 四向描边文字
		.slot(0, 0, 2, 2)
		// 第一步 GUI 数据同步：整数通道（原版 ContainerData，16 位内、每 tick 只发变化槽）
		.data("mana", 0, 0, 100)
		.data("heat", 0, 0, 500)
		.data("auto", 0, 0, 1)
		// P1 控件（开放种类）：滑块 / 只读进度条 / 开关。第三方可用 ChasmWidgetKinds 注册新种类。
		.slider("mana", 2, 1, 4)                       // 拖动或方向键调整，值写回 mana 数据键
		.bar("heat", 6, 1, 3, 1)                       // 只读展示 heat（含客户端平滑）
		.toggle("auto", "自动", 0, 2, 2)                // 开关：0/1 写入 auto 数据键（宽度 2 格，标签不会溢出）
		// 大值通道（任意 codec，仅变化时随批量快照包发送；演示同步一个字符串状态）
		.state("status", com.mojang.serialization.Codec.STRING, "空闲")
		.button("传送", 3, 0, (player, click) ->
			player.teleportTo(player.getX(), player.getY() + 10.0, player.getZ()))
		.button("治疗", 6, 0, (player, click) -> player.heal(10.0f))
		// 充能：改整数键（客户端会看到进度条平滑增长）
		.button("测温", 7, 2, (player, click) -> {
			if (player.containerMenu instanceof api.chasm.gui.ChasmMenu menu) {
				menu.setState("status", "热量 " + menu.data("heat") + " / 自动 " + menu.data("auto"));
			}
		})
		.button("充能", 3, 2, (player, click) -> {
			if (player.containerMenu instanceof api.chasm.gui.ChasmMenu menu) {
				int mana = menu.addData("mana", 20);
				int heat = menu.addData("heat", 100);
				menu.setState("status", heat >= 500 ? "过热！" : (mana >= 100 ? "充盈" : "充能中"));
			}
		})
		.button("泄压", 5, 2, (player, click) -> {
			if (player.containerMenu instanceof api.chasm.gui.ChasmMenu menu) {
				menu.setData("heat", 0);
				menu.setState("status", "已泄压");
			}
		})
		.button("关闭", 3, 3, (player, click) -> player.closeContainer())
		.onOpen(ctx -> {
			// 数据绑定（L4）：打开即预设物品，随整包同步到客户端
			ctx.setItem(0, Items.EMERALD, 5);
			ctx.setItem(1, Items.DIAMOND, 2);
			ctx.setItem(2, Items.GOLD_INGOT, 4);
			ctx.setItem(3, Items.BLAZE_ROD);
			ctx.player().sendSystemMessage(Component.literal("[Chasm.gui] 魔法盒已打开，预填了 4 件物品！"));
		})
		.onClose(() -> {})
		.register();

	/** 右键开启魔法盒（演示声明式界面入口的服务端调用）。 */
	@Register("magic_box_opener")
	public static final Item MAGIC_BOX_OPENER = Chasm.item()
		.maxStackSize(1)
		.name("Magic Box Opener")
		.texture("minecraft:item/writable_book")
		.onRightClick(ctx -> {
			if (!ctx.level().isClientSide && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
				// P0.5 演示：看着方块右键 → 把界面**绑定到那个方块**（走远/换维度会自动关窗）
				var hit = sp.pick(4.5, 0.0F, false);
				if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
					&& !ctx.level().getBlockState(blockHit.getBlockPos()).isAir()) {
					MAGIC_BOX.openAt(sp, blockHit.getBlockPos());
					ChasmLogger.info("mymod", "打开魔法盒并绑定到 {}（范围 {} 格，走远会自动关闭）",
						blockHit.getBlockPos(), 8.0);
				} else {
					// 便携持久盒：内容存在这个物品自带的容器组件里，关窗后依然在（不再吐回背包）
					MAGIC_BOX.openItem(sp, ctx.stack());
					ChasmLogger.info("mymod", "打开魔法盒（便携持久存储：内容存进物品自身）");
				}
			}
		})
		.register();

	// ============ 第十四步：模板插件层落地示例（Weapon / Food / ArmorSet） ============

	/**
	 * 武器能力模板「贪婪」：一次声明收敛 命中点燃 + 击杀必掉绿宝石 3-8 个。
	 *
	 * <p>{@code build()} 自动注册两件事：命中特质 {@code mymod:greed_hit}（叠加在原版物理
	 * 伤害之上）与击杀奖励 {@code mymod:greed_kill}（由 Core 的 AFTER_DEATH 监听器触发）。
	 * 一个模板可被多把武器复用（改改就能直接用）。</p>
	 */
	public static final WeaponTemplate GREED = Chasm.templates()
		.weapon("mymod", "greed")
		.attackDamage(5.0f)     // 模板默认攻击力（可被物品自身 attackDamage 覆盖）
		.attackSpeed(1.6f)      // 模板默认【面板总攻速】= 原版基础 4.0 + (-2.4) 修饰符
		.onHit(ctx -> ctx.target().igniteForSeconds(2)) // 命中：点燃目标 2 秒
		.killDrop(Items.EMERALD, 3, 8, 1.0f)            // 击杀：必掉 3-8 个绿宝石
		.build();

	/** 贪婪之刃：一行 {@code .template(GREED)} 应用整个武器模板。 */
	@Register("greed_sword")
	public static final Item GREED_SWORD = Chasm.item()
		.type(api.chasm.item.ChasmTypes.SWORD) // 真剑语义：横扫 + 剑类附魔池
		.maxStackSize(1)
		.durability(800)
		.name("Greed Sword")
		.texture("minecraft:item/diamond_sword")
		.template(GREED)
		// 开放事件层：命中（内置事件源）+ 自定义事件（第三方事件种类）都挂在同一套总线上
		.eventHandler(ChasmItemEventKeys.ON_HIT, id("greed_lifesteal"))
		.eventHandler(ON_GREED_TICK, id("greed_tick_bonus"))
		.register();

	/**
	 * 食物模板「深渊炖肉」：营养/饱和/余物/概率效果一次声明。
	 *
	 * <p>{@code ItemBuilder.food(template)} 把配置写入原版 {@code FOOD} 组件，
	 * 食用动画/营养结算/效果概率/余物归还（背包满自动掉落）全部由原版机制接管，
	 * 无需手写 {@code finishUsingItem}。</p>
	 */
	public static final FoodTemplate SEA_STEW = Chasm.templates()
		.food()
		.nutrition(6)
		.saturation(0.6f)
		.remainder(Items.BOWL)                                        // 吃完返还碗
		.effect(MobEffects.REGENERATION, 100, 0, 0.5f)               // 50% 概率回复 5s
		.build();

	/** 深渊炖肉：一行 {@code .food(SEA_STEW)} 应用食物模板。 */
	@Register("sea_stew")
	public static final Item SEA_STEW_ITEM = Chasm.item()
		.maxStackSize(16)
		.name("Abyssal Stew")
		.texture("minecraft:item/mushroom_stew")
		.food(SEA_STEW)
		.register();

	/**
	 * 护甲套装模板「深渊套装」：材质 + 半套/全套效果一次声明。
	 *
	 * <p>{@code .armor(ABYSSAL, Type)} 把材质/部位写入物品，物品栈携带
	 * {@code ARMOR_SET_ID} 组件；{@code ChasmArmorItem} 每 tick（服务端）统计已穿戴件数，
	 * 达到阈值触发半套（>=2 件：抗性）或全套（>=4 件：隐身），效果以持续刷新方式维持在场。</p>
	 */
	public static final ArmorSetSpec ABYSSAL = Chasm.templates()
		.armorSet("mymod", "abyssal")
		.defense(ArmorItem.Type.HELMET, 3)
		.defense(ArmorItem.Type.CHESTPLATE, 6)
		.defense(ArmorItem.Type.LEGGINGS, 5)
		.defense(ArmorItem.Type.BOOTS, 3)
		.enchantmentValue(15)
		.repair(Ingredient.of(Items.IRON_INGOT))                     // 铁锭修复
		.layer(ResourceLocation.fromNamespaceAndPath("mymod", "abyssal"))
		.durabilityFactor(15)
		.toughness(1.0f)
		.halfSet(ctx -> ctx.player().addEffect(new MobEffectInstance(
			MobEffects.DAMAGE_RESISTANCE, 5, 0)))                    // 半套：抗性持续刷新
		.fullSet(ctx -> ctx.player().addEffect(new MobEffectInstance(
			MobEffects.INVISIBILITY, 5, 0)))                         // 全套：隐身持续刷新
		.build();

	/** 深渊头盔：一行 {@code .armor(ABYSSAL, Type.HELMET)} 应用护甲套装模板。 */
	@Register("abyssal_helmet")
	public static final Item ABYSSAL_HELMET = Chasm.item()
		.maxStackSize(1)
		.name("Abyssal Helmet")
		.texture("minecraft:item/diamond_helmet")
		.armor(ABYSSAL, ArmorItem.Type.HELMET)
		.register();

	/** 深渊胸甲。 */
	@Register("abyssal_chestplate")
	public static final Item ABYSSAL_CHESTPLATE = Chasm.item()
		.maxStackSize(1)
		.name("Abyssal Chestplate")
		.texture("minecraft:item/diamond_chestplate")
		.armor(ABYSSAL, ArmorItem.Type.CHESTPLATE)
		.register();

	/** 深渊护腿。 */
	@Register("abyssal_leggings")
	public static final Item ABYSSAL_LEGGINGS = Chasm.item()
		.maxStackSize(1)
		.name("Abyssal Leggings")
		.texture("minecraft:item/diamond_leggings")
		.armor(ABYSSAL, ArmorItem.Type.LEGGINGS)
		.register();

	/** 深渊靴子。 */
	@Register("abyssal_boots")
	public static final Item ABYSSAL_BOOTS = Chasm.item()
		.maxStackSize(1)
		.name("Abyssal Boots")
		.texture("minecraft:item/diamond_boots")
		.armor(ABYSSAL, ArmorItem.Type.BOOTS)
		.register();

	/** 炒锅（B 版：方块实体机器）——挂在 ChasmBlock 上，由 Behaviour 模板（热源 + 加工状态机）驱动。 */
	@Register("skillet")
	public static final Block SKILLET = new SkilletBlock(
		BlockBehaviour.Properties.of()
			.strength(0.6F, 6.0F)
			.sound(SoundType.METAL),
		() -> skilletBlockEntityType());

	/** 油脂：给厨锅放油（森罗物语 OIL）。 */
	@Register("oil")
	public static final Item OIL = Chasm.item()
		.maxStackSize(16)
		.name("油脂")
		.texture("minecraft:item/honey_bottle")
		.register();

	/** 自有方法点：厨锅首次加热（默认放行；第三方可 after 监听/阻止，不碰他人） */
	public static final MethodPoint<net.minecraft.server.level.ServerPlayer, Boolean> POT_FIRST_HEAT =
		Spis.point("mymod:pot.first_heat", p -> Boolean.TRUE);

	/** 厨铲：翻炒 / 出锅（森罗物语 KitchenShovel）。 */
	@Register("kitchen_shovel")
	public static final Item KITCHEN_SHOVEL = Chasm.item()
		.maxStackSize(1)
		.name("厨铲")
		.texture("minecraft:item/iron_shovel")
		.register();

	/** 红宝石（演示宝石 = 贡献者 + 插槽）。 */
	@Register("ruby")
	public static final Item RUBY = Chasm.item()
		.maxStackSize(1)
		.name("Ruby")
		.texture("mymod:item/ruby")     // 原创图标（不用原版贴图 cos；本地资源包可替换为真素材）
		.register();

	// 注：神化移植内容（词缀/宝石/重铸锤）已隔离到独立模块 apoth-port（命名空间 apoth:*）

	// ==================== 物品注释：铭刻台（一个页面里快捷写入/追加/移除标识） ====================

	/** 正在被铭刻的物品（编辑器第 0 号槽）。 */
	private static net.minecraft.world.item.ItemStack editing(Player player) {
		if (player.containerMenu instanceof api.chasm.gui.ChasmMenu menu) {
			return menu.getItem(0);
		}
		return net.minecraft.world.item.ItemStack.EMPTY;
	}

	/** 写入/追加一条注释（同 id 幂等覆盖）。 */
	private static void note(Player player, String path, String text, int color, int order) {
		net.minecraft.world.item.ItemStack target = editing(player);
		if (target.isEmpty()) {
			player.displayClientMessage(Component.literal("先把要铭刻的物品放进左上角的槽"), false);
			return;
		}
		api.chasm.item.ChasmItemNotes.add(target,
			api.chasm.item.ChasmItemNote.of(ResourceLocation.fromNamespaceAndPath("mymod", path), text, color, order));
		player.displayClientMessage(Component.literal("已追加标识：" + text), false);
	}

	/** 移除一条注释。 */
	private static void unnoted(Player player, String path, String label) {
		net.minecraft.world.item.ItemStack target = editing(player);
		boolean removed = api.chasm.item.ChasmItemNotes.remove(target,
			ResourceLocation.fromNamespaceAndPath("mymod", path));
		player.displayClientMessage(Component.literal(removed ? "已移除标识：" + label : "该物品没有" + label), false);
	}

	/**
	 * 铭刻台：把动辄要打命令的'给物品写标识'变成点一下按钮。
	 *
	 * <p>整合包场景：某 Boss 打完/某劫难过了，这把武器就退役了 —— 直接点「标记·已退役」，
	 * 物品下方立刻出现一行注释（不是改名字，是像属性修饰符那样的注解行）。</p>
	 */
	public static final api.chasm.gui.ChasmGui NOTE_EDITOR = Chasm.gui("mymod", "note_editor")
		.title("铭刻台")
		.size(12, 4)
		// 物品过滤：只接受"值得铭刻"的单件物品（堆叠数 1）；矿石/方块之类的堆叠物塞不进来
		.slot(0, 0, 1, 1, stack -> stack.getMaxStackSize() == 1)
		.theme(api.chasm.gui.ChasmGuiTheme.DARK_ID)     // 深色皮肤 + 亮色文字
		.button("标记·已退役", 2, 0, 4, (p, c) -> note(p, "retired", "已退役：后续劫难不再生效", 0xFFFF7043, 0))
		.button("标记·劫难限定", 2, 1, 4, (p, c) -> note(p, "calamity", "劫难限定装备", 0xFFFFD24C, 1))
		.button("标记·Boss 掉落", 2, 2, 4, (p, c) -> note(p, "boss_drop", "Boss 战利品", 0xFF9A4FD6, 2))
		.button("移除·已退役", 7, 0, 4, (p, c) -> unnoted(p, "retired", "已退役"))
		.button("移除·劫难限定", 7, 1, 4, (p, c) -> unnoted(p, "calamity", "劫难限定"))
		.button("移除·Boss 掉落", 7, 2, 4, (p, c) -> unnoted(p, "boss_drop", "Boss 掉落"))
		.button("清空注释", 2, 3, 3, (p, c) -> {
			net.minecraft.world.item.ItemStack target = editing(p);
			api.chasm.item.ChasmItemNotes.clear(target);
			p.displayClientMessage(Component.literal("已清空该物品的全部注释"), false);
		})
		.button("关闭", 7, 3, 2, (p, c) -> p.closeContainer())
		.register();

	/** 铭刻笔：右键打开铭刻台。 */
	@Register("note_pen")
	public static final Item NOTE_PEN = Chasm.item()
		.maxStackSize(1)
		.durability(120)
		.name("铭刻笔")
		.texture("mymod:item/note_pen")
		.onRightClick(ctx -> ctx.serverOnly(() -> ctx.player().openMenu(NOTE_EDITOR.provider())))
		.register();

	/** 惰性取炒锅 BE 类型（绕开静态字段前向引用限制）。 */
	private static BlockEntityType<?> skilletBlockEntityType() {
		return SKILLET_TYPE;
	}

	/** 炒锅的方块实体类型（@Register 字段自动注册进 BLOCK_ENTITY_TYPE 注册表）。 */
	@Register("skillet_type")
	public static final BlockEntityType<SkilletBlockEntity> SKILLET_TYPE =
		Chasm.blockEntityType(SkilletBlockEntity::new).blocks(SKILLET).register();


	@Override
	public void onInitialize() {
		// 统一日志：自动带 [mymod] 前缀，结构化 CALL / EVENT / INJECT 记录
		ChasmLogger.info("mymod", "Initializing ExampleMod...");
		ChasmLogger.debug("mymod", "调试模式日志示例（需 setDebugMode(true) 才会输出）");

		// 创造模式标签页：把本模组的全部物品收进一个标签页（自动收录，未来加物品无需维护）
		Chasm.creativeTab("mymod", "main")
			.title("Chasm 示例")
			.icon(MANA_SWORD)
			.autoAll()
			.register();

		// 注册扫描：物品/方块/数据组件的注册日志自动经由 ChasmLogger 打上 [mymod] 前缀
		ChasmRegistrar.scan(this.getClass());

		// 第十步：JSON 软编码加载。
		// 1) 物品（data/mymod/chasm/items/*.json）由 core 在 ChasmInit 或此处自动注册。
		// 2) 先注册 JSON 界面按钮引用的"命名动作"，再加载 JSON 界面（gui/*.json 的按钮
		//    经 ChasmGuiBuilder.buttonAction 由动作 id 反查 handler；顺序必须在 loadAll 之前）。
		Chasm.itemLoader().loadAll();
		api.chasm.gui.ChasmGuiActions.register(
			"mymod", "teleport",
			(player, click) -> player.teleportTo(player.getX(), player.getY() + 10.0, player.getZ()));
		Chasm.guiLoader().loadAll();

		// SPI 自测：给 chasm 方法点挂 after 装饰（演示第三方监听/加料而不动框架本体）
		Spis.<api.chasm.blockentity.ChasmBlockEntity, Object>point("chasm:heat.changed", c -> null)
			.after("mymod", (be, r) -> { ChasmLogger.info("mymod", "[SPI] 热源翻转 @ {}", be.getBlockPos()); return r; });
		Spis.<net.minecraft.world.level.Level, Object>point("chasm:cooking.completed", c -> null)
			.after("mymod", (lvl, r) -> { ChasmLogger.info("mymod", "[SPI] 有 chasm 加工完成"); return r; });
		Spis.<net.minecraft.world.level.Level, Boolean>point("chasm:cooking.can_start", c -> Boolean.TRUE)
			.after("mymod", (lvl, ok) -> ok); // 只读不改，演示监听
		Spis.<net.minecraft.server.level.ServerPlayer, Boolean>get("mymod:pot.first_heat")
			.after("mymod", (p, ok) -> { ChasmLogger.info("mymod", "[SPI] 玩家 {} 首次热锅（可在此加奖励）", p.getGameProfile().getName()); return ok; });

		// 演示跨模组查询：伤害类型注册表 + 属性注册表（整合包友好基础设施）
		ChasmLogger.info("mymod",
			"需求类型注册表现有 {} 类型；自定义类型 {} isMagic={} bypassArmor={}",
			Chasm.damageTypes().size(), ARCANE_BURN.id(),
			ARCANE_BURN.hasTag("magic"), ARCANE_BURN.bypassesArmor());
		ChasmLogger.info("mymod",
			"主手攻击属性默认值={}，{}.attackDamage 修饰符 = 10-{} = +{}",
			net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value().getDefaultValue(),
			"mymod:mana_sword",
			api.chasm.attribute.ChasmAttributes.ATTACK_DAMAGE.defaultValue(),
			10.0f - api.chasm.attribute.ChasmAttributes.ATTACK_DAMAGE.defaultValue());
		// 类型系统验证：mana_sword 已被 SWORD 类型实例化为真正的 SwordItem 子类
		ChasmLogger.info("mymod",
			"类型系统验证：mana_sword 是 SwordItem = {}（类型 = {}），横扫/剑类附魔池已解锁",
			MANA_SWORD instanceof net.minecraft.world.item.SwordItem,
			net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(MANA_SWORD));

		// 类型系统验证：内置工具类型映射 + 自定义类型映射
		ChasmLogger.info("mymod",
			"类型系统验证：mana_pickaxe 是 PickaxeItem = {}，flame_axe 是 AxeItem = {}",
			MANA_PICKAXE instanceof net.minecraft.world.item.PickaxeItem,
			FLAME_AXE instanceof net.minecraft.world.item.AxeItem);
		ChasmLogger.info("mymod",
			"类型系统验证：类型注册表共 {} 个类型；wand 自定义类型存在 = {}",
			api.chasm.item.ChasmTypes.INSTANCE.size(),
			api.chasm.item.ChasmTypes.INSTANCE.get("mymod", "wand").isPresent());

		// 模板链路验证（第十四步）：武器模板自动注册的命中特质/击杀奖励 + 护甲套装注册表
		ChasmLogger.info("mymod",
			"模板验证：贪婪武器模板命中特质={} 击杀奖励={}；击杀奖励注册表共 {} 个；"
			+ "食物模板余物={} 效果 {} 条；护甲套装注册表共 {} 个",
			GREED.hitTraitId(), GREED.killRewardId(),
			Chasm.templates().killRewards().size(),
			SEA_STEW.remainder(), SEA_STEW.effects().size(),
			Chasm.templates().armorSetCount());
		ChasmLogger.info("mymod",
			"模板验证：greed_sword 是 SwordItem = {}，sea_stew 是 ChasmFoodItem = {}，"
			+ "abyssal_helmet 是 ChasmArmorItem = {}",
			GREED_SWORD instanceof net.minecraft.world.item.SwordItem,
			SEA_STEW_ITEM instanceof api.chasm.item.ChasmFoodItem,
			ABYSSAL_HELMET instanceof api.chasm.item.ChasmArmorItem);

		// 附魔池链路验证：mana_vampire 已注册；arcane_wand 的类型( WAND )附魔池已绑定该附魔；
		// 该附魔非宝藏 → 会聚合进 mymod:enchantable/mana_vampire 标签，附魔台可刷出
		ChasmLogger.info("mymod",
			"附魔池链路验证：mana_vampire 已注册 = {}（max_level={}, weight={}）；"
			+ "arcane_wand 类型附魔池绑定它 = {}，支持标签 = {}",
			Chasm.enchantments().isRegistered(MANA_VAMPIRE.key()),
			MANA_VAMPIRE.maxLevel(), MANA_VAMPIRE.weight(),
			WAND.enchantmentPool().containsKey(MANA_VAMPIRE.key()),
			MANA_VAMPIRE.supportedTag().location());

		// 配方声明：钻石 + 木棍 合成魔法剑（DataGen 自动生成配方 json）
		Chasm.recipe("mana_sword")
			.shaped(" D ", " D ", " S ",
				'D', Items.DIAMOND, 'S', Items.STICK)
			.result(MANA_SWORD)
			.register();
		ChasmLogger.call("mymod", "ExampleMod", "onInitialize", "声明配方 {}", "mymod:mana_sword");

		// 配方声明：烈焰棒 + 木棍 合成魔法杖
		Chasm.recipe("mana_staff")
			.shaped(" B ", " S ", " S ",
				'B', Items.BLAZE_ROD, 'S', Items.STICK)
			.result(MANA_STAFF)
			.register();
		ChasmLogger.call("mymod", "ExampleMod", "onInitialize", "声明配方 {}", "mymod:mana_staff");

		// 配方声明：三颗紫水晶碎片合成玛瑙水晶
		Chasm.recipe("mana_crystal")
			.shaped(" A ", " A ", " A ", 'A', Items.AMETHYST_SHARD)
			.result(MANA_CRYSTAL)
			.register();
		ChasmLogger.call("mymod", "ExampleMod", "onInitialize", "声明配方 {}", "mymod:mana_crystal");

		// 神化移植的配方（重铸锤/宝石）已随内容迁到独立模块 apoth-port

		Chasm.recipe("note_pen")
			.shaped(" G ", " I ", " I ",
				'G', Items.GLOW_INK_SAC, 'I', Items.IRON_INGOT)
			.result(NOTE_PEN)
			.register();
		ChasmLogger.call("mymod", "ExampleMod", "onInitialize", "声明配方 {}", "mymod:note_pen");

		// 玛瑙水晶回蓝已迁移到 INVENTORY_TICK 特质（CRYSTAL_TICK），此处不再手写 ServerTickEvents。

		// 演示 SPI 扩展：通过 modId 拿到自己的隔离上下文，追加一个行为。
		// 真实场景中，这一步由【另一个模组】对本模组执行（无需本模组开放任何接口，
		// 只要它用 Chasm 声明就会被暴露，闭源模组同样可扩展）。
		ChasmLogger.event("mymod", "ExampleMod", "onInitialize", "SPI 扩展 mana_sword");
		// ==================== 开放事件层：注册处理器实现 + 自定义事件源适配器 ====================
		// 1) 内置事件：命中（贪婪之刃吸血 + 额外伤害，经 ServerLivingEntityEvents.AFTER_DAMAGE 触发）
		ChasmItemEvents.handler(id("greed_lifesteal"), ChasmItemEventKeys.ON_HIT,
			(api.chasm.item.event.payload.HitPayload payload, ItemStack stack, Float current) -> {
				if (payload.attacker() instanceof net.minecraft.server.level.ServerPlayer attacker
					&& attacker.getHealth() < attacker.getMaxHealth()) {
					attacker.heal(2.0F);
				}
				return current + 4.0F;
			});
		// 2) 内置事件：挖方块后（魔法矿镐：额外收益强度，经 PlayerBlockBreakEvents.AFTER 触发）
		ChasmItemEvents.handler(id("mana_pickaxe_mining"), ChasmItemEventKeys.ON_BLOCK_BREAK_AFTER,
			(api.chasm.item.event.payload.BlockBreakPayload payload, ItemStack stack, Float current) ->
				current + (stack.getOrDefault(SOULS, 0) >= 100 ? 1.0F : 0.0F));
		// 3) 自定义事件：处理器 + 亲手注册的"栈来源"（开放注册表，框架无需改动）
		ChasmItemEvents.handler(id("greed_tick_bonus"), ON_GREED_TICK,
			(Float payload, ItemStack stack, Float current) -> current * 2.0F);
		ItemStackSources.register(ON_GREED_TICK, ItemStackSources.MAIN_HAND);
		// 4) 贡献管线（①）：宝石 = ItemContributor（属性 + 事件行为一次声明），走同一套编译快照
		ChasmContributors.register(new ItemContributor() {
			@Override public ResourceLocation id() { return RUBY_CONTRIBUTOR; }
			@Override public void contributeAttributes(AttributeSink sink) {
				sink.add(api.chasm.item.AttributeDeclaration.mainHand(
					net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH.value(),
					ExampleMod.id("gem/ruby_health"), 4.0F));
			}
			@Override public void contributeHandlers(HandlerSink sink) {
				sink.add(ChasmItemEventKeys.ON_HIT, ExampleMod.id("gem/ruby_burn"));
			}
		});
		ChasmItemEvents.handler(id("gem/ruby_burn"), ChasmItemEventKeys.ON_HIT,
			(api.chasm.item.event.payload.HitPayload payload, ItemStack stack, Float current) -> {
				payload.victim().igniteForSeconds(3);
				return current;
			});
		ChasmGems.register(RUBY, RUBY_CONTRIBUTOR);

		// 5) 自定义事件源适配器：每 5 秒对每个玩家主手分发一次自定义事件
		ChasmItemHookAdapters.registerAdapter("mymod:greed_tick", () -> ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 != 0) {
				return;
			}
			for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
				float factor = ChasmItemEvents.dispatchEntity(player, ON_GREED_TICK, 1.0F);
				if (factor > 1.0F) {
					player.displayClientMessage(Component.literal("[开放事件] 贪婪脉动 x" + factor), true);
				}
				demoSocketsOnMainHand(player);
			}
		}));
		ChasmLogger.call("mymod", "ExampleMod", "onInitialize",
			"开放事件层已接线：内置 {} 种事件 + 自定义事件 {} 个处理器",
			ChasmItemEvents.keyCount(), ChasmItemEvents.handlerCount());

		Chasm.mod("mymod").item("mana_sword")
			.appendRightClick(ctx -> {
				if (!ctx.level().isClientSide) {
					ctx.player().sendSystemMessage(Component.literal("[SPI 追加] Arcane pulse surges!"));
				}
			});
	}

	/**
	 * 演示 ④WeightedPool + ⑤ChasmSockets：给主手贪婪之刃开 2 个插槽并按权重镶嵌一颗宝石。
	 *
	 * <p>抽样用派生种子（salt = 玩家名哈希），因此同一玩家每次结果可复现；宝石插入即挂贡献者，
	 * 属性与命中点燃行为由统一管线一次性物化。</p>
	 */
	private static void demoSocketsOnMainHand(net.minecraft.server.level.ServerPlayer player) {
		ItemStack held = player.getMainHandItem();
		if (held.getItem() != GREED_SWORD || ChasmSockets.capacity(held) > 0) {
			return;
		}
		ChasmSockets.setCapacity(held, 2);
		String gemId = GEM_POOL.sampleDerived(player.getUUID().getLeastSignificantBits());
		ChasmSockets.socket(held, new ItemStack(RUBY));
		player.displayClientMessage(Component.literal("[贡献管线] 抽样 " + gemId + " -> " + ChasmSockets.describe(held)), true);
	}

	/** 魔法值数据（record 自动生成 Codec，无需手写 NBT/序列化）。 */
	public record ManaData(int current, int max) {
		public static final ManaData EMPTY = new ManaData(50, 50);
	}
}