package kwamster.core.service;

/** Lets CORE settle CAPTURE's pending state before a fight is finalised. Implemented by CAPTURE; the CORE default is a no-op. */
public interface CaptureControl {
	/**
	 * Settles pending trades now, posting their {@code TradeEvent}s synchronously. Must tolerate
	 * {@code mc.level == null} and {@code mc.player == null} (it is called after a disconnect, §5.13).
	 */
	void flushPending();
}
