package org.adempiere.web.route;

import java.util.Locale;

/**
 * Renders a redirect target as a diagnostic descriptor that is safe to log
 * permanently.
 *
 * <p>When {@link LoopbackProxy} refuses to rewrite a {@code Location} header it
 * fails closed, and the public response carries no indication of why. Without
 * the offending value the failure cannot be attributed to the servlet, the
 * container or the routing decision, so the descriptor is written to the
 * container log and harvested into the Phase 5f runtime evidence.
 *
 * <p>A {@code Location} is attacker- and application-influenced, so it is never
 * logged verbatim. This class preserves exactly the components the origin
 * decision depends on - scheme, normalized host, whether the port was explicit
 * or omitted, and the path - and reduces everything that can carry a secret to
 * a presence flag or a redacted placeholder:
 *
 * <ul>
 *   <li>userinfo is removed entirely and reported only as present or absent;
 *   <li>path parameters ({@code ;jsessionid=...}) keep their name and lose
 *       their value;
 *   <li>query parameters keep their name and lose their value;
 *   <li>the fragment is reported only as present or absent.
 * </ul>
 *
 * <p>An unparseable value is itself diagnostic, so this never throws: a value
 * that does not look like a URL is reported as a shape rather than discarded.
 */
public final class RedirectDescriptor {

	private static final String REDACTED = "<redacted>";

	private RedirectDescriptor() {
	}

	/** Describes a redirect target without disclosing any embedded secret. */
	public static String describe(String location) {
		if (location == null) {
			return "<null>";
		}
		if (location.isEmpty()) {
			return "<empty>";
		}
		int schemeEnd = location.indexOf("://");
		if (schemeEnd <= 0 || !isScheme(location.substring(0, schemeEnd))) {
			return "relative " + describeTail(location);
		}
		String scheme = location.substring(0, schemeEnd)
				.toLowerCase(Locale.ROOT);
		String remainder = location.substring(schemeEnd + 3);
		int authorityEnd = remainder.length();
		for (int index = 0; index < remainder.length(); index++) {
			char candidate = remainder.charAt(index);
			if (candidate == '/' || candidate == '?' || candidate == '#') {
				authorityEnd = index;
				break;
			}
		}
		String authority = remainder.substring(0, authorityEnd);
		String tail = remainder.substring(authorityEnd);

		boolean userinfo = authority.indexOf('@') >= 0;
		if (userinfo) {
			authority = authority.substring(authority.lastIndexOf('@') + 1);
		}
		String host;
		String port;
		if (authority.startsWith("[")) {
			int close = authority.indexOf(']');
			if (close < 0) {
				host = authority.toLowerCase(Locale.ROOT);
				port = "malformed";
			} else {
				host = authority.substring(0, close + 1)
						.toLowerCase(Locale.ROOT);
				port = portOf(authority.substring(close + 1));
			}
		} else {
			int colon = authority.lastIndexOf(':');
			if (colon < 0) {
				host = authority.toLowerCase(Locale.ROOT);
				port = "omitted";
			} else {
				host = authority.substring(0, colon).toLowerCase(Locale.ROOT);
				port = portOf(authority.substring(colon));
			}
		}
		return "scheme=" + scheme
				+ " host=" + (host.isEmpty() ? "<empty>" : host)
				+ " port=" + port
				+ " userinfo=" + (userinfo ? "present" : "absent")
				+ " " + describeTail(tail);
	}

	private static boolean isScheme(String candidate) {
		if (candidate.isEmpty()
				|| !Character.isLetter(candidate.charAt(0))) {
			return false;
		}
		for (int index = 1; index < candidate.length(); index++) {
			char character = candidate.charAt(index);
			if (!Character.isLetterOrDigit(character)
					&& character != '+' && character != '.'
					&& character != '-') {
				return false;
			}
		}
		return true;
	}

	private static String portOf(String withColon) {
		String digits = withColon.startsWith(":")
				? withColon.substring(1)
				: withColon;
		if (digits.isEmpty()) {
			return "empty";
		}
		for (int index = 0; index < digits.length(); index++) {
			if (digits.charAt(index) < '0' || digits.charAt(index) > '9') {
				return "malformed";
			}
		}
		return digits;
	}

	private static String describeTail(String tail) {
		String path = tail;
		String query = null;
		boolean fragment = false;
		int hash = path.indexOf('#');
		if (hash >= 0) {
			fragment = true;
			path = path.substring(0, hash);
		}
		int question = path.indexOf('?');
		if (question >= 0) {
			query = path.substring(question + 1);
			path = path.substring(0, question);
		}
		return "path=" + (path.isEmpty() ? "<empty>" : redactPathParameters(path))
				+ " query=" + (query == null ? "absent" : redactQuery(query))
				+ " fragment=" + (fragment ? "present" : "absent");
	}

	/**
	 * Keeps each path segment and the names of its parameters, and redacts
	 * every parameter value. A container may encode the session as
	 * {@code ;jsessionid=...} on any segment, not only the last.
	 */
	private static String redactPathParameters(String path) {
		String[] segments = path.split("/", -1);
		for (int index = 0; index < segments.length; index++) {
			segments[index] = redactSegmentParameters(segments[index]);
		}
		return String.join("/", segments);
	}

	private static String redactSegmentParameters(String segment) {
		int semicolon = segment.indexOf(';');
		if (semicolon < 0) {
			return segment;
		}
		StringBuilder rendered = new StringBuilder(segment.length());
		rendered.append(segment, 0, semicolon);
		for (String parameter
				: segment.substring(semicolon + 1).split(";", -1)) {
			int equals = parameter.indexOf('=');
			rendered.append(';')
					.append(equals < 0 ? parameter
							: parameter.substring(0, equals))
					.append('=')
					.append(REDACTED);
		}
		return rendered.toString();
	}

	/** Keeps every query parameter name and redacts every value. */
	private static String redactQuery(String query) {
		if (query.isEmpty()) {
			return "<empty>";
		}
		StringBuilder rendered = new StringBuilder(query.length());
		for (String parameter : query.split("&", -1)) {
			if (rendered.length() > 0) {
				rendered.append('&');
			}
			int equals = parameter.indexOf('=');
			rendered.append(equals < 0 ? parameter
					: parameter.substring(0, equals))
					.append('=')
					.append(REDACTED);
		}
		return rendered.toString();
	}
}
