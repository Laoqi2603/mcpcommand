package laoqi.mcpcommand.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地 MCP HTTP 服务（用 JDK 自带的 {@code com.sun.net.httpserver}，无第三方依赖）。
 *
 * <p>只绑定回环地址，提供两个端点：
 * <ul>
 *   <li>{@code POST /mcp} → JSON-RPC，交给 {@link McpProtocolHandler}</li>
 *   <li>{@code GET /health} → {@code {"status":"ok","server_available":<集成服务器是否存在>}}</li>
 * </ul>
 */
public final class McpHttpServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:mcp");
	/**
	 * 监听端口；被占用时启动失败并打日志（不是致命错误）。
	 *
	 * <p>可用 {@code -Dmcpcommand.port=25889} 覆盖，便于「生产客户端 + 测试客户端」并行调试
	 * （开发客户端：{@code gradlew runClient -Pport=25889}）。
	 */
	public static final int PORT = Integer.getInteger("mcpcommand.port", 25888);
	/** HttpServer 的 backlog，0 = 用系统默认。 */
	private static final int BACKLOG = 0;
	private static final Gson GSON = new Gson();
	private HttpServer server;
	/** 固定 4 线程池：工具调用会阻塞等待命令回显，所以不能用单线程。 */
	private final ExecutorService httpThreadPool = Executors.newFixedThreadPool(4, (r) -> {
		Thread t = new Thread(r, "mcpcommand-http");
		t.setDaemon(true);                    // 守护线程，不阻止客户端退出
		return t;
	});
	/** 与 {@link McpProtocolHandler} 共享的集成服务器引用；连专用服务器时始终为 null。 */
	private final AtomicReference<MinecraftServer> activeServer = new AtomicReference<>(null);
	private McpProtocolHandler protocolHandler;
	/** 当前 MCP 会话 id（initialize 时下发，DELETE 时清除） */
	private volatile String sessionId;

	/** 启动服务；重复调用只告警不重启。 */
	public void start() throws IOException {
		if (server != null) {
			LOGGER.warn("MCP HTTP Server already running — ignoring duplicate start");
		} else {
			protocolHandler = new McpProtocolHandler(activeServer);
			InetSocketAddress addr = new InetSocketAddress("127.0.0.1", PORT);
			server = HttpServer.create(addr, BACKLOG);
			server.createContext("/mcp", this::handleMcp);
			server.createContext("/health", this::handleHealth);
			server.setExecutor(httpThreadPool);
			server.start();
			LOGGER.info("MCP HTTP Server started on http://127.0.0.1:{}/ (mcp + health)", PORT);
		}
	}

	/** 停止服务并释放线程池（客户端退出时调用）。 */
	public void stop() {
		if (server != null) {
			server.stop(1);                   // 最多等 1 秒处理完在途请求
			httpThreadPool.shutdown();
			server = null;
			protocolHandler = null;
			LOGGER.info("MCP HTTP Server stopped");
		}
	}

	/** 记录集成服务器（单人/LAN 世界启动时）——仅影响 {@code /health} 的 server_available。 */
	public void setActiveServer(MinecraftServer mcServer) {
		activeServer.set(mcServer);
		LOGGER.info("Integrated server available — MCP command execution enabled");
	}

	/** 集成服务器关闭（退出世界）。命令执行不受影响，只是 {@code /health} 会显示 false。 */
	public void clearActiveServer() {
		activeServer.set(null);
		LOGGER.info("Integrated server gone — MCP command execution disabled");
	}

	/** {@code GET /health}：只反映集成服务器是否存在。 */
	private void handleHealth(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendPlain(exchange, 405, "Method Not Allowed — use GET");
		} else {
			boolean serverAvailable = activeServer.get() != null;
			JsonObject body = new JsonObject();
			body.addProperty("status", "ok");
			body.addProperty("server_available", serverAvailable);
			sendJson(exchange, 200, GSON.toJson(body));
		}
	}

	/** 会话/协议版本头（MCP Streamable HTTP） */
	private static final String SESSION_HEADER = "Mcp-Session-Id";
	private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

	/**
	 * {@code POST /mcp}：JSON-RPC 入口（同时实现 MCP Streamable HTTP 的会话语义）。
	 *
	 * <p>合规要点：
	 * <ul>
	 *   <li>initialize 的响应带 {@code Mcp-Session-Id}；后续请求带上的会话 id 不匹配则回 <b>404</b>；</li>
	 *   <li>{@code GET /mcp}：本服务不提供服务端主动推送的 SSE 流 → 按规范回 <b>405</b> 并带 {@code Allow}；</li>
	 *   <li>{@code DELETE /mcp}：客户端结束会话 → 重置状态并回 <b>204</b>；</li>
	 *   <li>{@code MCP-Protocol-Version} 头不匹配 → 回 <b>400</b>；</li>
	 *   <li>纯通知（无 id）→ <b>202</b> 无正文。</li>
	 * </ul>
	 *
	 * <p>只有 {@code tools/call(mc_command)} 留在 HTTP 线程（它要阻塞几秒等命令回显），
	 * 其余方法都丢到客户端主线程执行。
	 */
	private void handleMcp(HttpExchange exchange) throws IOException {
		String httpMethod = exchange.getRequestMethod();
		if ("GET".equalsIgnoreCase(httpMethod)) {
			exchange.getResponseHeaders().set("Allow", "POST, DELETE");
			sendPlain(exchange, 405, "Method Not Allowed — no server-initiated SSE stream; use POST");
			return;
		}
		if ("DELETE".equalsIgnoreCase(httpMethod)) {
			protocolHandler.resetSession();
			sessionId = null;
			exchange.sendResponseHeaders(204, -1L);
			LOGGER.info("MCP session terminated by client (DELETE /mcp)");
			return;
		}
		if (!"POST".equalsIgnoreCase(httpMethod)) {
			exchange.getResponseHeaders().set("Allow", "POST, DELETE");
			sendPlain(exchange, 405, "Method Not Allowed — use POST");
			return;
		}

		// 会话校验：带了会话 id 但不匹配 → 404（规范要求）；没带则宽松接受，兼容简单客户端
		String currentSession = sessionId;
		String providedSession = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
		if (currentSession != null && providedSession != null && !providedSession.isEmpty()
				&& !currentSession.equals(providedSession)) {
			LOGGER.warn("MCP request with unknown session id — rejecting with 404");
			sendJsonRpcError(exchange, JsonNull.INSTANCE, -32001, "Session not found: unknown Mcp-Session-Id", 404);
			return;
		}
		// 协议版本头（客户端 SHOULD 带上）
		String providedVersion = exchange.getRequestHeaders().getFirst(PROTOCOL_HEADER);
		if (providedVersion != null && !McpProtocolHandler.PROTOCOL_VERSION.equals(providedVersion)) {
			sendJsonRpcError(exchange, JsonNull.INSTANCE, -32600,
					"Unsupported protocol version: " + providedVersion, 400);
			return;
		}

		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		if (contentType != null && !contentType.toLowerCase().contains("application/json")) {
			LOGGER.debug("Unexpected Content-Type: {} — processing anyway", contentType);
		}

		JsonObject requestBody;
		try {
			requestBody = JsonParser.parseReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IllegalStateException | JsonSyntaxException e) {
			LOGGER.warn("Failed to parse MCP request body", e);
			// 只回标准错误码 + 通用描述，异常细节只进日志
			sendJsonRpcError(exchange, JsonNull.INSTANCE, -32700, "Parse error: invalid JSON request body");
			return;
		}

		String jsonrpc = requestBody.has("jsonrpc") ? requestBody.get("jsonrpc").getAsString() : null;
		if (!"2.0".equals(jsonrpc)) {
			sendJsonRpcError(exchange, requestBody.get("id"), -32600, "Invalid Request: jsonrpc must be \"2.0\"");
			return;
		}

		boolean isNotification = !requestBody.has("id") || requestBody.get("id").isJsonNull();
		JsonElement id = requestBody.has("id") ? requestBody.get("id") : JsonNull.INSTANCE;
		if (!requestBody.has("method") || !requestBody.get("method").isJsonPrimitive()) {
			sendJsonRpcError(exchange, id, -32600, "Invalid Request: missing or invalid 'method'");
			return;
		}

		String method = requestBody.get("method").getAsString();
		JsonElement params = requestBody.has("params") ? requestBody.get("params") : null;

		// initialize 时下发会话 id（Streamable HTTP）
		if ("initialize".equals(method)) {
			String newSession = java.util.UUID.randomUUID().toString();
			sessionId = newSession;
			exchange.getResponseHeaders().set(SESSION_HEADER, newSession);
			LOGGER.debug("MCP session created: {}", newSession);
		}

		try {
			// 全部在 HTTP 线程上分发：需要碰游戏状态的工具自己会用 MainThreadExecutor 回主线程。
			// 若在这里就把方法整体投递到主线程，工具内部再调 submitAndWait 会自我死锁。
			JsonElement response = protocolHandler.dispatch(method, params, id);

			if (!isNotification && response != null) {
				sendJson(exchange, 200, GSON.toJson(response));
			} else {
				exchange.sendResponseHeaders(202, -1L);   // 通知类请求：202 无正文
			}
		} catch (Exception e) {
			LOGGER.error("MCP dispatch failed for method '{}'", method, e);
			sendJsonRpcError(exchange, id, -32603, "Internal error");
		}
	}

	private void sendJsonRpcError(HttpExchange exchange, JsonElement id, int code, String message) throws IOException {
		sendJsonRpcError(exchange, id, code, message, 200);
	}

	/**
	 * 回 JSON-RPC 错误，并指定 HTTP 状态码。
	 *
	 * <p>Streamable HTTP 要求：未知会话 → <b>404</b>；不支持的协议版本 → <b>400</b>；
	 * 普通协议错误才是 200 + error 对象。
	 */
	private void sendJsonRpcError(HttpExchange exchange, JsonElement id, int code, String message, int httpStatus)
			throws IOException {
		JsonObject errorResponse = McpProtocolHandler.jsonRpcError(id, code, message);
		sendJson(exchange, httpStatus, GSON.toJson(errorResponse));
	}

	/** 回 JSON（UTF-8，200 语义由状态码决定）。 */
	private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStreamWriter writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
			writer.write(json);
			writer.flush();
		}
	}

	/** 回纯文本（用于 405 之类）。 */
	private void sendPlain(HttpExchange exchange, int status, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStreamWriter writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
			writer.write(text);
			writer.flush();
		}
	}
}
