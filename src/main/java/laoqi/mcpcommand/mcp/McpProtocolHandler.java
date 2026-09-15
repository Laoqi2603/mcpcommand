package laoqi.mcpcommand.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import laoqi.mcpcommand.tools.ClientQueryTools;
import laoqi.mcpcommand.tools.McCommandTool;
import laoqi.mcpcommand.tools.ToolRegistry;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 协议层：JSON-RPC 分发 + initialize / tools 方法。
 *
 * <p>职责拆分：捕获管线在 {@code capture} 包，工具定义在
 * {@link ToolRegistry}，工具实现在 {@code tools} 包；本类只做协议与分发。
 */
public final class McpProtocolHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:mcp");
	/** MCP 协议版本，随 initialize 结果返回。 */
	static final String PROTOCOL_VERSION = "2025-03-26";
	/**
	 * {@code serverInfo.version}：**直接取自模组元数据**（fabric.mod.json 的 version，
	 * 由 gradle.properties 的 {@code mod_version} 注入），所以改版本号不用再改这里。
	 * 取不到时回退到 UNKNOWN_VERSION。
	 */
	private static final String SERVER_VERSION = resolveServerVersion();
	private static final String UNKNOWN_VERSION = "unknown";
	private static final String SERVER_NAME = "MCPCommand";

	private static String resolveServerVersion() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance()
					.getModContainer("mcpcommand")
					.map(container -> container.getMetadata().getVersion().getFriendlyString())
					.orElse(UNKNOWN_VERSION);
		} catch (Throwable t) {
			return UNKNOWN_VERSION;
		}
	}

	/**
	 * 与 {@link McpHttpServer} 共享的集成服务器引用（同一个 AtomicReference 实例）。
	 * 语义：**只在单人/LAN 的集成服务器存在时非空**；连专用服务器时恒为 null。
	 */
	private final AtomicReference<MinecraftServer> integratedServer;

	/**
	 * 是否强制 initialize 前置。默认开启（符合 MCP 规范）；
	 * 若客户端不按规范先 initialize，可用系统属性 {@code -Dmcpcommand.lenientMcp=true} 关闭校验。
	 */
	private static final boolean REQUIRE_INITIALIZE =
			!Boolean.getBoolean("mcpcommand.lenientMcp");

	private volatile boolean initialized;

	/** @param integratedServer 与 {@link McpHttpServer} 共享的同一个引用（见字段说明）。 */
	McpProtocolHandler(AtomicReference<MinecraftServer> integratedServer) {
		this.integratedServer = integratedServer;
	}

	/** JSON-RPC 方法分发。 */
	JsonElement dispatch(String method, JsonElement params, JsonElement id) {
		if (REQUIRE_INITIALIZE && !initialized && requiresInitialized(method)) {
			LOGGER.debug("Rejecting '{}' — client has not sent initialize yet", method);
			return jsonRpcError(id, -32002, "Server not initialized: send 'initialize' first");
		}

		switch (method) {
			case "initialize":
				return handleInitialize(params, id);
			case "notifications/initialized":
				handleInitialized();
				return null;
			case "ping":
				return jsonRpcResult(id, params != null ? params : JsonNull.INSTANCE);
			case "tools/list":
				return handleToolsList(id);
			case "tools/call":
				return handleToolsCall(params, id);
			default:
				LOGGER.debug("Unknown MCP method: {}", method);
				return jsonRpcError(id, -32601, "Method not found: " + method);
		}
	}

	/** initialize 与其通知、以及 ping 不要求已初始化 */
	private static boolean requiresInitialized(String method) {
		return !"initialize".equals(method) && !"notifications/initialized".equals(method) && !"ping".equals(method);
	}

	/**
	 * 客户端通过 {@code DELETE /mcp} 结束会话时重置协议状态（MCP Streamable HTTP）：
	 * 之后必须重新 initialize。
	 */
	void resetSession() {
		initialized = false;
		LOGGER.info("MCP session reset — client must send initialize again");
	}

	/**
	 * 处理 initialize：记录客户端信息，返回协议版本、serverInfo、capabilities 与那段
	 * 「务必批量调用」的 instructions。
	 */
	private JsonElement handleInitialize(JsonElement params, JsonElement id) {
		if (params != null && params.isJsonObject()) {
			JsonObject paramsObj = params.getAsJsonObject();
			if (paramsObj.has("clientInfo")) {
				JsonObject clientInfo = paramsObj.getAsJsonObject("clientInfo");
				LOGGER.info("MCP client connected: {} v{}", clientInfo.get("name").getAsString(), clientInfo.get("version").getAsString());
			}
		}
		initialized = true;

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", PROTOCOL_VERSION);
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", SERVER_NAME);
		serverInfo.addProperty("version", SERVER_VERSION);
		result.add("serverInfo", serverInfo);
		JsonObject capabilities = new JsonObject();
		JsonObject toolsCap = new JsonObject();
		toolsCap.addProperty("listChanged", false);
		capabilities.add("tools", toolsCap);
		result.add("capabilities", capabilities);
		result.addProperty("instructions", "MCPCommand connects this Minecraft client to an AI assistant. CRITICAL RULE for mc_command: AVOID calling it once per command. Instead, batch EVERYTHING into ONE call using the 'commands' array — even if you have only 1 command, wrap it in an array. The single 'command' string exists only as a fallback; the array form is the DEFAULT. Multiple similar commands (e.g. browsing pages, giving items, setting up an area) MUST go into ONE array call — never split them across separate invocations. Each separate tool call forces the user to click 'Allow' again; the array form confirms once.");
		LOGGER.info("MCP initialize — protocol={}, capabilities sent", PROTOCOL_VERSION);
		return jsonRpcResult(id, result);
	}

	/** 处理 notifications/initialized（无返回值）。 */
	private void handleInitialized() {
		initialized = true;
		LOGGER.info("MCP client initialized — session ready");
	}

	/** 处理 tools/list：返回工具定义（在 {@link ToolRegistry} 内缓存）。 */
	private JsonElement handleToolsList(JsonElement id) {
		JsonObject result = new JsonObject();
		result.add("tools", ToolRegistry.definitions());
		return jsonRpcResult(id, result);
	}

	/** 处理 tools/call。 */
	private JsonElement handleToolsCall(JsonElement params, JsonElement id) {
		if (params == null || !params.isJsonObject()) {
			return jsonRpcError(id, -32602, "Invalid params: object required");
		}
		JsonObject paramsObj = params.getAsJsonObject();
		if (!paramsObj.has("name") || !paramsObj.get("name").isJsonPrimitive()) {
			return jsonRpcError(id, -32602, "Invalid params: 'name' is required");
		}
		String toolName = paramsObj.get("name").getAsString();
		JsonObject arguments = paramsObj.has("arguments") && paramsObj.get("arguments").isJsonObject()
				? paramsObj.get("arguments").getAsJsonObject()
				: new JsonObject();

		switch (toolName) {
			case "mc_command": {
				McCommandTool.Report report = McCommandTool.execute(arguments);
				return toolResult(id, report.text(), false, structuredCommandReport(report));
			}
			default:
				return toolResult(id, "[MCPCommand] Unknown tool: " + toolName, true, null);
		}
	}

	/** 批量/单条命令的结构化结果（文本之外再给一份机器可读的）。 */
	private static JsonObject structuredCommandReport(McCommandTool.Report report) {
		JsonObject structured = new JsonObject();
		JsonArray results = new JsonArray();
		for (McCommandTool.CommandResult r : report.results()) {
			JsonObject item = new JsonObject();
			item.addProperty("index", r.index());
			item.addProperty("command", r.command());
			item.addProperty("status", r.status());
			item.addProperty("lines", r.lines());
			item.addProperty("duplicates_dropped", r.duplicatesDropped());
			item.addProperty("late_dropped", r.lateDropped());
			item.addProperty("output", r.output());
			item.addProperty("messages", r.lines());     // 兼容旧字段名，语义：捕获到的行数
			if (r.queriedAt() != null) {
				item.addProperty("queried_at", r.queriedAt());   // 相对时间的换算参考点
			}
			if (r.diagnosis() != null) {
				item.addProperty("diagnosis", r.diagnosis());
			}
			results.add(item);
		}
		structured.add("results", results);
		JsonObject summary = new JsonObject();
		summary.addProperty("ok", report.ok());
		summary.addProperty("failed", report.failed());
		summary.addProperty("skipped", report.skipped());
		structured.add("summary", summary);
		if (report.hint() != null) {
			structured.addProperty("hint", report.hint());
		}
		return structured;
	}

	/** 按 MCP 规范包装 tools/call 的返回：{@code content[]} + 可选 {@code isError}/{@code structuredContent}。 */
	private static JsonElement toolResult(JsonElement id, String text, boolean isError, JsonObject structured) {
		JsonArray content = new JsonArray();
		JsonObject textItem = new JsonObject();
		textItem.addProperty("type", "text");
		textItem.addProperty("text", text);
		content.add(textItem);
		JsonObject result = new JsonObject();
		result.add("content", content);
		if (isError) {
			result.addProperty("isError", true);
		}
		if (structured != null) {
			result.add("structuredContent", structured);
		}
		return jsonRpcResult(id, result);
	}

	/** 组装 JSON-RPC 2.0 成功响应。 */
	static JsonObject jsonRpcResult(JsonElement id, JsonElement result) {
		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", id);
		resp.add("result", result);
		return resp;
	}

	/**
	 * 组装 JSON-RPC 2.0 错误响应（code/message 按规范取值）。
	 *
	 * <p>这里只回**标准错误码 + 通用描述**，内部异常细节只写日志，不回给客户端。
	 */
	static JsonObject jsonRpcError(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", id);
		resp.add("error", err);
		return resp;
	}
}
