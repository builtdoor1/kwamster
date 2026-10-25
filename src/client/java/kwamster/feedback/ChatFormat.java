package kwamster.feedback;

import kwamster.core.model.FightResult;
import kwamster.core.model.ShareLink;
import kwamster.core.stats.StatFormat;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Component builders for KWAMSTER's chat output (§8.2): the {@code [KW]} prefix, separators, label and
 * value runs, hover text and the clickable share actions. Every string is a {@link Component#literal}
 * (R5); colours are RGB (alpha is ignored by chat).
 *
 * <p>Hover components carry their own colours: a child's style inherits from its parent, so a span
 * built with {@link #withHover} passes the hover down to every run appended to it unless a run sets
 * its own (the share actions do).
 */
public final class ChatFormat {
	/** {@code [KW]} prefix colour (accent violet). */
	public static final int ACCENT = 0x8B5CF6;
	/** Separator colour. */
	public static final int SEPARATOR = 0x6B7280;
	/** Label colour. */
	public static final int LABEL = 0x9AA4B2;
	/** Value colour. */
	public static final int VALUE = 0xF1F3F5;
	/** Dim text (secondary hover lines). */
	public static final int DIM = 0x6B7280;
	/** Misses and NO-REG hits (the hit indicator's grey, §7.5). */
	public static final int GREY = 0x8A919E;
	/** Good (WIN, a successful jump reset). */
	public static final int GOOD = 0x3DDC84;
	/** Warn (DRAW). */
	public static final int WARN = 0xFFC53D;
	/** Bad (LOSS, a failed jump reset). */
	public static final int BAD = 0xFF5A5F;
	/** Share actions (accent 2, cyan). */
	public static final int LINK = 0x22D3EE;

	/** The separator text, {@code " · "}. */
	public static final String SEP = " · ";
	/** Indent of the second and third fight summary lines. */
	public static final String INDENT = "   ";
	/** Opponent names on line 1 are cut to this many characters (§8.2). */
	public static final int MAX_NAME_CHARS = 16;

	private ChatFormat() {
	}

	/** A plain run of {@code text} in {@code rgb}. */
	public static MutableComponent text(String text, int rgb) {
		return Component.literal(text).withColor(rgb & 0xFFFFFF);
	}

	/** A bold run of {@code text} in {@code rgb}. */
	public static MutableComponent bold(String text, int rgb) {
		return Component.literal(text).withStyle(s -> s.withColor(rgb & 0xFFFFFF).withBold(true));
	}

	/** A label run ({@link #LABEL}). */
	public static MutableComponent label(String text) {
		return text(text, LABEL);
	}

	/** A value run ({@link #VALUE}). */
	public static MutableComponent value(String text) {
		return text(text, VALUE);
	}

	/** {@code " · "} in {@link #SEPARATOR}. */
	public static MutableComponent sep() {
		return text(SEP, SEPARATOR);
	}

	/** {@code "<label> <value>"}: the label in {@link #LABEL}, the value in {@link #VALUE}. */
	public static MutableComponent stat(String label, String value) {
		return Component.empty().append(label(label + " ")).append(value(value));
	}

	/** {@code "<label><value>"} with no space, for compact tokens such as {@code C3}. */
	public static MutableComponent tight(String label, String value) {
		return Component.empty().append(label(label)).append(value(value));
	}

	/** Joins {@code parts} with {@link #sep()} into one new component. */
	public static MutableComponent joined(List<? extends Component> parts) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out.append(sep());
			}
			out.append(parts.get(i));
		}
		return out;
	}

	/** A new chat line starting with the prefix: {@code [KW]} in bold accent, then a plain space. */
	public static MutableComponent line() {
		return Component.empty().append(bold("[KW]", ACCENT)).append(Component.literal(" "));
	}

	/** A new indented continuation line (fight summary lines 2 and 3). */
	public static MutableComponent indented() {
		return Component.empty().append(Component.literal(INDENT));
	}

	/** Sets a {@link HoverEvent.ShowText} with {@code hover} on {@code c} and returns it. */
	public static MutableComponent withHover(MutableComponent c, Component hover) {
		return c.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	/** A new empty span carrying {@code hover}; runs appended to it inherit the hover. */
	public static MutableComponent hoverSpan(Component hover) {
		return withHover(Component.empty(), hover);
	}

	/** Joins hover lines with line breaks (vanilla's tooltip splitter breaks on {@code '\n'}). */
	public static MutableComponent lines(List<? extends Component> lines) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				out.append(Component.literal("\n"));
			}
			out.append(lines.get(i));
		}
		return out;
	}

	/** An action: {@code text} in {@link #LINK}, underlined, with its own click and hover. */
	public static MutableComponent action(String text, ClickEvent click, Component hover) {
		return Component.literal(text).withStyle(s -> s.withColor(LINK).withUnderlined(true)
				.withClickEvent(click).withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	/**
	 * {@code [Copy link]}: copies {@code link.url()} (handled by vanilla's
	 * {@code Screen.defaultHandleClickEvent}). The hover says the length and, for a thinned link, adds
	 * "replay thinned to fit".
	 */
	public static MutableComponent copyLinkAction(ShareLink link) {
		String hover = "Copy share link (" + link.length() + " chars)";
		if (link.downsampled()) {
			hover += SEP + "replay thinned to fit";
		}
		return action("[Copy link]", new ClickEvent.CopyToClipboard(link.url()), value(hover));
	}

	/**
	 * {@code [Open]}: opens the viewer through vanilla's link handling (which honours the chat-links and
	 * confirm-prompt options), or null when the URL does not parse as an http(s) URI, so the caller
	 * omits the action and still sends the rest of the line.
	 */
	public static @Nullable MutableComponent openAction(ShareLink link) {
		URI uri = viewerUri(link.url());
		if (uri == null) {
			return null;
		}
		return action("[Open]", new ClickEvent.OpenUrl(uri), value("Open the fight viewer"));
	}

	/** {@code url} as a URI, or null if it does not parse or its scheme is not http/https. */
	public static @Nullable URI viewerUri(@Nullable String url) {
		if (url == null) {
			return null;
		}
		try {
			// new URI(url) plus vanilla's own scheme allow-list (http, https).
			return Util.parseAndValidateUntrustedUri(url);
		} catch (URISyntaxException | RuntimeException e) {
			return null;
		}
	}

	/** {@code name} cut to {@link #MAX_NAME_CHARS} characters (with {@code "..."} when cut); "?" when blank. */
	public static String name(@Nullable String name) {
		return limit(name, MAX_NAME_CHARS, "?");
	}

	/** {@code s} cut to {@code maxChars} code points (with {@code "..."} when cut); {@code fallback} when blank. */
	public static String limit(@Nullable String s, int maxChars, String fallback) {
		if (s == null || s.isBlank()) {
			return fallback;
		}
		return StatFormat.truncate(s.trim(), maxChars, str -> str.codePointCount(0, str.length()));
	}

	/** {@code WIN}, {@code LOSS} or {@code DRAW}. */
	public static String resultWord(FightResult r) {
		return r.name();
	}

	/** WIN good, LOSS bad, DRAW warn (the card's result colours, §7.9). */
	public static int resultColour(FightResult r) {
		return switch (r) {
			case WIN -> GOOD;
			case LOSS -> BAD;
			case DRAW -> WARN;
		};
	}

	/** The result named by a record's {@code result} string; DRAW when missing or unknown. */
	public static FightResult parseResult(@Nullable String result) {
		if (result != null) {
			for (FightResult r : FightResult.values()) {
				if (r.name().equals(result)) {
					return r;
				}
			}
		}
		return FightResult.DRAW;
	}

	/** {@code "1 tick"} / {@code "2 ticks"}. */
	public static String ticks(int n) {
		return n + (n == 1 ? " tick" : " ticks");
	}
}
