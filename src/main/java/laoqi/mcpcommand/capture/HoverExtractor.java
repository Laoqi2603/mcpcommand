package laoqi.mcpcommand.capture;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.class_1799;
import net.minecraft.class_1887;
import net.minecraft.class_2561;
import net.minecraft.class_2568;
import net.minecraft.class_2583;
import net.minecraft.class_6880;
import net.minecraft.class_8824;
import net.minecraft.class_9304;
import net.minecraft.class_9334;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从一条聊天消息里额外抽取「悬浮（hover）信息」。
 *
 * <p>为什么需要它：有些插件的**主行只有物品 ID**（{@code added x1 copper_boots}），
 * 物品名、附魔、耐久等都在挂在消息上的 hover tooltip 里。
 * 不抓 hover 就等于丢掉了这些信息。
 *
 * <p>两条路径：
 * <ul>
 *   <li>{@code show_item} 悬浮 → 走 {@link #formatItemStackNbt}，直接读物品组件的 NBT 摘要；</li>
 *   <li>{@code show_text} 悬浮 → 走 {@link #formatTooltipText}，取多行 tooltip 的文本（插件多半走这条）。</li>
 * </ul>
 */
public final class HoverExtractor {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:mcp");

	/**
	 * 是否把**单行** hover 也附加进捕获文本（默认开）。
	 *
	 * <p>为什么重要：**绝对时间戳**就在悬停相对时间（如 {@code 9.04/d 前}）时弹出的
	 * <b>单行</b>提示里，所以单行 hover 也必须收。
	 * 用 {@code -Dmcpcommand.noHoverHints=true} 可全局关闭，或用 {@code mc_command} 的
	 * {@code hover_hints=false} 单次关闭。
	 */
	private static volatile boolean captureSingleLineHover = !Boolean.getBoolean("mcpcommand.noHoverHints");

	private HoverExtractor() {
	}

	/** 单次调用级开关（命令是串行执行的，所以这个状态不会互相干扰）。 */
	public static void setCaptureSingleLineHover(boolean value) {
		captureSingleLineHover = value;
	}

	/**
	 * 把「可见文本 + 悬浮信息」拼成捕获用的完整文本。
	 *
	 * @return 无 hover 时就是可见文本本身
	 */
	public static String enrich(class_2561 message) {
		String visible = message.getString();
		String hoverItems = extractHoverItems(message);
		if (hoverItems.isEmpty()) {
			if (LOGGER.isDebugEnabled()) {
				try {
					DataResult<JsonElement> result = class_8824.field_46597.encodeStart(JsonOps.INSTANCE, message);
					result.result().ifPresent((json) -> LOGGER.debug("No hover extracted — raw JSON: {}", json));
				} catch (Exception ignored) {
					// 仅调试日志用，序列化失败不影响捕获
				}
			}
			return visible;
		}
		return visible + "\n" + hoverItems;
	}

	/**
	 * 抽取消息里所有 hover 的物品信息与多行 tooltip。
	 * 物品按对象身份去重，tooltip 按文本去重；只收多行 tooltip（单行多是普通提示，噪音大）。
	 */
	private static String extractHoverItems(class_2561 component) {
		StringBuilder sb = new StringBuilder();
		Set<Integer> seenItems = new HashSet<>();
		Set<String> seenTooltips = new HashSet<>();
		component.method_27658((style, text) -> {
			class_2568 hover = style.method_10969();
			if (hover instanceof class_2568.class_10612 showItem) {
				class_1799 item = showItem.comp_3509();
				if (item != null && !item.method_7960() && seenItems.add(System.identityHashCode(item))) {
					String formatted = formatItemStackNbt(item);
					if (!formatted.isEmpty()) {
						if (!sb.isEmpty()) {
							sb.append('\n');
						}

						sb.append(formatted);
					}
				}
			}

			if (hover instanceof class_2568.class_10613 showText) {
				class_2561 tooltip = showText.comp_3510();
				if (tooltip != null) {
					String tip = tooltip.getString();
					if (!tip.isBlank() && seenTooltips.add(tip)) {
						String rendered = tip.contains("\n")
								? formatTooltipText(tip)
								: (captureSingleLineHover ? "  (hover) " + tip.trim() : null);
						// 单行 hover 默认收（绝对时间戳在这里）；关闭时不产生噪音
						if (rendered != null && !rendered.isEmpty()) {
							if (!sb.isEmpty()) {
								sb.append('\n');
							}

							sb.append(rendered);
						}
					}
				}
			}

			return Optional.empty();
		}, class_2583.field_24360);
		return sb.toString();
	}

	/**
	 * 把「显示名 + 属性行」的 tooltip 压成一行摘要，例如 {@code [Copper Boots] { Density III, Protection IV }}。
	 */
	private static String formatTooltipText(String raw) {
		String[] lines = raw.split("\n");
		if (lines.length == 0) {
			return "";
		} else {
			String name = lines[0].trim();
			StringBuilder sb = new StringBuilder();
			sb.append("  [").append(name).append("]");
			if (lines.length > 1) {
				sb.append(" {");

				for (int i = 1; i < lines.length; ++i) {
					if (i > 1) {
						sb.append(",");
					}

					sb.append(" ").append(lines[i].trim());
				}

				sb.append(" }");
			}

			return sb.toString();
		}
	}

	/**
	 * 把一个物品堆的组件信息格式化成文本：自定义名、附魔、存储内容（潜影盒等）、耐久、修复代价、数量。
	 */
	private static String formatItemStackNbt(class_1799 stack) {
		String name = stack.method_7964().getString();
		int count = stack.method_7947();
		StringBuilder sb = new StringBuilder();
		sb.append("  [");
		class_2561 customName = stack.method_58694(class_9334.field_49631);
		if (customName != null) {
			sb.append(customName.getString());
		} else {
			sb.append(name);
		}

		sb.append("]");
		class_9304 ench = stack.method_58694(class_9334.field_49633);
		if (ench != null && !ench.method_57543()) {
			sb.append(" {");
			boolean first = true;

			for (class_6880<class_1887> entry : ench.method_57534()) {
				if (!first) {
					sb.append(",");
				}

				first = false;
				sb.append(" ").append(entry.method_55840()).append("=").append(ench.method_57536(entry));
			}

			sb.append(" }");
		}

		class_9304 stored = stack.method_58694(class_9334.field_49643);
		if (stored != null && !stored.method_57543()) {
			sb.append(" {Stored:");
			boolean first = true;

			for (class_6880<class_1887> entry : stored.method_57534()) {
				if (!first) {
					sb.append(",");
				}

				first = false;
				sb.append(" ").append(entry.method_55840()).append("=").append(stored.method_57536(entry));
			}

			sb.append("}");
		}

		Integer damage = stack.method_58694(class_9334.field_49629);
		Integer maxDamage = stack.method_58694(class_9334.field_50072);
		if (maxDamage != null && maxDamage > 0) {
			int remaining = damage != null ? maxDamage - damage : maxDamage;
			sb.append(" dur:").append(remaining).append("/").append(maxDamage);
		}

		Integer repairCost = stack.method_58694(class_9334.field_49639);
		if (repairCost != null && repairCost > 0) {
			sb.append(" repair:").append(repairCost);
		}

		if (count > 1) {
			sb.append(" x").append(count);
		}

		return sb.toString();
	}
}
