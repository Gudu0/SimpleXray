package com.gudu0.simplexray.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;


public class SimpleXrayClient implements ClientModInitializer {
	private static final KeyBinding OPEN_CONFIG_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.xraymod.open_config",
			InputUtil.GLFW_KEY_V, // unbound by default; player sets it in Controls
			"category.xraymod"
	));
	private static final KeyBinding ADD_LOOKED_AT_BLOCK_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.xraymod.add_looked_at_block",
			InputUtil.UNKNOWN_KEY.getCode(),
			"category.xraymod"
	));

	@Override
	public void onInitializeClient() {
		XrayConfig.load();
		XrayBlockCache.register();
		XrayRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_CONFIG_KEY.wasPressed()) {
				client.setScreen(new XrayConfigScreen(client.currentScreen));
			}

			while (ADD_LOOKED_AT_BLOCK_KEY.wasPressed()) {
				if (client.currentScreen == null) { // see note below
					addLookedAtBlock(client);
				}
			}
		});
	}

	private static void addLookedAtBlock(MinecraftClient client) {
		HitResult hit = client.crosshairTarget;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;
		if (client.world == null) return;

		Block block = client.world.getBlockState(blockHit.getBlockPos()).getBlock();
		XrayConfig.addBlock(block);

		if (client.player != null) {
			client.player.sendMessage(Text.literal("Added " + block.getName().getString() + " to Xray list"), true);
		}
	}
}