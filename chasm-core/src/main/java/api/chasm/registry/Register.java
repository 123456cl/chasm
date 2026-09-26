package api.chasm.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个静态字段为待注册的玩法对象。
 *
 * <p>注册目标由字段类型自动判定：{@code net.minecraft.world.item.Item} → 物品注册表，
 * 方块 → 方块注册表，以此类推（同一心智模型，按类型路由）。</p>
 *
 * <p>注册 id 取自本注解的 value（相对 {@link api.chasm.ChasmMod#id() modId}），
 * 例如 {@code @ChasmMod(id="mymod")} + {@code @Register("mana_sword")} →
 * {@code mymod:mana_sword}。</p>
 *
 * <pre>{@code
 * @Register("mana_sword")
 * public static final Item MANA_SWORD = Chasm.item().durability(1200).register();
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Register {

	/** 注册 id（不含命名空间，例如 "mana_sword"） */
	String value();
}
