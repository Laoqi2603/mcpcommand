package laoqi.mcpcommand.capture;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.class_1792;
import net.minecraft.class_2960;
import net.minecraft.class_7923;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把捕获文本里的**物品 ID** 换成本地化的物品名。
 *
 * <p>为什么需要它：这类输出的主行只给 ID（{@code added x1 copper_boots.}），
 * 物品名/附魔在 hover tooltip 里；而**没有 NBT 的普通物品压根没有 tooltip**，
 * 于是"物品名"就丢了。这里用客户端自己的物品注册表把 ID 翻成玩家语言下的名字，
 * 例如 {@code copper_boots → 铜靴子}。
 *
 * <p>只处理「{@code x<数量> <物品ID>}」这种上下文（常见的物品行格式），
 * 避免误改普通文本；注册表里查不到的 token 原样保留。
 *
 * <p>默认**关闭**（由 {@code mc_command} 的 {@code resolve_item_ids} 参数开启）：
 * 打开会改写原文，而原文是与游戏内所见逐字对应的，统计脚本可能依赖它。
 */
public final class ItemNameResolver {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:capture");
	/** 只匹配「x<数量> 后面紧跟的物品 ID」 */
	private static final Pattern ITEM_TOKEN =
			Pattern.compile("(\\bx\\d+ )([a-z][a-z0-9_]*(?::[a-z0-9_/]+)?)");

	private ItemNameResolver() {
	}

	/** 把文本里的物品 ID 替换成本地化名称（查不到的保持原样）。 */
	public static String resolve(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		try {
			Matcher matcher = ITEM_TOKEN.matcher(text);
			StringBuilder out = new StringBuilder(text.length() + 32);
			boolean changed = false;
			while (matcher.find()) {
				String token = matcher.group(2);
				String name = localizedName(token);
				if (name != null) {
					matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + name));
					changed = true;
				} else {
					matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
				}
			}
			matcher.appendTail(out);
			return changed ? out.toString() : text;
		} catch (Exception e) {
			LOGGER.debug("item id resolution failed", e);
			return text;
		}
	}

	/**
	 * 查物品注册表拿本地化名。
	 *
	 * <p>intermediary 名对照：{@code class_7923.field_41178}=BuiltInRegistries.ITEM、
	 * {@code class_2960.method_60655}=Identifier.fromNamespaceAndPath、
	 * {@code method_17966}=Registry.getOptional（直接给值；{@code method_10223}=get 给的是 Holder 包装）、
	 * {@code class_1792.method_63680}=Item.getName。
	 */
	private static String localizedName(String token) {
		String namespace = "minecraft";
		String path = token;
		int colon = token.indexOf(':');
		if (colon > 0) {
			namespace = token.substring(0, colon);
			path = token.substring(colon + 1);
		}
		if (path.isEmpty()) {
			return null;
		}
		class_2960 id = class_2960.method_60655(namespace, path);
		return class_7923.field_41178.method_17966(id)
				.map(item -> item.method_63680().getString())
				.filter(name -> !name.isEmpty())
				.orElse(null);
	}
}
