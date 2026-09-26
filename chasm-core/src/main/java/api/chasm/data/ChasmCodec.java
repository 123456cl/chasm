package api.chasm.data;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据组件 Codec 自动生成器。
 *
 * <p>对 record 类型自动生成 Codec，免除手写 {@code RecordCodecBuilder} 样板：</p>
 * <ul>
 *   <li>字段类型支持：基本类型、String、枚举、嵌套 record（递归）</li>
 *   <li>编码为「字段名 → 值」映射（基于 {@link Codec#unboundedMap(Codec, Codec)}）</li>
 *   <li>解码：反射调用 record 规范构造器</li>
 * </ul>
 *
 * <p>如需完全自定义编解码，record 可提供静态 {@code CODEC} 字段或 {@code codec()} 方法，
 * 自动生成会优先使用它们。</p>
 */
public final class ChasmCodec {

	private ChasmCodec() {
	}

	/**
	 * 通用值 Codec：支持 null、基本类型、String、枚举、record（编码为嵌套 Map）。
	 * 1.21 无 Codec.ANY，此处手写等价实现。
	 */
	private static final Codec<Object> ANY = Codec.of(ChasmCodec::encodeAny, ChasmCodec::decodeAny);

	/**
	 * 递归编解码深度上限，防止恶意/畸形深度嵌套数据触发 StackOverflowError。
	 */
	private static final int MAX_CODEC_DEPTH = 64;

	/**
	 * 为指定 record 类型生成（或发现已有的）Codec。
	 *
	 * @param type record 类型
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Codec<?> codecFor(Class<?> type) {
		if (!type.isRecord()) {
			throw new IllegalArgumentException("@DataComponent 类型必须是 record: " + type.getName());
		}
		// 优先使用 record 内显式声明的 Codec（完全自定义）
		try {
			java.lang.reflect.Field codecField = type.getDeclaredField("CODEC");
			if (Codec.class.isAssignableFrom(codecField.getType())) {
				codecField.setAccessible(true);
				return (Codec<?>) codecField.get(null);
			}
		} catch (ReflectiveOperationException ignored) {
			// 无 CODEC 字段，继续自动生成
		}
		try {
			java.lang.reflect.Method codecMethod = type.getDeclaredMethod("codec");
			if (Codec.class.isAssignableFrom(codecMethod.getReturnType())) {
				codecMethod.setAccessible(true);
				return (Codec<?>) codecMethod.invoke(null);
			}
		} catch (ReflectiveOperationException ignored) {
			// 无 codec() 方法，继续自动生成
		}
		return autoRecordCodec(type);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <T> Codec<T> autoRecordCodec(Class<T> recordClass) {
		RecordComponent[] components = recordClass.getRecordComponents();
		return Codec.unboundedMap(Codec.STRING, ANY).xmap(
			(java.util.function.Function<Map<String, Object>, T>) map ->
				(T) decodeRecordReflect(recordClass, components, map),
			(java.util.function.Function<T, Map<String, Object>>) record ->
				encodeRecord(components, record));
	}

	// —— 编码（record → Map<String,Object>）——

	private static Map<String, Object> encodeRecord(RecordComponent[] components, Object record) {
		return encodeRecordDepth(components, record, 0);
	}

	private static Map<String, Object> encodeRecordDepth(RecordComponent[] components, Object record, int depth) {
		if (depth > MAX_CODEC_DEPTH) {
			throw new RuntimeException("数据组件编码深度超限: " + depth);
		}
		Map<String, Object> map = new LinkedHashMap<>();
		try {
			for (RecordComponent comp : components) {
				comp.getAccessor().setAccessible(true);
				Object value = comp.getAccessor().invoke(record);
				map.put(comp.getName(), encodeValueDepth(value, depth + 1));
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("数据组件编码失败", e);
		}
		return map;
	}

	private static Object encodeValue(Object value) {
		return encodeValueDepth(value, 0);
	}

	private static Object encodeValueDepth(Object value, int depth) {
		if (value == null) {
			return null;
		}
		if (value.getClass().isRecord()) {
			return encodeRecordDepth(value.getClass().getRecordComponents(), value, depth + 1);
		}
		return value; // 基本类型/String/enum 原样（ANY 可处理）
	}

	private static <O> DataResult<O> encodeAny(Object value, DynamicOps<O> ops, O prefix) {
		return encodeAnyDepth(value, ops, prefix, 0);
	}

	private static <O> DataResult<O> encodeAnyDepth(Object value, DynamicOps<O> ops, O prefix, int depth) {
		if (depth > MAX_CODEC_DEPTH) {
			return DataResult.error(() -> "Chasm 数据组件编码深度超限");
		}
		if (value == null) {
			return DataResult.success(ops.empty());
		}
		if (value instanceof Integer i) {
			return DataResult.success(ops.createInt(i));
		}
		if (value instanceof Long l) {
			return DataResult.success(ops.createLong(l));
		}
		if (value instanceof Float f) {
			return DataResult.success(ops.createFloat(f));
		}
		if (value instanceof Double d) {
			return DataResult.success(ops.createDouble(d));
		}
		if (value instanceof Boolean b) {
			return DataResult.success(ops.createBoolean(b));
		}
		if (value instanceof String s) {
			return DataResult.success(ops.createString(s));
		}
		if (value instanceof Enum<?> e) {
			return DataResult.success(ops.createString(e.name()));
		}
		if (value instanceof Map<?, ?> m) {
			// encodeValueDepth 已提前将嵌套 record 转为 Map，此处逐项递归编码
			Map<O, O> map = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				String key = String.valueOf(e.getKey());
				map.put(ops.createString(key),
					encodeAnyDepth(e.getValue(), ops, prefix, depth + 1).result().orElse(ops.empty()));
			}
			return DataResult.success(ops.createMap(map));
		}
		if (value.getClass().isRecord()) {
			Map<O, O> map = new LinkedHashMap<>();
			encodeRecord(value.getClass().getRecordComponents(), value).forEach((k, v) ->
				map.put(ops.createString(k), encodeAnyDepth(v, ops, prefix, depth + 1).result().orElse(ops.empty())));
			return DataResult.success(ops.createMap(map));
		}
		return DataResult.error(() -> "不支持的值类型: " + value.getClass().getName());
	}

	// —— 解码（Map<String,Object> → record）——

	@SuppressWarnings("unchecked")
	private static Object decodeRecordReflect(Class<?> recordClass, RecordComponent[] components, Map<String, Object> map) {
		return decodeRecordReflectDepth(recordClass, components, map, 0);
	}

	@SuppressWarnings("unchecked")
	private static Object decodeRecordReflectDepth(Class<?> recordClass, RecordComponent[] components, Map<String, Object> map, int depth) {
		if (depth > MAX_CODEC_DEPTH) {
			throw new RuntimeException("数据组件解码深度超限: " + depth);
		}
		Class<?>[] paramTypes = new Class<?>[components.length];
		Object[] args = new Object[components.length];
		try {
			for (int i = 0; i < components.length; i++) {
				paramTypes[i] = components[i].getType();
				args[i] = decodeValueDepth(paramTypes[i], map.get(components[i].getName()), depth + 1);
			}
			Constructor<?> ctor = recordClass.getDeclaredConstructor(paramTypes);
			ctor.setAccessible(true);
			return ctor.newInstance(args);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new RuntimeException("数据组件解码失败: " + recordClass.getName(), e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object decodeValue(Class<?> type, Object raw) {
		return decodeValueDepth(type, raw, 0);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object decodeValueDepth(Class<?> type, Object raw, int depth) {
		if (raw == null) {
			return null;
		}
		if (type.isRecord() && raw instanceof Map<?, ?> nested) {
			return decodeRecordReflectDepth(type, type.getRecordComponents(), (Map<String, Object>) nested, depth + 1);
		}
		if (type == int.class || type == Integer.class) {
			return raw instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(raw));
		}
		if (type == long.class || type == Long.class) {
			return raw instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(raw));
		}
		if (type == float.class || type == Float.class) {
			return raw instanceof Number n ? n.floatValue() : Float.valueOf(String.valueOf(raw));
		}
		if (type == double.class || type == Double.class) {
			return raw instanceof Number n ? n.doubleValue() : Double.valueOf(String.valueOf(raw));
		}
		if (type == boolean.class || type == Boolean.class) {
			return raw instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(raw));
		}
		if (type == String.class) {
			return String.valueOf(raw);
		}
		if (type.isEnum()) {
			return Enum.valueOf((Class<? extends Enum>) type, String.valueOf(raw));
		}
		return raw;
	}

	private static <O> DataResult<Pair<Object, O>> decodeAny(DynamicOps<O> ops, O input) {
		return decodeAnyDepth(ops, input, 0);
	}

	private static <O> DataResult<Pair<Object, O>> decodeAnyDepth(DynamicOps<O> ops, O input, int depth) {
		if (depth > MAX_CODEC_DEPTH) {
			return DataResult.error(() -> "Chasm 数据组件解码深度超限");
		}
		var number = ops.getNumberValue(input);
		if (number.result().isPresent()) {
			return DataResult.success(new Pair<>(number.result().get(), ops.empty()));
		}
		var string = ops.getStringValue(input);
		if (string.result().isPresent()) {
			return DataResult.success(new Pair<>(string.result().get(), ops.empty()));
		}
		var bool = ops.getBooleanValue(input);
		if (bool.result().isPresent()) {
			return DataResult.success(new Pair<>(bool.result().get(), ops.empty()));
		}
		var map = ops.getMapValues(input);
		if (map.result().isPresent()) {
			Map<String, Object> decoded = new LinkedHashMap<>();
			map.result().get().forEach(entry -> {
				String key = ops.getStringValue(entry.getFirst()).result().orElse("?");
				Object value = decodeAnyDepth(ops, entry.getSecond(), depth + 1).result().map(Pair::getFirst).orElse(null);
				decoded.put(key, value);
			});
			return DataResult.success(new Pair<>(decoded, ops.empty()));
		}
		return DataResult.error(() -> "无法解码 Chasm 数据组件值");
	}
}
