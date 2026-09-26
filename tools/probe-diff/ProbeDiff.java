import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * probe-diff：**端口侧 ↔ 真机侧** chasm.gui.probe/1 JSONL dump 的逐节点差分工具。
 *
 * <p>字段逐字对齐 chasm-core/src/main/java/api/chasm/gui/decl/GuiProbe.java（schema {@value #SCHEMA}）。
 * 只用到 JDK 自带类；JSON 是手写的严格递归下降解析器，零依赖、离线可跑。</p>
 *
 * <h2>记录配对（--pair-by）</h2>
 * <ul>
 *   <li>默认：gui + 归一化 state 全签名，并保留三级降级（exact → state-values-differ → order-guess），逐级标注置信度。</li>
 *   <li>--pair-by t1,t2,..：只用指定令牌（gui / state 键 / sem 键 / stateValues 键），且**不再降级猜测**；
 *       配不上就进"状态覆盖表"。同侧多条记录落到同一键会报 pair_key_collision。</li>
 * </ul>
 *
 * <h2>存储槽范围（--slots-scope，默认 role）</h2>
 * <ul>
 *   <li>real-all：全部槽，按数组下标 i 配对。</li>
 *   <li>role：只保留 sem.role 以 target/material 开头的槽，按 (role, containerIndex) 配对
 *       （无 ci 退回 (role, x, y)）—— 两侧数组下标不同也能配上。</li>
 *   <li>storage-only：在 role 基础上再要求 containerIndex &lt; --slots-container-max（默认 9）。</li>
 * </ul>
 * <p>实测动机：真机探针把 36 个玩家背包槽也算进 slots[]，且同样标成 sem.role=material，
 * 只有 containerIndex 能区分（容器自有槽 0..3，背包槽 9..44）。所以只按 role 过滤**排除不掉**背包槽，
 * storage-only + container-max 才行。报告会如实打印生效范围、每侧保留/丢弃数与被丢弃的 (role,ci) 分布。</p>
 *
 * <h2>报告（--focus / --ignore）</h2>
 * <p>--focus：明细按节点 key 前缀族汇总（frame: / grow: / attack_damage: / (structural r) /
 * (slots) / (state) / (line)）并下钻到具体节点（key + 两侧 rect/text/action 对照）。
 * --ignore c1,c2：整类忽略已确认的对齐噪声，报告里如实列出被忽略的类别与条数，且不计入总数、不参与 --fail-on。</p>
 *
 * <h2>源码编码（重要）</h2>
 * <p>本文件是 UTF-8 且含中文字面量。JDK 17 的 java Foo.java 单文件源码启动无法指定源码编码
 * （实测 -encoding / -J-D 都被 Launcher 拒绝），会按平台默认编码（本机 cp936）读源码 → 中文乱码。
 * 因此必须带 '-Dfile.encoding=UTF-8' 运行（JDK 17 与 JDK 25 实测产出逐字节相同）；
 * 或用 escape-src.py 生成纯 ASCII 副本。</p>
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 ProbeDiff.java a.jsonl b.jsonl
 *      --pair-by gui,page,modules --slots-scope storage-only --slots-container-max 9
 *      --focus --ignore line_sem,state_decl --out-md out/report.md --out-json out/report.json
 * java ProbeDiff.java --list-cats
 * </pre>
 */
public final class ProbeDiff {

	static final String TOOL = "probe-diff/2";
	static final String SCHEMA = "chasm.gui.probe/1";
	/** Markdown 行内代码分隔符（源码里不直接写反引号）。 */
	static final String BT = String.valueOf((char) 96);

	static String code(String s) {
		return BT + s + BT;
	}

	// ==================================================================================
	// 差异分类表
	// ==================================================================================

	enum Sev {
		CRITICAL, HIGH, MEDIUM, LOW, INFO
	}

	static final class Cat {
		final String id;
		final Sev sev;
		final String family;
		final String zh;

		Cat(String id, Sev sev, String family, String zh) {
			this.id = id;
			this.sev = sev;
			this.family = family;
			this.zh = zh;
		}
	}

	static final Map<String, Cat> CATS = new LinkedHashMap<>();

	static void cat(String id, Sev sev, String family, String zh) {
		CATS.put(id, new Cat(id, sev, family, zh));
	}

	static {
		cat("parse_error", Sev.CRITICAL, "line", "解析错误");
		cat("record_only_a", Sev.CRITICAL, "line", "记录仅 A 侧有");
		cat("record_only_b", Sev.CRITICAL, "line", "记录仅 B 侧有");
		cat("schema_mismatch", Sev.CRITICAL, "line", "schema 不符");
		cat("state_keys_only_a", Sev.CRITICAL, "state", "state 键仅 A 侧有");
		cat("state_keys_only_b", Sev.CRITICAL, "state", "state 键仅 B 侧有");
		cat("node_missing", Sev.CRITICAL, "node", "缺失节点");
		cat("node_extra", Sev.CRITICAL, "node", "多出节点");
		cat("node_type", Sev.CRITICAL, "node", "类型差");
		cat("node_action", Sev.CRITICAL, "node", "action/value 差");
		cat("node_enabled", Sev.CRITICAL, "node", "enabled 差");
		cat("slot_missing", Sev.CRITICAL, "slot", "存储槽缺失");
		cat("slot_extra", Sev.CRITICAL, "slot", "存储槽多出");
		cat("slot_item", Sev.CRITICAL, "slot", "存储槽物品差");
		cat("slot_empty", Sev.CRITICAL, "slot", "存储槽空标记差");
		cat("ia_missing", Sev.CRITICAL, "ia", "交互项缺失");
		cat("ia_action", Sev.CRITICAL, "ia", "交互 action 差");

		cat("node_coord", Sev.HIGH, "node", "坐标差");
		cat("node_size", Sev.HIGH, "node", "尺寸差");
		cat("node_texture", Sev.HIGH, "node", "纹理/源尺寸差");
		cat("node_uv", Sev.HIGH, "node", "UV 差");
		cat("state_value", Sev.HIGH, "state", "state 值不同");
		cat("slot_coord", Sev.HIGH, "slot", "存储槽坐标/索引差");
		cat("slot_active", Sev.HIGH, "slot", "存储槽 active 差");
		cat("ia_value", Sev.HIGH, "ia", "交互 value 差");
		cat("ia_enabled", Sev.HIGH, "ia", "交互 enabled 差");
		cat("line_gui", Sev.HIGH, "line", "界面 id 差");

		cat("node_text", Sev.MEDIUM, "node", "文本差");
		cat("node_color", Sev.MEDIUM, "node", "颜色/tint 差");
		cat("node_tooltip", Sev.MEDIUM, "node", "tooltip 差");
		cat("node_draw", Sev.MEDIUM, "node", "绘制遍差");
		cat("node_layer", Sev.MEDIUM, "node", "图层差");
		cat("node_slot", Sev.MEDIUM, "node", "槽位绑定差");
		cat("node_handler", Sev.MEDIUM, "node", "handler 差");
		cat("node_scaled", Sev.MEDIUM, "node", "缩放标记差");
		cat("slot_count", Sev.MEDIUM, "slot", "存储槽数量差");
		cat("ia_extra", Sev.MEDIUM, "ia", "交互项多出");
		cat("ia_rect", Sev.MEDIUM, "ia", "交互 rect/下标差");
		cat("ia_handler", Sev.MEDIUM, "ia", "交互 handler 差");
		cat("line_field", Sev.MEDIUM, "line", "行级字段差");
		cat("line_sem", Sev.MEDIUM, "line", "界面级语义差");
		cat("state_decl", Sev.MEDIUM, "state", "stateDecl 差");

		cat("node_opacity", Sev.LOW, "node", "透明度差");
		cat("node_textscale", Sev.LOW, "node", "文字缩放差");
		cat("node_shadow", Sev.LOW, "node", "文字阴影差");
		cat("node_anim", Sev.LOW, "node", "动画声明差");
		cat("node_sem", Sev.LOW, "node", "节点语义差");
		cat("slot_dynamic", Sev.LOW, "slot", "存储槽 dynamic 差");
		cat("slot_filtered", Sev.LOW, "slot", "存储槽 filtered 差");
		cat("slot_sem", Sev.LOW, "slot", "存储槽语义差");

		cat("node_pair_lowconf", Sev.INFO, "node", "低置信节点配对");
		cat("node_key_conflict", Sev.INFO, "node", "节点 key 冲突");
		cat("record_dup", Sev.INFO, "line", "同键记录重复");
		cat("line_nodesCount", Sev.INFO, "line", "nodesCount 自洽性");
		cat("pair_key_collision", Sev.INFO, "line", "配对键碰撞（--pair-by 过粗）");
	}

	static int sevRank(Sev s) {
		switch (s) {
			case CRITICAL: return 0;
			case HIGH: return 1;
			case MEDIUM: return 2;
			case LOW: return 3;
			default: return 4;
		}
	}

	static String sevName(Sev s) {
		switch (s) {
			case CRITICAL: return "critical";
			case HIGH: return "high";
			case MEDIUM: return "medium";
			case LOW: return "low";
			default: return "info";
		}
	}

	// ==================================================================================
	// 严格递归下降 JSON 解析器
	// ==================================================================================

	static final class JsonError extends RuntimeException {
		JsonError(String m) {
			super(m);
		}
	}

	static Object parseJson(String text) {
		Parser p = new Parser(text);
		p.ws();
		Object v = p.value();
		p.ws();
		if (p.i < p.n) {
			throw new JsonError("位置 " + p.i + "：JSON 结束后还有多余内容");
		}
		return v;
	}

	static final class Parser {
		final String s;
		final int n;
		int i;

		Parser(String s) {
			this.s = s;
			this.n = s.length();
		}

		void ws() {
			while (i < n) {
				char c = s.charAt(i);
				if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
					i++;
				} else {
					break;
				}
			}
		}

		JsonError err(String m) {
			return new JsonError("位置 " + i + "：" + m);
		}

		char peek() {
			if (i >= n) {
				throw err("意外到达输入末尾");
			}
			return s.charAt(i);
		}

		void expect(char c) {
			if (i >= n || s.charAt(i) != c) {
				throw err("期望 '" + c + "'，实际 " + (i >= n ? "输入末尾" : "'" + s.charAt(i) + "'"));
			}
			i++;
		}

		Object value() {
			ws();
			char c = peek();
			switch (c) {
				case '{': return object();
				case '[': return array();
				case '"': return string();
				case 't': literal("true"); return Boolean.TRUE;
				case 'f': literal("false"); return Boolean.FALSE;
				case 'n': literal("null"); return null;
				default: return number();
			}
		}

		void literal(String lit) {
			if (!s.startsWith(lit, i)) {
				throw err("期望 " + lit);
			}
			i += lit.length();
		}

		Map<String, Object> object() {
			expect('{');
			Map<String, Object> m = new LinkedHashMap<>();
			ws();
			if (i < n && s.charAt(i) == '}') {
				i++;
				return m;
			}
			while (true) {
				ws();
				if (peek() != '"') {
					throw err("对象的键必须是字符串");
				}
				String k = string();
				ws();
				expect(':');
				Object v = value();
				m.put(k, v);
				ws();
				char c = peek();
				if (c == ',') {
					i++;
					continue;
				}
				if (c == '}') {
					i++;
					return m;
				}
				throw err("对象里期望 ',' 或 '}'");
			}
		}

		List<Object> array() {
			expect('[');
			List<Object> out = new ArrayList<>();
			ws();
			if (i < n && s.charAt(i) == ']') {
				i++;
				return out;
			}
			while (true) {
				out.add(value());
				ws();
				char c = peek();
				if (c == ',') {
					i++;
					continue;
				}
				if (c == ']') {
					i++;
					return out;
				}
				throw err("数组里期望 ',' 或 ']'");
			}
		}

		String string() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (i >= n) {
					throw err("字符串没有闭合");
				}
				char c = s.charAt(i++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (i >= n) {
						throw err("转义符后到达输入末尾");
					}
					char e = s.charAt(i++);
					switch (e) {
						case '"': sb.append('"'); break;
						case '\\': sb.append('\\'); break;
						case '/': sb.append('/'); break;
						case 'b': sb.append('\b'); break;
						case 'f': sb.append('\f'); break;
						case 'n': sb.append('\n'); break;
						case 'r': sb.append('\r'); break;
						case 't': sb.append('\t'); break;
						case 'u': {
							if (i + 4 > n) {
								throw err("\\u 转义不足 4 位");
							}
							String hex = s.substring(i, i + 4);
							int cp;
							try {
								cp = Integer.parseInt(hex, 16);
							} catch (NumberFormatException ex) {
								throw err("非法 \\u 转义：" + hex);
							}
							sb.append((char) cp);
							i += 4;
							break;
						}
						default: throw err("未知转义 \\" + e);
					}
					continue;
				}
				if (c < 0x20) {
					throw err("字符串里出现未转义的控制字符 U+" + String.format("%04X", (int) c));
				}
				sb.append(c);
			}
		}

		Object number() {
			int start = i;
			if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
				i++;
			}
			boolean frac = false;
			boolean exp = false;
			while (i < n) {
				char c = s.charAt(i);
				if (c >= '0' && c <= '9') {
					i++;
				} else if (c == '.' && !frac && !exp) {
					frac = true;
					i++;
				} else if ((c == 'e' || c == 'E') && !exp) {
					exp = true;
					i++;
					if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
						i++;
					}
				} else {
					break;
				}
			}
			String tok = s.substring(start, i);
			if (tok.isEmpty() || "-".equals(tok) || "+".equals(tok)) {
				throw err("不是合法的 JSON 值");
			}
			try {
				if (frac || exp) {
					return Double.valueOf(tok);
				}
				return Long.valueOf(tok);
			} catch (NumberFormatException ex) {
				throw err("非法数字：" + tok);
			}
		}
	}

	// ==================================================================================
	// JSON 取值 / 规范化
	// ==================================================================================

	@SuppressWarnings("unchecked")
	static Map<String, Object> asObj(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	static List<Object> asArr(Object o) {
		return o instanceof List ? (List<Object>) o : new ArrayList<>();
	}

	static String asStr(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof String) {
			return (String) o;
		}
		if (o instanceof Number || o instanceof Boolean) {
			return numStr(o);
		}
		return compactJson(o);
	}

	static String numStr(Object o) {
		if (o instanceof Double) {
			double d = (Double) o;
			if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d) && Math.abs(d) < 1.0E7) {
				return Long.toString((long) d);
			}
			return Double.toString(Math.round(d * 1000.0) / 1000.0);
		}
		return String.valueOf(o);
	}

	static long asLong(Object o, long dflt) {
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		if (o instanceof String) {
			try {
				return Long.parseLong(((String) o).trim());
			} catch (NumberFormatException e) {
				return dflt;
			}
		}
		return dflt;
	}

	static double asDouble(Object o, double dflt) {
		if (o instanceof Number) {
			return ((Number) o).doubleValue();
		}
		return dflt;
	}

	static String canon(Object o) {
		return o == null ? null : asStr(o);
	}

	static String show(Object o) {
		if (o == null) {
			return "∅";
		}
		String s = asStr(o);
		if (s == null) {
			return "∅";
		}
		s = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
		return s.length() > 160 ? s.substring(0, 160) + "…" : s;
	}

	static String between(String text, String start, String end) {
		if (text == null) {
			return null;
		}
		int a = text.indexOf(start);
		if (a < 0) {
			return null;
		}
		a += start.length();
		int b = text.indexOf(end, a);
		if (b < 0) {
			b = text.length();
		}
		return text.substring(a, b);
	}

	static String padRight(String s, int w) {
		StringBuilder sb = new StringBuilder(s);
		while (sb.length() < w) {
			sb.append(' ');
		}
		return sb.toString();
	}

	// ==================================================================================
	// JSON 输出
	// ==================================================================================

	static String compactJson(Object o) {
		StringBuilder sb = new StringBuilder();
		writeCompact(sb, o);
		return sb.toString();
	}

	static void writeCompact(StringBuilder sb, Object o) {
		if (o == null) {
			sb.append("null");
		} else if (o instanceof String) {
			writeString(sb, (String) o);
		} else if (o instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeString(sb, String.valueOf(e.getKey()));
				sb.append(':');
				writeCompact(sb, e.getValue());
			}
			sb.append('}');
		} else if (o instanceof List) {
			sb.append('[');
			boolean first = true;
			for (Object v : (List<?>) o) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeCompact(sb, v);
			}
			sb.append(']');
		} else {
			sb.append(String.valueOf(o));
		}
	}

	static String prettyJson(Object o) {
		StringBuilder sb = new StringBuilder();
		writePretty(sb, o, 0);
		return sb.append('\n').toString();
	}

	static void writePretty(StringBuilder sb, Object o, int ind) {
		if (o instanceof Map) {
			Map<?, ?> m = (Map<?, ?>) o;
			if (m.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append("{\n");
			int k = 0;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				pad(sb, ind + 1);
				writeString(sb, String.valueOf(e.getKey()));
				sb.append(": ");
				writePretty(sb, e.getValue(), ind + 1);
				if (++k < m.size()) {
					sb.append(',');
				}
				sb.append('\n');
			}
			pad(sb, ind);
			sb.append('}');
			return;
		}
		if (o instanceof List) {
			List<?> l = (List<?>) o;
			if (l.isEmpty()) {
				sb.append("[]");
				return;
			}
			sb.append("[\n");
			for (int k = 0; k < l.size(); k++) {
				pad(sb, ind + 1);
				writePretty(sb, l.get(k), ind + 1);
				if (k + 1 < l.size()) {
					sb.append(',');
				}
				sb.append('\n');
			}
			pad(sb, ind);
			sb.append(']');
			return;
		}
		if (o == null) {
			sb.append("null");
			return;
		}
		if (o instanceof String) {
			writeString(sb, (String) o);
			return;
		}
		sb.append(String.valueOf(o));
	}

	static void pad(StringBuilder sb, int ind) {
		for (int i = 0; i < ind; i++) {
			sb.append("  ");
		}
	}

	static void writeString(StringBuilder sb, String v) {
		sb.append('"');
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			switch (c) {
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				case '\b': sb.append("\\b"); break;
				case '\f': sb.append("\\f"); break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}

	// ==================================================================================
	// 选项
	// ==================================================================================

	static final class Opts {
		Path a;
		Path b;
		Path outMd = Path.of("probe-diff-report.md");
		Path outJson = Path.of("probe-diff-report.json");
		String labelA = "A/真机侧";
		String labelB = "B/端口侧";
		int examples = 3;
		double posThreshold = 0.25;
		boolean includeInfo = true;
		boolean focus = false;
		boolean listCats = false;
		Set<String> ignoreKeys = new LinkedHashSet<>();
		Set<String> ignoreCats = new LinkedHashSet<>();
		List<String> pairBy = null;
		String slotsScope = "role";
		int slotsContainerMax = 9;
		boolean slotsContainerMaxGiven = false;
		Sev failOn = null;
	}

	static final class PairSpec {
		final List<String> tokens;

		PairSpec(List<String> tokens) {
			this.tokens = tokens;
		}

		boolean explicit() {
			return tokens != null;
		}

		String tokenOf(Rec r, String t) {
			if ("gui".equals(t)) {
				return r.gui == null ? "∅" : r.gui;
			}
			String ns = null;
			String key = t;
			int dot = t.indexOf('.');
			if (dot > 0) {
				String head = t.substring(0, dot);
				if ("state".equals(head) || "sem".equals(head) || "stateValues".equals(head)) {
					ns = head;
					key = t.substring(dot + 1);
				}
			}
			Object v;
			if ("state".equals(ns)) {
				v = r.state.get(key);
			} else if ("sem".equals(ns)) {
				v = r.sem.get(key);
			} else if ("stateValues".equals(ns)) {
				v = r.stateValues.get(key);
			} else if (r.state.containsKey(key)) {
				v = r.state.get(key);
			} else if (r.sem.containsKey(key)) {
				v = r.sem.get(key);
			} else if (r.stateValues.containsKey(key)) {
				v = r.stateValues.get(key);
			} else {
				v = null;
			}
			String c = canon(v);
			return c == null ? "∅" : c;
		}

		String keyOf(Rec r) {
			StringBuilder sb = new StringBuilder();
			for (String t : tokens) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				sb.append(t).append('=').append(tokenOf(r, t));
			}
			return sb.toString();
		}
	}

	// ==================================================================================
	// 模型
	// ==================================================================================

	static final class Rec {
		final int lineNo;
		final Map<String, Object> raw;
		final String gui;
		final Map<String, Object> state;
		final Map<String, Object> stateValues;
		final Map<String, Object> sem;
		final String stateSig;
		final String stateKeySet;
		final String pairKey;
		/** 分组/去重键 = gui + pairKey（gui 永远是分组键的一部分，否则不同界面的同状态会互相配对）。 */
		final String groupKey;
		final String pageToken;
		final List<Object> nodes;
		final List<Object> slots;
		final List<Object> interactive;
		int duplicateCount;

		Rec(int lineNo, Map<String, Object> raw, Opts o) {
			this.lineNo = lineNo;
			this.raw = raw;
			this.gui = asStr(raw.get("gui"));
			this.state = asObj(raw.get("state"));
			this.stateValues = asObj(raw.get("stateValues"));
			this.sem = asObj(raw.get("sem"));
			TreeMap<String, String> sorted = new TreeMap<>();
			for (Map.Entry<String, Object> e : state.entrySet()) {
				if (o.ignoreKeys.contains(e.getKey())) {
					continue;
				}
				String c = canon(e.getValue());
				sorted.put(e.getKey(), c == null ? "null" : c);
			}
			StringBuilder sig = new StringBuilder();
			StringBuilder keys = new StringBuilder();
			for (Map.Entry<String, String> e : sorted.entrySet()) {
				if (sig.length() > 0) {
					sig.append('&');
					keys.append('&');
				}
				sig.append(e.getKey()).append('=').append(e.getValue());
				keys.append(e.getKey());
			}
			this.stateSig = sig.toString();
			this.stateKeySet = keys.toString();
			this.pairKey = o.pairBy == null ? stateSig : new PairSpec(o.pairBy).keyOf(this);
			this.groupKey = (gui == null ? "?" : gui) + '\u0001' + pairKey;
			String pg = asStr(sem.get("page"));
			this.pageToken = pg == null ? "∅" : pg;
			this.nodes = asArr(raw.get("nodes"));
			this.slots = asArr(raw.get("slots"));
			this.interactive = asArr(raw.get("interactive"));
		}

		int groupSize() {
			return duplicateCount + 1;
		}

		String label() {
			String g = gui == null ? "?" : gui;
			String pk = pairKey;
			String px = "gui=" + (gui == null ? "∅" : gui);
			if (pk.equals(px)) {
				return g;
			}
			if (pk.startsWith(px + "|")) {
				pk = pk.substring(px.length() + 1);
			}
			return pk.isEmpty() ? g : g + "#" + pk;
		}

		String shortLabel() {
			String s = label();
			return s.length() <= 90 ? s : s.substring(0, 90) + "…";
		}
	}

	static final class Diff {
		final String cat;
		final String record;
		final String pairing;
		final int aLine;
		final int bLine;
		final String aKey;
		final String bKey;
		final String path;
		final String aVal;
		final String bVal;
		final String note;
		final boolean lowConf;
		String family;

		Diff(String cat, String record, String pairing, int aLine, int bLine, String aKey, String bKey,
			 String path, String aVal, String bVal, String note, boolean lowConf) {
			this.cat = cat;
			this.record = record;
			this.pairing = pairing;
			this.aLine = aLine;
			this.bLine = bLine;
			this.aKey = aKey;
			this.bKey = bKey;
			this.path = path;
			this.aVal = aVal;
			this.bVal = bVal;
			this.note = note;
			this.lowConf = lowConf;
		}
	}

	static final class Pair {
		Rec a;
		Rec b;
		String level;
		String reason;
		boolean lowConf;
		final List<Diff> diffs = new ArrayList<>();

		String label() {
			return a != null ? a.shortLabel() : (b != null ? b.shortLabel() : "?");
		}

		String key() {
			return a != null ? a.pairKey : (b != null ? b.pairKey : "");
		}

		String groupKey() {
			return a != null ? a.groupKey : (b != null ? b.groupKey : "");
		}

		void add(String cat, String path, String aVal, String bVal, String note, boolean lowConf) {
			diffs.add(new Diff(cat, label(), level, a == null ? 0 : a.lineNo, b == null ? 0 : b.lineNo,
				a == null ? null : a.gui, b == null ? null : b.gui, path, aVal, bVal, note, lowConf));
		}
	}

	// ==================================================================================
	// 族（节点 key 前缀）
	// ==================================================================================

	static final String FAM_NO_KEY = "(no-key)";
	static final String FAM_SLOTS = "(slots)";
	static final String FAM_STATE = "(state)";
	static final String FAM_LINE = "(line)";

	static String nodeKeyFamily(String key) {
		if (key == null || key.isEmpty() || "∅".equals(key)) {
			return FAM_NO_KEY;
		}
		int c = key.indexOf(':');
		if (c >= 0) {
			return key.substring(0, c + 1);
		}
		int s = key.indexOf('/');
		String lead = s >= 0 ? key.substring(0, s) : key;
		return "(structural " + lead + ")";
	}

	static String familyOf(Diff d) {
		String p = d.path == null ? "" : d.path;
		if (p.startsWith("nodes[")) {
			return nodeKeyFamily(between(p, "key=", "]"));
		}
		if (p.startsWith("interactive[") || d.cat.startsWith("ia_")) {
			return nodeKeyFamily(between(p, "node=", "]"));
		}
		if (p.startsWith("slots[") || p.startsWith("slot_sem") || d.cat.startsWith("slot_")) {
			return FAM_SLOTS;
		}
		if (p.startsWith("stateValues") || p.equals("stateDecl") || p.startsWith("stateDecl.")
			|| d.cat.startsWith("state_")) {
			return FAM_STATE;
		}
		return FAM_LINE;
	}

	// ==================================================================================
	// 入口
	// ==================================================================================

	public static void main(String[] args) throws IOException {
		Opts o;
		try {
			o = parseArgs(args);
		} catch (IllegalArgumentException e) {
			System.err.println("参数错误：" + e.getMessage());
			System.exit(1);
			return;
		}
		if (o.listCats) {
			printCats();
			System.exit(0);
			return;
		}
		Run run = new Run(o);
		try {
			run.execute();
		} catch (IOException e) {
			System.err.println("IO 错误：" + e.getMessage());
			System.exit(1);
			return;
		}
		System.exit(run.exitCode);
	}

	static void printCats() {
		System.out.println("probe-diff 差异类别（共 " + CATS.size() + " 个，可直接用于 --ignore）");
		System.out.println();
		for (Cat c : CATS.values()) {
			System.out.println("  " + sevName(c.sev) + "\t" + c.id + "\t" + c.zh);
		}
	}

	static Opts parseArgs(String[] args) {
		Opts o = new Opts();
		List<String> pos = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			switch (a) {
				case "--out-md": o.outMd = Path.of(req(args, ++i, a)); break;
				case "--out-json": o.outJson = Path.of(req(args, ++i, a)); break;
				case "--a-label": o.labelA = req(args, ++i, a); break;
				case "--b-label": o.labelB = req(args, ++i, a); break;
				case "--examples": o.examples = Integer.parseInt(req(args, ++i, a)); break;
				case "--pos-threshold": o.posThreshold = Double.parseDouble(req(args, ++i, a)); break;
				case "--state-ignore":
					for (String k : req(args, ++i, a).split(",")) {
						if (!k.isBlank()) {
							o.ignoreKeys.add(k.trim());
						}
					}
					break;
				case "--ignore":
					for (String k : req(args, ++i, a).split(",")) {
						String id = k.trim();
						if (id.isEmpty()) {
							continue;
						}
						if (!CATS.containsKey(id)) {
							throw new IllegalArgumentException("--ignore 里有未知类别 id：" + id
								+ "（用 --list-cats 看全部可用 id）");
						}
						o.ignoreCats.add(id);
					}
					break;
				case "--pair-by": {
					List<String> toks = new ArrayList<>();
					for (String t : req(args, ++i, a).split(",")) {
						if (!t.isBlank()) {
							toks.add(t.trim());
						}
					}
					if (toks.isEmpty()) {
						throw new IllegalArgumentException("--pair-by 至少要给一个令牌，例如 gui,page,modules");
					}
					o.pairBy = toks;
					break;
				}
				case "--slots-scope": {
					String v = req(args, ++i, a).trim().toLowerCase(Locale.ROOT);
					if (!v.equals("real-all") && !v.equals("role") && !v.equals("storage-only")) {
						throw new IllegalArgumentException("--slots-scope 只接受 real-all / role / storage-only，实际 " + v);
					}
					o.slotsScope = v;
					break;
				}
				case "--slots-container-max":
					o.slotsContainerMax = Integer.parseInt(req(args, ++i, a));
					o.slotsContainerMaxGiven = true;
					break;
				case "--focus": o.focus = true; break;
				case "--no-info": o.includeInfo = false; break;
				case "--list-cats": o.listCats = true; break;
				case "--fail-on": {
					String v = req(args, ++i, a).toLowerCase(Locale.ROOT);
					for (Sev s : Sev.values()) {
						if (sevName(s).equals(v)) {
							o.failOn = s;
						}
					}
					if (o.failOn == null && !"none".equals(v)) {
						throw new IllegalArgumentException("--fail-on 只接受 critical/high/medium/low/info/none，实际 " + v);
					}
					break;
				}
				case "-h":
				case "--help":
					usage();
					System.exit(0);
					break;
				default:
					if (a.startsWith("--")) {
						throw new IllegalArgumentException("未知选项：" + a);
					}
					pos.add(a);
			}
		}
		if (o.listCats) {
			return o;
		}
		if (pos.size() != 2) {
			usage();
			throw new IllegalArgumentException("需要恰好两个位置参数：A.jsonl B.jsonl（实际 " + pos.size() + " 个）");
		}
		o.a = Path.of(pos.get(0));
		o.b = Path.of(pos.get(1));
		return o;
	}

	static String req(String[] args, int i, String flag) {
		if (i >= args.length) {
			throw new IllegalArgumentException(flag + " 缺少取值");
		}
		return args[i];
	}

	static void usage() {
		System.out.println("probe-diff —— 端口↔真机 GUI dump 差分工具（schema " + SCHEMA + "）");
		System.out.println();
		System.out.println("用法： java ProbeDiff.java <A.jsonl> <B.jsonl> [选项]");
		System.out.println("       java ProbeDiff.java --list-cats");
		System.out.println();
		System.out.println("记录配对：");
		System.out.println("  --pair-by <t1,t2,..>   配对键令牌（gui / state 键 / sem 键 / stateValues 键，可加 state. sem. stateValues. 前缀）");
		System.out.println("                         不写 = gui + 归一化 state 全签名，保留三级降级配对");
		System.out.println("                         写了 = 只用这些令牌，且不再降级猜测（配不上进状态覆盖表）");
		System.out.println("  --state-ignore <k,..>  默认配对时把某些 state 键排除出签名（只影响配对）");
		System.out.println("存储槽：");
		System.out.println("  --slots-scope <mode>   real-all | role（默认） | storage-only");
		System.out.println("                         real-all=全部槽按 i 配对");
		System.out.println("                         role=只比 role 为 target/material* 的槽，按 (role,containerIndex) 配对");
		System.out.println("                         storage-only=role 基础上再要求 containerIndex < --slots-container-max");
		System.out.println("  --slots-container-max  容器自有槽的 containerIndex 上界（默认 9）");
		System.out.println("报告：");
		System.out.println("  --focus                明细改为按节点 key 前缀族汇总并下钻到具体节点");
		System.out.println("  --ignore <c1,c2,..>    整类忽略已确认的对齐噪声，报告里如实列出被忽略项与条数");
		System.out.println("  --list-cats            列出全部类别 id 后退出");
		System.out.println("  --out-md / --out-json  两份报告的输出路径");
		System.out.println("  --a-label / --b-label  两侧显示名（默认 A/真机侧、B/端口侧）");
		System.out.println("  --examples <n>         Markdown 里每类/每族列几个典型例子（默认 3）");
		System.out.println("  --pos-threshold <f>    节点位置回退配对阈值（面板对角线占比，默认 0.25）");
		System.out.println("  --no-info              不输出 info 级");
		System.out.println("  --fail-on <严重度>     差异达到该严重度则退出码 2（critical/high/medium/low/info/none）");
	}

	// ==================================================================================
	// 执行器
	// ==================================================================================

	static final class Run {
		final Opts o;
		final PairSpec pairSpec;
		final List<Rec> recsA = new ArrayList<>();
		final List<Rec> recsB = new ArrayList<>();
		final List<String> parseErrors = new ArrayList<>();
		final List<Pair> pairs = new ArrayList<>();
		final List<Rec> unpairedA = new ArrayList<>();
		final List<Rec> unpairedB = new ArrayList<>();
		final Map<String, List<Diff>> byCat = new LinkedHashMap<>();
		final Map<String, Integer> countsBySeverity = new LinkedHashMap<>();
		final Map<String, Integer> ignoredCounts = new LinkedHashMap<>();
		final Map<String, int[]> slotScopeStats = new LinkedHashMap<>();
		final Map<String, Integer> slotDroppedA = new LinkedHashMap<>();
		final Map<String, Integer> slotDroppedB = new LinkedHashMap<>();
		final Map<String, Cov> coverage = new LinkedHashMap<>();
		int totalDiffs;
		int rawA;
		int rawB;
		int parsedA;
		int parsedB;
		long bytesA;
		long bytesB;
		int exitCode;

		Run(Opts o) {
			this.o = o;
			this.pairSpec = new PairSpec(o.pairBy);
		}

		void execute() throws IOException {
			rawA = load(o.a, recsA, "A");
			rawB = load(o.b, recsB, "B");
			tallySlots();
			pairRecords();
			for (Pair p : pairs) {
				diffPair(p);
			}
			buildCoverage();
			collect();
			sortPairs();
			writeOutputs();
			printSummary();
			if (o.failOn != null) {
				int limit = sevRank(o.failOn);
				for (Diff d : allDiffs()) {
					if (sevRank(CATS.get(d.cat).sev) <= limit) {
						exitCode = 2;
						break;
					}
				}
			}
		}

		int load(Path p, List<Rec> out, String side) throws IOException {
			if (!Files.exists(p)) {
				throw new IOException(side + " 侧文件不存在：" + p.toAbsolutePath());
			}
			byte[] bytes = Files.readAllBytes(p);
			if ("A".equals(side)) {
				bytesA = bytes.length;
			} else {
				bytesB = bytes.length;
			}
			String text = new String(bytes, StandardCharsets.UTF_8);
			if (text.startsWith("\uFEFF")) {
				text = text.substring(1);
			}
			String[] lines = text.split("\r\n|\n|\r", -1);
			Map<String, Rec> firstByKey = new LinkedHashMap<>();
			int raw = 0;
			int parsed = 0;
			for (int i = 0; i < lines.length; i++) {
				String t = lines[i].trim();
				if (t.isEmpty() || t.startsWith("#")) {
					continue;
				}
				raw++;
				int lineNo = i + 1;
				Object parsedObj;
				try {
					parsedObj = parseJson(t);
				} catch (RuntimeException e) {
					parseErrors.add(side + " 第 " + lineNo + " 行解析失败：" + e.getMessage());
					continue;
				}
				if (!(parsedObj instanceof Map)) {
					parseErrors.add(side + " 第 " + lineNo + " 行不是 JSON 对象");
					continue;
				}
				parsed++;
				Rec r = new Rec(lineNo, asObj(parsedObj), o);
				Rec seen = firstByKey.get(r.groupKey);
				if (seen != null) {
					seen.duplicateCount++;
					continue;
				}
				firstByKey.put(r.groupKey, r);
				out.add(r);
			}
			if ("A".equals(side)) {
				parsedA = parsed;
			} else {
				parsedB = parsed;
			}
			return raw;
		}

		// ------------------------------ 存储槽范围统计 ------------------------------

		static final class SlotScope {
			final String mode;
			final int containerMax;

			SlotScope(String mode, int containerMax) {
				this.mode = mode;
				this.containerMax = containerMax;
			}

			boolean all() {
				return "real-all".equals(mode);
			}

			static String roleOf(Map<String, Object> slot) {
				return asStr(asObj(slot.get("sem")).get("role"));
			}

			static boolean storageRole(String role) {
				return role != null && (role.equals("target") || role.startsWith("material"));
			}

			boolean keep(Map<String, Object> slot) {
				if (all()) {
					return true;
				}
				if (!storageRole(roleOf(slot))) {
					return false;
				}
				if ("storage-only".equals(mode)) {
					long ci = asLong(slot.get("containerIndex"), -1);
					return ci >= 0 && ci < containerMax;
				}
				return true;
			}

			String keyOf(Map<String, Object> slot, int idx) {
				if (all()) {
					return "i=" + idx;
				}
				String role = roleOf(slot);
				if (slot.get("containerIndex") != null) {
					return role + "#ci=" + asLong(slot.get("containerIndex"), -1);
				}
				return role + "#xy=" + asLong(slot.get("x"), 0) + "," + asLong(slot.get("y"), 0);
			}

			String describe() {
				if (all()) {
					return "全部槽，按数组下标 i 配对";
				}
				if ("storage-only".equals(mode)) {
					return "role ∈ {target, material*} 且 containerIndex < " + containerMax
						+ "，按 (role, containerIndex) 配对";
				}
				return "role ∈ {target, material*}，按 (role, containerIndex) 配对（无 ci 则用 (role, x, y)）";
			}
		}

		void tallySlots() {
			SlotScope sc = new SlotScope(o.slotsScope, o.slotsContainerMax);
			tallySide(recsA, sc, "A");
			tallySide(recsB, sc, "B");
		}

		void tallySide(List<Rec> recs, SlotScope sc, String side) {
			int[] st = slotScopeStats.computeIfAbsent(side, k -> new int[2]);
			Map<String, Integer> drops = "A".equals(side) ? slotDroppedA : slotDroppedB;
			for (Rec r : recs) {
				for (Object os : r.slots) {
					if (!(os instanceof Map)) {
						continue;
					}
					Map<String, Object> s = asObj(os);
					st[0]++;
					if (sc.keep(s)) {
						st[1]++;
					} else {
						drops.merge("role=" + SlotScope.roleOf(s) + " ci=" + asLong(s.get("containerIndex"), -1),
							1, Integer::sum);
					}
				}
			}
		}

		// ------------------------------ 记录配对 ------------------------------

		void pairRecords() {
			Map<String, List<Rec>> ga = group(recsA);
			Map<String, List<Rec>> gb = group(recsB);
			Set<Rec> usedA = new HashSet<>();
			Set<Rec> usedB = new HashSet<>();

			for (Map.Entry<String, List<Rec>> e : ga.entrySet()) {
				List<Rec> bl = gb.get(e.getKey());
				if (bl == null || bl.isEmpty()) {
					continue;
				}
				pairAll(e.getValue(), bl, "exact", false, "配对键完全一致", usedA, usedB);
			}
			if (!pairSpec.explicit()) {
				Map<String, List<Rec>> ka = groupByKeySet(recsA, usedA);
				Map<String, List<Rec>> kb = groupByKeySet(recsB, usedB);
				for (Map.Entry<String, List<Rec>> e : ka.entrySet()) {
					List<Rec> bl = kb.get(e.getKey());
					if (bl == null || bl.isEmpty()) {
						continue;
					}
					pairAll(e.getValue(), bl, "state-values-differ", true,
						"gui 与 state 键名相同但值不同（键名集合：" + e.getKey().replace('\u0001', '|') + "）",
						usedA, usedB);
				}
				Map<String, List<Rec>> oa = groupByGui(recsA, usedA);
				Map<String, List<Rec>> ob = groupByGui(recsB, usedB);
				for (Map.Entry<String, List<Rec>> e : oa.entrySet()) {
					List<Rec> bl = ob.get(e.getKey());
					if (bl == null || bl.isEmpty()) {
						continue;
					}
					List<Rec> al = e.getValue();
					int n = Math.min(al.size(), bl.size());
					for (int i = 0; i < n; i++) {
						Pair p = new Pair();
						p.a = al.get(i);
						p.b = bl.get(i);
						p.level = "order-guess";
						p.lowConf = true;
						p.reason = "state 键名集合不同，仅凭 gui + 出现次序猜测配对（第 " + (i + 1) + " 个）";
						pairs.add(p);
						usedA.add(al.get(i));
						usedB.add(bl.get(i));
					}
				}
			}
			for (Rec r : recsA) {
				if (!usedA.contains(r)) {
					unpairedA.add(r);
				}
			}
			for (Rec r : recsB) {
				if (!usedB.contains(r)) {
					unpairedB.add(r);
				}
			}
			// 注意：buildCoverage() 必须等所有 diffPair() 跑完再调 —— 它要读每对的 diffCount 与 state 差异摘要
		}

		void pairAll(List<Rec> al, List<Rec> bl, String level, boolean lowConf, String reason,
					 Set<Rec> usedA, Set<Rec> usedB) {
			int n = Math.min(al.size(), bl.size());
			for (int i = 0; i < n; i++) {
				Pair p = new Pair();
				p.a = al.get(i);
				p.b = bl.get(i);
				p.level = level;
				p.lowConf = lowConf;
				p.reason = reason;
				pairs.add(p);
				usedA.add(al.get(i));
				usedB.add(bl.get(i));
			}
		}

		Map<String, List<Rec>> group(List<Rec> recs) {
			Map<String, List<Rec>> m = new LinkedHashMap<>();
			for (Rec r : recs) {
				m.computeIfAbsent(r.groupKey, k -> new ArrayList<>()).add(r);
			}
			return m;
		}

		Map<String, List<Rec>> groupByKeySet(List<Rec> recs, Set<Rec> used) {
			Map<String, List<Rec>> m = new LinkedHashMap<>();
			for (Rec r : recs) {
				if (used.contains(r)) {
					continue;
				}
				m.computeIfAbsent((r.gui == null ? "?" : r.gui) + "\u0001" + r.stateKeySet, x -> new ArrayList<>()).add(r);
			}
			return m;
		}

		Map<String, List<Rec>> groupByGui(List<Rec> recs, Set<Rec> used) {
			Map<String, List<Rec>> m = new LinkedHashMap<>();
			for (Rec r : recs) {
				if (used.contains(r)) {
					continue;
				}
				m.computeIfAbsent(r.gui == null ? "?" : r.gui, x -> new ArrayList<>()).add(r);
			}
			return m;
		}

		// ------------------------------ 状态覆盖表 ------------------------------

		static final class Cov {
			String key;
			int aCount;
			int bCount;
			String aPage = "∅";
			String bPage = "∅";
			int aNodes;
			int bNodes;
			String pairedWith;
			String level = "—";
			String reason = "";
			boolean lowConf;
			int diffCount;
			String stateDiff = "";

			/** 降级配对（state-values-differ / order-guess）时两侧分组键不同，所以"是否配上了"看 level 而不是看同键计数。 */
			String coverageKind() {
				if (!"—".equals(level)) {
					return "paired";
				}
				if (aCount > 0 && bCount == 0) {
					return "only-a";
				}
				if (aCount == 0 && bCount > 0) {
					return "only-b";
				}
				return "paired";
			}
		}

		void buildCoverage() {
			for (Rec r : recsA) {
				Cov c = coverage.computeIfAbsent(r.groupKey, k -> {
					Cov x = new Cov();
					x.key = r.label();
					return x;
				});
				c.aCount += r.groupSize();
				c.aNodes = r.nodes.size();
				c.aPage = r.pageToken;
			}
			for (Rec r : recsB) {
				Cov c = coverage.computeIfAbsent(r.groupKey, k -> {
					Cov x = new Cov();
					x.key = r.label();
					return x;
				});
				c.bCount += r.groupSize();
				c.bNodes = r.nodes.size();
				c.bPage = r.pageToken;
			}
			for (Pair p : pairs) {
				Cov ca = p.a == null ? null : coverage.get(p.a.groupKey);
				if (ca != null) {
					ca.level = p.level;
					ca.reason = p.reason;
					ca.lowConf = p.lowConf;
					ca.diffCount = p.diffs.size();
					ca.stateDiff = stateDiffSummary(p);
				}
				if (p.a != null && p.b != null && !p.a.groupKey.equals(p.b.groupKey)) {
					if (ca != null) {
						ca.pairedWith = p.b.label();
					}
					Cov cb = coverage.get(p.b.groupKey);
					if (cb != null) {
						cb.pairedWith = p.a.label();
						cb.level = p.level;
						cb.lowConf = p.lowConf;
						cb.diffCount = p.diffs.size();
						cb.stateDiff = stateDiffSummary(p);
					}
				}
			}
		}

		String stateDiffSummary(Pair p) {
			List<String> bits = new ArrayList<>();
			StringBuilder onlyA = new StringBuilder();
			StringBuilder onlyB = new StringBuilder();
			for (Diff d : p.diffs) {
				if ("state_keys_only_a".equals(d.cat)) {
					if (onlyA.length() > 0) {
						onlyA.append(',');
					}
					onlyA.append(d.path);
				} else if ("state_keys_only_b".equals(d.cat)) {
					if (onlyB.length() > 0) {
						onlyB.append(',');
					}
					onlyB.append(d.path);
				} else if ("state_value".equals(d.cat)) {
					bits.add(d.path + " " + d.aVal + "→" + d.bVal);
				}
			}
			if (onlyA.length() > 0) {
				bits.add("仅A有:" + onlyA);
			}
			if (onlyB.length() > 0) {
				bits.add("仅B有:" + onlyB);
			}
			return bits.isEmpty() ? "—" : String.join("；", bits);
		}

		// ------------------------------ 单对记录的差异 ------------------------------

		void diffPair(Pair p) {
			Map<String, Object> ra = p.a.raw;
			Map<String, Object> rb = p.b.raw;

			String sa = asStr(ra.get("schema"));
			String sb = asStr(rb.get("schema"));
			if (!SCHEMA.equals(sa) || !SCHEMA.equals(sb)) {
				p.add("schema_mismatch", "schema", sa, sb,
					"两侧都应当是 " + SCHEMA + "；不认识的 schema 会让字段语义失配", false);
			}
			if (p.a.gui == null || !p.a.gui.equals(p.b.gui)) {
				p.add("line_gui", "gui", p.a.gui, p.b.gui, "界面 id 不同 → 其实不是同一个界面", false);
			}

			lineField(p, "title", ra.get("title"), rb.get("title"));
			lineField(p, "origin", ra.get("origin"), rb.get("origin"));
			lineField(p, "panel", ra.get("panel"), rb.get("panel"));
			diffMapInto(p, "line_field", "line_field", "line_field", "menu",
				asObj(ra.get("menu")), asObj(rb.get("menu")));
			diffMapInto(p, "line_sem", "line_sem", "line_sem", "sem",
				asObj(ra.get("sem")), asObj(rb.get("sem")));
			diffMapInto(p, "state_decl", "state_decl", "state_decl", "stateDecl",
				asObj(ra.get("stateDecl")), asObj(rb.get("stateDecl")));
			diffMapInto(p, "state_keys_only_a", "state_keys_only_b", "state_value", null,
				p.a.state, p.b.state);
			diffMapInto(p, "state_keys_only_a", "state_keys_only_b", "state_value", "stateValues",
				p.a.stateValues, p.b.stateValues);

			long nca = asLong(ra.get("nodesCount"), -1);
			long ncb = asLong(rb.get("nodesCount"), -1);
			if (nca != p.a.nodes.size()) {
				p.add("line_nodesCount", "nodesCount(A)", String.valueOf(nca), String.valueOf(p.a.nodes.size()),
					"A 侧 nodesCount 与实际 nodes[] 长度不一致（dump 自身不自洽）", false);
			}
			if (ncb != p.b.nodes.size()) {
				p.add("line_nodesCount", "nodesCount(B)", String.valueOf(ncb), String.valueOf(p.b.nodes.size()),
					"B 侧 nodesCount 与实际 nodes[] 长度不一致（dump 自身不自洽）", false);
			}

			diffNodes(p);
			diffSlots(p);
			diffInteractive(p);
		}

		void lineField(Pair p, String field, Object av, Object bv) {
			String ca = canon(av);
			String cb = canon(bv);
			if (ca == null && cb == null) {
				return;
			}
			if (ca != null && ca.equals(cb)) {
				return;
			}
			p.add("line_field", field, show(av), show(bv), null, p.lowConf);
		}

		void diffMapInto(Pair p, String catOnlyA, String catOnlyB, String catValue, String pathPrefix,
						 Map<String, Object> a, Map<String, Object> b) {
			Set<String> keys = new LinkedHashSet<>();
			keys.addAll(a.keySet());
			keys.addAll(b.keySet());
			for (String k : keys) {
				boolean inA = a.containsKey(k);
				boolean inB = b.containsKey(k);
				String path = pathPrefix == null ? k : pathPrefix + "." + k;
				if (inA && !inB) {
					p.add(catOnlyA, path, show(a.get(k)), "缺失", "该键仅 A 侧有", p.lowConf);
				} else if (!inA && inB) {
					p.add(catOnlyB, path, "缺失", show(b.get(k)), "该键仅 B 侧有", p.lowConf);
				} else {
					String ca = canon(a.get(k));
					String cb = canon(b.get(k));
					if (ca == null ? cb != null : !ca.equals(cb)) {
						p.add(catValue, path, show(a.get(k)), show(b.get(k)), null, p.lowConf);
					}
				}
			}
		}

		// ------------------------------ 节点 ------------------------------

		static final class NodeRef {
			final Map<String, Object> node;
			final int idx;
			final double cx;
			final double cy;

			NodeRef(Map<String, Object> node, int idx) {
				this.node = node;
				this.idx = idx;
				List<Object> rect = asArr(node.get("rect"));
				double x = asDouble(rect.size() > 0 ? rect.get(0) : null, 0);
				double y = asDouble(rect.size() > 1 ? rect.get(1) : null, 0);
				double w = asDouble(rect.size() > 2 ? rect.get(2) : null, 0);
				double h = asDouble(rect.size() > 3 ? rect.get(3) : null, 0);
				this.cx = x + w / 2.0;
				this.cy = y + h / 2.0;
			}

			String key() {
				return asStr(node.get("key"));
			}

			String type() {
				return asStr(node.get("type"));
			}
		}

		void diffNodes(Pair p) {
			List<NodeRef> an = refs(p.a.nodes);
			List<NodeRef> bn = refs(p.b.nodes);
			Map<String, List<NodeRef>> ka = byKey(an);
			Map<String, List<NodeRef>> kb = byKey(bn);
			Set<NodeRef> doneA = new HashSet<>();
			Set<NodeRef> doneB = new HashSet<>();

			for (Map.Entry<String, List<NodeRef>> e : ka.entrySet()) {
				String k = e.getKey();
				List<NodeRef> la = e.getValue();
				List<NodeRef> lb = kb.get(k);
				if (lb == null) {
					continue;
				}
				if (la.size() == 1 && lb.size() == 1) {
					diffNode(p, la.get(0), lb.get(0), false, "key 配对");
					doneA.add(la.get(0));
					doneB.add(lb.get(0));
				} else {
					p.add("node_key_conflict", "nodes[key=" + k + "]",
						String.valueOf(la.size()), String.valueOf(lb.size()),
						"同一侧出现重复 key（A " + la.size() + " 个 / B " + lb.size()
							+ " 个）→ 这些节点降级为位置贪心配对，置信度低", true);
				}
			}

			List<NodeRef> fa = new ArrayList<>();
			List<NodeRef> fb = new ArrayList<>();
			for (NodeRef r : an) {
				if (!doneA.contains(r)) {
					fa.add(r);
				}
			}
			for (NodeRef r : bn) {
				if (!doneB.contains(r)) {
					fb.add(r);
				}
			}

			double diag = panelDiag(p);
			List<int[]> matched = greedyByPosition(fa, fb, o.posThreshold, diag);
			Set<NodeRef> lowA = new HashSet<>();
			Set<NodeRef> lowB = new HashSet<>();
			for (int[] m : matched) {
				NodeRef x = fa.get(m[0]);
				NodeRef y = fb.get(m[1]);
				lowA.add(x);
				lowB.add(y);
				diffNode(p, x, y, true, "位置贪心配对（key " + (x.key() == null ? "缺失" : x.key())
					+ " ↔ " + (y.key() == null ? "缺失" : y.key()) + "）");
				p.add("node_pair_lowconf", path(x, y), describeShort(x), describeShort(y),
					"key 不可用，按 type=" + (x.type() == null ? "?" : x.type())
						+ " + 相对位置贪心配对；距离 " + fmt3(dist(x, y) / (diag <= 0 ? 1 : diag))
						+ " × 面板对角线。此配对不确定，请人工确认。", true);
			}

			for (NodeRef r : fa) {
				if (!lowA.contains(r)) {
					p.add("node_missing", path(r, null), describeShort(r), "缺失", semNote(r.node), false);
				}
			}
			for (NodeRef r : fb) {
				if (!lowB.contains(r)) {
					p.add("node_extra", path(null, r), "缺失", describeShort(r), semNote(r.node), false);
				}
			}
		}

		List<NodeRef> refs(List<Object> nodes) {
			List<NodeRef> out = new ArrayList<>();
			for (int i = 0; i < nodes.size(); i++) {
				Object on = nodes.get(i);
				if (on instanceof Map) {
					out.add(new NodeRef(asObj(on), i));
				} else {
					Map<String, Object> empty = new LinkedHashMap<>();
					empty.put("key", null);
					empty.put("type", null);
					out.add(new NodeRef(empty, i));
				}
			}
			return out;
		}

		Map<String, List<NodeRef>> byKey(List<NodeRef> refs) {
			Map<String, List<NodeRef>> m = new LinkedHashMap<>();
			for (NodeRef r : refs) {
				String k = r.key();
				if (k == null || k.isEmpty()) {
					continue;
				}
				m.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
			}
			return m;
		}

		double panelDiag(Pair p) {
			List<Object> pa = asArr(p.a.raw.get("panel"));
			double w = asDouble(pa.size() > 0 ? pa.get(0) : null, 0);
			double h = asDouble(pa.size() > 1 ? pa.get(1) : null, 0);
			if (w <= 0 || h <= 0) {
				double mw = 0;
				double mh = 0;
				for (NodeRef r : refs(p.a.nodes)) {
					mw = Math.max(mw, r.cx * 2);
					mh = Math.max(mh, r.cy * 2);
				}
				w = mw <= 0 ? 320 : mw;
				h = mh <= 0 ? 240 : mh;
			}
			return Math.sqrt(w * w + h * h);
		}

		double dist(NodeRef a, NodeRef b) {
			double dx = a.cx - b.cx;
			double dy = a.cy - b.cy;
			return Math.sqrt(dx * dx + dy * dy);
		}

		List<int[]> greedyByPosition(List<NodeRef> fa, List<NodeRef> fb, double threshold, double diag) {
			List<double[]> cand = new ArrayList<>();
			for (int i = 0; i < fa.size(); i++) {
				for (int j = 0; j < fb.size(); j++) {
					String ta = fa.get(i).type();
					String tb = fb.get(j).type();
					if (ta != null && tb != null && !ta.equals(tb)) {
						continue;
					}
					cand.add(new double[]{dist(fa.get(i), fb.get(j)), i, j});
				}
			}
			cand.sort(Comparator.<double[]>comparingDouble(x -> x[0])
				.thenComparingDouble(x -> x[1]).thenComparingDouble(x -> x[2]));
			boolean[] ua = new boolean[fa.size()];
			boolean[] ub = new boolean[fb.size()];
			double limit = threshold * diag;
			List<int[]> out = new ArrayList<>();
			for (double[] c : cand) {
				int i = (int) c[1];
				int j = (int) c[2];
				if (ua[i] || ub[j] || c[0] > limit) {
					continue;
				}
				ua[i] = true;
				ub[j] = true;
				out.add(new int[]{i, j});
			}
			return out;
		}

		String describeShort(NodeRef r) {
			return "i=" + r.idx + " key=" + (r.key() == null ? "∅" : r.key())
				+ " type=" + (r.type() == null ? "∅" : r.type())
				+ " rect=" + show(r.node.get("rect"));
		}

		static String briefNode(Map<String, Object> n) {
			return "rect=" + compactJson(n.get("rect"))
				+ " text=" + show(n.get("text"))
				+ " action=" + show(n.get("action"));
		}

		String semNote(Map<String, Object> node) {
			Map<String, Object> sem = asObj(node.get("sem"));
			if (sem.isEmpty()) {
				return null;
			}
			StringBuilder sb = new StringBuilder("sem: ");
			boolean first = true;
			for (Map.Entry<String, Object> e : sem.entrySet()) {
				if (!first) {
					sb.append(", ");
				}
				first = false;
				sb.append(e.getKey()).append('=').append(show(e.getValue()));
			}
			return sb.toString();
		}

		String path(NodeRef a, NodeRef b) {
			NodeRef r = a != null ? a : b;
			String k = r.key();
			String side = a != null && b != null ? "两侧" : (a != null ? "A" : "B");
			return "nodes[" + side + ",i=" + r.idx + (k == null || k.isEmpty() ? "" : ",key=" + k) + "]";
		}

		static String fmt3(double d) {
			return String.format(Locale.ROOT, "%.3f", d);
		}

		void diffNode(Pair p, NodeRef a, NodeRef b, boolean lowConf, String how) {
			Map<String, Object> na = a.node;
			Map<String, Object> nb = b.node;
			String base = path(a, b);
			boolean lc = lowConf || p.lowConf;
			String note = lowConf ? "配对方式：" + how + "（不确定）" : null;

			List<Object> ra = asArr(na.get("rect"));
			List<Object> rb = asArr(nb.get("rect"));
			String[] rn = {"x", "y", "w", "h"};
			for (int i = 0; i < 4; i++) {
				Object va = i < ra.size() ? ra.get(i) : null;
				Object vb = i < rb.size() ? rb.get(i) : null;
				String ca = canon(va);
				String cb = canon(vb);
				if (ca == null ? cb == null : ca.equals(cb)) {
					continue;
				}
				String extra = note;
				if (va != null && vb != null) {
					extra = (extra == null ? "" : extra + "；") + "Δ" + rn[i] + "=" + (asLong(vb, 0) - asLong(va, 0));
				}
				p.add(i < 2 ? "node_coord" : "node_size", base + ".rect[" + i + "](" + rn[i] + ")",
					show(va), show(vb), extra, lc);
			}

			simple(p, a, b, "type", "node_type", lc, note);
			listField(p, a, b, "draw", "node_draw", lc, note);
			simple(p, a, b, "layer", "node_layer", lc, note);
			simple(p, a, b, "texture", "node_texture", lc, note);
			listField(p, a, b, "src", "node_texture", lc, note);
			listField(p, a, b, "uv", "node_uv", lc, note);
			simple(p, a, b, "scaled", "node_scaled", lc, note);

			simple(p, a, b, "text", "node_text", lc, join(note, langKeyNote(na, nb)));
			simple(p, a, b, "textScale", "node_textscale", lc, note);
			simple(p, a, b, "shadow", "node_shadow", lc, note);
			simple(p, a, b, "tooltip", "node_tooltip", lc, join(note, langKeyNote(na, nb)));

			simple(p, a, b, "tint", "node_color", lc, note);
			simple(p, a, b, "fill", "node_color", lc, note);
			simple(p, a, b, "color", "node_color", lc, note);
			simple(p, a, b, "hover", "node_color", lc, note);
			simple(p, a, b, "opacity", "node_opacity", lc, note);

			simple(p, a, b, "action", "node_action", lc, note);
			simple(p, a, b, "value", "node_action", lc, note);
			simple(p, a, b, "enabled", "node_enabled", lc, note);

			simple(p, a, b, "handler", "node_handler", lc, note);
			simple(p, a, b, "handlerClass", "node_handler", lc, note);
			simple(p, a, b, "handlerRegistered", "node_handler", lc, note);

			simple(p, a, b, "slot", "node_slot", lc, note);
			simple(p, a, b, "invSlot", "node_slot", lc, note);
			simple(p, a, b, "bound", "node_slot", lc, note);

			simple(p, a, b, "delay", "node_anim", lc, note);
			simple(p, a, b, "alphaTag", "node_anim", lc, note);
			simple(p, a, b, "alphaRest", "node_anim", lc, note);
			listField(p, a, b, "slide", "node_anim", lc, note);

			diffMapInto(p, "node_sem", "node_sem", "node_sem", base + ".sem",
				asObj(na.get("sem")), asObj(nb.get("sem")));
		}

		String join(String a, String b) {
			if (a == null) {
				return b;
			}
			if (b == null) {
				return a;
			}
			return a + "；" + b;
		}

		String langKeyNote(Map<String, Object> na, Map<String, Object> nb) {
			String la = asStr(asObj(na.get("sem")).get("langKey"));
			String lb = asStr(asObj(nb.get("sem")).get("langKey"));
			if (la == null && lb == null) {
				return null;
			}
			return "langKey(A)=" + (la == null ? "∅" : la) + " langKey(B)=" + (lb == null ? "∅" : lb);
		}

		void simple(Pair p, NodeRef a, NodeRef b, String field, String cat, boolean lc, String note) {
			Object va = a.node.get(field);
			Object vb = b.node.get(field);
			String ca = canon(va);
			String cb = canon(vb);
			if (ca == null && cb == null) {
				return;
			}
			if (ca != null && ca.equals(cb)) {
				return;
			}
			p.add(cat, path(a, b) + "." + field, show(va), show(vb), note, lc);
		}

		void listField(Pair p, NodeRef a, NodeRef b, String field, String cat, boolean lc, String note) {
			Object va = a.node.get(field);
			Object vb = b.node.get(field);
			if (va == null && vb == null) {
				return;
			}
			String ca = compactJson(va);
			String cb = compactJson(vb);
			if (ca.equals(cb)) {
				return;
			}
			p.add(cat, path(a, b) + "." + field, ca, cb, note, lc);
		}

		// ------------------------------ 存储槽 ------------------------------

		static final class SlotEntry {
			final Map<String, Object> slot;
			final int idx;
			final String key;

			SlotEntry(Map<String, Object> slot, int idx, String key) {
				this.slot = slot;
				this.idx = idx;
				this.key = key;
			}
		}

		void diffSlots(Pair p) {
			SlotScope sc = new SlotScope(o.slotsScope, o.slotsContainerMax);
			List<SlotEntry> sa = slotEntries(p.a, sc);
			List<SlotEntry> sb = slotEntries(p.b, sc);
			Map<String, SlotEntry> ma = new LinkedHashMap<>();
			Map<String, SlotEntry> mb = new LinkedHashMap<>();
			for (SlotEntry e : sa) {
				ma.putIfAbsent(e.key, e);
			}
			for (SlotEntry e : sb) {
				mb.putIfAbsent(e.key, e);
			}
			Set<String> keys = new LinkedHashSet<>();
			keys.addAll(ma.keySet());
			keys.addAll(mb.keySet());
			for (String k : keys) {
				SlotEntry ea = ma.get(k);
				SlotEntry eb = mb.get(k);
				String base = "slots[" + k + "]";
				if (ea != null && eb == null) {
					p.add("slot_missing", base, describeSlot(ea), "缺失",
						"A 侧有该存储槽（配对键 " + k + "），B 侧没有", p.lowConf);
					continue;
				}
				if (ea == null) {
					p.add("slot_extra", base, "缺失", describeSlot(eb),
						"B 侧多出该存储槽（配对键 " + k + "），A 侧没有", p.lowConf);
					continue;
				}
				Map<String, Object> x = ea.slot;
				Map<String, Object> y = eb.slot;
				int dx = (int) (asLong(y.get("x"), 0) - asLong(x.get("x"), 0));
				int dy = (int) (asLong(y.get("y"), 0) - asLong(x.get("y"), 0));
				if (dx != 0 || dy != 0) {
					p.add("slot_coord", base + ".x/y",
						show(x.get("x")) + "," + show(x.get("y")),
						show(y.get("x")) + "," + show(y.get("y")),
						"Δx=" + dx + " Δy=" + dy + "（A 数组下标 " + ea.idx + " ↔ B 数组下标 " + eb.idx + "）",
						p.lowConf);
				}
				slotField(p, base, "containerIndex", x, y, "slot_coord");
				slotField(p, base, "active", x, y, "slot_active");
				slotField(p, base, "empty", x, y, "slot_empty");
				slotField(p, base, "item", x, y, "slot_item");
				slotField(p, base, "count", x, y, "slot_count");
				slotField(p, base, "dynamic", x, y, "slot_dynamic");
				slotField(p, base, "filtered", x, y, "slot_filtered");
				diffMapInto(p, "slot_sem", "slot_sem", "slot_sem", base + ".sem",
					asObj(x.get("sem")), asObj(y.get("sem")));
			}
		}

		List<SlotEntry> slotEntries(Rec rec, SlotScope sc) {
			List<SlotEntry> out = new ArrayList<>();
			for (int i = 0; i < rec.slots.size(); i++) {
				Object os = rec.slots.get(i);
				if (!(os instanceof Map)) {
					continue;
				}
				Map<String, Object> s = asObj(os);
				if (sc.keep(s)) {
					out.add(new SlotEntry(s, i, sc.keyOf(s, i)));
				}
			}
			return out;
		}

		String describeSlot(SlotEntry e) {
			Map<String, Object> s = e.slot;
			return "i=" + e.idx + " ci=" + show(s.get("containerIndex"))
				+ " xy=" + show(s.get("x")) + "," + show(s.get("y"))
				+ " role=" + show(asObj(s.get("sem")).get("role"))
				+ " active=" + show(s.get("active")) + " empty=" + show(s.get("empty"))
				+ " item=" + show(s.get("item")) + " count=" + show(s.get("count"));
		}

		void slotField(Pair p, String base, String field, Map<String, Object> a, Map<String, Object> b, String cat) {
			Object va = a.get(field);
			Object vb = b.get(field);
			String ca = canon(va);
			String cb = canon(vb);
			if (ca == null && cb == null) {
				return;
			}
			if (ca != null && ca.equals(cb)) {
				return;
			}
			p.add(cat, base + "." + field, show(va), show(vb), null, p.lowConf);
		}

		// ------------------------------ 交互项 ------------------------------

		void diffInteractive(Pair p) {
			List<Map<String, Object>> ia = asMapList(p.a.interactive);
			List<Map<String, Object>> ib = asMapList(p.b.interactive);
			Set<Integer> doneA = new HashSet<>();
			Set<Integer> doneB = new HashSet<>();

			Map<String, List<Integer>> dupA = new LinkedHashMap<>();
			Map<String, List<Integer>> dupB = new LinkedHashMap<>();
			for (int i = 0; i < ia.size(); i++) {
				dupA.computeIfAbsent(iaPairKey(ia.get(i)), x -> new ArrayList<>()).add(i);
			}
			for (int i = 0; i < ib.size(); i++) {
				dupB.computeIfAbsent(iaPairKey(ib.get(i)), x -> new ArrayList<>()).add(i);
			}
			for (Map.Entry<String, List<Integer>> e : dupA.entrySet()) {
				List<Integer> lb = dupB.get(e.getKey());
				if (lb == null || e.getValue().size() != 1 || lb.size() != 1) {
					continue;
				}
				diffInteractiveEntry(p, ia.get(e.getValue().get(0)), ib.get(lb.get(0)), false);
				doneA.add(e.getValue().get(0));
				doneB.add(lb.get(0));
			}
			Map<String, List<Integer>> na = new LinkedHashMap<>();
			Map<String, List<Integer>> nb = new LinkedHashMap<>();
			for (int i = 0; i < ia.size(); i++) {
				if (!doneA.contains(i)) {
					na.computeIfAbsent(show(ia.get(i).get("node")), x -> new ArrayList<>()).add(i);
				}
			}
			for (int i = 0; i < ib.size(); i++) {
				if (!doneB.contains(i)) {
					nb.computeIfAbsent(show(ib.get(i).get("node")), x -> new ArrayList<>()).add(i);
				}
			}
			for (Map.Entry<String, List<Integer>> e : na.entrySet()) {
				List<Integer> lb = nb.get(e.getKey());
				if (lb == null) {
					continue;
				}
				List<Integer> la = e.getValue();
				int n = Math.min(la.size(), lb.size());
				for (int t = 0; t < n; t++) {
					diffInteractiveEntry(p, ia.get(la.get(t)), ib.get(lb.get(t)), true);
					doneA.add(la.get(t));
					doneB.add(lb.get(t));
				}
			}
			List<Integer> restA = new ArrayList<>();
			List<Integer> restB = new ArrayList<>();
			for (int i = 0; i < ia.size(); i++) {
				if (!doneA.contains(i)) {
					restA.add(i);
				}
			}
			for (int i = 0; i < ib.size(); i++) {
				if (!doneB.contains(i)) {
					restB.add(i);
				}
			}
			int n = Math.min(restA.size(), restB.size());
			for (int t = 0; t < n; t++) {
				diffInteractiveEntry(p, ia.get(restA.get(t)), ib.get(restB.get(t)), true);
				doneA.add(restA.get(t));
				doneB.add(restB.get(t));
			}
			for (int i = 0; i < ia.size(); i++) {
				if (!doneA.contains(i)) {
					Map<String, Object> e = ia.get(i);
					p.add("ia_missing", iaPath(e), describeIa(e), "缺失",
						"A 侧导出的可交互接口里 B 侧没有", p.lowConf);
				}
			}
			for (int i = 0; i < ib.size(); i++) {
				if (!doneB.contains(i)) {
					Map<String, Object> e = ib.get(i);
					p.add("ia_extra", iaPath(e), "缺失", describeIa(e), "B 侧多出的可交互接口", p.lowConf);
				}
			}
		}

		String iaPairKey(Map<String, Object> e) {
			return show(e.get("node")) + "\u0001" + show(e.get("action"));
		}

		String iaPath(Map<String, Object> e) {
			return "interactive[node=" + show(e.get("node")) + ",action=" + show(e.get("action")) + "]";
		}

		String describeIa(Map<String, Object> e) {
			return "i=" + show(e.get("i")) + " node=" + show(e.get("node")) + " action=" + show(e.get("action"))
				+ " value=" + show(e.get("value")) + " enabled=" + show(e.get("enabled"))
				+ " rect=" + show(e.get("rect"));
		}

		List<Map<String, Object>> asMapList(List<Object> l) {
			List<Map<String, Object>> out = new ArrayList<>();
			for (Object on : l) {
				if (on instanceof Map) {
					out.add(asObj(on));
				}
			}
			return out;
		}

		void diffInteractiveEntry(Pair p, Map<String, Object> a, Map<String, Object> b, boolean lowConf) {
			String base = "interactive[node=" + show(a.get("node")) + "]";
			boolean lc = lowConf || p.lowConf;
			String note = lowConf ? "按 node 名/次序回退配对（action 可能也不同，不确定）" : null;
			if (lowConf && !show(a.get("node")).equals(show(b.get("node")))) {
				p.add("ia_extra", base + ".node", show(a.get("node")), show(b.get("node")),
					"回退配对后节点名仍不同 → 这个配对不可靠", true);
			}
			iaField(p, base, "action", a, b, "ia_action", lc, note);
			iaField(p, base, "value", a, b, "ia_value", lc, note);
			iaField(p, base, "enabled", a, b, "ia_enabled", lc, note);
			iaField(p, base, "handler", a, b, "ia_handler", lc, note);
			iaField(p, base, "handlerClass", a, b, "ia_handler", lc, note);
			iaField(p, base, "handlerRegistered", a, b, "ia_handler", lc, note);
			String ca = compactJson(a.get("rect"));
			String cb = compactJson(b.get("rect"));
			if (!ca.equals(cb)) {
				p.add("ia_rect", base + ".rect", ca, cb, note, lc);
			}
			String ia = canon(a.get("i"));
			String ib = canon(b.get("i"));
			if (ia != null && ib != null && !ia.equals(ib)) {
				p.add("ia_rect", base + ".i", show(a.get("i")), show(b.get("i")),
					"节点下标不同（说明节点顺序变了）", lc);
			}
		}

		void iaField(Pair p, String base, String field, Map<String, Object> a, Map<String, Object> b,
					 String cat, boolean lc, String note) {
			Object va = a.get(field);
			Object vb = b.get(field);
			String ca = canon(va);
			String cb = canon(vb);
			if (ca == null && cb == null) {
				return;
			}
			if (ca != null && ca.equals(cb)) {
				return;
			}
			p.add(cat, base + "." + field, show(va), show(vb), note, lc);
		}

		// ------------------------------ 汇总 ------------------------------

		void collect() {
			for (Rec r : unpairedA) {
				addDiff(new Diff("record_only_a", r.shortLabel(), "none", r.lineNo, 0, r.gui, null,
					"record", "page=" + r.pageToken + " nodes=" + r.nodes.size(), "缺失",
					"A 侧独有的记录（配对键 " + r.pairKey + " 在 B 侧找不到对应）", false));
			}
			for (Rec r : unpairedB) {
				addDiff(new Diff("record_only_b", r.shortLabel(), "none", 0, r.lineNo, null, r.gui,
					"record", "缺失", "page=" + r.pageToken + " nodes=" + r.nodes.size(),
					"B 侧独有的记录（配对键 " + r.pairKey + " 在 A 侧找不到对应）", false));
			}
			for (Rec r : recsA) {
				if (r.duplicateCount > 0) {
					addDiff(new Diff("record_dup", r.shortLabel(), "none", r.lineNo, 0, r.gui, null,
						"record", String.valueOf(r.groupSize()), "1",
						"A 侧同配对键记录出现 " + r.groupSize() + " 次（只取首条参与 diff）", false));
					if (pairSpec.explicit()) {
						addDiff(new Diff("pair_key_collision", r.shortLabel(), "none", r.lineNo, 0, r.gui, null,
							"record", String.valueOf(r.groupSize()), "1",
							"A 侧 " + r.groupSize() + " 条记录落到同一配对键（--pair-by 过粗，这些状态无法区分）", false));
					}
				}
			}
			for (Rec r : recsB) {
				if (r.duplicateCount > 0) {
					addDiff(new Diff("record_dup", r.shortLabel(), "none", 0, r.lineNo, null, r.gui,
						"record", "1", String.valueOf(r.groupSize()),
						"B 侧同配对键记录出现 " + r.groupSize() + " 次（只取首条参与 diff）", false));
					if (pairSpec.explicit()) {
						addDiff(new Diff("pair_key_collision", r.shortLabel(), "none", 0, r.lineNo, null, r.gui,
							"record", "1", String.valueOf(r.groupSize()),
							"B 侧 " + r.groupSize() + " 条记录落到同一配对键（--pair-by 过粗，这些状态无法区分）", false));
					}
				}
			}
			for (String e : parseErrors) {
				addDiff(new Diff("parse_error", "?", "none", 0, 0, null, null, "line", null, null, e, false));
			}
			for (Pair p : pairs) {
				for (Diff d : p.diffs) {
					addDiff(d);
				}
			}
		}

		void addDiff(Diff d) {
			Cat c = CATS.get(d.cat);
			if (c == null) {
				throw new IllegalStateException("内部错误：使用了未注册的类别 id：" + d.cat);
			}
			if (!o.includeInfo && c.sev == Sev.INFO) {
				return;
			}
			if (o.ignoreCats.contains(d.cat)) {
				ignoredCounts.merge(d.cat, 1, Integer::sum);
				return;
			}
			d.family = familyOf(d);
			byCat.computeIfAbsent(d.cat, k -> new ArrayList<>()).add(d);
			countsBySeverity.merge(sevName(c.sev), 1, Integer::sum);
			totalDiffs++;
		}

		List<Diff> allDiffs() {
			List<Diff> all = new ArrayList<>();
			for (List<Diff> l : byCat.values()) {
				all.addAll(l);
			}
			return all;
		}

		void sortPairs() {
			pairs.sort(Comparator.comparingInt((Pair p) -> -p.diffs.size()).thenComparing(Pair::label));
		}

		// ------------------------------ 族汇总 ------------------------------

		static final class Fam {
			String name;
			int count;
			final Map<String, Integer> bySev = new LinkedHashMap<>();
			final Set<String> keys = new LinkedHashSet<>();
			final Map<String, Integer> byCat = new LinkedHashMap<>();
			final Set<String> records = new LinkedHashSet<>();
		}

		Map<String, Fam> familyAgg() {
			Map<String, Fam> m = new LinkedHashMap<>();
			for (Map.Entry<String, List<Diff>> e : byCat.entrySet()) {
				Cat c = CATS.get(e.getKey());
				for (Diff d : e.getValue()) {
					String f = d.family == null ? familyOf(d) : d.family;
					Fam fam = m.get(f);
					if (fam == null) {
						fam = new Fam();
						fam.name = f;
						m.put(f, fam);
					}
					fam.count++;
					fam.bySev.merge(sevName(c.sev), 1, Integer::sum);
					fam.byCat.merge(d.cat, 1, Integer::sum);
					fam.records.add(d.record);
					String nk = nodeKeyOfDiff(d);
					if (nk != null) {
						fam.keys.add(nk);
					}
				}
			}
			List<Map.Entry<String, Fam>> l = new ArrayList<>(m.entrySet());
			l.sort((x, y) -> Integer.compare(y.getValue().count, x.getValue().count));
			Map<String, Fam> out = new LinkedHashMap<>();
			for (Map.Entry<String, Fam> e : l) {
				out.put(e.getKey(), e.getValue());
			}
			return out;
		}

		static String nodeKeyOfDiff(Diff d) {
			String p = d.path == null ? "" : d.path;
			if (p.startsWith("nodes[")) {
				return between(p, "key=", "]");
			}
			if (p.startsWith("interactive[")) {
				return between(p, "node=", "]");
			}
			return null;
		}

		// ------------------------------ 输出 ------------------------------

		void writeOutputs() throws IOException {
			if (o.outMd.getParent() != null) {
				Files.createDirectories(o.outMd.getParent());
			}
			if (o.outJson.getParent() != null) {
				Files.createDirectories(o.outJson.getParent());
			}
			Files.writeString(o.outMd, markdown(), StandardCharsets.UTF_8);
			Files.writeString(o.outJson, prettyJson(jsonModel()), StandardCharsets.UTF_8);
		}

		String markdown() {
			StringBuilder sb = new StringBuilder();
			sb.append("# 端口 ↔ 真机 GUI dump 差分报告\n\n");
			sb.append("| 项 | 值 |\n|---|---|\n");
			sb.append("| 工具 | ").append(code(TOOL)).append(" |\n");
			sb.append("| dump schema | ").append(code(SCHEMA)).append(" |\n");
			sb.append("| A 侧（").append(o.labelA).append("） | ").append(code(o.a.toAbsolutePath().toString()))
				.append(" — ").append(rawA).append(" 行 / 解析成功 ").append(parsedA).append(" / 去重后 ")
				.append(recsA.size()).append(" 条记录 / ").append(bytesA).append(" 字节 |\n");
			sb.append("| B 侧（").append(o.labelB).append("） | ").append(code(o.b.toAbsolutePath().toString()))
				.append(" — ").append(rawB).append(" 行 / 解析成功 ").append(parsedB).append(" / 去重后 ")
				.append(recsB.size()).append(" 条记录 / ").append(bytesB).append(" 字节 |\n");
			sb.append("| 记录配对键 | ").append(pairSpec.explicit()
				? code(String.join(",", o.pairBy)) + "（只用这些令牌；不降级猜测）"
				: "gui + 归一化 state 全签名（保留三级降级配对）").append(" |\n");
			sb.append("| 存储槽范围 | ").append(code(o.slotsScope)).append(" — ").append(scopeShort()).append(" |\n");
			sb.append("| 存储槽配对键 | ").append(slotKeyDesc()).append(" |\n");
			sb.append("| 位置回退配对阈值 | ").append(fmt3(o.posThreshold)).append(" × 面板对角线 |\n");
			sb.append("| 报告模式 | ").append(o.focus ? "focus（按节点 key 前缀族汇总 + 下钻）" : "按严重度平铺").append(" |\n");
			if (!o.ignoreKeys.isEmpty()) {
				sb.append("| 归一化忽略的 state 键 | ").append(String.join(", ", o.ignoreKeys)).append(" |\n");
			}
			if (!o.ignoreCats.isEmpty()) {
				sb.append("| 已忽略的类别 | ").append(joinCode(o.ignoreCats)).append(" |\n");
			}
			sb.append('\n');

			sb.append("## 1. 摘要\n\n");
			sb.append("差异总数 **").append(totalDiffs).append("**（已排除被忽略项）；")
				.append("记录配对 ").append(pairs.size()).append(" 对（低置信 ")
				.append(pairs.stream().filter(p -> p.lowConf).count()).append(" 对），")
				.append("A 侧未配对 ").append(unpairedA.size()).append(" 条，")
				.append("B 侧未配对 ").append(unpairedB.size()).append(" 条。");
			if (!ignoredCounts.isEmpty()) {
				sb.append("另有 **").append(sum(ignoredCounts)).append("** 条被 ").append(code("--ignore")).append(" 忽略。");
			}
			sb.append("\n\n");

			sb.append("### 1.1 按严重度\n\n");
			sb.append("| 严重度 | 数量 |\n|---|---:|\n");
			for (Sev s : Sev.values()) {
				int c = countsBySeverity.getOrDefault(sevName(s), 0);
				if (c > 0) {
					sb.append("| ").append(sevName(s)).append(" | ").append(c).append(" |\n");
				}
			}
			sb.append("| **合计** | **").append(totalDiffs).append("** |\n\n");

			Map<String, Fam> fams = familyAgg();
			sb.append("### 1.2 按族（节点 key 前缀）\n\n");
			sb.append("| 族 | 差异数 | critical | high | medium | low | info | 涉及节点数 | 记录数 |\n");
			sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
			for (Fam f : fams.values()) {
				sb.append("| ").append(code(f.name)).append(" | ").append(f.count).append(" | ")
					.append(f.bySev.getOrDefault("critical", 0)).append(" | ")
					.append(f.bySev.getOrDefault("high", 0)).append(" | ")
					.append(f.bySev.getOrDefault("medium", 0)).append(" | ")
					.append(f.bySev.getOrDefault("low", 0)).append(" | ")
					.append(f.bySev.getOrDefault("info", 0)).append(" | ")
					.append(f.keys.size()).append(" | ").append(f.records.size()).append(" |\n");
			}
			sb.append('\n');

			sb.append("### 1.3 按类别（按严重度排序，只列非零）\n\n");
			sb.append("| 严重度 | 类别 id | 说明 | 数量 |\n|---|---|---|---:|\n");
			for (Cat c : sortedCats()) {
				int n = byCat.getOrDefault(c.id, new ArrayList<>()).size();
				if (n == 0) {
					continue;
				}
				sb.append("| ").append(sevName(c.sev)).append(" | ").append(code(c.id)).append(" | ")
					.append(c.zh).append(" | ").append(n).append(" |\n");
			}
			sb.append('\n');

			sb.append("### 1.4 存储槽范围（").append(code("--slots-scope " + o.slotsScope)).append("）\n\n");
			sb.append(scopeExplain()).append("\n\n");
			sb.append("| 侧 | slots[] 总数 | 参与比较 | 被范围排除 |\n|---|---:|---:|---:|\n");
			for (String side : new String[]{"A", "B"}) {
				int[] st = slotScopeStats.getOrDefault(side, new int[2]);
				sb.append("| ").append(side).append(" | ").append(st[0]).append(" | ").append(st[1])
					.append(" | ").append(st[0] - st[1]).append(" |\n");
			}
			sb.append('\n');
			if (!slotDroppedA.isEmpty() || !slotDroppedB.isEmpty()) {
				sb.append("被范围排除的槽（按 (role, containerIndex) 聚合，每侧最多列 15 项）：\n\n");
				sb.append("| 侧 | role / containerIndex | 条数 |\n|---|---|---:|\n");
				appendDropped(sb, "A", slotDroppedA);
				appendDropped(sb, "B", slotDroppedB);
				sb.append('\n');
			}

			appendCoverage(sb);

			if (o.focus) {
				appendFocusDetail(sb, fams);
			} else {
				appendSeverityDetail(sb);
			}

			if (!o.ignoreCats.isEmpty()) {
				sb.append("## 4. 被 --ignore 忽略的类别（如实列出）\n\n");
				sb.append("你请求忽略 ").append(o.ignoreCats.size()).append(" 个类别，其中 ")
					.append(ignoredCounts.size()).append(" 个本次真的命中了差异：\n\n");
				sb.append("| 严重度 | 类别 id | 说明 | 被忽略条数 |\n|---|---|---|---:|\n");
				List<String> ids = new ArrayList<>(o.ignoreCats);
				ids.sort(Comparator.comparingInt((String id) -> sevRank(CATS.get(id).sev))
					.thenComparingInt(id -> -ignoredCounts.getOrDefault(id, 0)));
				for (String id : ids) {
					Cat c = CATS.get(id);
					int n = ignoredCounts.getOrDefault(id, 0);
					sb.append("| ").append(sevName(c.sev)).append(" | ").append(code(id)).append(" | ")
						.append(c.zh).append(" | ").append(n)
						.append(n == 0 ? "（本次无此类差异）" : "").append(" |\n");
				}
				sb.append("| **合计** | | | **").append(sum(ignoredCounts)).append("** |\n\n");
				sb.append("> 被忽略的条目**不计入**上面的总数，也不参与 ").append(code("--fail-on"))
					.append("。忽略是**按类别整类忽略**，不是逐条判断。\n\n");
			}

			appendBoundaries(sb);
			return sb.toString();
		}

		void appendDropped(StringBuilder sb, String side, Map<String, Integer> m) {
			List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
			l.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
			int n = 0;
			for (Map.Entry<String, Integer> e : l) {
				if (n >= 15) {
					sb.append("| ").append(side).append(" | …… 另有 ").append(l.size() - n).append(" 种 | |\n");
					break;
				}
				sb.append("| ").append(side).append(" | ").append(code(e.getKey())).append(" | ")
					.append(e.getValue()).append(" |\n");
				n++;
			}
		}

		void appendCoverage(StringBuilder sb) {
			sb.append("## 2. 记录配对与状态覆盖表\n\n");
			if (pairSpec.explicit()) {
				sb.append("配对键令牌：").append(code(String.join(",", o.pairBy)))
					.append("。显式指定配对键时**不做降级猜测** —— 配不上就是配不上，逐条列在下面。\n\n");
			} else {
				sb.append("配对键 = ").append(code("gui")).append(" + 归一化后的 ").append(code("state"))
					.append(" 签名；配不上时按三级降级（").append(code("exact")).append(" → ")
					.append(code("state-values-differ")).append(" → ").append(code("order-guess"))
					.append("）并逐级标注置信度。\n\n");
			}

			sb.append("### 2.1 状态覆盖表\n\n");
			sb.append("| # | 配对键 | A 记录 | B 记录 | 覆盖 | 配对级别 | 置信度 | A page | B page | A/B 节点数 | state 差异摘要 | 差异数 |\n");
			sb.append("|---:|---|---:|---:|---|---|---|---|---|---|---:|\n");
			int i = 0;
			for (Cov c : coverage.values()) {
				String kind = c.coverageKind();
				String cover = "paired".equals(kind) ? "两侧都有" : ("only-a".equals(kind) ? "**仅 A 侧**" : "**仅 B 侧**");
				String lvl = c.level;
				if (c.pairedWith != null) {
					lvl = c.level + "（与 " + mdCell(c.pairedWith) + " 配对）";
				}
				sb.append("| ").append(++i).append(" | ").append(code(mdCell(c.key.isEmpty() ? "(空)" : c.key)))
					.append(" | ").append(c.aCount).append(" | ").append(c.bCount).append(" | ").append(cover)
					.append(" | ").append(mdCell(lvl)).append(" | ")
					.append("paired".equals(kind) ? (c.lowConf ? "**低**" : "高") : "—")
					.append(" | ").append(mdCell(c.aPage)).append(" | ").append(mdCell(c.bPage))
					.append(" | ").append(c.aNodes).append("/").append(c.bNodes)
					.append(" | ").append(mdCell(c.stateDiff)).append(" | ")
					.append("paired".equals(kind) ? String.valueOf(c.diffCount) : "—").append(" |\n");
			}
			sb.append('\n');
			sb.append("读法：").append(code("A 记录")).append("/").append(code("B 记录"))
				.append(" = 该配对键在那一侧的记录条数（含被去重的重复条数）。一侧为 0 表示**这一侧有、另一侧完全没有这个状态**；")
				.append("两侧都 > 0 但置信度是低，说明配对是降级猜出来的，")
				.append(code("state 差异摘要")).append(" 列出它为什么不是精确配对。\n\n");

			sb.append("### 2.2 配对明细\n\n");
			sb.append("| # | 记录 | 配对级别 | 置信度 | A 行 | B 行 | 差异数 |\n|---:|---|---|---|---:|---:|---:|\n");
			int k = 0;
			for (Pair p : pairs) {
				sb.append("| ").append(++k).append(" | ").append(code(mdCell(p.label()))).append(" | ")
					.append(p.level).append(" | ").append(p.lowConf ? "**低**" : "高").append(" | ")
					.append(p.a == null ? "-" : String.valueOf(p.a.lineNo)).append(" | ")
					.append(p.b == null ? "-" : String.valueOf(p.b.lineNo)).append(" | ")
					.append(p.diffs.size()).append(" |\n");
			}
			sb.append('\n');
			if (!pairs.isEmpty()) {
				sb.append("降级配对的原因（按级别去重）：\n\n");
				Set<String> seen = new LinkedHashSet<>();
				for (Pair p : pairs) {
					if (p.lowConf && seen.add(p.level)) {
						sb.append("- ").append(code(p.level)).append("：").append(p.reason).append('\n');
					}
				}
				sb.append('\n');
			}
			if (!unpairedA.isEmpty() || !unpairedB.isEmpty()) {
				sb.append("### 2.3 未配对的记录\n\n");
				sb.append("| 侧 | 行 | 配对键 | full state | sem.page | 节点数 |\n|---|---:|---|---|---|---:|\n");
				appendUnpaired(sb, "A", unpairedA);
				appendUnpaired(sb, "B", unpairedB);
				sb.append('\n');
			}
		}

		void appendUnpaired(StringBuilder sb, String side, List<Rec> recs) {
			for (Rec r : recs) {
				sb.append("| ").append(side).append(" | ").append(r.lineNo).append(" | ")
					.append(code(mdCell(r.pairKey))).append(" | ").append(code(mdCell(r.stateSig)))
					.append(" | ").append(mdCell(r.pageToken)).append(" | ").append(r.nodes.size()).append(" |\n");
			}
		}

		void appendSeverityDetail(StringBuilder sb) {
			sb.append("## 3. 差异明细（按严重度排序）\n\n");
			sb.append("每类给最多 ").append(o.examples).append(" 个典型例子；完整清单见 JSON。（想按族看请加 ")
				.append(code("--focus")).append("）\n\n");
			boolean any = false;
			for (Cat c : sortedCats()) {
				List<Diff> list = byCat.get(c.id);
				if (list == null || list.isEmpty()) {
					continue;
				}
				any = true;
				sb.append("### [").append(sevName(c.sev)).append("] ").append(code(c.id)).append(" — ")
					.append(c.zh).append("（").append(list.size()).append(" 处）\n\n");
				sb.append("| 记录 | 族 | 路径 | A | B | 备注 |\n|---|---|---|---|---|---|\n");
				int shown = 0;
				for (Diff d : list) {
					if (shown >= o.examples) {
						break;
					}
					shown++;
					sb.append("| ").append(code(mdCell(d.record))).append(" | ")
						.append(code(d.family == null ? familyOf(d) : d.family)).append(" | ")
						.append(code(mdCell(d.path))).append(" | ").append(mdCell(d.aVal))
						.append(" | ").append(mdCell(d.bVal)).append(" | ")
						.append(mdCell(diffNote(d))).append(" |\n");
				}
				if (list.size() > shown) {
					sb.append("\n> 另有 ").append(list.size() - shown).append(" 处同类差异未展开，见 JSON。\n");
				}
				sb.append('\n');
			}
			if (!any) {
				sb.append("（未发现任何差异）\n\n");
			}
		}

		String diffNote(Diff d) {
			String note = d.note == null ? "" : d.note;
			if (d.lowConf && !note.contains("不确定")) {
				note = note.isEmpty() ? "低置信配对" : note + "；低置信配对";
			}
			return note;
		}

		void appendFocusDetail(StringBuilder sb, Map<String, Fam> fams) {
			sb.append("## 3. 差异明细（--focus：按节点 key 前缀族汇总）\n\n");
			sb.append("族定义：key 里有 ").append(code(":")).append(" 就取到 ").append(code(":"))
				.append("（").append(code("frame:")).append(" / ").append(code("grow:"))
				.append(" / ").append(code("attack_damage:")).append("…）；没有 ").append(code(":"))
				.append(" 的取首个 ").append(code("/")).append(" 之前并写成 ").append(code("(structural x)"))
				.append("；另有 ").append(code("(slots)")).append(" / ").append(code("(state)"))
				.append(" / ").append(code("(line)")).append(" 三个元族。\n\n");
			if (fams.isEmpty()) {
				sb.append("（未发现任何差异）\n\n");
				return;
			}
			sb.append("### 3.1 族汇总（按差异数降序）\n\n");
			sb.append("| 族 | 差异数 | 主要类别（前 4） | 涉及节点数 | 记录数 |\n|---|---:|---|---:|---:|\n");
			for (Fam f : fams.values()) {
				sb.append("| ").append(code(f.name)).append(" | ").append(f.count).append(" | ")
					.append(mdCell(topCats(f))).append(" | ").append(f.keys.size()).append(" | ")
					.append(f.records.size()).append(" |\n");
			}
			sb.append('\n');

			sb.append("### 3.2 族下钻（每条差距追到具体节点：key + 两侧 rect/text/action 对照）\n\n");
			int famShown = 0;
			int famLimit = Math.max(o.examples, 5);
			for (Fam f : fams.values()) {
				if (famShown >= famLimit) {
					sb.append("> 其余 ").append(fams.size() - famShown).append(" 个族未展开，完整清单见 JSON 的 ")
						.append(code("families")).append(" 与 ").append(code("diffs")).append("。\n\n");
					break;
				}
				famShown++;
				sb.append("#### 族 ").append(code(f.name)).append("（").append(f.count).append(" 处）\n\n");
				sb.append("| 节点 key | A 侧（rect / text / action） | B 侧（rect / text / action） | 差异（类别：A → B） |\n");
				sb.append("|---|---|---|---|\n");
				int rowShown = 0;
				Set<String> done = new LinkedHashSet<>();
				for (Pair p : pairs) {
					for (Diff d : p.diffs) {
						// 下钻也必须尊重 --ignore，否则会和上面的族汇总自相矛盾
						if (o.ignoreCats.contains(d.cat)) {
							continue;
						}
						String df = d.family == null ? familyOf(d) : d.family;
						if (!f.name.equals(df)) {
							continue;
						}
						String nk = nodeKeyOfDiff(d);
						String rowKey = p.label() + "\u0001" + (nk == null ? d.path : nk);
						if (!done.add(rowKey)) {
							continue;
						}
						if (rowShown >= famLimit) {
							break;
						}
						rowShown++;
						String aDesc = nk == null ? "—" : orDash(nodeDesc(p.a, nk));
						String bDesc = nk == null ? "—" : orDash(nodeDesc(p.b, nk));
						sb.append("| ").append(code(mdCell(nk == null ? d.path : nk))).append(" | ")
							.append(mdCell(aDesc)).append(" | ").append(mdCell(bDesc)).append(" | ")
							.append(code(d.cat)).append("：").append(mdCell(d.aVal)).append(" → ")
							.append(mdCell(d.bVal)).append("<br>记录 ").append(code(mdCell(d.record)))
							.append(" |\n");
					}
				}
				if (rowShown == 0) {
					sb.append("| — | — | — | 该族没有可下钻到节点的差异（例如 ").append(code(FAM_SLOTS))
						.append(" / ").append(code(FAM_STATE)).append(" / ").append(code(FAM_LINE))
						.append("） |\n");
				} else if (f.keys.size() > rowShown) {
					sb.append("| …… | 另有 ").append(f.keys.size() - rowShown)
						.append(" 个节点未展开 | | 见 JSON |\n");
				}
				sb.append('\n');
			}
		}

		String orDash(String s) {
			return s == null ? "—（该侧无此 key）" : s;
		}

		String topCats(Fam f) {
			List<Map.Entry<String, Integer>> l = new ArrayList<>(f.byCat.entrySet());
			l.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
			StringBuilder sb = new StringBuilder();
			int n = 0;
			for (Map.Entry<String, Integer> e : l) {
				if (n >= 4) {
					sb.append("…");
					break;
				}
				if (n > 0) {
					sb.append(", ");
				}
				sb.append(e.getKey()).append("×").append(e.getValue());
				n++;
			}
			return sb.toString();
		}

		String nodeDesc(Rec rec, String key) {
			if (rec == null) {
				return null;
			}
			for (Object on : rec.nodes) {
				Map<String, Object> n = asObj(on);
				String k = asStr(n.get("key"));
				if (key == null ? k == null : key.equals(k)) {
					return briefNode(n);
				}
			}
			return null;
		}

		String scopeShort() {
			if ("real-all".equals(o.slotsScope)) {
				return "全部槽，按 i 配对";
			}
			if ("storage-only".equals(o.slotsScope)) {
				return "role 化槽且 containerIndex < " + o.slotsContainerMax;
			}
			return "role 化槽，按 (role, containerIndex) 配对";
		}

		String slotKeyDesc() {
			return "real-all".equals(o.slotsScope)
				? code("i") + "（数组下标）"
				: code("(sem.role, containerIndex)");
		}

		String scopeExplain() {
			SlotScope sc = new SlotScope(o.slotsScope, o.slotsContainerMax);
			StringBuilder sb = new StringBuilder();
			sb.append("生效范围：").append(sc.describe()).append("。\n\n");
			if ("storage-only".equals(o.slotsScope)) {
				sb.append("> ").append(code("--slots-container-max")).append(" = **").append(o.slotsContainerMax)
					.append("**").append(o.slotsContainerMaxGiven ? "（显式给出）" : "（默认值）")
					.append("。需要这个上界的原因：实测真机探针把玩家背包槽也算进 ").append(code("slots[]"))
					.append("，并且**同样标成 ").append(code("sem.role=material"))
					.append("**，只有 ").append(code("containerIndex")).append(" 能区分（容器自有槽 0..3、背包槽 9..44）。")
					.append("所以单纯按 ").append(code("role")).append(" 过滤排除不掉背包槽。\n\n");
			} else if ("role".equals(o.slotsScope)) {
				sb.append("> 注意：").append(code("role")).append(" 只按 ").append(code("sem.role"))
					.append(" 过滤。若真机侧把背包槽也标成 ").append(code("role=material"))
					.append("，它们**仍会被比较**（表现为成片 ").append(code("slot_missing"))
					.append("）。要真正排除请用 ").append(code("--slots-scope storage-only"))
					.append("（配合 ").append(code("--slots-container-max")).append("）。\n\n");
			}
			return sb.toString();
		}

		void appendBoundaries(StringBuilder sb) {
			sb.append("## ").append(o.ignoreCats.isEmpty() ? "4" : "5")
				.append(". 本工具的边界与未做项\n\n");
			sb.append("- **只比声明值，不比逐帧动画**：dump 里是声明值，不是被 ").append(code("ChasmGuiAnimations"))
				.append(" 平滑到的那一时刻的值。\n");
			sb.append("- **不判像素**：不做图像比对，只比 dump 字段。\n");
			sb.append("- **忽略 ").append(code("seq/ts/hash/mode")).append("**；")
				.append(code("stateChanged/delta")).append(" 是同一侧相邻帧的变化标记，两侧帧序列不同，互比无意义。\n");
			sb.append("- **handler 语义天然偏置**：真机侧没有框架 handler 注册表，")
				.append(code("handler/handlerRegistered")).append(" 只能「有就好」，归 medium。\n");
			sb.append("- **state 粒度差异**：两侧键名集合/维度不同会让配对降级（用 ").append(code("--pair-by"))
				.append(" 指定配对键，或用 ").append(code("--state-ignore")).append(" 排除干扰键）。\n");
			sb.append("- **--ignore 是按类别整类忽略**，不是逐条判断；被忽略条数单独列出、不计入总数、不参与 ")
				.append(code("--fail-on")).append("。\n");
			sb.append("- **配对键碰撞不会被掩盖**：显式 ").append(code("--pair-by"))
				.append(" 时若多条记录落到同一键，会报 ").append(code("pair_key_collision"))
				.append("，并在状态覆盖表的 A/B 记录数上体现（>1）。\n");
		}

		String joinCode(Iterable<String> ids) {
			StringBuilder sb = new StringBuilder();
			for (String s : ids) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				sb.append(code(s));
			}
			return sb.toString();
		}

		static int sum(Map<String, Integer> m) {
			int t = 0;
			for (int v : m.values()) {
				t += v;
			}
			return t;
		}

		List<Cat> sortedCats() {
			List<Cat> l = new ArrayList<>(CATS.values());
			l.sort(Comparator.comparingInt((Cat c) -> sevRank(c.sev))
				.thenComparingInt(c -> -byCat.getOrDefault(c.id, new ArrayList<>()).size()));
			return l;
		}

		static String mdCell(String s) {
			if (s == null) {
				return "";
			}
			return s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ");
		}

		// ---- JSON ----

		Map<String, Object> jsonModel() {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("tool", TOOL);
			root.put("schema", SCHEMA);
			root.put("generatedAt", java.time.Instant.now().toString());

			Map<String, Object> inputs = new LinkedHashMap<>();
			inputs.put("a", inputModel(o.a, o.labelA, rawA, parsedA, recsA.size(), bytesA));
			inputs.put("b", inputModel(o.b, o.labelB, rawB, parsedB, recsB.size(), bytesB));
			root.put("inputs", inputs);

			Map<String, Object> options = new LinkedHashMap<>();
			options.put("examples", o.examples);
			options.put("posThreshold", o.posThreshold);
			options.put("stateIgnore", new ArrayList<>(o.ignoreKeys));
			options.put("ignore", new ArrayList<>(o.ignoreCats));
			options.put("pairBy", o.pairBy == null ? null : String.join(",", o.pairBy));
			options.put("pairByExplicit", pairSpec.explicit());
			options.put("slotsScope", o.slotsScope);
			options.put("slotsContainerMax", o.slotsContainerMax);
			options.put("slotsPairKey", "real-all".equals(o.slotsScope) ? "i" : "(role,containerIndex)");
			options.put("focus", o.focus);
			options.put("failOn", o.failOn == null ? "none" : sevName(o.failOn));
			root.put("options", options);

			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("recordsA", recsA.size());
			summary.put("recordsB", recsB.size());
			summary.put("rawLinesA", rawA);
			summary.put("rawLinesB", rawB);
			summary.put("parsedLinesA", parsedA);
			summary.put("parsedLinesB", parsedB);
			summary.put("pairs", pairs.size());
			Map<String, Object> byLevel = new LinkedHashMap<>();
			for (Pair p : pairs) {
				byLevel.merge(p.level, 1, (x, y) -> (Integer) x + 1);
			}
			summary.put("pairsByLevel", byLevel);
			summary.put("lowConfidencePairs", (int) pairs.stream().filter(p -> p.lowConf).count());
			summary.put("unpairedA", unpairedA.size());
			summary.put("unpairedB", unpairedB.size());
			summary.put("diffTotal", totalDiffs);
			summary.put("ignoredTotal", sum(ignoredCounts));
			summary.put("countsBySeverity", nonZeroBySeverity());
			Map<String, Object> cbc = new LinkedHashMap<>();
			for (Cat c : sortedCats()) {
				List<Diff> l = byCat.get(c.id);
				if (l != null && !l.isEmpty()) {
					cbc.put(c.id, l.size());
				}
			}
			summary.put("countsByCategory", cbc);
			summary.put("ignoredByCategory", ignoredCounts);
			root.put("summary", summary);

			root.put("categoryCatalog", categoryCatalog());

			List<Object> famList = new ArrayList<>();
			for (Fam f : familyAgg().values()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("family", f.name);
				m.put("count", f.count);
				m.put("bySeverity", f.bySev);
				m.put("byCategory", f.byCat);
				m.put("nodeKeys", new ArrayList<>(f.keys));
				m.put("recordCount", f.records.size());
				famList.add(m);
			}
			root.put("families", famList);

			List<Object> covList = new ArrayList<>();
			for (Cov c : coverage.values()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("pairKey", c.key);
				m.put("aRecords", c.aCount);
				m.put("bRecords", c.bCount);
				m.put("coverage", c.coverageKind());
				m.put("pairedWith", c.pairedWith);
				m.put("pairing", c.level);
				m.put("pairingReason", c.reason);
				m.put("pairingConfidence", "paired".equals(c.coverageKind()) ? (c.lowConf ? "low" : "high") : null);
				m.put("aPage", c.aPage);
				m.put("bPage", c.bPage);
				m.put("aNodes", c.aNodes);
				m.put("bNodes", c.bNodes);
				m.put("stateDiffSummary", c.stateDiff);
				m.put("diffTotal", "paired".equals(c.coverageKind()) ? c.diffCount : null);
				covList.add(m);
			}
			root.put("stateCoverage", covList);

			Map<String, Object> slotsScope = new LinkedHashMap<>();
			slotsScope.put("mode", o.slotsScope);
			slotsScope.put("containerMax", o.slotsContainerMax);
			Map<String, Object> perSide = new LinkedHashMap<>();
			for (String side : new String[]{"A", "B"}) {
				int[] st = slotScopeStats.getOrDefault(side, new int[2]);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("total", st[0]);
				m.put("kept", st[1]);
				m.put("dropped", st[0] - st[1]);
				m.put("droppedByRoleContainer", "A".equals(side) ? slotDroppedA : slotDroppedB);
				perSide.put(side, m);
			}
			slotsScope.put("perSide", perSide);
			root.put("slotsScope", slotsScope);

			List<Object> recs = new ArrayList<>();
			for (Pair p : pairs) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("record", p.label());
				m.put("pairKey", p.key());
				m.put("groupKey", p.groupKey().replace('\u0001', '|'));
				m.put("gui", p.a != null ? p.a.gui : p.b.gui);
				m.put("stateSig", p.a != null ? p.a.stateSig : p.b.stateSig);
				m.put("pageA", p.a == null ? null : p.a.pageToken);
				m.put("pageB", p.b == null ? null : p.b.pageToken);
				m.put("pairing", p.level);
				m.put("pairingConfidence", p.lowConf ? "low" : "high");
				m.put("pairingReason", p.reason);
				m.put("aLine", p.a == null ? null : p.a.lineNo);
				m.put("bLine", p.b == null ? null : p.b.lineNo);
				m.put("nodesA", p.a == null ? 0 : p.a.nodes.size());
				m.put("nodesB", p.b == null ? 0 : p.b.nodes.size());
				m.put("slotsA", p.a == null ? 0 : p.a.slots.size());
				m.put("slotsB", p.b == null ? 0 : p.b.slots.size());
				m.put("interactiveA", p.a == null ? 0 : p.a.interactive.size());
				m.put("interactiveB", p.b == null ? 0 : p.b.interactive.size());
				m.put("diffTotal", p.diffs.size());
				Map<String, Object> perCat = new LinkedHashMap<>();
				for (Diff d : p.diffs) {
					perCat.merge(d.cat, 1, (x, y) -> (Integer) x + 1);
				}
				m.put("diffsByCategory", perCat);
				recs.add(m);
			}
			root.put("records", recs);

			Map<String, Object> unpaired = new LinkedHashMap<>();
			unpaired.put("a", recSummary(unpairedA));
			unpaired.put("b", recSummary(unpairedB));
			root.put("unpairedRecords", unpaired);

			List<Object> pel = new ArrayList<>();
			for (String e : parseErrors) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("error", e);
				pel.add(m);
			}
			root.put("parseErrors", pel);

			List<Object> diffs = new ArrayList<>();
			for (Cat c : sortedCats()) {
				List<Diff> l = byCat.get(c.id);
				if (l == null) {
					continue;
				}
				for (Diff d : l) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("category", d.cat);
					m.put("severity", sevName(c.sev));
					m.put("family", d.family == null ? familyOf(d) : d.family);
					m.put("nodeKey", nodeKeyOfDiff(d));
					m.put("title", c.zh);
					m.put("record", d.record);
					m.put("pairing", d.pairing);
					m.put("aLine", d.aLine == 0 ? null : d.aLine);
					m.put("bLine", d.bLine == 0 ? null : d.bLine);
					m.put("aKey", d.aKey);
					m.put("bKey", d.bKey);
					m.put("path", d.path);
					m.put("aValue", d.aVal);
					m.put("bValue", d.bVal);
					m.put("note", d.note);
					m.put("lowConfidence", d.lowConf);
					diffs.add(m);
				}
			}
			root.put("diffs", diffs);
			return root;
		}

		List<Object> categoryCatalog() {
			List<Object> out = new ArrayList<>();
			for (Cat c : sortedCats()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", c.id);
				m.put("severity", sevName(c.sev));
				m.put("family", c.family);
				m.put("title", c.zh);
				m.put("count", byCat.getOrDefault(c.id, new ArrayList<>()).size());
				m.put("ignoredCount", ignoredCounts.getOrDefault(c.id, 0));
				out.add(m);
			}
			return out;
		}

		Map<String, Object> inputModel(Path p, String label, int rawLines, int parsedLines, int records, long bytes) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("path", p.toAbsolutePath().toString());
			m.put("label", label);
			m.put("rawLines", rawLines);
			m.put("parsedLines", parsedLines);
			m.put("recordsAfterDedup", records);
			m.put("bytes", bytes);
			return m;
		}

		List<Object> recSummary(List<Rec> recs) {
			List<Object> out = new ArrayList<>();
			for (Rec r : recs) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("line", r.lineNo);
				m.put("gui", r.gui);
				m.put("pairKey", r.pairKey);
				m.put("stateSig", r.stateSig);
				m.put("page", r.pageToken);
				m.put("nodes", r.nodes.size());
				out.add(m);
			}
			return out;
		}

		Map<String, Object> nonZeroBySeverity() {
			Map<String, Object> m = new LinkedHashMap<>();
			for (Sev s : Sev.values()) {
				int c = countsBySeverity.getOrDefault(sevName(s), 0);
				if (c > 0) {
					m.put(sevName(s), c);
				}
			}
			return m;
		}

		void printSummary() {
			System.out.println("probe-diff: " + o.a.getFileName() + " (" + rawA + " 行 → " + recsA.size()
				+ " 条) vs " + o.b.getFileName() + " (" + rawB + " 行 → " + recsB.size() + " 条)");
			System.out.println("配对键: " + (pairSpec.explicit()
				? String.join(",", o.pairBy) + "（不降级猜测）" : "gui+state全签名（三级降级）"));
			int[] sa = slotScopeStats.getOrDefault("A", new int[2]);
			int[] sbb = slotScopeStats.getOrDefault("B", new int[2]);
			System.out.println("存储槽: scope=" + o.slotsScope
				+ " 配对键=" + ("real-all".equals(o.slotsScope) ? "i" : "(role,ci)")
				+ "  A " + sa[1] + "/" + sa[0] + "  B " + sbb[1] + "/" + sbb[0] + "（保留/总数）");
			System.out.println("配对 " + pairs.size() + " 对（低置信 "
				+ pairs.stream().filter(p -> p.lowConf).count() + " 对）；未配对 A=" + unpairedA.size()
				+ " B=" + unpairedB.size());
			System.out.println("差异总数 " + totalDiffs);
			for (Sev s : Sev.values()) {
				int c = countsBySeverity.getOrDefault(sevName(s), 0);
				if (c > 0) {
					System.out.println("  " + sevName(s) + ": " + c);
				}
			}
			Map<String, Fam> fams = familyAgg();
			if (!fams.isEmpty()) {
				System.out.println("按族（前 10）：");
				int n = 0;
				for (Fam f : fams.values()) {
					if (n++ >= 10) {
						break;
					}
					System.out.println("  " + padRight(f.name, 24) + f.count);
				}
			}
			if (!ignoredCounts.isEmpty()) {
				System.out.println("被 --ignore 忽略 " + sum(ignoredCounts) + " 条：");
				for (Map.Entry<String, Integer> e : ignoredCounts.entrySet()) {
					System.out.println("  " + padRight(e.getKey(), 24) + e.getValue());
				}
			}
			if (!parseErrors.isEmpty()) {
				System.out.println("解析错误 " + parseErrors.size() + " 条：");
				for (String e : parseErrors) {
					System.out.println("  " + e);
				}
			}
			System.out.println("报告已写出：");
			System.out.println("  " + o.outMd.toAbsolutePath());
			System.out.println("  " + o.outJson.toAbsolutePath());
		}
	}
}
