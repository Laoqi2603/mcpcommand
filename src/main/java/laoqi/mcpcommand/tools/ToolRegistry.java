package laoqi.mcpcommand.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * {@code tools/list} 返回的工具定义（首次构造后缓存）。
 *
 * <p><b>注意</b>：下面这些 description 是给 AI 客户端看的提示词，属于线上行为的一部分，
 * 改动会影响客户端怎么调用工具 —— 除新增可选参数外不要随手改写文案。
 *
 * <p>当前 {@code tools/list} 只暴露 {@code mc_command}。
 */
public final class ToolRegistry {
	private static volatile JsonArray cached;

	private ToolRegistry() {
	}

	public static JsonArray definitions() {
		JsonArray local = cached;
		if (local == null) {
			synchronized (ToolRegistry.class) {
				local = cached;
				if (local == null) {
					local = build();
					cached = local;
				}
			}
		}
		return local;
	}

	private static JsonArray build() {
		JsonArray tools = new JsonArray();
		tools.add(toolDef("mc_command",
				"Execute one or more Minecraft commands. Accepts EITHER:\n- 'command' (string): a SINGLE command with leading slash.\n- 'commands' (string[]): MULTIPLE commands — run sequentially with a combined report.\n\n*** ARRAY IS THE DEFAULT. Avoid calling this tool once per command — batch everything into ONE array call. Even a single command should go in an array. ***\n\nThe single 'command' string exists only as a fallback for truly trivial one-liners. Multiple similar commands (browsing pages, giving items, setting up areas) MUST share one array — never split them across separate invocations. Each separate call forces another 'Allow' click from the user.\n\nBATCH EXAMPLES (use these patterns):\n  {'commands': ['/time set day', '/weather clear', '/give @p stone 64']}\n  {'commands': ['/tp @p 100 64 200', '/fill ~-5 ~-1 ~-5 ~5 ~5 ~5 stone', '/setblock ~ ~5 ~ sea_lantern']}\n  {'commands': ['/gamemode creative', '/effect give @p minecraft:speed 600 5', '/give @p diamond_sword[minecraft:enchantments={levels:{\"minecraft:sharpness\":5}}] 1']}\n\nSINGLE (fallback only):\n  {'command': '/seed'}",
				schemaObj()
						.addProperty("command", prop("string", "FALLBACK: a SINGLE Minecraft command with leading slash. Prefer 'commands' array even for one command. Only use this when you are absolutely certain there is just one trivial command. Example: '/time set day'"))
						.addProperty("commands", propArray("string", "DEFAULT: array of Minecraft commands, each with leading slash. ALWAYS use this — even for a single command, wrap it in an array. Commands run sequentially; later ones can depend on earlier ones. Multiple similar commands (pages, items, blocks) MUST share one array — never fan them out into separate calls. Example: ['/time set day', '/weather clear', '/give @p diamond 64']"))
						.addProperty("quiet_ms", prop("integer", "Optional. Silence window in ms (50-10000, default 250): output unchanged for this long is treated as end-of-command. For plugins that first print 'searching...' then the result, 800-1500 is recommended."))
						.addProperty("timeout_ms", prop("integer", "Optional. Per-command hard timeout in ms (500-120000, default 5000)."))
						.addProperty("strip_codes", prop("boolean", "Optional. Strip Minecraft § colour/format codes from the captured text (default false = keep exactly what the game shows)."))
						.addProperty("resolve_item_ids", prop("boolean", "Optional. Rewrite bare item ids (e.g. 'x1 copper_boots') into the client's localized item names (default false). Useful when plain items in the output carry no tooltip."))
						.addProperty("hover_hints", prop("boolean", "Optional. Attach single-line hover tooltips to the captured text (default true). Plugins often put the ABSOLUTE timestamp in the hover of a relative time (e.g. hovering '9.04/d 前'), so this is what makes per-event timestamps recoverable. Set false to reduce noise."))
						.addProperty("stop_regex", prop("string", "Optional. Stop capturing as soon as the accumulated output matches this Java regex (e.g. '第 \\\\d+/\\\\d+ 页' or 'Page \\\\d+/\\\\d+' for paging output). Beats waiting for silence."))
						.addProperty("filter_chat", prop("boolean", "Optional. Drop player chat from the capture, keeping only system/command feedback (default true)."))
						.build()));
		return tools;
	}

	private static JsonObject toolDef(String name, String description, JsonObject inputSchema) {
		JsonObject def = new JsonObject();
		def.addProperty("name", name);
		def.addProperty("description", description);
		def.add("inputSchema", inputSchema);
		return def;
	}

	private static JsonObject prop(String type, String description) {
		JsonObject propDef = new JsonObject();
		propDef.addProperty("type", type);
		propDef.addProperty("description", description);
		return propDef;
	}

	private static JsonObject propArray(String itemType, String description) {
		JsonObject propDef = new JsonObject();
		propDef.addProperty("type", "array");
		propDef.addProperty("description", description);
		JsonObject items = new JsonObject();
		items.addProperty("type", itemType);
		propDef.add("items", items);
		return propDef;
	}

	private static SchemaBuilder schemaObj() {
		return new SchemaBuilder();
	}

	/** 极简 JSON Schema 构造器：只在有内容时才写入 properties / required。 */
	private static final class SchemaBuilder {
		private final JsonObject schema = new JsonObject();
		private final JsonObject properties = new JsonObject();
		private final JsonArray required = new JsonArray();

		SchemaBuilder() {
			schema.addProperty("type", "object");
		}

		SchemaBuilder addProperty(String name, JsonObject propDef) {
			properties.add(name, propDef);
			return this;
		}

		SchemaBuilder addRequired(String name) {
			required.add(name);
			return this;
		}

		JsonObject build() {
			if (properties.size() > 0) {
				schema.add("properties", properties);
			}

			if (required.size() > 0) {
				schema.add("required", required);
			}

			return schema;
		}
	}
}
