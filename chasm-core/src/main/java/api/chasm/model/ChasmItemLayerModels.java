package api.chasm.model;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;

import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **物品外观分层子系统的客户端接线**（零 mixin，只用 Fabric 官方模型 API）。
 *
 * <h2>可用钩子清单（都是实打实读 jar 得到的，不是猜的）</h2>
 * <table border="1">
 *   <caption>1.21.1 + fabric-api 0.116.15 上可用的无 mixin 模型入口</caption>
 *   <tr><th>入口</th><th>签名</th><th>来源</th></tr>
 *   <tr><td>{@code ModelLoadingPlugin.register}</td>
 *       <td>{@code static void register(ModelLoadingPlugin)}</td>
 *       <td>{@code fabric-model-loading-api-v1-2.1.0+b4d813fc19.jar}
 *           （{@code net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin.class}）</td></tr>
 *   <tr><td>{@code ModelLoadingPlugin.Context#modifyModelAfterBake}</td>
 *       <td>{@code Event<ModelModifier.AfterBake> modifyModelAfterBake()}</td>
 *       <td>同上（{@code ModelLoadingPlugin$Context.class}）</td></tr>
 *   <tr><td>{@code ModelModifier.AfterBake}</td>
 *       <td>{@code @Nullable BakedModel modifyModelAfterBake(@Nullable BakedModel, Context)}</td>
 *       <td>同上（{@code ModelModifier$AfterBake.class}）</td></tr>
 *   <tr><td>{@code ModelModifier.WRAP_PHASE}</td>
 *       <td>{@code static final ResourceLocation WRAP_PHASE}</td>
 *       <td>同上（{@code ModelModifier.class}）</td></tr>
 *   <tr><td>{@code ForwardingBakedModel}（包模型的现成基类，转发
 *       {@code isVanillaAdapter()}）</td>
 *       <td>{@code public abstract class ForwardingBakedModel implements BakedModel}</td>
 *       <td>{@code fabric-renderer-api-v1-3.4.1+b4d813fc19.jar}</td></tr>
 *   <tr><td>{@code ColorProviderRegistry.ITEM}（给物品注册颜色提供者 =
 *       {@code ItemColors} 的官方入口）</td>
 *       <td>{@code ColorProviderRegistry<ItemLike, ItemColor> ITEM} /
 *           {@code void register(ItemColor, ItemLike...)} / {@code ItemColor get(ItemLike)}</td>
 *       <td>{@code fabric-rendering-v1-5.1.0+ab4c25a019.jar}</td></tr>
 * </table>
 * <p><b>不存在的入口（同样是读 jar 得到的结论，避免以后白找）</b>：
 * {@code ItemModelProvider} 在 fabric-api 0.116.15 里没有；
 * {@code FabricBakedModel}（model-loading 版）在这一代的 {@code fabric-model-loading-api-v1} 里已经删掉了
 * —— 该 jar 的 {@code net/fabricmc/fabric/api/client/model/loading/v1/} 下只有
 * {@code BlockStateResolver / DelegatingUnbakedModel / FabricBakedModelManager / ModelLoadingPlugin /
 * ModelModifier / ModelResolver / PreparableModelLoadingPlugin}。
 * （注意别与 Indigo 的 {@code net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel} 混淆，
 * 那是渲染器接口，1.21.1 的 {@code BakedModel} 继承了它，方法全是 default。）</p>
 *
 * <h2>为什么用 {@code modifyModelAfterBake} 而不是 ModelResolver / 自定义 loader</h2>
 * <p>1.21.1 的物品模型路径是：{@code ModelBakery.loadItemModelAndDependencies(物品 id)}
 * → {@code ModelResourceLocation.inventory(物品 id)}（变体名固定 {@code "inventory"}）
 * → {@code topLevelModels.put(mrl, 未烘焙模型)}（反汇编 {@code ModelBakery} 第 314-331、374-382 行）；
 * 之后 {@code bakeModels} 遍历 {@code topLevelModels} 逐个烘焙（反汇编第 0-16 行），
 * Fabric 的 mixin 正是在这一步把事件发出来，且**顶模型 id 走 {@code topLevelId}、{@code resourceId} 为 null**
 * （{@code ModelLoaderMixin} 源码第 169-180 行）。所以：</p>
 * <ul>
 *   <li>{@code ctx.topLevelId()} 非空且 {@code variant().equals("inventory")} → 这就是某个物品的物品模型；</li>
 *   <li>{@code topLevelId().id()} 就是物品注册 id（{@code ModelResourceLocation.inventory} 实现见反汇编：
 *       {@code new ModelResourceLocation(id, "inventory")}），O(1) 查注册表即得 {@code Item}；</li>
 *   <li>该物品没注册 provider 就**原样返回同一个模型实例** —— 未注册物品行为完全不变。</li>
 * </ul>
 *
 * <h2>插件注册时机</h2>
 * <p>{@code ModelLoadingPlugin.register} 只是往一个静态表里追加
 * （{@code ModelLoadingPluginManager.registerPlugin} 源码第 35-39 行：{@code PLUGINS.add(plugin)}），
 * 不要求"客户端初始化期"。因此本子系统在**第一次 {@code ChasmItemLayers.register(...)} 时**懒注册，
 * 并先做 {@code EnvType.CLIENT} 判断 —— 专用服务器上这套客户端类根本不会被加载，也不需要改
 * {@code fabric.mod.json} 加客户端入口点。代价：模组必须在**首次资源加载前**注册 provider
 * （正常模组初始化都在这个窗口内）；晚注册要等一次资源重载（F3+T / {@code /reload}）才生效。</p>
 *
 * <h2>为什么染色要另外注册颜色提供者</h2>
 * <p>见 {@link LayeredItemModel} 的说明：原版物品渲染丢弃 quad 顶点色，颜色只能走
 * {@code ItemColors.getColor(stack, tintIndex)}（反汇编 {@code ItemRenderer#renderQuadList} 第 443-456 行）。
 * 我们给第 i 层发 {@code tintIndex = 1000 + i}，颜色提供者按此还原
 * {@link ItemLayer#tint()}；低于 1000 的索引一律交还给"原来那个提供者"（没有就返回 -1 = 白色），
 * 于是**这个物品的其它 quad（包括没走分层时的原版平面 quad）颜色与注册前完全一致**。</p>
 */
@Environment(EnvType.CLIENT)
public final class ChasmItemLayerModels implements ModelLoadingPlugin {

	/** 已包装的物品 → 当前生效的包装模型（每次资源重载会换成新实例）。 */
	static final Map<Item, LayeredItemModel> ACTIVE = new ConcurrentHashMap<>();

	/** 已经挂过颜色提供者的物品（含挂失败后不再重试的）。 */
	private static final Map<Item, ItemColor> TINTED = new ConcurrentHashMap<>();

	/** 告警去重（渲染线程上不能刷屏）。 */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	/** 装不上颜色提供者时的退化实现：-1 = 白色，等同"没注册过"。 */
	private static final ItemColor FAILED = (stack, tintIndex) -> -1;

	private static volatile boolean installed;

	private ChasmItemLayerModels() {
	}

	/** 懒注册模型插件（幂等；由 {@link ChasmItemLayers#register} 触发）。 */
	static void ensureInstalled() {
		if (installed) {
			return;
		}
		synchronized (ChasmItemLayerModels.class) {
			if (installed) {
				return;
			}
			installed = true;
			ModelLoadingPlugin.register(new ChasmItemLayerModels());
		}
	}

	/**
	 * 让所有图层缓存（图层表 + 已建模型）失效。
	 *
	 * <p>资源重载不需要调它：模型会整批重烘焙，包装模型与其缓存一起重建。
	 * 只有"改了外观数据但没有触发资源/模型重载"的自定义链路才需要。</p>
	 */
	public static void invalidateCaches() {
		ChasmItemLayerCache.invalidateAll();
	}

	@Override
	public void onInitializeModelLoader(ModelLoadingPlugin.Context context) {
		// WRAP_PHASE：包装型改动（Fabric 约定的相位之一，见 ModelModifier.WRAP_PHASE）
		context.modifyModelAfterBake().register(ModelModifier.WRAP_PHASE, ChasmItemLayerModels::afterBake);
	}

	/** 烘焙钩子：只动"注册过 provider 的物品的物品模型"。 */
	private static BakedModel afterBake(BakedModel model, ModelModifier.AfterBake.Context context) {
		if (model == null) {
			return null; // 钩子允许收到/返回 null（缺失模型），照原样传下去
		}
		try {
			ModelResourceLocation topLevelId = context.topLevelId();
			if (topLevelId == null || !ModelResourceLocation.INVENTORY_VARIANT.equals(topLevelId.variant())) {
				return model; // 方块模型/未烘焙顶模型：不是物品模型，不碰
			}
			Item item = BuiltInRegistries.ITEM.get(topLevelId.id());
			if (item == null || item == Items.AIR) {
				return model;
			}
			if (ChasmItemLayers.providerOf(item) == null) {
				return model; // 兼容底线：没有注册外观来源的物品走原路，连包装对象都不建
			}
			// 只要 spriteGetter：几何按每层自己的贴图现生成（见 LayeredItemModel 的"几何从哪来"），
			// 不再需要 ModelBaker 去借别的模型的几何
			LayeredItemModel wrapper = new LayeredItemModel(model, item, context.textureGetter());
			ACTIVE.put(item, wrapper);
			return wrapper;
		} catch (Throwable t) {
			// 模型烘焙期抛异常会连带整个资源重载失败（最坏是进不去游戏），所以一律退回原模型
			warnOnce("bake", "物品外观分层包装失败（已退回原模型）: {}", t.toString());
			return model;
		}
	}

	/** 给该物品挂上"图层染色"提供者（幂等；会串到原来那个提供者后面）。 */
	static void ensureTint(Item item) {
		if (item == null || TINTED.containsKey(item)) {
			return;
		}
		TINTED.computeIfAbsent(item, key -> {
			try {
				ItemColor previous = ColorProviderRegistry.ITEM.get(key); // 可能是原版/别的模组注册的
				ItemColor mine = new LayerTint(previous);
				ColorProviderRegistry.ITEM.register(mine, key);
				return mine;
			} catch (Throwable t) {
				warnOnce("tint:" + key, "物品 {} 的图层染色注册失败（该物品的层不染色）: {}", key, t.toString());
				return FAILED;
			}
		});
	}

	/**
	 * 颜色查询（{@code ItemColors.getColor(stack, tintIndex)} → 这里）。
	 *
	 * @return {@code tintIndex < 1000} 时交还原来那个提供者（没有则 -1 = 白色，等价于"没注册过"）；
	 *         否则返回第 {@code tintIndex - 1000} 层的染色
	 */
	static int tintOf(ItemStack stack, int tintIndex) {
		if (tintIndex < LayeredItemModel.TINT_BASE) {
			return -1;
		}
		return layerTintOf(stack, tintIndex - LayeredItemModel.TINT_BASE);
	}

	/** 第 {@code index} 层的 ARGB（查不到就 -1 = 白色）。 */
	static int layerTintOf(ItemStack stack, int index) {
		if (stack == null || stack.isEmpty() || index < 0) {
			return -1;
		}
		LayeredItemModel model = ACTIVE.get(stack.getItem());
		if (model == null) {
			return -1; // 没包装模型就不该被问到（防御：绝不在染色路径上建模型）
		}
		List<ItemLayer> layers = model.layersOf(stack); // 与 resolve 走同一张缓存 → O(1) 命中
		if (index >= layers.size()) {
			return -1;
		}
		return opaque(layers.get(index).tint());
	}

	/** alpha 位为 0 视为不透明（真实 {@code ColorQuadTransformer.java:10-12} 的判据）。 */
	static int opaque(int argb) {
		return ((argb >>> 24) & 0xFF) == 0 ? (argb | 0xFF000000) : argb;
	}

	static void warnOnce(String key, String format, Object... args) {
		if (WARNED.add(key) && WARNED.size() <= 256) {
			api.chasm.log.ChasmLogger.warn("chasm", format, args);
		}
	}

	/** 实际的 {@code ItemColor} 实现：本系统的索引自己算，别的索引串给原来的提供者。 */
	private static final class LayerTint implements ItemColor {

		private final ItemColor previous;

		LayerTint(ItemColor previous) {
			this.previous = previous;
		}

		@Override
		public int getColor(ItemStack stack, int tintIndex) {
			if (tintIndex >= LayeredItemModel.TINT_BASE) {
				return layerTintOf(stack, tintIndex - LayeredItemModel.TINT_BASE);
			}
			if (previous == null) {
				return -1; // 白色：与"这个物品没有颜色提供者"完全等价
			}
			try {
				return previous.getColor(stack, tintIndex);
			} catch (Throwable t) {
				return -1;
			}
		}
	}
}
