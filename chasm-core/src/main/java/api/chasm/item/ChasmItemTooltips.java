package api.chasm.item;

import api.chasm.log.ChasmLogger;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品提示行的**开放注册表**（对**任意**物品生效，包括原版物品）。
 *
 * <p>为什么需要：物品子类（{@code ChasmItem.appendHoverText}）只能给自己模组的物品加提示行，
 * 而词缀/宝石这类系统要把提示写在**别人家的物品**上（比如原版钻石剑）。本类通过 Fabric 的
 * {@code ItemTooltipCallback} 实现"零 mixin 的全局提示注入"。</p>
 *
 * <p>注册是**通用代码**（任何模组在自己的 onInitialize 里调用即可，服务端调用安全——只是写注册表）；
 * 真正绘制在客户端由 {@code api.chasm.item.client.ChasmTooltipClient} 接上渲染事件。</p>
 *
 * <pre>{@code
 * ChasmItemTooltips.register(id("mymod","affix_lines"), (stack, flag, lines) -> {
 *     if (stack.has(MY_AFFIX_COMPONENT)) lines.add(Component.literal("…"));
 * });
 * }</pre>
 */
public final class ChasmItemTooltips {

	/** 提示行贡献者。{@code lines} 是可变列表，追加即显示（顺序 = 注册顺序）。 */
	@FunctionalInterface
	public interface Contributor {
		void append(ItemStack stack, TooltipFlag flag, List<Component> lines);
	}

	private static final Map<ResourceLocation, Contributor> CONTRIBUTORS = new ConcurrentHashMap<>();

	private ChasmItemTooltips() {
	}

	/** 注册提示行贡献者（幂等覆盖）。 */
	public static void register(ResourceLocation id, Contributor contributor) {
		if (id == null || contributor == null) {
			throw new IllegalArgumentException("提示行 id 与贡献者不可为 null");
		}
		CONTRIBUTORS.put(id, contributor);
	}

	public static int size() {
		return CONTRIBUTORS.size();
	}

	/**
	 * 把全部贡献者的提示行追加到 {@code lines}（客户端渲染时调用；逐个隔离异常）。
	 *
	 * @param stack 目标物品栈
	 * @param flag  原版提示标志（普通/高级提示）
	 * @param lines 原版已生成的提示行（可变）
	 */
	public static void appendAll(ItemStack stack, TooltipFlag flag, List<Component> lines) {
		if (CONTRIBUTORS.isEmpty() || stack == null || stack.isEmpty()) {
			return;
		}
		for (Map.Entry<ResourceLocation, Contributor> entry : CONTRIBUTORS.entrySet()) {
			try {
				entry.getValue().append(stack, flag, lines);
			} catch (RuntimeException e) {
				ChasmLogger.error("chasm", "提示行贡献者 {} 异常（已隔离）: {}", entry.getKey(), e.toString());
			}
		}
	}
}
