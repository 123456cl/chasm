package api.chasm.registry;

import api.chasm.ChasmMod;
import api.chasm.context.ChasmContextRegistry;
import api.chasm.context.ModContext;
import api.chasm.data.ChasmCodec;
import api.chasm.data.ChasmData;
import api.chasm.data.DataComponent;
import api.chasm.item.ChasmItemSupport;
import api.chasm.log.ChasmLogger;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import com.mojang.serialization.Codec;

import java.lang.reflect.Field;

/**
 * 声明式注册扫描器。
 *
 * <p>扫描 {@code @ChasmMod} 入口类（以及 {@link ChasmContentHolder 内容容器类}）的所有
 * {@code @Register} 字段：</p>
 * <ol>
 *   <li>根据字段类型路由到对应原版注册表（Item → 物品、Block → 方块……）</li>
 *   <li>把每个声明暴露到该 mod 的 {@link ModContext}（SPI 暴露：其他模组可查询/修改）</li>
 * </ol>
 *
 * <p>id 解析：{@code modId:注解value}，例如 {@code @ChasmMod(id="mymod")} +
 * {@code @Register("mana_sword")} → {@code mymod:mana_sword}。</p>
 *
 * <p><b>入口类与内容容器走的是同一段实现</b>（{@code scanFields}）：拆类只改变"声明写在哪里"，
 * 不改变 id 前缀、上下文归属、收集模式与错误信息。</p>
 */
public final class ChasmRegistrar {

	private ChasmRegistrar() {
	}

	/**
	 * 扫描并注册一个 {@code @ChasmMod} 类中的全部声明。
	 *
	 * @param modClass 带 {@link ChasmMod} 注解的入口类
	 */
	public static void scan(Class<?> modClass) {
		scan(modClass, true, true);
	}

	/**
	 * 扫描一个 {@code @ChasmMod} 类的全部声明。
	 *
	 * @param modClass               带 {@link ChasmMod} 注解的入口类
	 * @param includeDataComponents 是否注册数据组件（DataGen 等注册表冻结场景传 false）
	 * @param registerToRegistry    是否真实注册到原版注册表（DataGen 收集声明时传 false）
	 */
	public static void scan(Class<?> modClass, boolean includeDataComponents, boolean registerToRegistry) {
		ChasmMod mod = modClass.getAnnotation(ChasmMod.class);
		if (mod == null) {
			throw new IllegalArgumentException("类缺少 @ChasmMod 注解: " + modClass.getName());
		}
		scanFields(mod.id(), modClass, includeDataComponents, registerToRegistry);
	}

	/**
	 * 扫描一个 {@link ChasmContentHolder} 内容容器类的全部声明（注册到容器注解里写的那个 mod 下）。
	 *
	 * <p>为什么要有这条入口：{@link #scan(Class)} 只能扫 mod 入口类，于是"一个模组的全部内容"
	 * 被迫挤进同一个类 —— 真实端口（tetra-port）的入口类因此长到 1300+ 行，每加一件内容都要改它，
	 * 多个功能并行开发时互相踩。内容容器把声明按主题拆开，而注册语义与入口类**逐字一致**：
	 * 同一个 modId 前缀、同一个 {@link ModContext}、同一个 DataGen 收集路径。</p>
	 *
	 * @param contentClass 带 {@link ChasmContentHolder} 注解的类
	 */
	public static void scanContent(Class<?> contentClass) {
		scanContent(contentClass, true, true);
	}

	/**
	 * 扫描一个内容容器类。
	 *
	 * @param contentClass          带 {@link ChasmContentHolder} 注解的类
	 * @param includeDataComponents 是否注册数据组件（DataGen 等注册表冻结场景传 false）
	 * @param registerToRegistry    是否真实注册到原版注册表（DataGen 收集声明时传 false）
	 */
	public static void scanContent(Class<?> contentClass, boolean includeDataComponents, boolean registerToRegistry) {
		ChasmContentHolder holder = contentClass.getAnnotation(ChasmContentHolder.class);
		if (holder == null) {
			throw new IllegalArgumentException("类缺少 @ChasmContentHolder 注解: " + contentClass.getName());
		}
		if (holder.value().isEmpty()) {
			throw new IllegalArgumentException("@ChasmContentHolder 的 mod 命名空间不能为空: " + contentClass.getName());
		}
		scanFields(holder.value(), contentClass, includeDataComponents, registerToRegistry);
	}

