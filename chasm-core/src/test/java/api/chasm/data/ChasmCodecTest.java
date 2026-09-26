package api.chasm.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 纯 JVM 单测：{@link ChasmCodec} 自动生成 Codec。
 *
 * <p>覆盖：基本类型/枚举/嵌套 record 编解码往返、自定义 CODEC 字段优先、
 * 非 record 拒绝、递归深度上限（防 StackOverflowError）。</p>
 */
class ChasmCodecTest {

	/** 枚举字段。 */
	enum TestMode {
		ALPHA, BETA
	}

	/** 嵌套 record。 */
	record TestInner(String name, int value) {
	}

	/** 被测 record：基本类型 + String + 枚举 + 嵌套 record。 */
	record TestOuter(int id, String label, boolean flag, TestMode mode, TestInner inner) {
	}

	/** 自带静态 CODEC 的 record：自动生成必须优先返回它。 */
	record HasCustom(int v) {
		static final Codec<HasCustom> CODEC = Codec.INT.xmap(HasCustom::new, HasCustom::v);
	}

	/** 递归链式 record：用于深度超限测试。 */
	record Node(Node child, int v) {
	}

	@SuppressWarnings("unchecked")
	private static <T> Codec<Object> asObjectCodec(Class<T> type) {
		return (Codec<Object>) (Codec<?>) ChasmCodec.codecFor(type);
	}

	@Test
	void roundtripBasicTypesEnumAndNested() {
		Codec<Object> codec = asObjectCodec(TestOuter.class);
		TestOuter original = new TestOuter(42, "hello", true, TestMode.BETA, new TestInner("n", 7));

		DataResult<JsonElement> encoded = codec.encodeStart(JsonOps.INSTANCE, original);
		assertTrue(encoded.result().isPresent(), "编码应成功: " + encoded.error());
		JsonObject obj = encoded.result().get().getAsJsonObject();
		assertEquals(42, obj.get("id").getAsInt());
		assertEquals("hello", obj.get("label").getAsString());
		assertEquals(true, obj.get("flag").getAsBoolean());
		assertEquals("BETA", obj.get("mode").getAsString());
		JsonElement inner = obj.get("inner");
		assertInstanceOf(JsonObject.class, inner, "嵌套 record 应编码为对象");
		assertEquals(7, inner.getAsJsonObject().get("value").getAsInt());

		DataResult<Pair<Object, JsonElement>> decoded = codec.decode(JsonOps.INSTANCE, obj);
		assertTrue(decoded.result().isPresent(), "解码应成功");
		assertEquals(original, decoded.result().get().getFirst(), "往返解码应与原值相等");
	}

	@Test
	void customCodecFieldIsPreferred() {
		assertSame(HasCustom.CODEC, ChasmCodec.codecFor(HasCustom.class),
			"record 显式声明静态 CODEC 字段时应优先返回该实例");
	}

	@Test
	void nonRecordTypeIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> ChasmCodec.codecFor(String.class),
			"非 record 类型应抛 IllegalArgumentException");
	}

	@Test
	void encodeDepthLimitPreventsStackOverflow() {
		// 构造 70 层嵌套（超过 MAX_CODEC_DEPTH=64）
		Node node = new Node(null, 0);
		for (int i = 1; i <= 70; i++) {
			node = new Node(node, i);
		}
		Codec<Object> codec = asObjectCodec(Node.class);
		final Node deep = node;
		assertThrows(RuntimeException.class, () -> codec.encodeStart(JsonOps.INSTANCE, deep),
			"编码深度超过上限应抛异常而非 StackOverflowError");
	}

	@Test
	void decodeDepthLimitPreventsStackOverflow() {
		// 构造 70 层嵌套 Json 对象
		JsonObject innermost = new JsonObject();
		innermost.addProperty("v", 0);
		innermost.add("child", JsonNull.INSTANCE);
		JsonObject cur = innermost;
		for (int i = 1; i <= 70; i++) {
			JsonObject node = new JsonObject();
			node.addProperty("v", i);
			node.add("child", cur);
			cur = node;
		}
		Codec<Object> codec = asObjectCodec(Node.class);
		final JsonObject deepJson = cur;
		assertThrows(RuntimeException.class, () -> codec.decode(JsonOps.INSTANCE, deepJson),
			"解码深度超过上限应抛异常而非 StackOverflowError");
	}
}
