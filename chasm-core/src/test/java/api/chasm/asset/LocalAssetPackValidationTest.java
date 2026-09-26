package api.chasm.asset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **素材搬运自检**（常驻测试，不是一次性工具）：扫描本地测试素材包，抓两类经典事故：
 *
 * <ol>
 *   <li><b>模型引用了不存在的贴图</b> → 游戏里紫黑块（模型指过去了、图没带过来）；</li>
 *   <li><b>多帧条纹贴图缺少 .mcmeta 动画元数据</b> → 动画贴图被当成一张大图 → 拉伸/错乱
 *       （神化宝石就是 16×304 这类竖排帧，实际踩过）。</li>
 * </ol>
 *
 * <p>本地包不存在时自动跳过（别人 clone 仓库后没有 run/ 目录）。</p>
 */
class LocalAssetPackValidationTest {

	private static final Path PACKS = Path.of("..", "chasm-example", "run", "resourcepacks");

	@Test
	void localAssetPackHasNoBrokenReferencesOrMissingAnimationMeta() throws Exception {
		if (!Files.isDirectory(PACKS)) {
			System.out.println("ASSETCHECK skipped (no local pack dir)");
			return;
		}
		List<String> problems = new ArrayList<>();
		int models = 0;
		int animated = 0;
		try (Stream<Path> packs = Files.list(PACKS)) {
			for (Path pack : packs.filter(Files::isDirectory).toList()) {
				Path assets = pack.resolve("assets");
				if (!Files.isDirectory(assets)) {
					continue;
				}
				// ① 模型引用的外部素材贴图必须同包存在
				try (Stream<Path> all = Files.walk(assets)) {
					for (Path json : all.filter(x -> x.toString().endsWith(".json"))
						.filter(x -> x.toString().contains("models")).toList()) {
						models++;
						JsonObject root = JsonParser.parseString(
							Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
						if (!root.has("textures")) {
							continue;
						}
						for (String key : root.getAsJsonObject("textures").keySet()) {
							String ref = root.getAsJsonObject("textures").get(key).getAsString();
							int colon = ref.indexOf(58);
							String ns = colon < 0 ? "minecraft" : ref.substring(0, colon);
							String path = colon < 0 ? ref : ref.substring(colon + 1);
							if (ns.equals("minecraft")) {
								continue;
							}
							Path texture = pack.resolve("assets/" + ns + "/textures/" + path + ".png");
							if (!Files.isRegularFile(texture)) {
								problems.add(pack.getFileName() + " / " + json.getFileName()
									+ " 引用 " + ref + " 但包内缺 " + texture);
							}
						}
					}
				}
				// ② 多帧条纹贴图必须带 .mcmeta
				try (Stream<Path> all = Files.walk(assets)) {
					for (Path png : all.filter(x -> x.toString().endsWith(".png")).toList()) {
						int w = dimension(png, 16);
						int h = dimension(png, 20);
						if (w > 0 && h > w && h % w == 0) {
							animated++;
							if (!Files.isRegularFile(png.resolveSibling(png.getFileName() + ".mcmeta"))) {
								problems.add(pack.getFileName() + " / " + png.getFileName() + " 是 "
									+ w + "x" + h + " 多帧条纹但缺 .mcmeta（会拉伸错乱）");
							}
						}
					}
				}
			}
		}
		System.out.println("ASSETCHECK 模型 " + models + " 个 / 动画贴图 " + animated
			+ " 张 / 问题 " + problems.size() + " 个");
		assertTrue(problems.isEmpty(), "素材包自检失败:\n" + String.join("\n", problems));
	}

	/** 直接读 PNG 的 IHDR 宽/高（offset 16 = 宽，20 = 高），不依赖 AWT。 */
	private static int dimension(Path png, int offset) {
		try {
			byte[] bytes = Files.readAllBytes(png);
			if (bytes.length < 24) {
				return 0;
			}
			return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
				| ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
		} catch (Exception e) {
			return 0;
		}
	}
}