	/**
	 * 入口类与内容容器**共用**的字段扫描。
	 *
	 * <p>只有这一份实现是刻意的：拆类之后两条路径的注册语义（id 前缀、上下文归属、
	 * 收集模式、错误信息）必须完全一致，任何分叉都会变成"容器里的物品少了点什么"这类幽灵问题。</p>
	 */
	private static void scanFields(String modId, Class<?> holderClass, boolean includeDataComponents,
		boolean registerToRegistry) {
		ChasmContextRegistry.setCurrent(modId);
		ModContext context = ChasmContextRegistry.get(modId);

		for (Field field : holderClass.getDeclaredFields()) {
			DataComponent dataAnn = field.getAnnotation(DataComponent.class);
			api.chasm.registry.Register registerAnn = field.getAnnotation(api.chasm.registry.Register.class);
			if (dataAnn == null && registerAnn == null) {
				continue;
			}
			if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				// 扫描器按"类"读字段（field.get(null)）：实例字段读不到，早报错胜过运行时 NPE
				throw new IllegalStateException("声明字段必须是 static: "
					+ holderClass.getName() + "#" + field.getName());
			}
			if (dataAnn != null) {
				if (includeDataComponents && registerToRegistry) {
					registerDataComponent(modId, dataAnn.value(), field);
				}
				continue;
			}
			Object value = readField(field);
			if (value instanceof ChasmItemSupport support) {
				Item item = (Item) support;
				if (registerToRegistry) {
					ChasmRegistration.registerItem(modId, registerAnn.value(), item);
					// 输出显式绑定的默认组件日志（带 modId）
					support.chasmBehavior().chasmDefaultComponents().forEach((type, val) ->
						ChasmLogger.call(modId, "ItemBuilder", "component",
							"设置默认组件 {} = {} 到物品 {}", type, registerAnn.value(), val));
				}
				context.exposeItem(registerAnn.value(), support);
			} else if (value instanceof Item item) {
				if (registerToRegistry) {
					ChasmRegistration.registerItem(modId, registerAnn.value(), item);
				}
			} else if (value instanceof api.chasm.block.ChasmBlock chasmBlock) {
				if (registerToRegistry) {
					ChasmRegistration.registerBlock(modId, registerAnn.value(), chasmBlock);
					// 自动生成方块物品（BlockItem），与方块同 id
					net.minecraft.world.item.BlockItem item = ChasmRegistration.registerBlockItem(modId,
						registerAnn.value(), chasmBlock, new net.minecraft.world.item.Item.Properties());
					context.exposeBlock(registerAnn.value(), chasmBlock, item);
				} else {
					// 收集模式（DataGen）：不构造 BlockItem（避免 intrusive holder 未注册）
					context.exposeBlock(registerAnn.value(), chasmBlock, null);
				}
			} else if (value instanceof Block block) {
				if (registerToRegistry) {
					ChasmRegistration.registerBlockWithItem(modId, registerAnn.value(), block);
				}
			} else if (value instanceof BlockEntityType<?> beType) {
				if (registerToRegistry) {
					ChasmRegistration.registerBlockEntityType(modId, registerAnn.value(), beType);
				}
				context.exposeBlockEntity(registerAnn.value(), beType);
			} else {
				throw new IllegalStateException(
					"@Register 字段 " + holderClass.getName() + "#" + field.getName() + " 的类型暂不支持: "
						+ (value == null ? "null" : value.getClass().getName()));
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void registerDataComponent(String modId, String id, Field field) {
		Class<?> type = field.getType();
		Codec<?> codec = ChasmCodec.codecFor(type);
		ChasmData.register(modId, id, (Class) type, (Codec) codec);
	}

	private static Object readField(Field field) {
		try {
			field.setAccessible(true);
			return field.get(null);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("无法读取 @Register 字段 " + field.getName(), e);
		}
	}
}
