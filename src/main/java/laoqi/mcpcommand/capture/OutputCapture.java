package laoqi.mcpcommand.capture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.class_2561;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命令回显捕获：一次命令 = 一个 {@link Session}。
 *
 * <p>几条关键设计：
 * <ol>
 *   <li><b>身份感知去重</b>：只在「<b>另一条通道</b>在 {@value #DUP_WINDOW_MS}ms 内送来<b>同一文本</b>」时
 *       才判为重复并丢弃 —— 同一条消息确实会经 ChatHud 与网络包两条通道各到一次。
 *       判据是"它是不是同一条消息的另一通道副本"，<b>不是</b>"这段文本是否出现过"，
 *       所以重复坐标行、1 秒内两笔同文本交易都会<b>各自保留</b>。</li>
 *   <li><b>会话化</b>：缓冲与去重状态都在 Session 实例里，不全局共享。</li>
 *   <li><b>串行化</b>：同一时刻只允许一条命令在飞，其余排队等待（最多 {@value #LOCK_WAIT_MS}ms）。</li>
 *   <li><b>冷却隔离</b>：窗口关闭后 {@value #COOLDOWN_MS}ms 内到达的消息直接丢弃，
 *       不会落进下一条命令的缓冲。</li>
 *   <li><b>可观测</b>：每次捕获都返回统计（行数、跨通道去重数、被过滤的玩家聊天数、迟到丢弃数）。</li>
 * </ol>
 */
public final class OutputCapture {
	/** 消息来源通道 */
	public enum Channel {
		/** 聊天栏渲染路径（ChatHudMixin） */
		CHAT_HUD,
		/** 网络包路径（ClientPlayNetworkHandlerMixin） */
		NETWORK
	}

	/**
	 * 跨通道判重窗口（毫秒）。
	 *
	 * <p>取 2 秒而不是几百毫秒：两条通道的送达间隔可能达到数百毫秒（客户端忙/消息排队时更久），
	 * 窗口太小会把同一条消息当成两条重复输出。放大窗口没有副作用，因为这里是**成对消费**：
	 * 每条网络消息只会匹配掉它自己的那条聊天栏消息，所以"同一文本真实出现两次"不会被误吞
	 * （每次出现的两条通道副本各自配对）。
	 */
	private static final long DUP_WINDOW_MS = 2000L;
	/** 捕获窗口关闭后的冷却时间（毫秒）：这期间的消息视为"迟到输出"直接丢弃 */
	public static final long COOLDOWN_MS = 1500L;
	/** 同一时刻只允许一条命令在飞；等待上限 */
	private static final long LOCK_WAIT_MS = 30_000L;
	/** 捕获轮询间隔 */
	public static final long POLL_INTERVAL_MS = 50L;
	/**
	 * 「进行中占位」时使用的静默窗口下限（毫秒）。
	 * 插件先回"正在搜索，请稍等..."再出结果时，普通静默窗口会把整页丢掉（实测 551 页里丢过 6 页）。
	 */
	private static final long IN_PROGRESS_QUIET_MS = 1500L;
	/** 进行中占位的特征词（中英双语；插件输出跟随客户端语言） */
	private static final java.util.List<String> IN_PROGRESS_MARKERS = java.util.List.of(
			"searching", "正在搜索", "please wait", "请稍等", "loading", "正在加载");
	/**
	 * 分页页脚（`Page 1/6` / `第 1/6 页` / `1/6 页`）—— 也不是数据行。
	 * 中英两种语序都要覆盖（"页"在数字前或后），只写 `页\s*\d+/\d+` 会漏掉 `第 1/6 页`。
	 */
	private static final java.util.regex.Pattern PAGE_FOOTER =
			java.util.regex.Pattern.compile("(?i)(page\\s*\\d+\\s*/\\s*\\d+|\\d+\\s*/\\s*\\d+\\s*页)");

	private static final ReentrantLock COMMAND_LOCK = new ReentrantLock(true);
	private static final AtomicInteger LATE_DROPPED = new AtomicInteger();
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:capture");
	private static volatile Session current;
	private static volatile long cooldownUntil;

	private OutputCapture() {
	}

	/** 开始一次捕获（拿不到"命令锁"说明已有命令在飞）。 */
	public static Session begin() throws IllegalStateException {
		boolean locked;
		try {
			locked = COMMAND_LOCK.tryLock(LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the command slot");
		}
		if (!locked) {
			throw new IllegalStateException("another command is still in flight (waited " + LOCK_WAIT_MS + "ms)");
		}
		Session session = new Session(LATE_DROPPED.getAndSet(0));
		current = session;
		return session;
	}

	/** 距离冷却结束还有多少毫秒（0 = 不在冷却期）。 */
	public static long cooldownRemainingMs() {
		return Math.max(0L, cooldownUntil - System.currentTimeMillis());
	}

	/**
	 * 两个 mixin 的入口：把一条游戏消息投给当前会话。
	 *
	 * @param playerChat 是否已判定为玩家聊天（命中则直接丢弃，见 {@link MessageFilter}）
	 */
	public static void accept(Channel channel, class_2561 message, boolean playerChat) {
		try {
			Session session = current;
			if (session != null) {
				session.offer(channel, message, playerChat);
				return;
			}
			if (System.currentTimeMillis() < cooldownUntil) {
				LATE_DROPPED.incrementAndGet();     // 迟到输出：丢弃并计数
			}
		} catch (Exception ignored) {
			// 捕获路径绝不能把异常抛回游戏主线程
		}
	}

	/**
	 * 一次捕获的统计结果。
	 *
	 * @param messages        实际写入缓冲的消息条数（0 表示这条命令一条反馈都没收到）
	 * @param placeholderOnly 最终文本**全部**是"非数据行"（进行中占位 / 分页页脚），即一条真结果都没有——
	 *                        说明命令有输出但**没在窗口内到达**，属于数据不完整，而不是"命令无输出"
	 * @param timedOut        是否因超时结束（false = 输出静默后自然收口）
	 */
	public record Stats(String text, int lines, int duplicates, int filteredPlayerChat, int lateDropped,
						int messages, boolean placeholderOnly, long elapsedMs, boolean timedOut) {
	}

	/** 一次命令的捕获会话（只有一条命令在飞，故实例内部同步足够）。 */
	public static final class Session {
		private final StringBuilder buffer = new StringBuilder();
		private final Deque<Entry> fromChatHud = new ArrayDeque<>();
		private final Deque<Entry> fromNetwork = new ArrayDeque<>();
		private final int inheritedLateDropped;
		private int duplicates;
		private int filtered;
		private int messages;
		private boolean finished;

		private Session(int inheritedLateDropped) {
			this.inheritedLateDropped = inheritedLateDropped;
		}

		private synchronized void offer(Channel channel, class_2561 message, boolean playerChat) {
			if (finished) {
				return;
			}
			if (playerChat) {
				filtered++;
				LOGGER.debug("[capture] {} filtered as player chat: {}", channel, message.getString());
				return;
			}
			String text = HoverExtractor.enrich(message);
			if (text == null || text.isBlank()) {
				return;
			}
			long now = System.currentTimeMillis();
			Deque<Entry> mine = channel == Channel.CHAT_HUD ? fromChatHud : fromNetwork;
			Deque<Entry> other = channel == Channel.CHAT_HUD ? fromNetwork : fromChatHud;

			prune(mine, now);
			prune(other, now);

			// 仅当"另一条通道刚刚送来过同一文本"时才算重复（并消费掉那条记录）
			for (java.util.Iterator<Entry> it = other.iterator(); it.hasNext(); ) {
				Entry e = it.next();
				if (e.text.equals(text)) {
					it.remove();
					duplicates++;
					LOGGER.debug("[capture] {} dropped cross-channel duplicate: {}", channel, text);
					return;
				}
			}

			if (!buffer.isEmpty()) {
				buffer.append('\n');
			}
			buffer.append(text);
			messages++;
			mine.addLast(new Entry(text, now));
			LOGGER.debug("[capture] {} appended (pending: this={}, other={}): {}", channel, mine.size(), other.size(), text);
		}

		private static void prune(Deque<Entry> deque, long now) {
			while (!deque.isEmpty() && now - deque.peekFirst().time > DUP_WINDOW_MS) {
				deque.pollFirst();
			}
		}

		/** 当前缓冲内容（trim 后）。 */
		public synchronized String snapshot() {
			return buffer.toString().trim();
		}

		/**
		 * 轮询等待输出结束：内容 {@code quietMs} 不变即认为结束，最多等 {@code maxMs}。
		 *
		 * <p><b>进行中占位保护</b>：有些插件先回一句"正在搜索，请稍等..."，真正的结果随后才到；
		 * 此时按普通静默窗口收口会**整页丢失**（实测：551 页里有 6 页被这样截断，且每次丢的页不同）。
		 * 所以只要缓冲区里**只有占位行**，就把静默窗口放宽到 {@value #IN_PROGRESS_QUIET_MS}ms。
		 *
		 * @param stopPattern 可选的显式结束标志（如 {@code 第 \d+/\d+ 页}）：一旦匹配立即收口，不再等静默
		 */
		public Stats poll(long quietMs, long maxMs, java.util.regex.Pattern stopPattern) {
			long started = System.currentTimeMillis();
			String last = "";
			long lastChange = started;

			while (System.currentTimeMillis() - started < maxMs) {
				String now = snapshot();
				if (!now.equals(last)) {
					last = now;
					lastChange = System.currentTimeMillis();
				} else if (!now.isEmpty()) {
					if (stopPattern != null && stopPattern.matcher(now).find()) {
						return finish(false);           // 显式结束标志：不必再等
					}
					// 内容还很少又含"进行中"字样 = 占位刚落地、结果还在路上 → 放宽静默窗口。
					// 注意这里和 finish() 的 placeholderOnly 判据**故意不同**：这一条宁可多等（保守防截断），
					// 那一条宁可少报（避免把已收口的结论错报成 incomplete），见各自 javadoc。
					long effectiveQuiet = shouldExtendQuiet(now) ? Math.max(quietMs, IN_PROGRESS_QUIET_MS) : quietMs;
					if (System.currentTimeMillis() - lastChange >= effectiveQuiet) {
						return finish(false);
					}
				}

				try {
					Thread.sleep(POLL_INTERVAL_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			return finish(true);
		}

		/**
		 * 当前缓冲是否"只有进行中占位、还没有任何真正结果"——用于 {@link #finish} 判定 {@code incomplete}。
		 *
		 * <p>判据是 **所有非空行都是"非数据行"**（进行中占位 / 分页页脚）。<b>不能</b>用"含关键词且行数少"：
		 * 像 `/co lookup u:<查无此人>` 的正常输出就是「占位行 + 一句『玩家 xxx 未找到。』」两行，
		 * 按"≤2 行且含关键词"会被错报成 {@code incomplete}（叫调用方白白重取）。
		 * 只要还剩任何一行真数据，就是有结论了。
		 */
		private static boolean looksInProgress(String text) {
			if (text.isEmpty()) {
				return false;
			}
			boolean sawAnyLine = false;
			for (String raw : text.split("\n", -1)) {
				String line = raw.trim();
				if (line.isEmpty()) {
					continue;
				}
				sawAnyLine = true;
				if (!isNonDataLine(line)) {
					return false;                       // 有真数据 → 不是"只有占位"
				}
			}
			return sawAnyLine;
		}

		/** 该行是否只是"非数据行"（进行中占位 / 分页页脚），即不承载查询结果。 */
		private static boolean isNonDataLine(String line) {
			String lower = line.toLowerCase(java.util.Locale.ROOT);
			for (String marker : IN_PROGRESS_MARKERS) {
				if (lower.contains(marker)) {
					return true;
				}
			}
			return PAGE_FOOTER.matcher(line).find();
		}

		/**
		 * 轮询时是否要放宽静默窗口：内容很少（≤2 行）且含"进行中"字样。
		 *
		 * <p>故意保留"≤2 行"这个偏保守的口径：占位之后结果还在陆续到达时多等一会儿没有代价，
		 * 而早收口会整页丢失。
		 */
		private static boolean shouldExtendQuiet(String text) {
			if (text.isEmpty() || text.split("\n", -1).length > 2) {
				return false;
			}
			String lower = text.toLowerCase(java.util.Locale.ROOT);
			for (String marker : IN_PROGRESS_MARKERS) {
				if (lower.contains(marker)) {
					return true;
				}
			}
			return false;
		}

		/** 结束捕获并返回统计；可重复调用（第二次返回空结果）。 */
		public synchronized Stats finish(boolean timedOut) {
			if (finished) {
				return new Stats("", 0, duplicates, filtered, inheritedLateDropped, messages, false, 0L, timedOut);
			}
			finished = true;
			current = null;
			cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
			String text = buffer.toString().trim();
			buffer.setLength(0);
			if (COMMAND_LOCK.isHeldByCurrentThread()) {
				COMMAND_LOCK.unlock();
			}
			int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
			// 全是"非数据行"（只有占位/页脚，没有任何真结果）= 有输出但没等到 → 数据不完整
			// （与"命令本来就没输出"区分开；详见 looksInProgress 的 javadoc）
			boolean placeholderOnly = looksInProgress(text);
			return new Stats(text, lines, duplicates, filtered, inheritedLateDropped, messages,
					placeholderOnly, 0L, timedOut);
		}

		/** 便捷方法：立即结束（异常路径用）。 */
		public Stats finish() {
			return finish(false);
		}

		private record Entry(String text, long time) {
		}
	}
}
