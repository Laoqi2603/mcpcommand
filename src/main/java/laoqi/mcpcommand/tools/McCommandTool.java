package laoqi.mcpcommand.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import laoqi.mcpcommand.capture.MessageFilter;
import laoqi.mcpcommand.capture.OutputCapture;
import net.minecraft.class_310;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code mc_command} 工具：把命令以本地玩家身份发到聊天栏，并把回显抓回来。
 *
 * <p>几点说明：
 * <ul>
 *   <li>捕获走会话化 + 身份感知去重 + 冷却隔离（见 {@link OutputCapture}）；</li>
 *   <li>静默窗口与超时可配：参数 {@code quiet_ms} / {@code timeout_ms}；</li>
 *   <li>输出结构化结果：每条命令的 status/lines/output + 汇总；</li>
 *   <li>识别"服务端不接受该指令"并给出 hint（可用指令集取决于目标服务端）；</li>
 *   <li>可选剥离 {@code §} 颜色码：参数 {@code strip_codes}；</li>
 *   <li>可选过滤玩家聊天：参数 {@code filter_chat}，默认开。</li>
 * </ul>
 */
public final class McCommandTool {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:mcp");
	/** 默认静默窗口：输出这么久没变化即认为命令结束 */
	public static final long DEFAULT_QUIET_MS = 250L;
	/** 默认单条超时 */
	public static final long DEFAULT_TIMEOUT_MS = 5000L;
	private static final long MIN_QUIET_MS = 50L;
	private static final long MAX_QUIET_MS = 10_000L;
	private static final long MIN_TIMEOUT_MS = 500L;
	private static final long MAX_TIMEOUT_MS = 120_000L;
	/** 批量总超时 */
	private static final long BATCH_TIMEOUT_MS = 60_000L;

	/**
	 * 服务端"不认识这条指令/没权限"的典型回显。命中时在结构化结果里给 hint，
	 * 不改动返回文本本身（避免破坏上层对文本的解析）。
	 */
	private static final List<String> REJECTION_MARKERS = List.of(
			"unknown or incomplete command", "未知或不完整的命令",
			"unknown command", "未知的命令",
			"you do not have permission", "你没有权限", "权限不足",
			"incorrect argument", "参数不正确");

	private McCommandTool() {
	}

	/** 单条命令的执行结果（结构化输出用） */
	public record CommandResult(int index, String command, String status, int lines,
								int duplicatesDropped, int lateDropped, String output, String diagnosis,
								String queriedAt) {
		/**
		 * 兼容构造器：不带 queried_at 的调用点（错误/跳过等）走这里。
		 *
		 * @param queriedAt 该命令**抓取时刻**的 ISO-8601 时间戳。输出里的相对时间
		 *                  （{@code 9.04/d 前}）是"渲染那一刻"算出来的，只有拿到这个参考时刻，
		 *                  下游才能把相对时间换算成绝对时间。
		 */
		CommandResult(int index, String command, String status, int lines,
					  int duplicatesDropped, int lateDropped, String output, String diagnosis) {
			this(index, command, status, lines, duplicatesDropped, lateDropped, output, diagnosis, null);
		}
	}

	/** 一次 mc_command 调用的完整报告 */
	public record Report(String text, List<CommandResult> results, int ok, int failed, int skipped, String hint) {
	}

	/** 解析后的调用参数 */
	private record Options(long quietMs, long timeoutMs, boolean filterChat, boolean stripCodes, boolean resolveItemIds,
						   java.util.regex.Pattern stopPattern, boolean hoverHints) {
	}

