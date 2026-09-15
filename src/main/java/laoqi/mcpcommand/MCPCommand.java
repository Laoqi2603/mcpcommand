package laoqi.mcpcommand;

import net.fabricmc.api.ModInitializer;
import net.minecraft.class_2960;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组通用入口（{@code fabric.mod.json} 的 main entrypoint）。
 *
 * <p>本模组的功能全在客户端，真正的初始化在 {@link MCPCommandClient}；这里只提供
 * {@link #MOD_ID} / {@link #LOGGER} 两个全局常量并打一条启动日志。
 */
public class MCPCommand implements ModInitializer {
	/** 模组 ID，必须与 {@code fabric.mod.json} 的 id、资源目录名保持一致。 */
	public static final String MOD_ID = "mcpcommand";
	/** 全局日志器：以模组 ID 命名（Fabric 惯例，便于在日志里区分来源）。 */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 通用入口在「mod 已加载但资源可能尚未就绪」时执行，不要在这里做重活。
		LOGGER.info("MCPCommand common entrypoint ready (mod id={})", MOD_ID);
	}

	/**
	 * 生成命名空间化的 Identifier：{@code mcpcommand:<path>}。
	 * intermediary 名对照：{@code class_2960}=Identifier，{@code method_60655}=of。
	 */
	public static class_2960 id(String path) {
		return class_2960.method_60655(MOD_ID, path);
	}
}
