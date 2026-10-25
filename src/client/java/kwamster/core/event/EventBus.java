package kwamster.core.event;

import kwamster.KwamsterClient;
import kwamster.core.LogLimiter;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Synchronous KWAMSTER event dispatcher (§4.3).
 *
 * <ul>
 *   <li><b>Order.</b> The core listener (FightManager) always receives an event first, then every other
 *       listener in registration (module) order.</li>
 *   <li><b>Re-entrancy.</b> An event posted while a dispatch is running is appended to a FIFO queue and
 *       dispatched right after the current one, so for example a {@link HitResolvedEvent} always
 *       follows its {@link SwingEvent} at every listener.</li>
 *   <li><b>Failure isolation.</b> Each listener call is wrapped in try/catch and logged (rate-limited),
 *       so one unit cannot break another or vanilla.</li>
 *   <li><b>Thread.</b> {@link #post} and {@link #postTick} are client-thread only (R2). The bus does not lock.</li>
 * </ul>
 */
public final class EventBus {
	/** Upper bound on events dispatched by one outer {@link #post}, to stop a listener feedback loop. */
	private static final int MAX_CHAINED_EVENTS = 10_000;

	private KwamsterListener core;
	private final List<KwamsterListener> listeners = new CopyOnWriteArrayList<>();
	private final ArrayDeque<Object> queue = new ArrayDeque<>();
	private final Set<Class<?>> unknownTypesLogged = new HashSet<>();
	private boolean dispatching;

	/** CORE only: the listener that is always dispatched first. */
	public void registerCore(KwamsterListener l) {
		Objects.requireNonNull(l, "listener");
		if (this.core != null && this.core != l) {
			KwamsterClient.LOG.warn("Replacing the core KWAMSTER listener {} with {}", this.core.getClass().getName(), l.getClass().getName());
		}
		this.core = l;
	}

	/** Adds a listener, dispatched after the core listener in registration order. Duplicates are ignored. */
	public void register(KwamsterListener l) {
		Objects.requireNonNull(l, "listener");
		if (l != this.core && !this.listeners.contains(l)) {
			this.listeners.add(l);
		}
	}

	/** Removes a listener registered with {@link #register}. Safe to call during a dispatch. */
	public void unregister(KwamsterListener l) {
		this.listeners.remove(l);
	}

	/**
	 * Dispatches now. If a dispatch is already running, the event is appended to a FIFO queue and
	 * dispatched after it. A null event is ignored; an unknown type is logged once and dropped.
	 */
	public void post(Object event) {
		if (event == null) {
			return;
		}
		if (this.dispatching) {
			this.queue.addLast(event);
			return;
		}
		this.dispatching = true;
		try {
			dispatch(event);
			int chained = 0;
			Object next;
			while ((next = this.queue.pollFirst()) != null) {
				if (++chained > MAX_CHAINED_EVENTS) {
					KwamsterClient.LOG.error("KWAMSTER event loop detected: dropping {} queued events (last {})",
							this.queue.size() + 1, next.getClass().getSimpleName());
					this.queue.clear();
					break;
				}
				dispatch(next);
			}
		} finally {
			this.dispatching = false;
			this.queue.clear();
		}
	}

	/**
	 * Calls {@code onClientTick} on the core listener, then on every other listener. This is NOT a
	 * dispatch: {@link #dispatching()} is false while tick handlers run, so an event posted from a tick
	 * handler is dispatched synchronously and completely before {@code post()} returns.
	 */
	public void postTick(Minecraft mc) {
		KwamsterListener c = this.core;
		if (c != null) {
			tick(c, mc);
		}
		for (KwamsterListener l : this.listeners) {
			tick(l, mc);
		}
	}

	/** True while an event dispatch (not a tick) is running. */
	public boolean dispatching() {
		return this.dispatching;
	}

	private void dispatch(Object event) {
		switch (event) {
			case SwingEvent e -> deliver(e, KwamsterListener::onSwing);
			case PiercingSwingEvent e -> deliver(e, KwamsterListener::onPiercingSwing);
			case HitTakenEvent e -> deliver(e, KwamsterListener::onHitTaken);
			case JumpEvent e -> deliver(e, KwamsterListener::onJump);
			case JumpResetEvent e -> deliver(e, KwamsterListener::onJumpReset);
			case DeathEvent e -> deliver(e, KwamsterListener::onDeath);
			case HitConfirmedEvent e -> deliver(e, KwamsterListener::onHitConfirmed);
			case DamageObservedEvent e -> deliver(e, KwamsterListener::onDamageObserved);
			case TradeEvent e -> deliver(e, KwamsterListener::onTrade);
			case WorldLeaveEvent e -> deliver(e, KwamsterListener::onWorldLeave);
			case FightStartedEvent e -> deliver(e, KwamsterListener::onFightStarted);
			case HitResolvedEvent e -> deliver(e, KwamsterListener::onHitResolved);
			case FightEndedEvent e -> deliver(e, KwamsterListener::onFightEnded);
			default -> {
				if (this.unknownTypesLogged.add(event.getClass())) {
					KwamsterClient.LOG.warn("Ignoring unknown KWAMSTER event type {}", event.getClass().getName());
				}
			}
		}
	}

	private <E> void deliver(E event, BiConsumer<KwamsterListener, E> call) {
		KwamsterListener c = this.core;
		if (c != null) {
			invoke(c, event, call);
		}
		for (KwamsterListener l : this.listeners) {
			invoke(l, event, call);
		}
	}

	private static <E> void invoke(KwamsterListener l, E event, BiConsumer<KwamsterListener, E> call) {
		try {
			call.accept(l, event);
		} catch (Throwable t) {
			LogLimiter.error("EventBus/" + l.getClass().getName() + "/" + event.getClass().getSimpleName(),
					"Listener threw while handling " + event.getClass().getSimpleName(), t);
		}
	}

	private static void tick(KwamsterListener l, Minecraft mc) {
		try {
			l.onClientTick(mc);
		} catch (Throwable t) {
			LogLimiter.error("EventBus/" + l.getClass().getName() + "/tick", "Listener threw in onClientTick", t);
		}
	}
}
