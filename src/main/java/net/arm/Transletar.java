package net.arm;

import net.fabricmc.api.ModInitializer;

public class Transletar implements ModInitializer {
	@Override
	public void onInitialize() {
		TextDumper.loadCacheFromFile();
	}
}