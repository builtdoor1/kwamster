package kwamster.core.model;

/**
 * A built share link (§9).
 *
 * @param url         the full URL, base plus {@code #} fragment
 * @param length      {@code url.length()}; may exceed the budget when nothing fit (callers can warn)
 * @param downsampled the replay events were thinned to fit the budget
 * @param eventsKept  events encoded in the stream
 * @param eventsTotal events in the stream before thinning
 */
public record ShareLink(String url, int length, boolean downsampled, int eventsKept, int eventsTotal) {
}
