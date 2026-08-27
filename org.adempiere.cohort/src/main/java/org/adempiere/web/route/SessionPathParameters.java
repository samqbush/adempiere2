package org.adempiere.web.route;

import java.util.Locale;

/**
 * Removes {@code ;jsessionid=} (and any other) path parameters from a request
 * path.
 *
 * <p>Two reasons, both required by the Phase 5e contract:
 *
 * <ol>
 *   <li>An inbound URL-rewritten session identifier must never be honoured or
 *       forwarded. Both contexts are cookie-only; a path parameter can only be
 *       an attempt to fix a session or to smuggle an identifier past the route
 *       classifier.</li>
 *   <li>{@link PublicRouteClassifier} refuses any path still containing
 *       {@code ;}, so stripping has to happen before classification rather than
 *       inside it - a classifier that silently repaired its input would make
 *       "the path contained a session parameter" unobservable.</li>
 * </ol>
 */
public final class SessionPathParameters {

	private static final String SESSION_PARAMETER = ";jsessionid=";

	private SessionPathParameters() {
	}

	/** @return {@code path} with every {@code ;parameter} segment removed */
	public static String strip(String path) {
		if (path == null || path.indexOf(';') < 0) {
			return path;
		}
		StringBuilder stripped = new StringBuilder(path.length());
		for (String segment : path.split("/", -1)) {
			if (stripped.length() > 0 || path.startsWith("/")) {
				stripped.append('/');
			}
			int parameter = segment.indexOf(';');
			stripped.append(parameter < 0 ? segment : segment.substring(0, parameter));
		}
		String result = stripped.toString();
		// split("/", -1) on "/a" yields ["", "a"], so the loop above emits one
		// leading slash per element. Collapse the duplicate the first element
		// introduces without touching any other position.
		while (result.startsWith("//")) {
			result = result.substring(1);
		}
		return result;
	}

	/** Whether the path carried a URL-rewritten session identifier. */
	public static boolean carriesSessionParameter(String path) {
		return path != null
				&& path.toLowerCase(Locale.ROOT).contains(SESSION_PARAMETER);
	}
}
