package org.adempiere.web.route;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.adempiere.web.handoff.HandoffProtocol;

/**
 * The exact header rules of the Phase 5e proxy, as allowlists.
 *
 * <p>Allowlists rather than denylists: a header the reviewer has not considered
 * must not cross the boundary in either direction. That is the only rule that
 * stays correct when a future ZK version, a future Tomcat version, or a browser
 * adds a header nobody here has heard of.
 */
public final class ProxyHeaderPolicy {

	/**
	 * RFC 7230 section 6.1 connection-specific headers. They describe one hop
	 * and must never be copied to the next.
	 */
	private static final Set<String> HOP_BY_HOP = Set.of(
			"connection",
			"keep-alive",
			"proxy-authenticate",
			"proxy-authorization",
			"te",
			"trailer",
			"transfer-encoding",
			"upgrade");

	/**
	 * Browser to modern runtime.
	 *
	 * <p>{@code cookie} is absent on purpose: the router synthesises exactly one
	 * modern cookie from the identifier it holds server-side, so the browser can
	 * neither see nor choose the internal session. {@code host} is absent
	 * because the router replaces it. {@code referer} and {@code origin} are
	 * absent because they carry the public URL into an internal hop that has no
	 * use for it.
	 */
	private static final Set<String> REQUEST_ALLOWLIST = Set.of(
			"accept",
			"accept-encoding",
			"accept-language",
			"cache-control",
			"content-type",
			"if-modified-since",
			"if-none-match",
			"pragma",
			"user-agent",
			"x-requested-with");

	/**
	 * Modern runtime to browser.
	 *
	 * <p>{@code set-cookie} is absent because the router consumes it. Anything
	 * that could carry the internal session or an internal URL is absent.
	 */
	private static final Set<String> RESPONSE_ALLOWLIST = Set.of(
			"cache-control",
			"content-disposition",
			"content-encoding",
			"content-language",
			"content-type",
			"etag",
			"expires",
			"last-modified",
			"location",
			"pragma",
			"vary",
			"x-content-type-options",
			"x-frame-options");

	private ProxyHeaderPolicy() {
	}

	/** Whether a browser request header may be forwarded. */
	public static boolean forwardRequestHeader(String name) {
		String lower = lower(name);
		return lower != null
				&& !HOP_BY_HOP.contains(lower)
				&& !HandoffProtocol.reserved(name)
				&& REQUEST_ALLOWLIST.contains(lower);
	}

	/** Whether a modern response header may be returned to the browser. */
	public static boolean forwardResponseHeader(String name) {
		String lower = lower(name);
		return lower != null
				&& !HOP_BY_HOP.contains(lower)
				&& !HandoffProtocol.reserved(name)
				&& RESPONSE_ALLOWLIST.contains(lower);
	}

	/** The reviewed request allowlist, sorted, for the frozen contract. */
	public static List<String> requestAllowlist() {
		return REQUEST_ALLOWLIST.stream().sorted().toList();
	}

	/** The reviewed response allowlist, sorted, for the frozen contract. */
	public static List<String> responseAllowlist() {
		return RESPONSE_ALLOWLIST.stream().sorted().toList();
	}

	/** The hop-by-hop set, sorted, for the frozen contract. */
	public static List<String> hopByHop() {
		return HOP_BY_HOP.stream().sorted().toList();
	}

	private static String lower(String name) {
		return name == null ? null : name.toLowerCase(Locale.ROOT);
	}
}
