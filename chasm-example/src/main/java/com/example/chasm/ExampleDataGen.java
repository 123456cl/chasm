package com.example.chasm;

import api.chasm.datagen.ChasmDataGen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * 示例模组的 DataGen 入口：引导 Chasm 自动生成模型/语言/配方。
 */
public class ExampleDataGen implements DataGeneratorEntrypoint {

	@Override
	public void onInitializeDataGenerator(FabricDataGenerator generator) {
		ChasmDataGen.generate(generator, ExampleMod.class);
	}
}