	private static Options parseOptions(JsonObject arguments) {
		long quiet = clamp(longArg(arguments, "quiet_ms", DEFAULT_QUIET_MS), MIN_QUIET_MS, MAX_QUIET_MS);
		long timeout = clamp(longArg(arguments, "timeout_ms", DEFAULT_TIMEOUT_MS), MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
		boolean filter = boolArg(arguments, "filter_chat", true);
		boolean strip = boolArg(arguments, "strip_codes", false);
		boolean resolveItems = boolArg(arguments, "resolve_item_ids", false);
		java.util.regex.Pattern stop = null;
		try {
			if (arguments.has("stop_regex") && arguments.get("stop_regex").isJsonPrimitive()) {
				String regex = arguments.get("stop_regex").getAsString();
				if (!regex.isBlank()) {
					stop = java.util.regex.Pattern.compile(regex);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("invalid stop_regex ignored: {}", e.getMessage());
		}
		boolean hoverHints = boolArg(arguments, "hover_hints", true);
		return new Options(quiet, timeout, filter, strip, resolveItems, stop, hoverHints);
	}

	private static long longArg(JsonObject o, String name, long def) {
		try {
			if (o.has(name) && o.get(name).isJsonPrimitive()) {
				return o.get(name).getAsLong();
			}
		} catch (Exception ignored) {
		}
		return def;
	}

	private static boolean boolArg(JsonObject o, String name, boolean def) {
		try {
			if (o.has(name) && o.get(name).isJsonPrimitive()) {
				return o.get(name).getAsBoolean();
			}
		} catch (Exception ignored) {
		}
		return def;
	}

	private static long clamp(long v, long lo, long hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** 工具入口：优先 commands 数组，其次单个 command。 */
	public static Report execute(JsonObject arguments) {
		Options opts = parseOptions(arguments);
		if (arguments.has("commands") && arguments.get("commands").isJsonArray()) {
			return executeBatch(arguments.getAsJsonArray("commands"), opts);
		}
		if (arguments.has("command") && arguments.get("command").isJsonPrimitive()) {
			String raw = arguments.get("command").getAsString().trim();
			if (raw.isEmpty()) {
				return new Report("Error: 'command' must not be empty", List.of(), 0, 0, 0, null);
			}
			CommandResult r = executeOne(raw, 1, opts);
			boolean accepted = "ok".equals(r.status()) || "timeout".equals(r.status());
			return new Report(r.output(), List.of(r), accepted ? 1 : 0, accepted ? 0 : 1, 0, hintFor(r));
		}
		return new Report("Error: missing required parameter 'command' (string) or 'commands' (array)",
				List.of(), 0, 0, 0, null);
	}

	/**
	 * 执行单条命令：以本地玩家身份在聊天栏发送，然后轮询捕获回显。
	 *
	 * <p>intermediary 名对照：{@code class_310}=Minecraft、{@code field_1724}=player、
	 * {@code field_3944}=connection、{@code method_45730}=sendCommand。
	 */
	private static CommandResult executeOne(String rawCommand, int index, Options opts) {
		class_310 client = class_310.method_1551();
		if (client.field_1724 == null) {
			return new CommandResult(index, rawCommand, "error", 0, 0, 0, "Error: not connected to a server", null);
		}
		String command = rawCommand.startsWith("/") ? rawCommand.substring(1).trim() : rawCommand;
		if (command.isEmpty()) {
			return new CommandResult(index, rawCommand, "error", 0, 0, 0, "Error: empty command after stripping slash", null);
		}

		LOGGER.debug("Executing command via chat: /{}", command);
		// 记录抓取时刻：相对时间是按“渲染那一刻”算的，下游需要这个参考点才能换算绝对时间
		String queriedAt = java.time.Instant.now().toString();
		// 单行 hover（绝对时间戳就在这里）是否附加，按本次调用参数设置
		laoqi.mcpcommand.capture.HoverExtractor.setCaptureSingleLineHover(opts.hoverHints());
		OutputCapture.Session session;
		try {
			session = OutputCapture.begin();
		} catch (IllegalStateException e) {
			return new CommandResult(index, rawCommand, "error", 0, 0, 0, "Error: " + e.getMessage(), null);
		}

		try {
			client.execute(() -> client.field_1724.field_3944.method_45730(command));
			OutputCapture.Stats stats = session.poll(opts.quietMs(), opts.timeoutMs(), opts.stopPattern());
			String output = stats.text();
			if (opts.stripCodes()) {
				output = MessageFilter.stripCodes(output);
			}
			if (opts.resolveItemIds()) {
				// 把物品行里的 ID 换成本地化物品名
				output = laoqi.mcpcommand.capture.ItemNameResolver.resolve(output);
			}
			int lines = output.isEmpty() ? 0 : output.split("\n", -1).length;

			// 分清三种"没有正常输出"的情况：
			//   incomplete = 一行真结果都没有（只收到占位/页脚）→ 数据不完整，建议提高 timeout_ms / 用 stop_regex
			//   timeout    = 窗口内一条消息都没收到
			//   ok + 说明   = 命令本身就静默（正常，不是丢数据）
			if (stats.placeholderOnly()) {
				return new CommandResult(index, rawCommand, "incomplete", lines, stats.duplicates(),
						stats.lateDropped(), output,
						"只收到进行中占位/页脚（如“正在搜索，请稍等…”），真实结果未在 timeout_ms=" + opts.timeoutMs()
								+ " 内到达 → 建议提高 timeout_ms，或用 stop_regex 指定明确结束标志后重试", queriedAt);
			}
			if (output.isEmpty()) {
				if (stats.timedOut()) {
					String msg = "Command executed: /" + command + "\n(No output captured within "
							+ opts.timeoutMs() + "ms — timed out)";
					return new CommandResult(index, rawCommand, "timeout", 0, stats.duplicates(),
							stats.lateDropped(), msg,
							"计时窗口内没有捕获到任何输出：命令可能本来就没有反馈，也可能是窗口太短", queriedAt);
				}
				String msg = "Command executed: /" + command + "\n(No output — command produced no feedback)";
				return new CommandResult(index, rawCommand, "ok", 0, stats.duplicates(), stats.lateDropped(),
						msg, "命令已静默收口且没有任何反馈 —— 这是“命令本身没有输出”，不是丢数据", queriedAt);
			}
			return new CommandResult(index, rawCommand, "ok", lines, stats.duplicates(), stats.lateDropped(),
					output, null, queriedAt);
		} catch (Exception e) {
			session.finish();
			LOGGER.warn("Command failed: /{}", command, e);
			return new CommandResult(index, rawCommand, "error", 0, 0, 0, "Command failed: " + e.getMessage(), null);
		}
	}

	/**
	 * 顺序执行 {@code commands} 数组并汇总报告。
	 *
	 * <p>注意：每条命令各自拥有捕获会话（互不串扰），
	 * 但整批共享 {@value #BATCH_TIMEOUT_MS}ms 的总超时。
	 */
	private static Report executeBatch(JsonArray commands, Options opts) {
		if (commands.isEmpty()) {
			return new Report("Error: 'commands' array must not be empty", List.of(), 0, 0, 0, null);
		}
		int total = commands.size();
		int ok = 0;
		int failed = 0;
		StringBuilder report = new StringBuilder();
		List<CommandResult> results = new ArrayList<>(total);
		long batchStarted = System.currentTimeMillis();
		String hint = null;

		for (int i = 0; i < total; ++i) {
			if (System.currentTimeMillis() - batchStarted >= BATCH_TIMEOUT_MS) {
				report.append("\n[BATCH TIMEOUT] Stopped after ").append(i).append(" of ").append(total)
						.append(" commands (60s elapsed)");
				break;
			}

			JsonElement elem = commands.get(i);
			if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
				String rawCmd = elem.getAsString().trim();
				if (rawCmd.isEmpty()) {
					report.append("/[").append(i + 1).append("] (skipped — empty)\n");
					results.add(new CommandResult(i + 1, "", "skipped", 0, 0, 0, "", null));
					++failed;
					continue;
				}
				CommandResult r = executeOne(rawCmd, i + 1, opts);
				results.add(r);
				report.append("/[").append(i + 1).append("] ").append(rawCmd).append('\n');
				report.append(r.output()).append('\n');
				if ("ok".equals(r.status()) || "timeout".equals(r.status())) {
					++ok;
				} else {
					++failed;
				}
				if (hint == null) {
					hint = hintFor(r);
				}
			} else {
				report.append("/[").append(i + 1).append("] (skipped — not a string)\n");
				results.add(new CommandResult(i + 1, "", "skipped", 0, 0, 0, "", null));
				++failed;
			}
		}

		int skipped = total - ok - failed;
		report.append("\n─── ").append(ok).append(" ok, ").append(failed).append(" failed, ")
				.append(skipped).append(" skipped");
		return new Report(report.toString(), results, ok, failed, skipped, hint);
	}

	/**
	 * 识别"服务端不接受该指令"并给出提示（不改动返回文本）。
	 */
	private static String hintFor(CommandResult r) {
		String out = r.output() == null ? "" : r.output().toLowerCase(Locale.ROOT);
		for (String marker : REJECTION_MARKERS) {
			if (out.contains(marker)) {
				return "目标服务端未接受该指令（未知指令或权限不足）。可用指令集取决于服务端："
						+ "单人世界的集成服务端不注册 /stop 这类专用服务端指令，多人服务器则受 OP/权限节点限制。";
			}
		}
		return null;
	}
}
