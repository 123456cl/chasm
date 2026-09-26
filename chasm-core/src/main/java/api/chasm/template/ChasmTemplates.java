package api.chasm.template;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩法模板门面（第十四步：模板插件层入口）。
 *
 * <p>把社区验证过的玩法模式（Aquamirae 武器/食物/护甲套装）收敛为「声明 + 行为钩子 +
 * 参数槽位」的模板：</p>
 * <ul>
 *   <li>{@link #weapon(String, String)}：武器能力模板（命中效果/击杀效果/击杀掉落）</li>
 *   <li>{@link #food()}：食物模板（营养/效果/余物）</li>
 *   <li>{@link #armorSet(String, String)}：护甲套装模板（材质 + 半套/全套效果）</li>
 *   <li>{@link #killReward(String, String)}：击杀奖励（独立声明，武器模板内部复用）</li>
 * </ul>
 *
 * <p>模板可被多件物品复用（改改就能直接用），是「深度嵌入 + 社区反哺」的最小落地单元。</p>
 */
public final class ChasmTemplates {

	/** 全局单例。 */
	public static final ChasmTemplates INSTANCE = new ChasmTemplates();

	/** 护甲套装注册表：套装 id → 规格（与 ChasmTraits 同构，O(1) 反查）。 */
	private final ConcurrentHashMap<ResourceLocation, ArmorSetSpec> armorSets = new ConcurrentHashMap<>();

	private ChasmTemplates() {
	}

	/** 启动一个武器能力模板声明（T-A1）。 */
	public WeaponTemplate.Builder weapon(String modId, String name) {
		return new WeaponTemplate.Builder(modId, name);
	}

	/** 启动一个食物模板声明（T-A4，无需注册名）。 */
	public FoodTemplate.Builder food() {
		return new FoodTemplate.Builder();
	}

	/** 启动一个护甲套装模板声明（T-A2）。 */
	public ArmorSetSpec.Builder armorSet(String modId, String name) {
		return new ArmorSetSpec.Builder(modId, name);
	}

	/** 启动一个击杀奖励声明（供独立使用或自定义模板复用）。 */
	public ChasmKillRewards.Builder killReward(String modId, String name) {
		return ChasmKillRewards.INSTANCE.create(modId, name);
	}

	/** 击杀奖励注册表（跨模组查询）。 */
	public ChasmKillRewards killRewards() {
		return ChasmKillRewards.INSTANCE;
	}

	/** 注册一个护甲套装（由 {@link ArmorSetSpec.Builder#build()} 调用）。 */
	public void registerArmorSet(ArmorSetSpec spec) {
		armorSets.put(spec.id(), spec);
	}

	/** 按 id 查询护甲套装（未注册返回 null）。 */
	public ArmorSetSpec getArmorSet(ResourceLocation id) {
		return armorSets.get(id);
	}

	/** 已注册护甲套装总数。 */
	public int armorSetCount() {
		return armorSets.size();
	}

	/** 全部已注册护甲套装（只读视图）。 */
	public Collection<ArmorSetSpec> allArmorSets() {
		return Collections.unmodifiableCollection(armorSets.values());
	}
}
