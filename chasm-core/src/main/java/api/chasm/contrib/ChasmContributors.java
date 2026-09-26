package api.chasm.contrib;

import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 贡献来源注册表（开放）：任何模组都能注册自己的 {@link ItemContributor}
 * （词缀、宝石、套装、诅咒、天赋、符文…），由统一管线消费。
 *
 * <p>注册幂等：同 id 重复注册覆盖并记日志（便于热重载/开发期覆盖）。</p>
 */
public final class ChasmContributors {

	private static final Map<ResourceLocation, ItemContributor> REGISTRY = new ConcurrentHashMap<>();

	private ChasmContributors() {
	}

	/** 注册一个贡献者（以 {@code contributor.id()} 为键）。 */
	public static ItemContributor register(ItemContributor contributor) {
		if (contributor == null || contributor.id() == null) {
			throw new IllegalArgumentException("ItemContributor 及其 id 不可为 null");
		}
		ItemContributor old = REGISTRY.put(contributor.id(), contributor);
		if (old != null) {
			ChasmLogger.warn("chasm", "贡献者 {} 被重复注册（已覆盖）", contributor.id());
		}
		return contributor;
	}

	/** 按 id 查询（未注册返回 null）。 */
	public static ItemContributor get(ResourceLocation id) {
		return REGISTRY.get(id);
	}

	/** 全部已注册贡献者（只读）。 */
	public static Collection<ItemContributor> all() {
		return Collections.unmodifiableCollection(REGISTRY.values());
	}

	public static int size() {
		return REGISTRY.size();
	}
}
