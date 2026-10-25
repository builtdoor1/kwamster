package kwamster.core.model;

import java.util.UUID;

/**
 * A player as KWAMSTER records them: the UUID (the identity every lookup is keyed by) and the name
 * shown to the user.
 *
 * <p>The name has formatting codes stripped and is trimmed ({@code kwamster.core.fight.PlayerRefs}
 * builds instances from live entities). Two refs with the same UUID are the same player even if the
 * names differ (a rename mid-session keeps the first name seen in a fight).
 *
 * @param uuid the player's UUID
 * @param name the display name, formatting stripped and trimmed
 */
public record PlayerRef(UUID uuid, String name) {
}
