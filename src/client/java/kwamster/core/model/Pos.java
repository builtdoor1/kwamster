package kwamster.core.model;

/**
 * A Minecraft-free 3-D position in blocks.
 *
 * @param x east-west coordinate
 * @param y height
 * @param z north-south coordinate
 */
public record Pos(double x, double y, double z) {
	/** The world origin, used as a fallback when a position is unknown. */
	public static final Pos ZERO = new Pos(0.0, 0.0, 0.0);
}
