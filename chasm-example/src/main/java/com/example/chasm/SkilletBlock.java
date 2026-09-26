package com.example.chasm;

import api.chasm.block.ChasmBlock;
import api.chasm.block.LootDeclaration;
import api.chasm.log.ChasmLogger;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.function.Supplier;

/** 厨锅(Pot) 方块 —— 森罗物语厨锅的 chasm 移植（阶段1 MVP）。 */
public class SkilletBlock extends ChasmBlock {

	private static final ResourceLocation FIRST_HEAT_ADVANCEMENT =
		ResourceLocation.fromNamespaceAndPath("mymod", "main/first_heated_skillet");

	private static final VoxelShape SHAPE = box(1.0D, 0.0D, 1.0D, 15.0D, 4.0D, 15.0D);

	public SkilletBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {
		super(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
			properties, null, null, null, LootDeclaration.DEFAULT, blockEntityType);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		if (!level.isClientSide && placer instanceof ServerPlayer sp
			&& level.getBlockEntity(pos) instanceof SkilletBlockEntity be && be.isHot()) {
			grantFirstHeat(sp, pos);
		}
		super.setPlacedBy(level, pos, state, placer, stack);
	}

	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (level.isClientSide) {
			player.swing(hand);
			return ItemInteractionResult.CONSUME;
		}
		if (!(level.getBlockEntity(pos) instanceof SkilletBlockEntity be)) {
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		if (!(player instanceof ServerPlayer sp) || !be.canPlayerUse(player)) {
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		if (be.isHot()) {
			grantFirstHeat(sp, pos);
		}
		// 1) 放油（油脂）
		if (stack.is(ExampleMod.OIL)) {
			if (be.placeOil(sp)) {
				level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 0.8F, 0.9F);
				stack.shrink(1);
				sp.displayClientMessage(Component.literal("起锅烧油！快往锅里放食材（约60秒内）……"), false);
				return ItemInteractionResult.SUCCESS;
			}
			sp.displayClientMessage(Component.literal("锅已经有油了。"), false);
			return ItemInteractionResult.SUCCESS;
		}
		// 2) 厨铲：翻炒 / 出锅
		if (stack.is(ExampleMod.KITCHEN_SHOVEL)) {
			int s = be.getStatus();
			if (s == SkilletBlockEntity.COOKING || s == SkilletBlockEntity.PUT_INGREDIENT) {
				if (be.shovelStir(sp)) {
					level.playSound(null, pos, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.8F, 1.1F);
					sp.displayClientMessage(Component.literal("翻炒！"), false);
					return ItemInteractionResult.SUCCESS;
				}
				sp.displayClientMessage(Component.literal("锅里没有要炒的食材。"), false);
				return ItemInteractionResult.SUCCESS;
			}
			if (be.takeOut(sp, stack)) {
				return ItemInteractionResult.SUCCESS;
			}
			return ItemInteractionResult.SUCCESS;
		}
		// 3) 放食材
		if (be.getStatus() == SkilletBlockEntity.PUT_INGREDIENT && !stack.isEmpty()) {
			if (be.addIngredient(sp, stack)) {
				ChasmLogger.call("mymod", "PotBlock", "add", "玩家 {} 放入食材", sp.getGameProfile().getName());
				return ItemInteractionResult.SUCCESS;
			}
			sp.displayClientMessage(Component.literal("只能放可食用的食物（需先放油，且在放料窗口内）。"), false);
			return ItemInteractionResult.SUCCESS;
		}
		// 4) 其它：尝试取出生食材
		if (be.removeIngredient(sp)) {
			return ItemInteractionResult.SUCCESS;
		}
		sp.displayClientMessage(Component.literal("请按流程：放油→放食材→厨铲翻炒→出锅。"), false);
		return ItemInteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
		Player player, BlockHitResult hitResult) {
		if (level.isClientSide) {
			return InteractionResult.CONSUME;
		}
		if (!(level.getBlockEntity(pos) instanceof SkilletBlockEntity be)) {
			return InteractionResult.PASS;
		}
		if (be.getStatus() == SkilletBlockEntity.PUT_INGREDIENT) {
			if (be.removeIngredient(player)) {
				return InteractionResult.sidedSuccess(false);
			}
			player.displayClientMessage(Component.literal("锅里没有要取出的食材。"), false);
		}
		return InteractionResult.sidedSuccess(false);
	}

	private static void grantFirstHeat(ServerPlayer player, BlockPos pos) {
		// 走自有方法点：第三方可 after 监听/阻止（默认 TRUE=放行）
		if (!Boolean.TRUE.equals(ExampleMod.POT_FIRST_HEAT.apply(player))) {
			return;
		}
		AdvancementHolder advancement = player.server.getAdvancements().get(FIRST_HEAT_ADVANCEMENT);
		if (advancement != null) {
			player.getAdvancements().award(advancement, "heat");
		}
	}
}