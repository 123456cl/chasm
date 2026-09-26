package api.chasm.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * 一条**物品注释**（显示在物品提示里、名字下方的注解行）。
 *
 * <p>和"改物品名"完全不同：名字只有一行，注释是**可叠加的多行**，像属性修饰符那样挂在物品下方。
 * 典型整合包用途：给装备打上"某劫难后废弃""某 Boss 掉落限定""赛季装备"之类的**标识**，
 * 既不破坏名字，又能让玩家一眼看懂。</p>
 *
 * <p>每条注释带唯一 {@code id}：所以追加是幂等的、移除是精确的（"给特定物品写入某个标识""之后再摘掉它"）。</p>
 *
 * @param id      注释标识（如 {@code mypack:retired}）—— 幂等覆盖、可按 id 精确移除
 * @param textKey 显示文本：翻译键或字面量（含 {@code .} 视为翻译键）
 * @param color   ARGB 颜色（0x00000000 = 用默认注释色）
 * @param order   排序权重（小的在上；同权重按 id 稳定排序）
 */
public record ChasmItemNote(ResourceLocation id, String textKey, int color, int order) {

	/** 默认注释色（原版注释偏灰紫）。 */
	public static final int DEFAULT_COLOR = 0xFFAAAAAA;

	public static final Codec<ChasmItemNote> CODEC = RecordCodecBuilder.create(inst -> inst.group(
		ResourceLocation.CODEC.fieldOf("id").forGetter(ChasmItemNote::id),
		Codec.STRING.fieldOf("text").forGetter(ChasmItemNote::textKey),
		Codec.INT.optionalFieldOf("color", DEFAULT_COLOR).forGetter(ChasmItemNote::color),
		Codec.INT.optionalFieldOf("order", 0).forGetter(ChasmItemNote::order)
	).apply(inst, ChasmItemNote::new));

	/** 便捷：用字面量文本 + 默认颜色。 */
	public static ChasmItemNote of(ResourceLocation id, String text) {
		return new ChasmItemNote(id, text, DEFAULT_COLOR, 0);
	}

	/** 便捷：字面量文本 + 颜色 + 排序。 */
	public static ChasmItemNote of(ResourceLocation id, String text, int color, int order) {
		return new ChasmItemNote(id, text, color, order);
	}

	/** 渲染成一行文本（{@code .} 视为翻译键，否则字面量；颜色为 0 时用默认注释色）。 */
	public MutableComponent render() {
		Component base = textKey.indexOf(46) >= 0 ? Component.translatable(textKey) : Component.literal(textKey);
		return base.copy().withStyle(Style.EMPTY.withColor(color == 0 ? DEFAULT_COLOR & 0xFFFFFF : color & 0xFFFFFF));
	}
}
