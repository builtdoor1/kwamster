package kwamster.record;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.KwamsterModule;
import kwamster.core.LogLimiter;
import kwamster.core.ScreenRegistry;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.KwamsterListener;
import kwamster.record.screen.SavedFightsScreen;

/**
 * RECORD's entrypoint (order 200): registers the {@link FightStore} as the {@code FightArchive}
 * service, the {@code SAVED_FIGHTS} screen factory and the {@code FightEndedEvent} listener that
 * saves fights.
 *
 * <p>It registers no Fabric lifecycle listener: CORE's single {@code CLIENT_STOPPING} handler calls
 * {@code archive().shutdown()} after finalising the last fight (§2.1).
 */
public final class RecordModule implements KwamsterModule {
	@Override
	public int order() {
		return 200;
	}

	@Override
	public void init() {
		FightStore store = new FightStore(Kwamster.dataDir());
		store.init();
		Kwamster.services().setArchive(store);
		Kwamster.services().screens().register(ScreenRegistry.ScreenId.SAVED_FIGHTS,
				(parent, arg) -> new SavedFightsScreen(parent, store));
		Kwamster.events().register(new KwamsterListener() {
			@Override
			public void onFightEnded(FightEndedEvent e) {
				try {
					store.onFightEnded(e);
				} catch (Throwable t) {
					LogLimiter.error("RecordModule.onFightEnded", "Could not save the fight", t);
				}
			}
		});
		KwamsterClient.LOG.debug("RECORD ready");
	}
}
