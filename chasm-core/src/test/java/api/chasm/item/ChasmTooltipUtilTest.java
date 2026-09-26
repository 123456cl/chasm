package api.chasm.item;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.Attributes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具提示里"属性行"的识别。
 *
 * <p>回归点：1.21 的属性行把属性名放在 {@code TranslatableContents} 的**参数**里
 * （外层 literal + 内层 translatable("attribute.modifier.…", 数值, translatable(属性名))），
 * 只查 contents/siblings 会永远匹配不上，导致攻速行替换失效、玩家看到原始的负修饰符。</p>
 */
class ChasmTooltipUtilTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void attributeNameNestedInTranslationArgsIsFound() {
		String attackSpeedKey = Attributes.ATTACK_SPEED.value().getDescriptionId();
		// 复刻原版结构：外层 literal(" ") → 内层 translatable，属性名**作为参数**嵌进去
		Component vanillaLine = Component.literal(" ").append(
			Component.translatable("attribute.modifier.equals.0", "+1.60",
				Component.translatable(attackSpeedKey)));
		assertTrue(ChasmTooltipUtil.containsTranslatableKey(vanillaLine, attackSpeedKey),
			"属性名藏在翻译参数里时也必须命中（否则攻速行替换失效）");
	}

	@Test
	void unrelatedLineDoesNotMatch() {
		Component other = Component.literal(" ").append(
			Component.translatable("attribute.modifier.equals.0", "+3",
				Component.translatable("attribute.name.generic.attack_damage")));
		assertFalse(ChasmTooltipUtil.containsTranslatableKey(other,
			Attributes.ATTACK_SPEED.value().getDescriptionId()), "别的属性行不该被误判为攻速行");
	}

	@Test
	void transformReplacesVanillaAttackSpeedLine() {
		String attackSpeedKey = Attributes.ATTACK_SPEED.value().getDescriptionId();
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(Component.literal("模块化剑"));
		tooltip.add(Component.literal(" ").append(
			Component.translatable("attribute.modifier.equals.0", "-1.11",
				Component.translatable(attackSpeedKey))));
		ChasmTooltipUtil.transform(new net.minecraft.world.item.ItemStack(
			net.minecraft.world.item.Items.IRON_SWORD), tooltip);
		boolean stillHasRawLine = tooltip.stream()
			.anyMatch(line -> ChasmTooltipUtil.containsTranslatableKey(line, attackSpeedKey));
		assertFalse(stillHasRawLine, "原版的攻速修饰符行必须被替换掉");
	}
}
