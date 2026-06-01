package net.arm.mixin;

import net.arm.TextDumper;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Component.class)
public interface ComponentMixin {

    @ModifyVariable(method = "literal", at = @At("HEAD"), argsOnly = true)
    private static String transletar$onLiteralCreated(String text) {
        if (text == null || text.trim().isEmpty() || text.length() < 2) {
            return text;
        }
        return TextDumper.getOrRequestTranslationOnTheFly("literal:" + text, text);
    }
}