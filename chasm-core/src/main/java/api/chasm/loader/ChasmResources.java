package api.chasm.loader;

import api.chasm.log.ChasmLogger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Chasm JSON 软编码加载器的 classpath 资源扫描器。
 *
 * <p>负责从 classpath（开发环境的 `build/resources/main` 目录，或打包后的模组
 * {@code .jar}）中枚举满足 {@code data/<命名空间>/chasm/<kind>/<文件名>.json}
 * 形式的所有资源，其中 {@code kind} ∈ {items, gui}。</p>
 *
 * <p>两种 classpath 根都被支持：</p>
 * <ul>
 *   <li><b>file（目录）</b>：用 {@link Files#walk} 递归遍历，按相对路径过滤；</li>
 *   <li><b>jar（{@code jar:file:...!/}）</b>：用 {@link ZipFile} 遍历条目过滤。</li>
 * </ul>
 *
 * <p>扫描对单个根读取失败保持静默（不中断），并按键值去重。</p>
 */
public final class ChasmResources {

	/**
	 * 一个已发现资源：命名空间、kind、文件名（含子路径、不含 .json）、数据流。
	 *
	 * <p>数据流由调用方负责在使用完毕后关闭（jar 场景内部已拷贝为内存流，
	 * 保证返回后仍可安全读取）。</p>
	 */
	public record ChasmResource(String namespace, String kind, String name, InputStream stream) {

		/** 便捷关闭数据流（吞异常，安全可重复调用）。 */
		public void close() {
			try {
				stream.close();
			} catch (Exception ignored) {
				// 关闭失败静默
			}
		}
	}

	private ChasmResources() {
	}

	/**
	 * 扫描 classpath，返回满足 {@code data/<ns>/chasm/{kind}/*.json} 的全部资源。
	 *
	 * @param kind "items" 或 "gui"
	 * @return 按相对路径去重、按发现顺序排列的资源列表
	 */
	public static List<ChasmResource> find(String kind) {
		ClassLoader cl = ChasmResources.class.getClassLoader();
		// 相对路径（如 data/ns/chasm/items/foo.json）→ 资源，用于去重与保序
		Map<String, ChasmResource> found = new LinkedHashMap<>();

		for (String rootStr : collectRoots(cl)) {
			try {
				URL url = new URL(rootStr);
				String protocol = url.getProtocol();
				if ("file".equals(protocol)) {
					scanDirectory(url, kind, found);
				} else if ("jar".equals(protocol) || "zip".equals(protocol)) {
					scanJar(url, kind, found);
				}
			} catch (Exception e) {
				ChasmLogger.warn("chasm", "跳过 classpath 根 {}", rootStr);
			}
		}
		return new ArrayList<>(found.values());
	}

	/**
	 * 收集候选 classpath 根：优先拿 {@link URLClassLoader} 的全部 URL；
	 * 并补充 {@code ClassLoader.getResources("data")} 枚举到的 {@code data} 目录根
	 * （覆盖非 URLClassLoader 的类加载器在目录/jar 混布场景）。
	 */
	private static Set<String> collectRoots(ClassLoader cl) {
		Set<String> roots = new LinkedHashSet<>();
		if (cl instanceof URLClassLoader urlCl) {
			for (URL u : urlCl.getURLs()) {
				roots.add(u.toString());
			}
		}
		try {
			Enumeration<URL> dataRoots = cl.getResources("data");
			while (dataRoots.hasMoreElements()) {
				roots.add(dataRoots.nextElement().toString());
			}
		} catch (Exception e) {
			ChasmLogger.warn("chasm", "枚举 data 根失败: {}", String.valueOf(e));
		}
		return roots;
	}

	/** 扫描一个 file（目录）根：递归遍历目录，过滤并收集合法 JSON 资源。 */
	private static void scanDirectory(URL url, String kind, Map<String, ChasmResource> found) throws Exception {
		Path dir = Path.of(url.toURI());
		if (!Files.isDirectory(dir)) {
			return;
		}
		// 扫描根本身就是 data 目录（getResources("data") 补充的根）时，相对路径需补回 data/ 前缀，
		// 否则 accept/namespaceAndName 的 data/ 前缀规则会把它们全部过滤掉
		boolean dataRoot = dir.getFileName() != null && "data".equals(dir.getFileName().toString());
		try (var walk = Files.walk(dir)) {
			walk.forEach(p -> {
				if (!Files.isRegularFile(p)) {
					return;
				}
				String raw = dir.relativize(p).toString().replace('\\', '/');
				String rel = dataRoot ? "data/" + raw : raw;
				if (!accept(rel, kind)) {
					return;
				}
				String key = rel;
				if (found.containsKey(key)) {
					return;
				}
				String[] nsn = namespaceAndName(rel, kind);
				if (nsn == null) {
					return;
				}
				try {
					// 读入内存流再返回，避免文件句柄泄漏（Files.readAllBytes 读取后自行关闭文件）
					found.put(key, new ChasmResource(nsn[0], kind, nsn[1],
						new ByteArrayInputStream(Files.readAllBytes(p))));
				} catch (Exception e) {
					ChasmLogger.warn("chasm", "读取 JSON 资源 {} 失败: {}", rel, String.valueOf(e));
				}
			});
		}
	}

	/** 扫描一个 jar 根：遍历 zip 条目，过滤并收集合法 JSON 资源（读入内存流以防 ZipFile 提前关闭）。 */
	private static void scanJar(URL url, String kind, Map<String, ChasmResource> found) throws Exception {
		String s = url.toString();
		int marker = s.indexOf("!/");
		if (marker < 0) {
			return;
		}
		String base = s.substring(0, marker);
		if (base.startsWith("jar:")) {
			base = base.substring("jar:".length());
		}
		Path jarPath = Path.of(new URL(base).toURI());
		if (!Files.isRegularFile(jarPath)) {
			return;
		}
		try (ZipFile zip = new ZipFile(jarPath.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				String rel = entry.getName().replace('\\', '/');
				if (!accept(rel, kind)) {
					continue;
				}
				String key = rel;
				if (found.containsKey(key)) {
					continue;
				}
				String[] nsn = namespaceAndName(rel, kind);
				if (nsn == null) {
					continue;
				}
				try (InputStream in = zip.getInputStream(entry)) {
					found.put(key, new ChasmResource(nsn[0], kind, nsn[1],
						new java.io.ByteArrayInputStream(in.readAllBytes())));
				}
			}
		}
	}

	/** 判断相对路径是否属于指定 kind 的合法 JSON 资源：以 data/ 开头、含 /chasm/<kind>/、以 .json 结尾。 */
	private static boolean accept(String rel, String kind) {
		return rel.startsWith("data/")
			&& rel.contains("/chasm/" + kind + "/")
			&& rel.endsWith(".json");
	}

	/**
	 * 从相对路径 {@code data/<ns>/chasm/<kind>/<file>.json} 解析出命名空间与文件名（含子路径、不含 .json）。
	 *
	 * @return 长度 2 的数组 [namespace, name]；结构不匹配返回 null
	 */
	private static String[] namespaceAndName(String rel, String kind) {
		String after = rel.substring("data/".length()); // <ns>/chasm/<kind>/<file>.json
		int slash = after.indexOf('/');
		if (slash < 0) {
			return null;
		}
		String ns = after.substring(0, slash);
		String rest = after.substring(slash + 1); // chasm/<kind>/<file>.json
		if (!rest.startsWith("chasm/")) {
			return null;
		}
		String tail = rest.substring("chasm/".length()); // <kind>/<file>.json
		int slash2 = tail.indexOf('/');
		if (slash2 < 0) {
			return null;
		}
		String k = tail.substring(0, slash2);
		if (!k.equals(kind)) {
			return null;
		}
		String file = tail.substring(slash2 + 1); // <file>.json
		String name = file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : file;
		return new String[] { ns, name };
	}
}