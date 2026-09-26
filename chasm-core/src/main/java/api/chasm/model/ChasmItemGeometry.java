package api.chasm.model;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * **图层几何生成**：把一张贴图变成"物品形状的那一片带厚度的网格"（客户端；框架内部件）。
 *
 * <h2>为什么必须是"每层按自己的贴图现生成"，而不是复用某个现成模型的 quad</h2>
 * <p>真实 Tetra 的每一层都是**按该层贴图自己的不透明像素**临时生成几何的：
 * {@code _ref/Tetra-1.20/tetra-1.20/src/main/java/se/mickelus/tetra/client/model/ItemLayerModel.java:55-63}</p>
 * <pre>
 * for (int i = 0; i &lt; textures.size(); i++) {
 *     TextureAtlasSprite sprite = spriteGetter.apply(textures.get(i));
 *     List&lt;BlockElement&gt; unbaked = UnbakedGeometryHelper.createUnbakedItemElements(i, sprite.contents());
 *     List&lt;BakedQuad&gt; quads = UnbakedGeometryHelper.bakeElements(unbaked, $ -&gt; sprite, modelState, modelLocation);
 * }</pre>
 * <p>Forge 的 {@code UnbakedGeometryHelper.createUnbakedItemElements(int, SpriteContents)}
 * （{@code MinecraftForge/MinecraftForge 1.20.x .../client/model/geometry/UnbakedGeometryHelper.java}）就是
 * 一行转发：{@code ITEM_MODEL_GENERATOR.processFrames(layerIndex, "layer" + layerIndex, spriteContents);}</p>
 * <p>而 {@code ItemModelGenerator.processFrames} 的字节码（{@code javap -c -p
 * net.minecraft.client.renderer.block.model.ItemModelGenerator}）证明它做三件事：</p>
 * <ol>
 *   <li>先放**一个铺满 16×16、带 1/16 厚度的长方体**
 *       {@code new BlockElement(new Vector3f(0,0,7.5F), new Vector3f(16,16,8.5F), faces, null, true)}
 *       （字节码 {@code ldc #31 // float 7.5f} / {@code ldc #33 // float 8.5f}）；</li>
 *   <li>它的两个面是 {@code Direction.SOUTH}（uv {@code [0,0,16,16]}）与 {@code Direction.NORTH}
 *       （uv {@code [16,0,0,16]}，**水平镜像**）—— 这就是"正面 + 背面"，背面不是空的；</li>
 *   <li>再调 {@code createSideElements(sprite, texture, layer)} 按该贴图的**不透明横向段**补侧壁。</li>
 * </ol>
 * <p>所以<b>层的形状来自贴图自己</b>：剑柄的贴图只有左下角一小块不透明，生成的侧壁就只在那一小块上。
 * 本类把这条链路原样搬到 1.21.1 Fabric：调用原版 {@link ItemModelGenerator}（不带任何自造几何），
 * 再用原版 {@link FaceBakery} 烘焙顶点。**没有自己猜顶点顺序、没有自己算 UV**。</p>
 *
 * <h2>UV 为什么天然是对的</h2>
 * <p>UV 来自 {@code BlockElementFace.uv()}（元素空间 0..16），{@link FaceBakery} 内部会调
 * {@code TextureAtlasSprite.getU(float)/getV(float)} 把它换算进**该层 sprite 自己的图集区域**。
 * 所以不需要任何"从旧贴图区域线性重映射到新贴图区域"的后处理 —— 那正是错位/歪斜的来源。</p>
 *
 * <h2>层间为什么没有缝</h2>
 * <p>原版（以及真实 Tetra）**不给层做 z 偏移**：每层都是同一个 z 区间 {@code [7.5, 8.5]}，
 * 靠绘制顺序（见 {@link LayeredItemModel}）叠出外观。本类保持同一约定 ——
 * 层间共面且完全重叠，不会因为错开而露出后一层的边。</p>
 *
 * <h2>复杂度</h2>
 * <p>每次调用 {@code O(贴图像素数 16×16 + 生成的面数)}，且**只在建"某个栈的图层模型"时发生一次**
 * （结果按图层表缓存，见 {@link LayeredItemModel#modelFor}）。渲染热路径上零分配。</p>
 */
@Environment(EnvType.CLIENT)
public final class ChasmItemGeometry {

	/** 原版物品层的 z 区间（{@code ItemModelGenerator} 的 {@code MIN_Z}/{@code MAX_Z}，字节码 {@code 7.5f}/{@code 8.5f}）。 */
	public static final float MIN_Z = 7.5F;

	/** 见 {@link #MIN_Z}。 */
	public static final float MAX_Z = 8.5F;

	/** 原版 {@code ItemModelGenerator.LAYERS} 的第一项；我们每层只声明这一个槽（等价真实 "layer" + layerIndex）。 */
	private static final String SLOT = "layer0";

	/** 原版生成器：**唯一的几何来源**（真实 Tetra 走的就是它，见类注释）。 */
	private static final ItemModelGenerator GENERATOR = new ItemModelGenerator();

	/** 原版烘焙器：把 (元素, 面) 变成顶点。 */
	private static final FaceBakery BAKERY = new FaceBakery();

	/** 不做任何模型旋转（物品模型烘焙的固定约定，真实 {@code UnbakedGeometryHelper.bakeElements} 用 modelState 原值）。 */
	private static final BlockModelRotation NO_ROTATION = BlockModelRotation.X0_Y0;

	private ChasmItemGeometry() {
	}

	/**
	 * 把一层贴图烘成 quad 并追加到给定的两个表里。
	 *
	 * @param sprite    该层的贴图（**非 null**；调用方负责在拿不到时跳过这一层）
	 * @param tintIndex 该层 quad 的 tint index（由调用方按层序给出，见 {@link LayeredItemModel}）
	 * @param unculled  输出：没有 cull 方向的面（物品渲染走的就是这一张表）
	 * @param culled    输出：带 cull 方向的面，按方向分桶（**必须是可变 Map**）
	 */
	public static void bake(TextureAtlasSprite sprite, int tintIndex,
			List<BakedQuad> unculled, Map<Direction, List<BakedQuad>> culled) {
		if (sprite == null || unculled == null || culled == null) {
			return;
		}
		for (BlockElement element : elements(sprite)) {
			for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
				Direction side = entry.getKey();
				BlockElementFace face = entry.getValue();
				// 原版 processFrames 把 tintIndex 写成 layerIndex（我们恒为 0）；这里换成"这个物品里的第几层"。
				BlockElementFace tinted = face.tintIndex() == tintIndex ? face
					: new BlockElementFace(face.cullForDirection(), tintIndex, face.texture(), face.uv());
				// 与原版 BlockModel.bakeFace / Forge UnbakedGeometryHelper.bakeElementFace 同一行写法
				BakedQuad quad = BAKERY.bakeQuad(element.from, element.to, tinted, sprite, side,
					NO_ROTATION, element.rotation, element.shade);
				Direction cull = tinted.cullForDirection();
				if (cull == null) {
					unculled.add(quad);
				} else {
					Direction rotated = Direction.rotate(NO_ROTATION.getRotation().getMatrix(), cull);
					List<BakedQuad> bucket = culled.get(rotated);
					if (bucket == null) {
						bucket = new ArrayList<>(1);
						culled.put(rotated, bucket);
					}
					bucket.add(quad);
				}
			}
		}
	}

	/** 只取"没有 cull 方向"的那些面（物品渲染只需要这一张表）。 */
	public static List<BakedQuad> quadsFor(TextureAtlasSprite sprite, int tintIndex) {
		if (sprite == null) {
			return List.of();
		}
		List<BakedQuad> unculled = new ArrayList<>();
		bake(sprite, tintIndex, unculled, new EnumMap<>(Direction.class));
		return unculled.isEmpty() ? List.of() : List.copyOf(unculled);
	}

	/**
	 * 让原版生成器按该贴图产出一组元素。
	 *
	 * <p>喂给生成器的"模型"只用到一个字段：{@code textures.layer0}。
	 * {@code ItemModelGenerator.generateBlockModel} 的循环以
	 * {@code if (!model.hasTexture("layer0")) break;} 开头（字节码 {@code invokevirtual BlockModel.hasTexture} +
	 * {@code ifne}/{@code goto}），所以**只有声明了 layer0 才有几何** ——
	 * 这正是我们那份 {@code modular_sword.json}（只有 {@code parent:item/generated}、没有 {@code textures}）
	 * 在原版管线下产不出任何元素的原因，也是本类必须自己喂 texture 的原因。</p>
	 *
	 * <p>spriteGetter 恒返回这一层的 sprite：与真实 Tetra 把
	 * {@code $ -> sprite} 传进 {@code bakeElements} 完全等价
	 * （{@code ItemLayerModel.java:59}）。</p>
	 */
	private static List<BlockElement> elements(TextureAtlasSprite sprite) {
		ResourceLocation name = sprite.contents().name();
		if (name == null) {
			return List.of();
		}
		// 贴图 id 只含 [a-z0-9_./-] 与命名空间冒号，直接拼 JSON 不会破坏引号
		BlockModel template = BlockModel.fromString("{\"textures\":{\"" + SLOT + "\":\"" + name + "\"}}");
		List<BlockElement> elements = GENERATOR.generateBlockModel(material -> sprite, template).getElements();
		return elements == null ? List.of() : elements;
	}

	/** 供单测/调试：这一层会生成多少个元素（= 1 个"正背面"板 + N 个按贴图轮廓的侧壁）。 */
	public static int elementCount(TextureAtlasSprite sprite) {
		return sprite == null ? 0 : elements(sprite).size();
	}
}
