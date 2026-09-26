package api.chasm.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * 一次属性声明：属性 + 属性修饰符 + 生效槽位组。
 *
 * <p>由 {@code ItemBuilder.attribute()} 收集，{@code ChasmItem} 在构建时把它们合并进
 * {@code ATTRIBUTE_MODIFIERS} 组件（原版机制，工具提示/整合包增强系统可直接读取）。</p>
 *
 * @param attribute  目标属性
 * @param modifierId 修饰符唯一 id
 * @param amount     修饰符数值
 * @param operation  运算（默认 ADD_VALUE）
 * @param slot       生效槽位组（主手/副手/部位等）
 */
public record AttributeDeclaration(Attribute attribute, ResourceLocation modifierId, float amount,
	AttributeModifier.Operation operation, EquipmentSlotGroup slot) {

	/** 便捷工厂：ADD_VALUE 运算、主手槽位。 */
	public static AttributeDeclaration mainHand(Attribute attribute, ResourceLocation modifierId, float amount) {
		return new AttributeDeclaration(attribute, modifierId, amount,
			AttributeModifier.Operation.ADD_VALUE, EquipmentSlotGroup.MAINHAND);
	}
}