package laoqi.mcpcommand.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import laoqi.mcpcommand.mcp.MainThreadExecutor;
import net.minecraft.class_1297;
import net.minecraft.class_1799;
import net.minecraft.class_310;
import net.minecraft.class_640;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 五个客户端查询工具的实现：{@code mc_players} / {@code mc_position} / {@code mc_world_info} /
 * {@code mc_inventory} / {@code mc_chat}。
 *
 * <p><b>当前不在 {@code tools/list} 中暴露，也不可从 {@code tools/call} 调用</b>
 * （{@link ToolRegistry} 与协议分发里都只注册了 {@code mc_command}）。
 * 实现保留在此备用；要重新启用，需要同时在 {@link ToolRegistry} 加回定义、
 * 并在协议分发里加回对应分支。
 *
 * <p>这些工具都必须在**客户端主线程**上读游戏状态，所以统一走
 * {@link MainThreadExecutor#submitAndWait(java.util.function.Supplier)}。
 *
 * <p>能力边界（如实写进返回文本，避免上层误判）：
 * <ul>
 *   <li>{@code mc_players} 的在线名单来自玩家列表（服务端下发），**坐标只有渲染距离内的玩家才有**
 *       —— 客户端拿不到远处玩家的位置，这是协议限制，不是缺陷；</li>
 *   <li>{@code mc_inventory} 只能读**本地玩家**（或已加载实体）的背包，别人的背包在客户端不存在；</li>
 *   <li>intermediary 名对照集中在各类注释里，便于阅读。</li>
 * </ul>
 */
public final class ClientQueryTools {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcpcommand:mcp");

	private ClientQueryTools() {
	}

	/** 统一返回结构：文本 + 是否错误 + 可选结构化数据。 */
	public record Result(String text, boolean isError, JsonObject structured) {
		static Result ok(String text) {
			return new Result(text, false, null);
		}

		static Result ok(String text, JsonObject structured) {
			return new Result(text, false, structured);
		}

		static Result error(String text) {
			return new Result(text, true, null);
		}
	}

	/** 本地玩家与世界的公共前置检查。 */
	private static String guard() {
		class_310 client = class_310.method_1551();
		if (client.field_1724 == null || client.field_1687 == null) {
			return "Error: not connected to a world";
		}
		return null;
	}

	/** {@code mc_position}：本地玩家坐标 / 维度 / 朝向。 */
	public static Result position(JsonObject args) {
		String problem = guard();
		if (problem != null) {
			return Result.error(problem);
		}
		try {
			return MainThreadExecutor.submitAndWait(() -> {
				class_310 client = class_310.method_1551();
				double x = client.field_1724.method_23317();
				double y = client.field_1724.method_23318();
				double z = client.field_1724.method_23321();
				float yaw = client.field_1724.method_36454();
				float pitch = client.field_1724.method_36455();
				String dimension = client.field_1687.method_27983().method_29177().toString();

				JsonObject structured = new JsonObject();
				structured.addProperty("x", x);
				structured.addProperty("y", y);
				structured.addProperty("z", z);
				structured.addProperty("yaw", yaw);
				structured.addProperty("pitch", pitch);
				structured.addProperty("dimension", dimension);

				StringBuilder sb = new StringBuilder();
				sb.append(String.format("position: x=%.2f y=%.2f z=%.2f%n", x, y, z));
				sb.append("block: ").append((int) Math.floor(x)).append(' ')
						.append((int) Math.floor(y)).append(' ').append((int) Math.floor(z)).append('\n');
				sb.append("dimension: ").append(dimension).append('\n');
				sb.append(String.format("rotation: yaw=%.1f pitch=%.1f", yaw, pitch));
				return Result.ok(sb.toString(), structured);
			});
		} catch (Exception e) {
			LOGGER.warn("mc_position failed", e);
			return Result.error("Error: failed to read position (" + e.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * {@code mc_players}：在线玩家列表。
	 *
	 * <p>intermediary 名对照：{@code class_634}=ClientPacketListener、
	 * {@code method_2880}=getOnlinePlayers、{@code class_640}=PlayerInfo、
	 * {@code method_2966}=getProfile、{@code method_2958}=getGameMode、{@code method_2959}=getLatency；
	 * {@code class_638.field_18226}=ClientLevel.players（本地已加载的玩家实体）。
	 */
	public static Result players(JsonObject args) {
		String problem = guard();
		if (problem != null) {
			return Result.error(problem);
		}
		try {
			return MainThreadExecutor.submitAndWait(() -> {
				class_310 client = class_310.method_1551();
				Collection<?> online = client.field_1724.field_3944.method_2880();

				JsonArray array = new JsonArray();
				StringBuilder sb = new StringBuilder();
				sb.append("online players: ").append(online.size()).append('\n');

				for (Object raw : online) {
					class_640 info = (class_640) raw;
					// authlib 7.x 的 GameProfile 是 record：用 name()/id() 而不是 getName()/getId()
					String name = info.method_2966().name();
					java.util.UUID uuid = info.method_2966().id();
					int ping = info.method_2959();
					String mode = info.method_2958().method_8381();

					// 位置：EntityGetter.getPlayerByUUID（method_18470）只对"本地已加载"的实体返回非空，
					// 也就是渲染距离内的玩家；远处玩家的位置客户端拿不到（协议限制）
					class_1297 entity = uuid == null ? null : client.field_1687.method_18470(uuid);
					JsonObject item = new JsonObject();
					item.addProperty("name", name);
					item.addProperty("uuid", uuid == null ? "" : uuid.toString());
					item.addProperty("ping_ms", ping);
					item.addProperty("gamemode", mode);
					if (entity != null) {
						double ex = entity.method_23317();
						double ey = entity.method_23318();
						double ez = entity.method_23321();
						item.addProperty("x", ex);
						item.addProperty("y", ey);
						item.addProperty("z", ez);
						if (entity instanceof net.minecraft.class_1309 living) {
							item.addProperty("health", living.method_6032());
							item.addProperty("max_health", living.method_6063());
						}
						sb.append(String.format("- %s  %s  ping=%dms  pos=(%.1f, %.1f, %.1f)%n",
								name, mode, ping, ex, ey, ez));
					} else {
						item.addProperty("position_known", false);
						sb.append(String.format("- %s  %s  ping=%dms  pos=(out of range)%n", name, mode, ping));
					}
					array.add(item);
				}
				sb.append("(note: positions are only available for players within render distance)");

				JsonObject structured = new JsonObject();
				structured.add("players", array);
				structured.addProperty("count", array.size());
				return Result.ok(sb.toString(), structured);
			});
		} catch (Exception e) {
			LOGGER.warn("mc_players failed", e);
			return Result.error("Error: failed to list players (" + e.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * {@code mc_world_info}：世界名 / 难度 / 时间 / 天气 / 游戏模式。
	 *
	 * <p>intermediary 名对照：{@code method_8532}=getDayTime、{@code method_8419}=isRaining、
	 * {@code method_8546}=isThundering、{@code method_8407}=getDifficulty（LevelAccessor）、
	 * {@code method_5463}=Difficulty.getDisplayName、{@code field_1761}=Minecraft.gameMode、
	 * {@code method_2920}=getPlayerMode、{@code method_8381}=GameType.getName、
	 * {@code method_3865}=getLevelIdName、{@code field_3752}/{@code field_3761}=ServerData.name/ip。
	 */
	public static Result worldInfo(JsonObject args) {
		String problem = guard();
		if (problem != null) {
			return Result.error(problem);
		}
		try {
			return MainThreadExecutor.submitAndWait(() -> {
				class_310 client = class_310.method_1551();
				long dayTime = client.field_1687.method_8532();
				boolean raining = client.field_1687.method_8419();
				boolean thundering = client.field_1687.method_8546();
				String difficulty = client.field_1687.method_8407().method_5463().getString();
				String gameMode = client.field_1761.method_2920().method_8381();
				String dimension = client.field_1687.method_27983().method_29177().toString();

				String world;
				String kind;
				if (client.method_1576() != null) {
					kind = "singleplayer (integrated server)";
					// 客户端 API 不暴露单人世界的存档名（IntegratedServer 上没有公开的取名字方法），
					// 这里如实说明，而不是编一个名字出来
					world = "(singleplayer world — folder name not exposed by the client API)";
				} else if (client.method_1558() != null) {
					kind = "multiplayer";
					world = client.method_1558().field_3752 + " (" + client.method_1558().field_3761 + ")";
				} else {
					kind = "unknown";
					world = "unknown";
				}

				JsonObject structured = new JsonObject();
				structured.addProperty("world", world);
				structured.addProperty("kind", kind);
				structured.addProperty("difficulty", difficulty);
				structured.addProperty("day_time", dayTime);
				structured.addProperty("raining", raining);
				structured.addProperty("thundering", thundering);
				structured.addProperty("gamemode", gameMode);
				structured.addProperty("dimension", dimension);

				StringBuilder sb = new StringBuilder();
				sb.append("world: ").append(world).append("  [").append(kind).append("]\n");
				sb.append("dimension: ").append(dimension).append('\n');
				sb.append("difficulty: ").append(difficulty).append('\n');
				sb.append("time: ").append(dayTime % 24000L).append(" ticks (dayTime=").append(dayTime).append(")\n");
				sb.append("weather: ").append(raining ? (thundering ? "thunder" : "rain") : "clear").append('\n');
				sb.append("gamemode: ").append(gameMode);
				return Result.ok(sb.toString(), structured);
			});
		} catch (Exception e) {
			LOGGER.warn("mc_world_info failed", e);
			return Result.error("Error: failed to read world info (" + e.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * {@code mc_inventory}：本地玩家背包（含装备与副手）。
	 *
	 * <p>intermediary 名对照：{@code method_31548}=Player.getInventory、{@code class_1263}=Container
	 * （{@code method_5439}=getContainerSize、{@code method_5438}=getItem）、
	 * {@code method_67532}=getSelectedSlot、{@code field_30639}=SLOT_OFFHAND、{@code field_60982}=SLOT_BODY_ARMOR；
	 * {@code method_7960}=isEmpty、{@code method_7947}=getCount、{@code method_7964}=getHoverName。
	 */
	public static Result inventory(JsonObject args) {
		String problem = guard();
		if (problem != null) {
			return Result.error(problem);
		}
		String requested = args.has("player") && args.get("player").isJsonPrimitive()
				? args.get("player").getAsString().trim()
				: "";
		try {
			return MainThreadExecutor.submitAndWait(() -> {
				class_310 client = class_310.method_1551();
				String localName = client.method_53462().name();   // GameProfile 是 record
				if (!requested.isEmpty() && !requested.equalsIgnoreCase(localName)) {
					return Result.error("Error: only the local player's inventory is available from the client "
							+ "(requested '" + requested + "', local player is '" + localName + "'). "
							+ "Remote inventories are not sent to clients.");
				}

				Object inventory = client.field_1724.method_31548();
				net.minecraft.class_1263 container = (net.minecraft.class_1263) inventory;
				int size = container.method_5439();
				int selected = client.field_1724.method_31548().method_67532();

				JsonArray items = new JsonArray();
				StringBuilder sb = new StringBuilder();
				sb.append("inventory of ").append(localName).append(" (").append(size).append(" slots)\n");

				for (int slot = 0; slot < size; slot++) {
					class_1799 stack = container.method_5438(slot);
					if (stack == null || stack.method_7960()) {
						continue;
					}
					String label = slotLabel(slot, selected);
					JsonObject item = new JsonObject();
					item.addProperty("slot", slot);
					item.addProperty("slot_label", label);
					item.addProperty("count", stack.method_7947());
					item.addProperty("name", stack.method_7964().getString());
					items.add(item);
					sb.append(String.format("- slot %d%s: %dx %s%n", slot, label.isEmpty() ? "" : " " + label,
							stack.method_7947(), stack.method_7964().getString()));
				}
				if (items.isEmpty()) {
					sb.append("(inventory is empty)");
				}

				JsonObject structured = new JsonObject();
				structured.addProperty("player", localName);
				structured.add("items", items);
				structured.addProperty("selected_slot", selected);
				return Result.ok(sb.toString(), structured);
			});
		} catch (Exception e) {
			LOGGER.warn("mc_inventory failed", e);
			return Result.error("Error: failed to read inventory (" + e.getClass().getSimpleName() + ")");
		}
	}

	private static String slotLabel(int slot, int selected) {
		if (slot == selected) {
			return "(held)";
		}
		if (slot == 40) {
			return "(offhand)";
		}
		if (slot >= 36 && slot <= 39) {
			return "(armor)";
		}
		if (slot < 9) {
			return "(hotbar)";
		}
		return "";
	}

	/**
	 * {@code mc_chat}：以本地玩家身份发一条聊天消息（不是命令）。
	 *
	 * <p>intermediary 名对照：{@code method_45729}=sendChat。
	 */
	public static Result chat(JsonObject args) {
		String problem = guard();
		if (problem != null) {
			return Result.error(problem);
		}
		if (!args.has("message") || !args.get("message").isJsonPrimitive()) {
			return Result.error("Error: missing required parameter 'message' (string)");
		}
		String message = args.get("message").getAsString();
		if (message.isBlank()) {
			return Result.error("Error: 'message' must not be empty");
		}
		try {
			MainThreadExecutor.submitAndWait(() -> {
				class_310 client = class_310.method_1551();
				client.field_1724.field_3944.method_45729(message);
				return null;
			});
			LOGGER.debug("Chat message sent ({} chars)", message.length());
			return Result.ok("Chat message sent (" + message.length() + " chars).");
		} catch (Exception e) {
			LOGGER.warn("mc_chat failed", e);
			return Result.error("Error: failed to send chat message (" + e.getClass().getSimpleName() + ")");
		}
	}
}
