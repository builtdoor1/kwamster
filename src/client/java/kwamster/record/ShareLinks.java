package kwamster.record;

import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.FightRecord;
import kwamster.core.model.ShareLink;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * Share-link assembly (§9.1) and the size budget with stratified downsampling (§9.5).
 *
 * <p>{@link #build(FightRecord, String, boolean, boolean, int)} encodes the whole fight and returns
 * it if the URL fits {@code maxChars}. Otherwise it thins the event stream with a keep fraction
 * {@code f} found by {@link #search}, then drops positions, then drops the server and shortens names,
 * and finally returns the shortest attempt even if it is still over budget (the summary block is
 * always exact; only the replay loses detail).
 *
 * <p>Minecraft-free (R6). Stateless and thread-safe.
 */
public final class ShareLinks {
	/** {@code f = i / SEARCH_DENOMINATOR}. */
	public static final int SEARCH_DENOMINATOR = 4096;
	/** Binary-search iterations before the final probe at {@code f = 0}. */
	public static final int SEARCH_ITERATIONS = 13;

	private ShareLinks() {
	}

	/**
	 * One encoding attempt: the keep fraction, how many events it kept, and the resulting URL and its
	 * length. Tests may build probes with any {@code url} to drive {@link #search} with an artificial
	 * size function.
	 */
	public record Probe(double keepFraction, int eventsKept, int length, String url) {
	}

	// ---- base URL -------------------------------------------------------------

	/**
	 * The §9.1 base normalisation: trim, cut everything from the first {@code #}, then add a trailing
	 * {@code /} unless the base contains {@code ?}, already ends with {@code /}, or its last path
	 * segment contains a {@code .} (a file such as {@code index.html}). Returns null when the result
	 * fails the shared base check ({@link KwamsterConfig#isValidShareBaseUrl}: parses as a URI, http or
	 * https, non-empty host).
	 */
	public static @Nullable String normalizeBase(@Nullable String base) {
		if (base == null) {
			return null;
		}
		String s = base.trim();
		int hash = s.indexOf('#');
		if (hash >= 0) {
			s = s.substring(0, hash).trim();
		}
		if (!KwamsterConfig.isValidShareBaseUrl(s)) {
			return null;
		}
		if (s.indexOf('?') >= 0 || s.endsWith("/")) {
			return s;
		}
		String path;
		try {
			path = new URI(s).getRawPath();
		} catch (URISyntaxException e) {
			return null; // unreachable: validated above
		}
		String last = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
		return last.indexOf('.') >= 0 ? s : s + "/";
	}

	/** {@code normalizedBase + "#" + fragment(payload)}. */
	public static String url(String normalizedBase, byte[] payload) {
		return normalizedBase + "#" + SharePayloadCodec.fragment(payload);
	}

	// ---- build ----------------------------------------------------------------

	/**
	 * The share link of a finished record, or null if {@code baseUrl} is invalid (§9.1).
	 *
	 * @param includeServer {@code recording.shareIncludeServer}
	 * @param positions     {@code recording.sharePositions}
	 * @param maxChars      {@code recording.shareMaxChars}
	 */
	public static @Nullable ShareLink build(FightRecord r, @Nullable String baseUrl, boolean includeServer,
			boolean positions, int maxChars) {
		Objects.requireNonNull(r, "record");
		String base = normalizeBase(baseUrl);
		if (base == null) {
			return null;
		}
		return build(SharePayloadCodec.content(r, includeServer), base, positions, maxChars);
	}

	/**
	 * The §9.5 procedure on prepared content and an already normalised base.
	 *
	 * <ol>
	 *   <li>Encode everything; done if it fits (not downsampled).</li>
	 *   <li>Otherwise search the keep fraction and take the fitting probe with the most events.</li>
	 *   <li>If nothing fits, clear POSITIONS and repeat.</li>
	 *   <li>If still nothing fits, drop SERVER and cap names at 10 bytes, and repeat.</li>
	 *   <li>Return the shortest attempt, even if it is over budget.</li>
	 * </ol>
	 * A stage that would encode exactly like the previous one is skipped. DOWNSAMPLED is set only when
	 * events were actually left out.
	 */
	public static ShareLink build(SharePayloadCodec.Content c, String normalizedBase, boolean positions, int maxChars) {
		Objects.requireNonNull(c, "content");
		Objects.requireNonNull(normalizedBase, "normalizedBase");
		List<SharePayloadCodec.Event> all = c.events();
		int total = all.size();

		List<SharePayloadCodec.Options> stages = new ArrayList<>(3);
		stages.add(new SharePayloadCodec.Options(positions, true, SharePayloadCodec.NAME_CAP_BYTES, false));
		if (positions) {
			stages.add(new SharePayloadCodec.Options(false, true, SharePayloadCodec.NAME_CAP_BYTES, false));
		}
		// The previous stage always has positions off, so the last one only matters if it drops something.
		if (c.server() != null || !namesFit(c, SharePayloadCodec.SHORT_NAME_CAP_BYTES)) {
			stages.add(new SharePayloadCodec.Options(false, false, SharePayloadCodec.SHORT_NAME_CAP_BYTES, false));
		}

		Probe shortest = null;
		for (SharePayloadCodec.Options stage : stages) {
			List<Probe> tried = new ArrayList<>();
			DoubleFunction<Probe> encodeAt = f -> {
				List<SharePayloadCodec.Event> kept = f >= 1.0 ? all : thin(all, f);
				SharePayloadCodec.Options o = new SharePayloadCodec.Options(stage.positions(), stage.server(),
						stage.nameCapBytes(), kept.size() < total);
				String url = url(normalizedBase, SharePayloadCodec.encode(c, kept, o));
				Probe p = new Probe(f, kept.size(), url.length(), url);
				tried.add(p);
				return p;
			};
			Probe full = encodeAt.apply(1.0);
			Probe best = full.length() <= maxChars ? full : search(encodeAt, maxChars);
			for (Probe p : tried) {
				if (shortest == null || p.length() < shortest.length()) {
					shortest = p;
				}
			}
			if (best != null) {
				return link(best, total);
			}
		}
		return link(Objects.requireNonNull(shortest), total);
	}

	// ---- downsampling ---------------------------------------------------------

	/**
	 * The fitting probe that keeps the most events, or null if none fits. Binary-searches
	 * {@code f = i/4096} for {@value #SEARCH_ITERATIONS} iterations (moving up when a probe fits, down
	 * when it does not), then probes {@code f = 0}. URL length is not strictly monotone in {@code f}, so
	 * every probe is kept and the best fitting one wins, not merely the last one tried. Ties go to the
	 * shorter URL, then the larger {@code f}.
	 *
	 * @param encodeAt builds the probe for a keep fraction (called at most once per distinct {@code f})
	 */
	public static @Nullable Probe search(DoubleFunction<Probe> encodeAt, int maxChars) {
		Objects.requireNonNull(encodeAt, "encodeAt");
		Map<Integer, Probe> probes = new LinkedHashMap<>();
		int lo = 0;
		int hi = SEARCH_DENOMINATOR;
		for (int it = 0; it < SEARCH_ITERATIONS; it++) {
			int mid = (lo + hi) >>> 1;
			if (probes.containsKey(mid)) {
				break; // converged
			}
			Probe p = encodeAt.apply(mid / (double) SEARCH_DENOMINATOR);
			probes.put(mid, p);
			if (p.length() <= maxChars) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		if (!probes.containsKey(0)) {
			probes.put(0, encodeAt.apply(0.0));
		}
		Probe best = null;
		for (Probe p : probes.values()) {
			if (p.length() <= maxChars && better(p, best)) {
				best = p;
			}
		}
		return best;
	}

	/**
	 * The §9.5 thinning at keep fraction {@code f} (clamped to [0, 1]), preserving stream order.
	 *
	 * <ul>
	 *   <li>Always kept: every DEATH, and the first and last event of each kind.</li>
	 *   <li>Priority events (HITs of quality code 0–3): {@code round(min(1, 2f)·n_P)}, evenly spaced.</li>
	 *   <li>Every other HIT, and each of MISS, TAKEN, JUMP: {@code round(f·n_k)}, evenly spaced.</li>
	 *   <li>Evenly spaced, n keeping m: {@code idx_j = m == 1 ? 0 : floor(j·(n−1)/(m−1))}.</li>
	 * </ul>
	 * Reserved kinds (never produced by the encoder) are always kept.
	 */
	public static List<SharePayloadCodec.Event> thin(List<SharePayloadCodec.Event> events, double keepFraction) {
		Objects.requireNonNull(events, "events");
		double f = Double.isNaN(keepFraction) ? 0.0 : Math.max(0.0, Math.min(1.0, keepFraction));
		int n = events.size();
		boolean[] keep = new boolean[n];
		int[] first = new int[8];
		int[] last = new int[8];
		Arrays.fill(first, -1);
		List<Integer> priority = new ArrayList<>();
		List<List<Integer>> byKind = new ArrayList<>(4);
		for (int k = 0; k < 4; k++) {
			byKind.add(new ArrayList<>());
		}
		for (int i = 0; i < n; i++) {
			SharePayloadCodec.Event e = events.get(i);
			int kind = e.kind() & 0x7;
			if (first[kind] < 0) {
				first[kind] = i;
			}
			last[kind] = i;
			if (kind >= SharePayloadCodec.KIND_DEATH) {
				keep[i] = true;
			} else if (e.priority()) {
				priority.add(i);
			} else {
				byKind.get(kind).add(i);
			}
		}
		for (int k = 0; k < 8; k++) {
			if (first[k] >= 0) {
				keep[first[k]] = true;
				keep[last[k]] = true;
			}
		}
		keepEvenly(priority, Math.round(Math.min(1.0, 2.0 * f) * priority.size()), keep);
		for (List<Integer> group : byKind) {
			keepEvenly(group, Math.round(f * group.size()), keep);
		}
		List<SharePayloadCodec.Event> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (keep[i]) {
				out.add(events.get(i));
			}
		}
		return out;
	}

	/** Indices {@code floor(j·(n−1)/(m−1))} for j in 0..m−1 ({@code [0]} when m = 1); m is clamped to [0, n]. */
	public static int[] evenlySpaced(int n, long m) {
		int count = (int) Math.max(0L, Math.min(n, m));
		int[] idx = new int[count];
		for (int j = 0; j < count; j++) {
			idx[j] = count == 1 ? 0 : (int) ((long) j * (n - 1) / (count - 1));
		}
		return idx;
	}

	private static void keepEvenly(List<Integer> group, long m, boolean[] keep) {
		for (int j : evenlySpaced(group.size(), m)) {
			keep[group.get(j)] = true;
		}
	}

	private static boolean better(Probe p, @Nullable Probe best) {
		if (best == null) {
			return true;
		}
		if (p.eventsKept() != best.eventsKept()) {
			return p.eventsKept() > best.eventsKept();
		}
		if (p.length() != best.length()) {
			return p.length() < best.length();
		}
		return p.keepFraction() > best.keepFraction();
	}

	private static boolean namesFit(SharePayloadCodec.Content c, int capBytes) {
		if (c.player().getBytes(StandardCharsets.UTF_8).length > capBytes) {
			return false;
		}
		for (String name : c.opponents()) {
			if (name.getBytes(StandardCharsets.UTF_8).length > capBytes) {
				return false;
			}
		}
		return true;
	}

	private static ShareLink link(Probe p, int total) {
		return new ShareLink(p.url(), p.length(), p.eventsKept() < total, p.eventsKept(), total);
	}
}
