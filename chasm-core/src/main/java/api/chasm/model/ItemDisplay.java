package api.chasm.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;

import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * **一个物品在某个姿态下的 {@code display} 变换集合**（框架的"模型/姿态变换"通道的数据类型）。
 *
 * <h2>为什么需要一个框架自己的类型，而不是直接用 1.21.1 的 {@code ItemTransforms}</h2>
 * <p>1.21.1 的变换类型是 <b>客户端专用</b>的：
 * {@code net.minecraft.client.renderer.block.model.ItemTransforms}（8 个 {@code ItemTransform} 字段）
 * 与 {@code ItemTransform}（{@code rotation}/{@code translation}/{@code scale} 三个 {@code Vector3f}）。
 * 而"给某个状态指定 display"这件事要由**通用代码**做：端口的 {@link ItemLayerProvider} 实现类
 * （如 {@code TetraShieldLayers}）在主源集里，**服务端也要加载**。让它们的
 * 签名里出现客户端类，专用服务器上就会在类加载/校验阶段碰到缺失的客户端类 ——
 * 所以这里只保留**数据**：{@link ItemDisplayContext} 本身是通用类
 * （{@code net.minecraft.world.item}，{@code StringRepresentable}），
 * {@code Vector3f} 是纯 JOML 数学类；到真正的渲染侧再由
 * {@link LayeredItemModel} 还原成原版 {@code ItemTransforms}，
 * **变换的语义与施加过程仍然是原版那一份代码**（{@code ItemTransform#apply}），
 * 本类一行都没有重写。</p>
 *
 * <h2>数据语义 = 1.21.1 {@code ItemTransform$Deserializer} 逐行等价</h2>
 * <p>本类的 {@link #fromJson(JsonElement)} 是原版那个反序列化器的镜像，依据是
 * {@code javap -p -c 'net.minecraft.client.renderer.block.model.ItemTransform$Deserializer'}：</p>
 * <ul>
 *   <li>键名不是硬编码表：{@code ItemTransforms$Deserializer.getTransform} 的字节码是
 *       {@code invokevirtual ItemDisplayContext.getSerializedName()} + {@code JsonObject.has/get}
 *       —— 所以本类同样用 {@link ItemDisplayContext#getSerializedName()} 取键；</li>
 *   <li>{@code rotation} 缺失 → {@code (0,0,0)}（静态块 {@code new Vector3f(0,0,0)}）；</li>
 *   <li>{@code translation} 缺失 → {@code (0,0,0)}；读出来之后
 *       <b>乘 {@code 0.0625f}</b>（字节码 offset 37 的 {@code ldc 0.0625f}）再按
 *       {@code [-5,5]} 夹取（offset 49/59/71 的 {@code ldc -5.0f}/{@code 5.0f}）——
 *       也就是说 JSON 里的位移单位是 1/16 方块，而字段里存的是**方块**；</li>
 *   <li>{@code scale} 缺失 → {@code (1,1,1)}，按 {@code [-4,4]} 夹取
 *       （offset 102/114/128 的 {@code ldc -4.0f}/{@code 4.0f}）；</li>
 *   <li>三个数组长度不是 3 → {@code JsonParseException}（offset 26 起的 {@code athrow}）。</li>
 * </ul>
 * <p><b>没有 {@code display} 块里那个姿态 = 原版 {@code ItemTransform.NO_TRANSFORM}</b>：
 * 映射里就没有这一项，渲染侧遇到缺失项直接回落 {@code ItemTransform.NO_TRANSFORM}。
 * 这正是原版 override 模型的语义 —— <b>不做与父模型的合并</b>。</p>
 *
 * <h2>兼容底线</h2>
 * <p>{@link ItemLayerProvider#displayOf} 默认返回 {@code null}，那时
 * {@link LayeredItemModel} 原样转发被包模型的 {@code getTransforms()} ——
 * **没有实现这个通道的物品/状态，变换与从前逐字一致**。</p>
 *
 * @param transforms 姿态 → 变换；**永不为 null**，可以为空（空 = 不表态）
 */
public record ItemDisplay(Map<ItemDisplayContext, Transform> transforms) {

	/** JSON 位移的单位（1/16 方块）：{@code ItemTransform$Deserializer} 字节码 offset 37 的 {@code ldc 0.0625f}。 */
	public static final float TRANSLATION_UNIT = 0.0625F;

	/** 位移夹取上限（字节码 offset 49/59/71 的 {@code ldc 5.0f}，下限是 {@code -5.0f}）。 */
	public static final float MAX_TRANSLATION = 5.0F;

	/** 缩放夹取上限（字节码 offset 102/114/128 的 {@code ldc 4.0f}，下限是 {@code -4.0f}）。 */
	public static final float MAX_SCALE = 4.0F;

	/** 原版各字段的缺失默认值（{@code ItemTransform$Deserializer} 静态块：rotation/translation = (0,0,0)。 */
	private static final Vector3f DEFAULT_ROTATION = new Vector3f(0.0F, 0.0F, 0.0F);

	/** 见 {@link #DEFAULT_ROTATION}。 */
	private static final Vector3f DEFAULT_TRANSLATION = new Vector3f(0.0F, 0.0F, 0.0F);

	/** 原版 {@code scale} 的缺失默认值：静态块里的 {@code fconst_1, fconst_1, fconst_1} = (1,1,1)。 */
	private static final Vector3f DEFAULT_SCALE = new Vector3f(1.0F, 1.0F, 1.0F);

	/** 一个姿态都不指定（渲染侧等价于"没有这条通道"）。 */
	public static final ItemDisplay EMPTY = new ItemDisplay(Map.of());

	/**
	 * **单个姿态的变换**。
	 *
	 * <p>字段语义与 1.21.1 {@code ItemTransform} 的三个公开 final 字段**逐字对应**：
	 * {@code rotation}（度，XYZ 欧拉）、{@code translation}（**方块**，已经乘过 1/16 并夹取）、
	 * {@code scale}（已经夹取）。构造时**防御性拷贝**（{@code Vector3f} 是可变的，
	 * 而记录要能安全地进缓存键）。</p>
	 */
	public record Transform(Vector3f rotation, Vector3f translation, Vector3f scale) {

		/** 原版 {@code ItemTransform.NO_TRANSFORM} 的等价数据（无旋转、无位移、不缩放）。 */
		public static final Transform IDENTITY =
			new Transform(new Vector3f(), new Vector3f(), new Vector3f(1.0F, 1.0F, 1.0F));

		public Transform {
			if (rotation == null || translation == null || scale == null) {
				throw new IllegalArgumentException("变换的三个分量都不能为 null");
			}
			rotation = new Vector3f(rotation);
			translation = new Vector3f(translation);
			scale = new Vector3f(scale);
		}

		/** 这个变换是不是恒等（渲染上等价于原版 {@code NO_TRANSFORM}）。 */
		public boolean isIdentity() {
			return rotation.equals(0.0F, 0.0F, 0.0F)
				&& translation.equals(0.0F, 0.0F, 0.0F)
				&& scale.equals(1.0F, 1.0F, 1.0F);
		}
	}

	public ItemDisplay {
		if (transforms == null || transforms.isEmpty()) {
			transforms = Map.of();
		} else {
			transforms = Map.copyOf(transforms);
		}
	}

	/** 该姿态的变换；本记录没指定就是 {@code null}（渲染侧回落 {@code ItemTransform.NO_TRANSFORM}）。 */
	public Transform transform(ItemDisplayContext context) {
		return context == null ? null : transforms.get(context);
	}

	/** 有没有指定任何姿态。 */
	public boolean isEmpty() {
		return transforms.isEmpty();
	}

	/** 只有一项的 display（书写样板时用）。 */
	public static ItemDisplay of(ItemDisplayContext context, Transform transform) {
		Map<ItemDisplayContext, Transform> map = new EnumMap<>(ItemDisplayContext.class);
		map.put(context, transform);
		return new ItemDisplay(map);
	}

	/**
	 * **解析一个模型 JSON 的 {@code display} 块**（镜像 1.21.1 {@code ItemTransform$Deserializer}，
	 * 见类注释的字节码依据）。
	 *
	 * @param display JSON 里 {@code "display"} 的值（可以是 null / 非对象 → 返回 {@link #EMPTY}）
	 * @throws JsonParseException 某个姿态的数组长度不是 3（与原版同款失败，不静默吞掉）
	 */
	public static ItemDisplay fromJson(JsonElement display) {
		if (display == null || !display.isJsonObject()) {
			return EMPTY;
		}
		JsonObject object = display.getAsJsonObject();
		Map<ItemDisplayContext, Transform> out = new EnumMap<>(ItemDisplayContext.class);
		for (ItemDisplayContext context : ItemDisplayContext.values()) {
			if (context == ItemDisplayContext.NONE) {
				continue; // 原版只读 8 个真实姿态（ItemTransforms$Deserializer 里恰好 8 次 getTransform）
			}
			String key = context.getSerializedName();
			if (!object.has(key)) {
				continue; // 缺这个姿态 = 原版 ItemTransform.NO_TRANSFORM，不进表
			}
			JsonElement value = object.get(key);
			if (value == null || value.isJsonNull() || !value.isJsonObject()) {
				continue; // 原版会在这里抛；端口按"没写这个姿态"处理，绝不因为外观数据让客户端崩
			}
			out.put(context, transformOf(value.getAsJsonObject()));
		}
		return out.isEmpty() ? EMPTY : new ItemDisplay(out);
	}

	/** 解析整份模型 JSON 文本的 {@code display} 块（模型文件本身就是一层 {@code display} 键）。 */
	public static ItemDisplay fromModelJson(String json) {
		if (json == null || json.isBlank()) {
			return EMPTY;
		}
		JsonElement parsed = JsonParser.parseString(json);
		if (!parsed.isJsonObject()) {
			return EMPTY;
		}
		return fromJson(parsed.getAsJsonObject().get("display"));
	}

	/** 一个姿态：{@code rotation}/{@code translation}/{@code scale}（缺失取原版默认值）。 */
	private static Transform transformOf(JsonObject pose) {
		Vector3f rotation = vector(pose, "rotation", DEFAULT_ROTATION);
		Vector3f translation = vector(pose, "translation", DEFAULT_TRANSLATION);
		translation.mul(TRANSLATION_UNIT); // 1/16 方块 → 方块（原版字节码 offset 37）
		translation.set(
			Mth.clamp(translation.x, -MAX_TRANSLATION, MAX_TRANSLATION),
			Mth.clamp(translation.y, -MAX_TRANSLATION, MAX_TRANSLATION),
			Mth.clamp(translation.z, -MAX_TRANSLATION, MAX_TRANSLATION));
		Vector3f scale = vector(pose, "scale", DEFAULT_SCALE);
		scale.set(
			Mth.clamp(scale.x, -MAX_SCALE, MAX_SCALE),
			Mth.clamp(scale.y, -MAX_SCALE, MAX_SCALE),
			Mth.clamp(scale.z, -MAX_SCALE, MAX_SCALE));
		return new Transform(rotation, translation, scale);
	}

	/** 原版 {@code ItemTransform$Deserializer#getVector3f}：{@code !has} → 默认值；长度必须 3。 */
	private static Vector3f vector(JsonObject pose, String key, Vector3f fallback) {
		if (!pose.has(key)) {
			return new Vector3f(fallback);
		}
		JsonElement element = pose.get(key);
		if (element == null || !element.isJsonArray()) {
			throw new JsonParseException(key + " 必须是 3 个数字的数组");
		}
		JsonArray array = element.getAsJsonArray();
		if (array.size() != 3) {
			throw new JsonParseException(key + " 必须是 3 个数字的数组（实际 " + array.size() + " 个）");
		}
		return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
	}

	@Override
	public String toString() {
		StringJoiner joiner = new StringJoiner(", ", "ItemDisplay[", "]");
		transforms.forEach((context, transform) -> joiner.add(context.getSerializedName()
			+ "={rot" + transform.rotation() + " pos" + transform.translation()
			+ " scale" + transform.scale() + "}"));
		return joiner.toString();
	}
}
