package com.gudu0.simplexray.client;

import com.gudu0.simplexray.SimpleXray;
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
import org.slf4j.Logger;


public class SimpleXrayClient implements ClientModInitializer {
	Logger logger = SimpleXray.LOGGER;

	private static final KeyBinding OPEN_CONFIG_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.xraymod.open_config",
			InputUtil.GLFW_KEY_V,
			"category.xraymod"
	));
	// Add-block key ships unbound — it's a secondary convenience shortcut and V is already
	// a visible default, so we don't want to stomp a key the player may already use.
	private static final KeyBinding ADD_LOOKED_AT_BLOCK_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.xraymod.add_looked_at_block",
			InputUtil.UNKNOWN_KEY.getCode(),
			"category.xraymod"
	));

	@Override
	public void onInitializeClient() {
		// Order matters: XrayConfig must be loaded before XrayBlockCache.register() subscribes
		// to CHUNK_LOAD — chunks can start loading immediately and will query XrayConfig.
		XrayConfig.load();
		XrayBlockCache.register();
		XrayRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_CONFIG_KEY.wasPressed()) {
				client.setScreen(new XrayConfigScreen(client.currentScreen));
			}

			while (ADD_LOOKED_AT_BLOCK_KEY.wasPressed()) {
				// Guard against a screen being open: crosshairTarget isn't updated while
				// the player's cursor is in a GUI, so the result would be stale/wrong.
				if (client.currentScreen == null) {
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