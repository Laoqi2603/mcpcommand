package laoqi.mcpcommand.mixin;

import laoqi.mcpcommand.capture.MessageFilter;
import laoqi.mcpcommand.capture.OutputCapture;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.class_2561;
import net.minecraft.class_634;
import net.minecraft.class_7439;
import net.minecraft.class_8824;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 捕获通道 2/2：<b>网络包路径</b>。
 *
 * <p>目标 {@code class_634}=ClientPacketListener，注入 {@code method_43596}=
 * handleSystemChat 的 <b>RETURN</b>；跳过 overlay/actionbar 消息
 * （{@code comp_906()} 为 true 的那些）。
 *
 * <p><b>为什么用 RETURN 而不是 HEAD</b>：MC 对网络包做线程亲和检查 ——
 * 包先在 Netty 线程被调用一次（发现不是主线程就抛异常中止），随后重新派发到主线程真正执行。
 * 注入在 HEAD 会让同一条消息被抓两次（同一个包对象 ids 相同、线程不同），
 * 而被中止的那次走不到 RETURN，所以 RETURN 只会命中真正生效的那一次。
 *
 * <p>两条通道送来的同一条消息由 {@code OutputCapture} 按「通道 + 文本 + 时间窗」成对去重（见 capture 包）。
 */
@Mixin({class_634.class})
public class ClientPlayNetworkHandlerMixin {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:mcp");

	@Inject(
		method = {"method_43596"},
		at = {@At("RETURN")}          // 不能用 HEAD：见下方注释
	)
	private void onGameMessage(class_7439 packet, CallbackInfo ci) {
		class_2561 content = packet.comp_763();
		if (!packet.comp_906()) {                       // true = overlay/actionbar，丢弃
			// 这条通道没有 GuiMessageTag，只能靠组件树里的聊天翻译键判断玩家聊天
			OutputCapture.accept(OutputCapture.Channel.NETWORK, content, MessageFilter.hasPlayerChatKey(content));
			if (LOGGER.isDebugEnabled()) {
				try {
					DataResult<JsonElement> result = class_8824.field_46597.encodeStart(JsonOps.INSTANCE, content);
					result.result().ifPresent((json) -> LOGGER.debug("GameMessage raw JSON: {}", json));
				} catch (Exception e) {
					LOGGER.debug("Failed to serialise GameMessage Text to JSON", e);
				}
			}

		}
	}
}
