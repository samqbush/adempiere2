package org.adempiere.web.route;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a public {@code /webui} request into the reviewed Phase 5e
 * affinity unit.
 *
 * <p>Classification is a pure function of the HTTP method and the path within
 * the context. It deliberately does not read parameters: a ZK asynchronous
 * update carries its command in the request <em>body</em>, and reading the body
 * to classify a request would consume the stream the proxy has to forward
 * verbatim. Polling is therefore an AU request like any other and is
 * distinguished only by the longer read timeout
 * {@link ProxyLimits#POLLING_READ_TIMEOUT_MILLIS} that every AU request gets.
 */
public final class PublicRouteClassifier {

	/** The ZK asynchronous update engine mount inside the context. */
	public static final String AU_PREFIX = "/zkau";

	/** ZK client resources are served by the same servlet under this prefix. */
	public static final String RESOURCE_PREFIX = "/zkau/web/";

	/**
	 * Reviewed static prefixes. The modern WAR's asset ledger
	 * ({@code gradle/phase5/modern-web-assets.tsv}) decides what exists; this
	 * list decides what the public router is willing to forward.
	 */
	private static final List<String> STATIC_PREFIXES =
			List.of("/css/", "/js/", "/images/", "/theme/", "/zkau/view/");

	private static final List<String> STATIC_FILES = List.of("/favicon.ico");

	private PublicRouteClassifier() {
	}

	/**
	 * @param method     the HTTP method, any case
	 * @param pathInside the decoded path within the context, beginning with
	 *                   {@code /} or empty for the bare context; session path
	 *                   parameters must already have been removed by
	 *                   {@link SessionPathParameters}
	 */
	public static PublicRouteClass classify(String method, String pathInside) {
		if (method == null) {
			return PublicRouteClass.UNKNOWN;
		}
		String verb = method.toUpperCase(Locale.ROOT);
		String path = pathInside == null || pathInside.isEmpty() ? "/" : pathInside;
		if (!path.startsWith("/") || path.contains("..") || path.contains(";")) {
			return PublicRouteClass.UNKNOWN;
		}
		boolean read = "GET".equals(verb) || "HEAD".equals(verb);
		boolean post = "POST".equals(verb);
		if (!read && !post) {
			return PublicRouteClass.UNKNOWN;
		}

		if (path.startsWith(RESOURCE_PREFIX)) {
			return read ? PublicRouteClass.ZK_RESOURCE : PublicRouteClass.UNKNOWN;
		}
		if (path.equals(AU_PREFIX) || path.startsWith(AU_PREFIX + "/")) {
			for (String prefix : STATIC_PREFIXES) {
				if (path.startsWith(prefix)) {
					return read ? PublicRouteClass.STATIC_ASSET : PublicRouteClass.UNKNOWN;
				}
			}
			return PublicRouteClass.ZK_AU;
		}
		if (path.equals("/")) {
			return read ? PublicRouteClass.CONTEXT_ROOT : PublicRouteClass.UNKNOWN;
		}
		if (path.endsWith(".zul")) {
			return read ? PublicRouteClass.ZK_PAGE : PublicRouteClass.ZK_PUBLIC_FORM;
		}
		if (path.endsWith(".zhtml")) {
			return read ? PublicRouteClass.ZK_PAGE : PublicRouteClass.UNKNOWN;
		}
		if (!read) {
			return PublicRouteClass.UNKNOWN;
		}
		for (String prefix : STATIC_PREFIXES) {
			if (path.startsWith(prefix)) {
				return PublicRouteClass.STATIC_ASSET;
			}
		}
		return STATIC_FILES.contains(path)
				? PublicRouteClass.STATIC_ASSET
				: PublicRouteClass.UNKNOWN;
	}

	/** Whether an AU request gets the longer polling read timeout. */
	public static boolean polling(PublicRouteClass routeClass) {
		return routeClass == PublicRouteClass.ZK_AU;
	}

	/** The reviewed static prefixes, for the frozen route contract. */
	public static List<String> staticPrefixes() {
		return STATIC_PREFIXES;
	}
}
