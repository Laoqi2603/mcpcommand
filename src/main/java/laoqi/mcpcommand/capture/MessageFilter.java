package laoqi.mcpcommand.capture;

import java.util.List;
import net.minecraft.class_2561;
import net.minecraft.class_2588;
import net.minecraft.class_7591;

/**
 * 消息甄别与文本清洗。
 *
 * <p>用途：模组只能抓到"聊天栏里出现的一切"，
 * 所以要在入口处把**玩家聊天**滤掉，只留系统消息/命令反馈。
 * 这里用的是"**只排除玩家聊天**"而不是"只保留系统消息"（更稳妥）：
 * 后者一旦漏判就会把命令结果也丢掉（比混入噪音更糟）。
 *
 * <p>判定依据（按可靠性排序）：
 * <ol>
 *   <li>带签名（{@code MessageSignature} 非空）= 玩家聊天；</li>
 *   <li>指示器为 {@code GuiMessageTag.CHAT_NOT_SECURE}（离线/未验证聊天）= 玩家聊天；</li>
 *   <li>组件树里出现玩家聊天的翻译键（{@code chat.type.text} / {@code chat.type.team.text}）= 玩家聊天。</li>
 * </ol>
 */
public final class MessageFilter {
	/** 原版玩家聊天的翻译键；团队聊天也算玩家聊天 */
	private static final List<String> PLAYER_CHAT_KEYS = List.of("chat.type.text", "chat.type.team.text");

	private MessageFilter() {
	}

	/**
	 * 通道 1（聊天栏）用：能拿到签名与指示器，判定最准。
	 *
	 * <p>{@code GuiMessageTag} 的常量字段是 private，但官方提供了 public 工厂方法
	 * （{@code system()} / {@code systemSinglePlayer()} / {@code chatNotSecure()}），
	 * 它们返回的是同一个缓存实例，所以可以直接用 {@code ==} 比较。
	 *
	 * @param hasSignature 消息是否带玩家签名（{@code class_7469} 非空）
	 * @param tag          {@code GuiMessageTag}，可能为 null
	 */
	public static boolean isPlayerChat(class_2561 message, boolean hasSignature, class_7591 tag) {
		if (tag != null && (tag == class_7591.method_44751() || tag == class_7591.method_47391())) {
			return false;                                    // 明确标记为系统消息
		}
		if (hasSignature) {
			return true;                                     // 带签名 = 玩家聊天
		}
		if (tag != null && tag == class_7591.method_44709()) {   // chatNotSecure：离线/未验证聊天
			return true;
		}
		return hasPlayerChatKey(message);
	}

	/** 通道 2（网络包）与兜底用：只能靠组件树判断 */
	public static boolean hasPlayerChatKey(class_2561 message) {
		if (message == null) {
			return false;
		}
		try {
			if (message.method_10851() instanceof class_2588 translatable
					&& PLAYER_CHAT_KEYS.contains(translatable.method_11022())) {
				return true;
			}
			for (class_2561 sibling : message.method_10855()) {
				if (hasPlayerChatKey(sibling)) {
					return true;
				}
			}
		} catch (Exception ignored) {
			// 组件结构异常不应影响捕获
		}
		return false;
	}

	/**
	 * 剥离 Minecraft 的 {@code §} 颜色/格式代码（插件输出里大量存在）。
	 * 只在调用方显式要求时使用，默认保持原文以便与游戏内所见完全一致。
	 */
	public static String stripCodes(String text) {
		if (text == null || text.indexOf('\u00a7') < 0) {
			return text;
		}
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\u00a7' && i + 1 < text.length()) {
				i++;                                  // 跳过格式码本身
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}
}
