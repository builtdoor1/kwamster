package kwamster.core.fight;

import kwamster.Kwamster;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Minecraft glue shared by CORE and CAPTURE: live entities to KWAMSTER value types, and the NPC
 * filter (§5.4). Client thread.
 */
public final class PlayerRefs {
	private static final char SECTION_SIGN = '§';

	private PlayerRefs() {
	}

	/** The player's UUID and name, formatting codes stripped and trimmed (combat-report {@code FightWatcher.stripFormatting}). */
	public static PlayerRef of(Player p) {
		return new PlayerRef(p.getUUID(), stripFormatting(p.getName().getString()));
	}

	/** A Minecraft-free copy of a position. */
	public static Pos of(Vec3 v) {
		return v == null ? Pos.ZERO : new Pos(v.x, v.y, v.z);
	}

	/**
	 * The local player as a ref: from {@code mc.player} when in a world, otherwise from the session
	 * user (so a fight finalised after a disconnect still has a name).
	 */
	public static PlayerRef self(Minecraft mc) {
		if (mc == null) {
			return new PlayerRef(new UUID(0L, 0L), "");
		}
		if (mc.player != null) {
			return of(mc.player);
		}
		User user = mc.getUser();
		if (user != null) {
			UUID id = user.getProfileId();
			return new PlayerRef(id == null ? new UUID(0L, 0L) : id, stripFormatting(user.getName()));
		}
		return new PlayerRef(new UUID(0L, 0L), "");
	}

	/**
	 * True if {@code p} is a server NPC (a fake player) that KWAMSTER must treat like a non-player
	 * entity: no connection, not in the tab-list registry ({@code getPlayerInfo(uuid) == null}), or a
	 * version-2 UUID (Citizens and most NPC plugins; real players are v4 online or v3 offline).
	 *
	 * <p>Always false when {@code advanced.ignoreNpcs} is off, and for the local player (never tested).
	 */
	public static boolean isNpc(Player p) {
		if (p == null || !Kwamster.config().advanced.ignoreNpcs) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (p == mc.player) {
			return false;
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return true;
		}
		UUID id = p.getUUID();
		if (connection.getPlayerInfo(id) == null) {
			return true;
		}
		return id.version() == 2;
	}

	/** Removes {@code §x} formatting codes and trims. Null becomes "". */
	public static String stripFormatting(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == SECTION_SIGN && i + 1 < raw.length()) {
				i++;
				continue;
			}
			out.append(c);
		}
		return out.toString().trim();
	}
}
