package api.chasm.model;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * **把一个物品模型包成"按栈叠层"的模型**（客户端；由 {@link ChasmItemLayerModels} 在烘焙钩子里挂上）。
 *
 * <h2>为什么必须是"每帧按栈取模型"</h2>
 * <p>{@link ItemLayerProvider} 按**栈**求值，而 baked model 按**物品**烘焙一次。1.21.1 的物品渲染路径里
 * 唯一的按栈切换点就是 {@code ItemOverrides}：{@code ItemRenderer.getModel}
 * 先取物品模型（{@code ItemModelShaper.getItemModel}），再调
 * {@code BakedModel.getOverrides().resolve(model, stack, level, entity, seed)}
 * —— 反汇编 {@code ItemRenderer#getModel} 第 518-538 行。<b>所以本类只重写 {@code getOverrides()}，
 * 让 {@code resolve} 按栈返回"叠好层的模型"。</b>（同一条路真实 Tetra 也走过：
 * {@code _ref/Tetra-1.20/.../client/model/ModularOverrideList.java:42} {@code extends ItemOverrides}。）</p>
 *
 * <h2>渲染路径必须是"原版路径"</h2>
 * <p>本类继承 Fabric 的 {@code ForwardingBakedModel}，它把 {@code isVanillaAdapter()} 转发给被包模型
 * （反汇编 {@code ForwardingBakedModel#isVanillaAdapter}：{@code wrapped.isVanillaAdapter()}），
 * 于是 Indigo 的 {@code ItemRendererMixin.hook_renderItem} 会走
 * {@code if (!model.isVanillaAdapter()) {...cancel}} 的**否分支** —— 也就是原版
 * {@code renderModelLists → renderQuadList}。这很关键：只有原版路径才会调用
 * {@code ItemColors.getColor(stack, quad.getTintIndex())}（反汇编 {@code ItemRenderer#renderQuadList}
 * 第 443-456 行），我们的图层染色才生效。</p>
 *
 * <h2>染色为什么走 tint index 而不是顶点色</h2>
 * <p>原版物品渲染**丢弃** quad 自带的顶点色：{@code renderQuadList} 调的是 8 参数
 * {@code putBulkData(pose, quad, r, g, b, a, light, overlay)}，而它内部固定传
 * {@code readExistingColor = false}（反汇编 {@code VertexConsumer#putBulkData} 第 121-169 行，
 * 第 167 行 {@code iconst_0}），走的是"颜色只由入参决定"的分支（第 296-319 行）。
 * 所以每层染色只能通过 **tint index → {@code ItemColors}** 表达（原版皮革盔甲就是这么分层的）。</p>
 * <p>约定：第 i 层的 tint index = {@link #TINT_BASE} + i，低于 {@link #TINT_BASE} 的索引交还给
 * 别的颜色提供者（没有就返回 -1 = 白色），因此**未注册物品、以及本物品的普通平面 quad 颜色完全不变**。</p>
 *
 * <h2>几何从哪来（2026-09-20 修正）</h2>
 * <p><b>每一层按"该层贴图自己的不透明像素"现生成</b>，由 {@link ChasmItemGeometry} 调用原版
 * {@code ItemModelGenerator} + {@code FaceBakery} 完成 —— 与真实 Tetra 逐行同构：</p>
 * <pre>
 * 真实：{@code ItemLayerModel.java:55-63} 逐层 spriteGetter.apply(textures.get(i))
 *       → UnbakedGeometryHelper.createUnbakedItemElements(i, sprite.contents())
 *       （Forge 源码：{@code ITEM_MODEL_GENERATOR.processFrames(layerIndex, "layer"+layerIndex, spriteContents)}）
 *       → bakeElements(...)
 * 这里：{@link ChasmItemGeometry#bake(TextureAtlasSprite, int, List, Map)} 逐层做同一件事
 * </pre>
 * <p><b>曾经的错误做法</b>（本次修掉的根因）：当被包模型自己没有几何时（我们那份
 * {@code assets/tetra/models/item/modular_sword.json} 只有 {@code parent: item/generated}、
 * 没有 {@code textures}，原版 {@code ItemModelGenerator} 的
 * {@code if (!model.hasTexture("layer0")) break;} 直接跳出 → 零元素），
 * 旧实现改为<b>借 {@code minecraft:item/paper} 的 quad 当模板</b>，再把 UV 从 paper 的图集区域线性
 * 重映射到该层贴图的区域。后果是三件事同时错：</p>
 * <ol>
 *   <li>{@code paper} 的几何是**纸的轮廓**，剑自己的轮廓一个侧壁都没有 →
 *       正面一张图、侧面没有"体"（"只有一个面，后面是空的"）；</li>
 *   <li>原版 {@code processFrames} 会按 paper 的不透明横向段补出**几十个侧壁元素**
 *       （实测 {@code paper.png} 生成 31 个 element，其中 30 个是侧壁），它们被原样复制到每一层
 *       → 方向/位置全是纸的（"有一点还竖竖的"）；</li>
 *   <li>几何是 paper 的、UV 却被换成剑贴图的 → 采到贴图里不相干的像素（"有些图片歪了"）、
 *       轮廓凹陷处露底（"拼装的时候有些缝隙"）。</li>
 * </ol>
 * <p>新实现不再需要任何"借几何 + UV 重映射"：几何与 UV 都由原版按该层 sprite 一次算出。</p>
 *
 * <h2>姿态变换（display）通道（2026-09-23 新增）</h2>
 * <p>分层只是外观的一半：真实 Tetra 的盾有**收起/格挡/投掷三份物品模型**，三份的差别
 * **只有 {@code display} 块**（{@code assets/tetra/models/item/modular_shield.json} 的
 * {@code overrides} 指向 {@code modular_shield_blocking}/_throwing）。所以本类除了逐层生成几何，
 * 还让 {@link ItemLayerProvider#displayOf} 能把"这个状态的姿态变换"交上来，
 * 由 {@link LayeredVariant#getTransforms()} 交给原版施加 —— 生效点是
 * {@code ItemRenderer#render} 里的 {@code BakedModel.getTransforms()}
 * （{@code javap -c -p net.minecraft.client.renderer.entity.ItemRenderer}：{@code render} offset 101
 * 取 {@code getTransforms()}、offset 107 取 {@code ItemTransforms.getTransform(ItemDisplayContext)}），
 * 而那个模型正是 {@code ItemOverrides.resolve}（offset 98）返回的本类实例。
 * <b>零 mixin、也不需要 {@code BuiltinItemRendererRegistry}。</b></p>
 * <p>帧只算一次（{@code ChasmItemLayers.frameOf}），图层与姿态共用同一个帧 ——
 * 否则会出现"格挡的贴图 + 收起的姿势"。姿态进模型缓存的键（见 {@link #variants}）。</p>
 *
 * <h2>层序 = 绘制顺序</h2>
 * <p>quad 的追加顺序就是 {@link ItemLayerProvider} 给出的图层顺序（真实 Tetra 的
 * {@code ModularOverrideList.createLayerModel:121-144} 也是"按 models 顺序 addQuads"）。
 * 物品渲染不写深度、靠 alpha 混合叠加，所以**后面的层画在上面**。</p>
 *
 * <h2>复杂度</h2>
 * <ul>
 *   <li>{@code resolve}：{@code O(1)}（数据组件内容哈希查表 + 图层表身份查表，见 {@link ChasmItemLayerCache}）。</li>
 *   <li>建模型：只在 miss 时发生，{@code O(层数 × 256 像素 + 层数 × 该层面数)}，结果按图层表实例缓存。</li>
 *   <li>没有全表扫描；每帧不新建 quad、不新建集合。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class LayeredItemModel extends ForwardingBakedModel {

	/** 图层 quad 的 tint index 起点。原版物品模型的 tint index 只用到 0-2，取 1000 不会撞。 */
	static final int TINT_BASE = 1000;

	private final Item item;
	private final Function<Material, TextureAtlasSprite> spriteGetter;
	private final ChasmItemLayerCache layers;
	private final ItemOverrides overrides = new LayerOverrides();

	/**
	 * **（图层表, 姿态变换）→ 已建模型**。
	 *
	 * <p>键必须是这一对：同一个图层表在不同状态下可能是**同一个贴图、不同的 display**
	 * （盾的三态就是如此 —— 贴图层不随帧变化，差的全是 display）。只拿图层表当键会把
	 * "格挡的姿势"发给"收起的手"，所以姿态必须进键。</p>
	 *
	 * <p>相等性是**内容**相等（{@link ItemLayer} 与 {@link ItemDisplay} 都是记录／不可变值），
	 * 与旧实现的身份表相比更宽一点但语义更正确：内容相同的两个图层表本来就该复用同一个模型。</p>
	 */
	private final Map<VariantKey, BakedModel> variants = new HashMap<>();
	private long seenEpoch = Long.MIN_VALUE;

	/** 一次"叠好层 + 摆好姿势"的结果的键。{@code display} 为 null = 不表态（沿用被包模型）。 */
	private record VariantKey(List<ItemLayer> layers, ItemDisplay display) {
	}

	LayeredItemModel(BakedModel wrapped, Item item, Function<Material, TextureAtlasSprite> spriteGetter) {
		super(wrapped);
		this.item = item;
		this.spriteGetter = spriteGetter;
		this.layers = new ChasmItemLayerCache(item);
	}

	Item item() {
		return item;
	}

	/**
	 * 该栈当前的图层（染色提供者用；命中 {@code O(1)}）。
	 *
	 * <p>用**最近一次 {@link #modelFor(ItemStack, LivingEntity)} 求值时的帧**：染色路径
	 * （{@code ItemColors.getColor(stack, tintIndex)}）没有实体入参，只能沿用它，否则拉弓动画帧的
	 * 颜色会与刚建的几何张冠李戴。没有任何求值记录时退化为无帧（{@code ""}）—— 与旧行为一致。</p>
	 */
	List<ItemLayer> layersOf(ItemStack stack) {
		return layers.layersOf(stack, layers.frameFor(stack));
	}

	@Override
	public ItemOverrides getOverrides() {
		return overrides;
	}

	/**
	 * 按栈取"叠好层的模型"（无持有者版本，等价于 {@code modelFor(stack, null)}）。
	 *
	 * @return 有图层 → 叠层模型；没有图层/建不出来 → {@code this}（= 原来的单张贴图，行为完全不变）
	 */
	BakedModel modelFor(ItemStack stack) {
		return modelFor(stack, null);
	}

	/**
	 * **按栈 + 持有者取"叠好层的模型"**（{@code ItemOverrides.resolve} 的真实入口）。
	 *
	 * <p>持有者只用来算**动画帧**（{@link ItemLayerProvider#frameOf}）：帧变了图层表就不同，
	 * 于是"拉弓"与"静止"各得其所。没实现帧语义的 provider 会走默认方法直接转发 ——
	 * 它们的物品行为与从前逐字一致。</p>
	 *
	 * @param entity 持有该物品的实体；null（GUI/物品栏/未知）= 无帧
	 * @return 有图层 → 叠层模型；没有图层/建不出来 → {@code this}（= 原来的单张贴图，行为完全不变）
	 */
	BakedModel modelFor(ItemStack stack, LivingEntity entity) {
		if (stack == null || stack.isEmpty()) {
			return this;
		}
		long epoch = ChasmItemLayerCache.epoch();
		if (epoch != seenEpoch) {
			variants.clear(); // 数据重载后图层表实例会换新，旧映射必须丢
			seenEpoch = epoch;
		}
		// 帧只算一次：图层与姿态必须用**同一个**帧，否则会出现"格挡的贴图 + 收起的姿势"
		String frame = ChasmItemLayers.frameOf(stack, entity);
		List<ItemLayer> layerList = layers.layersOf(stack, frame);
		if (layerList.isEmpty()) {
			return this;
		}
		// display 通道（默认实现在 ItemLayerProvider 里返回 null = 这个物品不表态）
		VariantKey key = new VariantKey(layerList, ChasmItemLayers.displayOf(stack, frame));
		BakedModel cached = variants.get(key);
		if (cached != null) {
			return cached;
		}
		BakedModel built = build(key);
		if (built == null) {
			return this;
		}
		ChasmItemLayerModels.ensureTint(item); // 有图层才需要颜色提供者（tint index ≥ TINT_BASE）
		if (variants.size() >= ChasmItemLayerCache.MAX_ENTRIES) {
			variants.clear();
		}
		variants.put(key, built);
		return built;
	}

	/**
	 * 逐层生成几何（{@link ChasmItemGeometry}）；任何一步拿不到（贴图缺失/异常）都退化为"少这一层"，
	 * 一层都建不出来时返回 {@code null} → 调用方回落到原模型。
	 */
	private BakedModel build(VariantKey key) {
		try {
			List<ItemLayer> layerList = key.layers();
			List<BakedQuad> unculled = new ArrayList<>(layerList.size() * 4);
			Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
			for (int i = 0; i < layerList.size(); i++) {
				TextureAtlasSprite sprite = sprite(layerList.get(i).texture());
				if (sprite == null) {
					continue; // 贴图拿不到 → 丢这一层（画"缺失贴图"比不画更糟）
				}
				// tintIndex = 层序：把"这一层"交代给 ItemColors（见类注释"染色为什么走 tint index"）
				ChasmItemGeometry.bake(sprite, TINT_BASE + i, unculled, culled);
			}
			if (unculled.isEmpty() && culled.isEmpty()) {
				return null; // 一层都没落下来（贴图全拿不到）→ 原模型
			}
			// 不表态（display == null）→ null，LayeredVariant 会转发被包模型的变换
			return new LayeredVariant(this, unculled, culled, ItemDisplays.toVanilla(key.display()));
		} catch (Throwable t) {
			ChasmItemLayerModels.warnOnce("build:" + item, "物品 {} 的图层模型构建失败（已退回原模型）: {}",
				item, t.toString());
			return null;
		}
	}

	/**
	 * 取贴图。物品贴图在方块图集里（与真实 Tetra 一致：{@code ModularOverrideList.java:123}）。
	 *
	 * <p>用 {@code InventoryMenu.BLOCK_ATLAS} 而不是 {@code TextureAtlas.LOCATION_BLOCKS}：
	 * 两者是同一个 id（都是 {@code minecraft:textures/atlas/blocks.png}，
	 * 见 {@code InventoryMenu.<clinit>} 反汇编第 0-6 行），但后者在 1.21.1 被标了 {@code @Deprecated}
	 * （反汇编 {@code TextureAtlas.LOCATION_BLOCKS} 带 {@code Deprecated: true}）。</p>
	 */
	private TextureAtlasSprite sprite(ResourceLocation texture) {
		if (texture == null || spriteGetter == null) {
			return null;
		}
		try {
			return spriteGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, texture));
		} catch (Throwable t) {
			ChasmItemLayerModels.warnOnce("sprite:" + texture, "贴图 {} 取不到（跳过该层）: {}", texture, t.toString());
			return null;
		}
	}

	/**
	 * 叠好层的模型（每栈一个，缓存在 {@link #variants} 里）。
	 *
	 * <p>除 {@code getQuads} 与 {@link #getTransforms()} 外全部转发给包装模型 ——
	 * {@code isGui3d}/{@code useAmbientOcclusion}/{@code usesBlockLight}、粒子图标都保持原样，
	 * 渲染类型也仍由原版 {@code ItemBlockRenderTypes.getRenderType(stack, fabulous)} 决定
	 * （反汇编 {@code ItemRenderer#render} 第 246 行）。</p>
	 *
	 * <p>{@link #getTransforms()} 只在**提供方通过 {@link ItemLayerProvider#displayOf} 表了态**时
	 * 才不转发（那时它返回该状态的姿态表）—— 没表态的物品/状态与从前逐字一致。</p>
	 */
	private static final class LayeredVariant extends ForwardingBakedModel {

		private final List<BakedQuad> unculled;
		private final Map<Direction, List<BakedQuad>> culled;
		/** 该状态的姿态变换；{@code null} = 不表态 → 沿用被包模型（{@link #getTransforms()}）。 */
		private final ItemTransforms transforms;

		LayeredVariant(BakedModel wrapped, List<BakedQuad> unculled, Map<Direction, List<BakedQuad>> culled,
				ItemTransforms transforms) {
			super(wrapped);
			this.unculled = List.copyOf(unculled);
			this.culled = culled;
			this.transforms = transforms;
		}

		/**
		 * **该状态的姿态变换**（{@code ItemRenderer#render} 里真的会读它）。
		 *
		 * <p>1.21.1 的调用点（{@code javap -c -p net.minecraft.client.renderer.entity.ItemRenderer}）：
		 * {@code render(...)} 在 offset 101 先
		 * {@code invokeinterface BakedModel.getTransforms()}，紧接 offset 107
		 * {@code invokevirtual ItemTransforms.getTransform(ItemDisplayContext)} 再 {@code apply}；
		 * 而传进 {@code render} 的那个模型正是 {@code getModel(...)} 在 offset 98 调
		 * {@code ItemOverrides.resolve} 拿到的 —— 也就是本类。所以覆写这里就是**原版路径上的真实生效点**，
		 * 不需要 mixin，也不需要 {@code BuiltinItemRendererRegistry}。</p>
		 */
		@Override
		public ItemTransforms getTransforms() {
			// null → super.getTransforms() → 被包模型（LayeredItemModel → 原始物品模型）：与从前逐字一致
			return transforms == null ? super.getTransforms() : transforms;
		}

		@Override
		public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
			if (state != null) {
				return wrapped.getQuads(state, direction, random); // 方块查询不该走到这里：原样转发
			}
			if (direction == null) {
				return unculled;
			}
			List<BakedQuad> quads = culled.get(direction);
			return quads == null ? List.of() : quads;
		}

		@Override
		public ItemOverrides getOverrides() {
			// 叠层模型不再参与"按栈切换"，避免万一有人再 resolve 一次造成递归
			return ItemOverrides.EMPTY;
		}
	}

	/** 按栈切换的入口（{@code ItemRenderer.getModel} 每帧都会调它）。 */
	private final class LayerOverrides extends ItemOverrides {

		LayerOverrides() {
			// ItemOverrides 的无参构造器是 private，只能走这个公开构造器；
			// 传空 override 表时构造器不会解引用 baker/model（反汇编第 25-108 行：解引用只在非空循环体内）
			super(null, null, List.of());
		}

		@Override
		public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
			try {
				// entity 只用来算动画帧（拉弓进度），见 modelFor(stack, entity)
				return modelFor(stack, entity);
			} catch (Throwable t) {
				// 渲染线程上抛异常 = 整个客户端崩；出事就退回原模型
				ChasmItemLayerModels.warnOnce("resolve:" + item, "物品 {} 的按栈取模型失败（已退回原模型）: {}",
					item, t.toString());
				return model == null ? LayeredItemModel.this : model;
			}
		}
	}
}
