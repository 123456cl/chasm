package com.example.chasm;

import api.chasm.blockentity.ChasmBlockEntity;
import api.chasm.blockentity.ChasmBlockEntityBehaviour;
import api.chasm.blockentity.template.HeatSourceBehaviour;
import api.chasm.log.ChasmLogger;
import api.chasm.safety.ChasmSafety;
import api.chasm.state.StateMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 厨锅(Pot) 方块实体 —— 森罗物语移植（阶段1 MVP），状态推进已迁移到 {@link StateMachine}。
 *
 * <p>状态机：PUT(放料) -> COOK(炒) -> DONE(可出锅) -> BURNT(糊)，均为定时自动切换，
 * 统一走 {@link StateMachine#to} 收口（不会再忘 setChanged/忘置状态）。</p>
 *
 * 玩法：架到热源上 -> 用"油脂"放油 -> 放带 FOOD 的食材 -> 用"厨铲"右键开始炒 ->
 * 炒完 DONE 窗口内用碗（或潜行+厨铲）出锅；超时变糊、掉木炭。
 */
public class SkilletBlockEntity extends ChasmBlockEntity {

	/** 对外状态常量（保持与调用方一致）。 */
	public static final int PUT_INGREDIENT = 0;
	public static final int COOKING = 1;
	public static final int FINISHED = 2;
	public static final int BURNT = 3;

	/** 食材槽位数。 */
	private static final int RECIPES_SIZE = 4;

	/** 阶段枚举（序数与对外常量一致）。 */
	private enum Phase { PUT, COOK, DONE, BURNT }

	/** 时间（tick）：COOK 200、DONE 800、BURNT 400。 */
	private static final long COOK_TIME = 200, DONE_TIME = 800, BURNT_TIME = 400;

	private final NonNullList<ItemStack> inputs = NonNullList.withSize(RECIPES_SIZE, ItemStack.EMPTY);
	private boolean hasOil = false;
	private boolean burntHandled = false;

	private final StateMachine<Phase> machine = StateMachine.of(Phase.PUT)
		.at(Phase.COOK, COOK_TIME, Phase.DONE, this::cookTick)
		.at(Phase.DONE, DONE_TIME, Phase.BURNT, null)
		.at(Phase.BURNT, BURNT_TIME, null, null)
		.onChanged(p -> { setChanged(); refresh(); });

	public SkilletBlockEntity(BlockPos pos, BlockState state) {
		super(ExampleMod.SKILLET_TYPE, pos, state);
	}

	@Override
	public void addBehaviours(List<ChasmBlockEntityBehaviour<?>> behaviours) {
		behaviours.add(new HeatSourceBehaviour<>(this)); // 下方热源
	}

	/** 是否热。 */
	public boolean isHot() {
		HeatSourceBehaviour<?> heat = getBehaviour(HeatSourceBehaviour.TYPE);
		return heat != null && heat.isHot();
	}

	@Override
	public void tick() {
		super.tick();
		// 容错入口：仅服务端 + 吞异常
		ChasmSafety.serverOnly(level, () -> ChasmSafety.safe("mymod:pot_tick", this::potTick));
	}

	private void potTick() {
		if (!isHot()) {
			return; // 无热源：暂停推进（放料仍可用厨铲手动开始，但炒/出锅倒计时不动）
		}
		machine.tick();
		// BURNT 计时结束（terminal）：掉木炭并复位
		if (machine.state() == Phase.BURNT && machine.remaining() == 0 && !burntHandled) {
			burntHandled = true;
			dropCharcoal();
			smoke(8);
			resetMachine();
		}
	}

	/** COOK 每 tick：冒一点烟即可（视觉）。 */
	private void cookTick() {
		if (level != null && level.random.nextInt(10) == 0 && level instanceof ServerLevel sl) {
			sl.sendParticles(ParticleTypes.SMOKE,
				worldPosition.getX() + 0.5, worldPosition.getY() + 0.3, worldPosition.getZ() + 0.5,
				1, 0.2, 0.1, 0.2, 0.02);
		}
	}

	// —— 玩法交互（对外，块/铲调用）——

	/** 放油。 */
	public boolean placeOil(LivingEntity user) {
		if (hasOil) {
			return false;
		}
		hasOil = true;
		burntHandled = false;
		machine.to(Phase.PUT);
		playFireOut();
		smoke(6);
		ChasmLogger.call("mymod", "PotBE", "place_oil", "玩家 {} 给 {} 放油", user.getDisplayName().getString(), worldPosition);
		return true;
	}

	/** 加一个食材（仅 PUT 态）。 */
	public boolean addIngredient(LivingEntity user, ItemStack held) {
		if (machine.state() != Phase.PUT || held.isEmpty() || !held.has(DataComponents.FOOD)) {
			return false;
		}
		for (int i = 0; i < inputs.size(); i++) {
			if (inputs.get(i).isEmpty()) {
				inputs.set(i, held.copyWithCount(1));
				held.shrink(1);
				playPlace();
				markActive();
				return true;
			}
		}
		return false;
	}

	/** 移除最后放入的食材。 */
	public boolean removeIngredient(LivingEntity user) {
		if (machine.state() != Phase.PUT) {
			return false;
		}
		for (int i = inputs.size() - 1; i >= 0; i--) {
			ItemStack s = inputs.get(i);
			if (!s.isEmpty()) {
				inputs.set(i, ItemStack.EMPTY);
				giveTo(user, s);
				if (isHot()) {
					burn(user);
				}
				return true;
			}
		}
		return false;
	}

	/** 锅铲：PUT 且有料且有油 -> 开始炒；COOK -> 视为翻炒（装饰）。 */
	public boolean shovelStir(LivingEntity user) {
		Phase p = machine.state();
		if (p == Phase.PUT) {
			if (isEmpty() || !hasOil) {
				return false;
			}
			machine.to(Phase.COOK);
			markActive();
			ChasmLogger.call("mymod", "PotBE", "start_cook", "玩家 {} 开始炒菜", user.getDisplayName().getString());
			return true;
		}
		if (p == Phase.COOK) {
			return true; // 翻炒
		}
		return false;
	}

	/** 出锅：仅 DONE/BURNT。 */
	public boolean takeOut(LivingEntity user, ItemStack held) {
		Phase p = machine.state();
		if (p != Phase.DONE && p != Phase.BURNT) {
			return false;
		}
		boolean burnt = p == Phase.BURNT;
		ItemStack product = burnt ? new ItemStack(Items.CHARCOAL) : new ItemStack(Items.SUSPICIOUS_STEW);
		if (!held.is(Items.BOWL) && !held.is(ExampleMod.KITCHEN_SHOVEL)) {
			if (isHot()) {
				burn(user);
			}
			sendActionBar(user, "需要碗（或潜行+厨铲）来接菜！");
			return false;
		}
		if (!(user instanceof Player pl) || !pl.isSecondaryUseActive()) {
			sendActionBar(user, "请潜行(Shift)+" + (held.is(Items.BOWL) ? "碗" : "厨铲") + " 来接菜");
			return false;
		}
		if (held.is(Items.BOWL)) {
			held.shrink(1);
		}
		giveTo(user, product);
		resetMachine();
		return true;
	}

	// —— 内部 ——

	private void resetMachine() {
		inputs.clear();
		hasOil = false;
		burntHandled = false;
		machine.to(Phase.PUT);
		setChanged();
		refresh();
	}

	private void dropCharcoal() {
		if (level != null && !level.isClientSide) {
			Block.popResource(level, worldPosition, new ItemStack(Items.CHARCOAL, 1 + level.random.nextInt(3)));
		}
	}

	private void burn(LivingEntity user) {
		if (level != null) {
			user.hurt(level.damageSources().inFire(), 1);
		}
		ChasmLogger.call("mymod", "PotBE", "burn", "玩家 {} 被热锅烫伤", user.getDisplayName().getString());
	}

	private void sendActionBar(LivingEntity user, String msg) {
		if (user instanceof ServerPlayer sp) {
			sp.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
		}
	}

	private void giveTo(LivingEntity user, ItemStack stack) {
		if (user instanceof Player pl) {
			if (!pl.getInventory().add(stack)) {
				Block.popResource(level, worldPosition.above(), stack);
			}
		} else if (level != null) {
			level.addFreshEntity(new ItemEntity(level,
				worldPosition.getX() + 0.5, worldPosition.getY() + 0.4, worldPosition.getZ() + 0.5, stack));
		}
	}

	private void playFireOut() {
		level.playSound(null, worldPosition, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F,
			(level.random.nextFloat() - level.random.nextFloat()) * 0.8F);
	}

	private void playPlace() {
		level.playSound(null, worldPosition, SoundEvents.LANTERN_PLACE, SoundSource.BLOCKS, 1.0F, 0.5F);
	}

	private void smoke(int count) {
		if (level instanceof ServerLevel sl) {
			sl.sendParticles(ParticleTypes.SMOKE,
				worldPosition.getX() + 0.5, worldPosition.getY() + 0.3, worldPosition.getZ() + 0.5,
				count, 0.2, 0.1, 0.2, 0.02);
		}
	}

	public boolean isEmpty() {
		for (ItemStack s : inputs) {
			if (!s.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** 对外状态序号（PUT0/COOK1/DONE2/BURNT3）。 */
	public int getStatus() {
		return machine.state().ordinal();
	}

	public NonNullList<ItemStack> getInputs() {
		return inputs;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		var list = new net.minecraft.nbt.ListTag();
		for (ItemStack s : inputs) {
			list.add(s.saveOptional(registries));
		}
		tag.put("Inputs", list);
		tag.putBoolean("HasOil", hasOil);
		tag.putInt("Phase", machine.state().ordinal());
	}

	@Override
	public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		if (tag.contains("Inputs", 9)) {
			var list = tag.getList("Inputs", 10);
			for (int i = 0; i < list.size() && i < inputs.size(); i++) {
				inputs.set(i, ItemStack.parseOptional(registries, list.getCompound(i)));
			}
		}
		hasOil = tag.getBoolean("HasOil");
		int ph = tag.getInt("Phase");
		Phase[] phases = Phase.values();
		machine.to(ph >= 0 && ph < phases.length ? phases[ph] : Phase.PUT); // 恢复阶段（计时按节点全长重开）
	}

	@Override
	public void destroy() {
		if (level != null && !level.isClientSide) {
			for (ItemStack s : inputs) {
				if (!s.isEmpty()) {
					Block.popResource(level, worldPosition, s);
				}
			}
		}
		super.destroy();
	}
}
