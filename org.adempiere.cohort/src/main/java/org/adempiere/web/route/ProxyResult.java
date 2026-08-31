package org.adempiere.web.route;

import java.util.List;

/**
 * Framework-neutral result of one bounded proxy exchange.
 *
 * <p>{@code failure} is the stable reason code: it is the affinity key and the
 * value the routing contracts assert, so it never varies with the request.
 * {@code detail} is an optional, already-sanitized diagnostic descriptor that
 * explains a specific failure well enough to act on. It is written to the
 * container log and the Phase 5f runtime evidence, never to the wire, and it
 * must already have passed through a sanitizer such as
 * {@link RedirectDescriptor} before it reaches this record.
 */
public record ProxyResult(
		boolean completed,
		String failure,
		String modernSessionId,
		boolean sessionEnded,
		List<String> applicationCookies,
		String detail) {

	public ProxyResult {
		applicationCookies = List.copyOf(applicationCookies);
	}

	public static ProxyResult completed(String modernSessionId) {
		return completed(modernSessionId, List.of());
	}

	public static ProxyResult completed(
			String modernSessionId, List<String> applicationCookies) {
		return new ProxyResult(
				true, null, modernSessionId, false, applicationCookies, null);
	}

	public static ProxyResult failed(String failure) {
		return failed(failure, null);
	}

	/** Fails with a stable reason code and a sanitized diagnostic descriptor. */
	public static ProxyResult failed(String failure, String detail) {
		return new ProxyResult(false, failure, null, false, List.of(), detail);
	}

	public static ProxyResult ended() {
		return new ProxyResult(true, null, null, true, List.of(), null);
	}

	/** The reason code, with its sanitized descriptor when one was recorded. */
	public String diagnostic() {
		return detail == null ? failure : failure + " [" + detail + "]";
	}
}
