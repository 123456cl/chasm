package api.chasm.safety;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **javadoc 里不许出现会提前闭合注释的字符序列**（真实事故的回归测试）。
 *
 * <p>2026-09-18 连续两次构建被同一个坑打断：注释里写正则/通配（例如
 * {@code ".*&#47;" + material} 或 {@code *&#47;iron}）时，中间那个"星号+斜杠"会被 javac 当成
 * **注释结束符**，后面的文字立刻变成代码 —— 报错却是"非法字符 / 未结束的字符串文字 / 需要标识符"，
 * 指向的行号还在注释中间，非常难一眼看出。</p>
 *
 * <p>正确写法：把斜杠写成 HTML 实体（渲染出来仍是斜杠），或者避开"星号紧跟斜杠"。</p>
 *
 * <p>本测试扫描仓库里全部 {@code src/main/java/**&#47;*.java}：<b>以 * 开头的续行里不得含"星号+斜杠"</b>
 * （含则说明该注释会在那里提前结束）。</p>
 */
class JavadocTerminatorLintTest {

	/** 会被 javac 当作注释结束符的两字符序列（分开写，避免本文件自己触发扫描规则）。 */
	private static final String TERMINATOR = "*" + "/";

	@Test
	void noJavadocLineClosesTheCommentEarly() throws IOException {
		Path root = repositoryRoot();
		List<String> offenders = new ArrayList<>();

		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String path = file.toString().replace('\\', '/');
				if (!path.endsWith(".java") || !path.contains("/src/main/java/") || path.contains("/build/")) {
					continue;
				}
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i).trim();
					if (!line.startsWith("*")) {
						continue;   // 只看注释续行
					}
					int at = line.indexOf(TERMINATOR);
					// 只有"闭合符后面还有内容"才是提前闭合；行尾的 */ 是正常注释结束
					if (at >= 0 && !line.substring(at + TERMINATOR.length()).isBlank()) {
						offenders.add(root.relativize(file) + ":" + (i + 1) + "  " + line);
					}
				}
			}
		}

		assertTrue(offenders.isEmpty(),
			"这些注释行会在中间提前闭合（后面的字会变成代码，报错却指向注释）：\n" + String.join("\n", offenders));
	}

	/** 从工作目录往上找带 settings.gradle 的仓库根。 */
	private static Path repositoryRoot() {
		Path dir = Path.of("").toAbsolutePath();
		while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
			dir = dir.getParent();
		}
		return dir == null ? Path.of("").toAbsolutePath() : dir;
	}
}
