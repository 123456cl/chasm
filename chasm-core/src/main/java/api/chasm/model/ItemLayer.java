package api.chasm.model;

import net.minecraft.resources.ResourceLocation;

/**
 * **物品外观的一层**：一张贴图 + 一个染色值。
 *
 * <p>真实模组（如 Tetra）的模块化物品外观是**多层贴图叠出来的**：装了什么模块、什么材料，
 * 就决定有哪些层、各层什么颜色。框架以前只会给物品画一张平面贴图，这一层能力完全缺失。</p>
 *
 * @param texture 贴图 id（如 {@code tetra:item/module/sword/blade/short/metal}，不带 .png）
 * @param tint    染色（ARGB；{@code 0xFFFFFFFF} = 不染色）。真实 Tetra 里由材料的 tints 决定
 */
public record ItemLayer(ResourceLocation texture, int tint) {

	/** 不染色的一层。 */
	public static ItemLayer of(ResourceLocation texture) {
		return new ItemLayer(texture, 0xFFFFFFFF);
	}
}
