package org.adempiere.web.route;

import java.util.List;

/** Framework-neutral result of one bounded proxy exchange. */
public record ProxyResult(
		boolean completed,
		String failure,
		String modernSessionId,
		boolean sessionEnded,
		List<String> applicationCookies) {

	public ProxyResult {
		applicationCookies = List.copyOf(applicationCookies);
	}

	public static ProxyResult completed(String modernSessionId) {
		return completed(modernSessionId, List.of());
	}

	public static ProxyResult completed(
			String modernSessionId, List<String> applicationCookies) {
		return new ProxyResult(
				true, null, modernSessionId, false, applicationCookies);
	}

	public static ProxyResult failed(String failure) {
		return new ProxyResult(false, failure, null, false, List.of());
	}

	public static ProxyResult ended() {
		return new ProxyResult(true, null, null, true, List.of());
	}
}
