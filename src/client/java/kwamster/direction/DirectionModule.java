package kwamster.direction;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.KwamsterModule;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/**
 * The DIRECTION unit's entrypoint (S12, order 300). It registers:
 * <ul>
 *   <li>one {@link HeadBarTracker} as a bus listener (client tick, world leave) and
 *       {@link HeadBarRenderer} on {@code WorldRenderEvents.END_MAIN}: the above-head bars and the jump
 *       cue ported from Direction Indicator (§5.11a);</li>
 *   <li>one {@link OpponentMotionTracker} as the {@code StoplightSource} service (read by the HUD's
 *       optional stoplight) and as a bus listener (§5.11).</li>
 * </ul>
 */
public final class DirectionModule implements KwamsterModule {
	/** DIRECTION's place in the module order (§2.1). */
	public static final int ORDER = 300;

	@Override
	public int order() {
		return ORDER;
	}

	@Override
	public void init() {
		HeadBarTracker bars = new HeadBarTracker();
		Kwamster.events().register(bars);
		WorldRenderEvents.END_MAIN.register(HeadBarRenderer::render);

		OpponentMotionTracker tracker = new OpponentMotionTracker();
		Kwamster.services().setStoplight(tracker);
		Kwamster.events().register(tracker);
		KwamsterClient.LOG.debug("KWAMSTER head bars and direction stoplight registered");
	}
}
