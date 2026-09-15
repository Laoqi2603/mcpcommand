package laoqi.mcpcommand.mcp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.class_310;

/**
 * 把任务投递到 Minecraft 客户端主线程并（可选）等待结果。
 *
 * <p>MCP 的 HTTP 线程不能直接碰游戏状态，必须回到主线程执行。
 * intermediary 名对照：{@code class_310}=MinecraftClient、{@code method_1551}=getInstance、
 * {@code method_5385}=submit。
 */
public final class MainThreadExecutor {
	/** 默认等待上限；超时抛 {@link TimeoutException}。 */
	private static final long DEFAULT_TIMEOUT_MS = 5000L;

	private MainThreadExecutor() {
		throw new UnsupportedOperationException("Utility class — do not instantiate");
	}

	/** 投递到主线程，不等待结果。 */
	public static <T> CompletableFuture<T> submit(Supplier<T> task) {
		return class_310.method_1551().method_5385(task);
	}

	/** 投递到主线程并等待，超时 {@value #DEFAULT_TIMEOUT_MS} 毫秒。 */
	public static <T> T submitAndWait(Supplier<T> task) throws TimeoutException, ExecutionException, InterruptedException {
		return submit(task).get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
	}

	/** 投递到主线程并等待，自定义超时。 */
	public static <T> T submitAndWait(Supplier<T> task, long timeoutMs) throws TimeoutException, ExecutionException, InterruptedException {
		return submit(task).get(timeoutMs, TimeUnit.MILLISECONDS);
	}
}
