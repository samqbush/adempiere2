package org.adempiere.web.route;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Closed, context-specific Phase 5f proxy policy.
 *
 * <p>There is deliberately no fallback/default context. Each public context
 * names its complete transport policy so adding a context cannot accidentally
 * inherit the comparatively narrow {@code /webui} policy.
 */
public final class ContextRoutingPolicy {

	private static final Set<String> COMMON_REQUEST = Set.of(
			"accept", "accept-encoding", "accept-language", "cache-control",
			"content-type", "if-modified-since", "if-none-match", "pragma",
			"user-agent", "x-requested-with");
	private static final Set<String> COMMON_RESPONSE = Set.of(
			"cache-control", "content-disposition", "content-encoding",
			"content-language", "content-type", "etag", "expires",
			"last-modified", "location", "pragma", "vary",
			"x-content-type-options", "x-frame-options");

	private static final Map<String, ContextRoutingPolicy> POLICIES = Map.of(
			"/admin", policy("/admin", "ADMIN",
					Set.of("authorization"), Set.of("www-authenticate"),
					null, 1L << 20, 64L << 20, 5_000, 30_000, 900,
					Set.of()),
			"", policy("", "ROOT",
					Set.of("referer"), Set.of(),
					null, 1L << 20, 16L << 20, 5_000, 15_000, 600,
					Set.of()),
			"/mobile", policy("/mobile", "MOBILE",
					Set.of("referer", "origin"), Set.of(),
					"adempiereInfo", 8L << 20, 64L << 20, 5_000, 30_000, 900,
					Set.of()),
			"/adempiere", policy("/adempiere", "ADEMPIERE",
					Set.of("referer", "origin"), Set.of("content-location"),
					"AdempiereWebUser", 64L << 20, 256L << 20,
					5_000, 120_000, 900, Set.of()),
			"/wstore", policy("/wstore", "WSTORE",
					Set.of("referer", "origin"), Set.of("content-location"),
					"AdempiereWebUser", 32L << 20, 256L << 20,
					5_000, 120_000, 1_800,
					Set.of("/login.jsp", "/loginServlet",
							"/checkOutServlet", "/orderServlet")));

	private final String contextPath;
	private final String keyToken;
	private final Set<String> requestHeaders;
	private final Set<String> responseHeaders;
	private final String applicationCookie;
	private final String sessionCookie;
	private final long requestLimit;
	private final long responseLimit;
	private final int connectTimeout;
	private final int readTimeout;
	private final int sessionTimeout;
	private final Set<String> confidentialPaths;
	private final boolean eligibleInPhase5f;

	private ContextRoutingPolicy(
			String contextPath,
			String keyToken,
			Set<String> requestHeaders,
			Set<String> responseHeaders,
			String applicationCookie,
			long requestLimit,
			long responseLimit,
			int connectTimeout,
			int readTimeout,
			int sessionTimeout,
			Set<String> confidentialPaths,
			boolean eligibleInPhase5f) {
		this.contextPath = contextPath;
		this.keyToken = keyToken;
		this.requestHeaders = requestHeaders;
		this.responseHeaders = responseHeaders;
		this.applicationCookie = applicationCookie;
		this.sessionCookie = "JSESSIONID_" + keyToken;
		this.requestLimit = requestLimit;
		this.responseLimit = responseLimit;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.sessionTimeout = sessionTimeout;
		this.confidentialPaths = confidentialPaths;
		this.eligibleInPhase5f = eligibleInPhase5f;
	}

	private static ContextRoutingPolicy policy(
			String contextPath,
			String keyToken,
			Set<String> request,
			Set<String> response,
			String applicationCookie,
			long requestLimit,
			long responseLimit,
			int connectTimeout,
			int readTimeout,
			int sessionTimeout,
			Set<String> confidentialPaths) {
		return new ContextRoutingPolicy(
				contextPath, keyToken, union(COMMON_REQUEST, request),
				union(COMMON_RESPONSE, response), applicationCookie,
				requestLimit, responseLimit, connectTimeout, readTimeout,
				sessionTimeout, Set.copyOf(confidentialPaths),
				"ROOT".equals(keyToken) || "WSTORE".equals(keyToken));
	}

	private static Set<String> union(Set<String> common, Set<String> additions) {
		java.util.HashSet<String> result = new java.util.HashSet<>(common);
		result.addAll(additions);
		return Set.copyOf(result);
	}

	/** Returns the reviewed policy, or {@code null} for an unknown context. */
	public static ContextRoutingPolicy forContext(String contextPath) {
		return POLICIES.get(contextPath == null ? "" : contextPath);
	}

	public String contextPath() {
		return contextPath;
	}

	public String enableKey() {
		return "MODERN_WEB_" + keyToken + "_ENABLED";
	}

	public boolean forwardRequestHeader(String name) {
		String lower = lower(name);
		if ("origin".equals(lower)) {
			return requestHeaders.contains(lower);
		}
		return lower != null && requestHeaders.contains(lower);
	}

	public boolean forwardResponseHeader(String name) {
		String lower = lower(name);
		return lower != null && responseHeaders.contains(lower);
	}

	public boolean sameOriginOnly(String name) {
		return "origin".equals(lower(name)) && requestHeaders.contains("origin");
	}

	public String applicationCookie() {
		return applicationCookie;
	}

	/** The exact Tomcat 10 cookie name configured for this modern context. */
	public String sessionCookie() {
		return sessionCookie;
	}

	public long requestLimit() {
		return requestLimit;
	}

	public long responseLimit() {
		return responseLimit;
	}

	public int connectTimeout() {
		return connectTimeout;
	}

	public int readTimeout() {
		return readTimeout;
	}

	public int sessionTimeout() {
		return sessionTimeout;
	}

	public boolean confidential(String pathInside) {
		return confidentialPaths.stream().anyMatch(path ->
				path.equals(pathInside) || pathInside.startsWith(path + "/"));
	}

	/** Phase 5f may only activate ROOT and wstore after their gates pass. */
	public boolean eligibleInPhase5f() {
		return eligibleInPhase5f;
	}

	public List<String> requestAllowlist() {
		return requestHeaders.stream().sorted().toList();
	}

	public List<String> responseAllowlist() {
		return responseHeaders.stream().sorted().toList();
	}

	private static String lower(String name) {
		return name == null ? null : name.toLowerCase(Locale.ROOT);
	}
}
