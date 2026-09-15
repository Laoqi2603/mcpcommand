package laoqi.mcpcommand.mixin;

import laoqi.mcpcommand.capture.MessageFilter;
import laoqi.mcpcommand.capture.OutputCapture;
import net.minecraft.class_2561;
import net.minecraft.class_338;
import net.minecraft.class_7469;
import net.minecraft.class_7591;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 捕获通道 1/2：<b>聊天栏渲染路径</b>。
 *
 * <p>目标 {@code class_338}=ChatHud，注入 {@code method_44811}=addMessage 的 HEAD。
 * 用 {@link ThreadLocal} 做防重入：捕获过程中若再次触发渲染会递归。
 *
 * <p><b>注意</b>：下面的 mixin 目标名与描述符都是 intermediary 字符串，改动它们等于改注入目标
 * （除非整份源码迁移到 yarn 命名）。
 */
@Mixin({class_338.class})
public class ChatHudMixin {
	@Unique
	private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);

	@Inject(
		method = {"method_44811(Lnet/minecraft/class_2561;Lnet/minecraft/class_7469;Lnet/minecraft/class_7591;)V"},
		at = {@At("HEAD")}
	)
	private void onAddMessage(class_2561 message, class_7469 signature, class_7591 indicator, CallbackInfo ci) {
		if (!CAPTURING.get()) {
			CAPTURING.set(true);

			try {
				// 这条通道能拿到「签名」与「GuiMessageTag」，所以玩家聊天的判定最准
				OutputCapture.accept(OutputCapture.Channel.CHAT_HUD, message,
						MessageFilter.isPlayerChat(message, signature != null, indicator));
			} finally {
				CAPTURING.set(false);
			}

		}
	}
}
