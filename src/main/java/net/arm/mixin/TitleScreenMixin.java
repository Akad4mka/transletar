package net.arm.mixin;

import net.arm.TextDumper;
import net.arm.TranslationProgressScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {

    @Unique
    private static boolean transletar$hasAutoApplied = false;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("TAIL"), method = "init")
    private void onInit(CallbackInfo info) {
        if (!transletar$hasAutoApplied) {
            transletar$hasAutoApplied = true;
            TextDumper.loadCacheFromFile();
            if (!TextDumper.getCachedTranslations().isEmpty()) {
                TextDumper.injectTranslations();
                this.rebuildWidgets();
            }
        }

        this.addRenderableWidget(Button.builder(
                        Component.literal("Dump & Translate"),
                        button -> Minecraft.getInstance().setScreen(new TranslationProgressScreen(this))
                )
                .bounds(10, 10, 130, 20)
                .build());
    }
}