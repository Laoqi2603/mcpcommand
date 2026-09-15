package laoqi.mcpcommand;

import laoqi.mcpcommand.mcp.McpHttpServer;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口：负责 MCP HTTP 服务的启停与生命周期钩子。
 *
 * <p>关键点：<b>服务生命周期 = 客户端进程生命周期</b>，游戏一退端口立即消失。
 * <ul>
 *   <li>{@code CLIENT_STARTED} → 绑定 {@code 127.0.0.1}{@link McpHttpServer#PORT}</li>
 *   <li>{@code CLIENT_STOPPING} → 停止服务</li>
 *   <li>集成服务器（单人/LAN 世界）启停 → 只影响 {@code /health} 的 {@code server_available}，
 *       连专用服务器时它一直是 false，但命令执行照常可用</li>
 * </ul>
 */
public class MCPCommandClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(MCPCommand.MOD_ID);
	private final McpHttpServer mcpServer = new McpHttpServer();

	@Override
	public void onInitializeClient() {
		LOGGER.info("MCPCommand client initializing — registering lifecycle hooks");
		ClientLifecycleEvents.CLIENT_STARTED.register((ClientLifecycleEvents.ClientStarted) (client) -> {
			LOGGER.info("Client started — launching MCP HTTP server");

			try {
				mcpServer.start();
			} catch (IOException e) {
				LOGGER.error("Failed to start MCP HTTP server on port {}", McpHttpServer.PORT, e);
				LOGGER.error("Is another instance of Minecraft (or another program) already using this port?");
			}

		});
		ClientLifecycleEvents.CLIENT_STOPPING.register((ClientLifecycleEvents.ClientStopping) (client) -> {
			LOGGER.info("Client stopping — shutting down MCP HTTP server");
			mcpServer.stop();
		});
		ServerLifecycleEvents.SERVER_STARTED.register((ServerLifecycleEvents.ServerStarted) (server) -> {
			LOGGER.info("Integrated server started — MCP command execution available");
			mcpServer.setActiveServer(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register((ServerLifecycleEvents.ServerStopped) (server) -> {
			LOGGER.info("Integrated server stopped — MCP command execution paused");
			mcpServer.clearActiveServer();
		});
		LOGGER.info("MCPCommand client lifecycle hooks registered");
	}
}
