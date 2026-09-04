package org.adempiere.web.route;

/**
 * Transport- and framework-neutral policy for the public routing boundary.
 *
 * <p>The Javax filter is only an adapter: this class owns the sticky
 * no-fallback decision, path validation, route classification, and the
 * pre-bootstrap transition rule.
 */
public final class RoutingCore {

	public enum Action {
		LEGACY,
		ROUTE,
		TRANSITION,
		PASS_THROUGH,
		REFUSE,
		NOT_FOUND,
		FAIL
	}

	public record Plan(
			Action action,
			PublicRouteClass routeClass,
			String pathInside,
			String reason,
			int status) {
	}

	private RoutingCore() {
	}

	/** A missing affinity is legacy only when the session was not modern. */
	public static Plan withoutAffinity(boolean decidedModern) {
		return decidedModern
				? plan(Action.FAIL, PublicRouteClass.UNKNOWN, null,
						"decided-modern-without-affinity", 503)
				: plan(Action.LEGACY, PublicRouteClass.UNKNOWN, null, null, 0);
	}

	/**
	 * Closed policy for a request that arrives before the deciding legacy
	 * response has released its redirect barrier.
	 */
	public static Plan redirectPending(String method, String rawPath) {
		boolean rewritten =
				SessionPathParameters.carriesSessionParameter(rawPath);
		String pathInside = SessionPathParameters.strip(rawPath);
		PublicRouteClass routeClass =
				PublicRouteClassifier.classify(method, pathInside);
		if (rewritten) {
			return plan(Action.REFUSE, routeClass, pathInside,
					"url-rewritten-session", 400);
		}
		if (PublicRouteClassifier.transitionSafeAsset(method, pathInside)) {
			return plan(Action.PASS_THROUGH, routeClass, pathInside,
					"transition-safe-asset", 0);
		}
		return plan(Action.REFUSE, routeClass, pathInside,
				"redirect-in-progress", 503);
	}

	/**
	 * Applies every policy that precedes session rotation and proxy admission.
	 */
	public static Plan preflight(
			boolean routingPossible,
			ModernSessionAffinity affinity,
			String method,
			String rawPath) {
		if (affinity == null) {
			throw new IllegalArgumentException("A routed request needs an affinity");
		}
		if (!routingPossible) {
			return terminal(affinity, PublicRouteClass.UNKNOWN,
					"handoff-unavailable", 503);
		}
		if (!affinity.usable()) {
			return terminal(affinity, PublicRouteClass.UNKNOWN,
					affinity.failureReason() == null
							? "affinity-failed"
							: affinity.failureReason(),
					503);
		}

		boolean rewritten =
				SessionPathParameters.carriesSessionParameter(rawPath);
		String pathInside = SessionPathParameters.strip(rawPath);
		PublicRouteClass routeClass =
				PublicRouteClassifier.classify(method, pathInside);
		if (rewritten) {
			return terminal(affinity, routeClass, "url-rewritten-session", 400);
		}
		if (!routeClass.proxyable()) {
			return plan(Action.NOT_FOUND, routeClass, pathInside,
					"route-not-owned", 404);
		}
		if (affinity.phase() == ModernSessionAffinity.Phase.PENDING_ROTATION
				&& (!"GET".equalsIgnoreCase(method)
						|| routeClass != PublicRouteClass.CONTEXT_ROOT)) {
			if (PublicRouteClassifier.transitionSafeAsset(method, pathInside)) {
				return plan(Action.PASS_THROUGH, routeClass, pathInside,
						"transition-safe-asset", 0);
			}
			if ("GET".equalsIgnoreCase(method)
					&& routeClass == PublicRouteClass.ZK_RESOURCE
					&& pathInside.endsWith("/zul/keylistener.js")) {
				return plan(Action.TRANSITION, routeClass, pathInside,
						"transition-to-context-root", 200);
			}
			return plan(Action.REFUSE, routeClass, pathInside,
					"awaiting-context-root", 503);
		}
		return plan(Action.ROUTE, routeClass, pathInside, null, 0);
	}

	/** A binding mismatch is terminal and can never select legacy. */
	public static Plan validateBinding(
			ModernSessionAffinity affinity,
			PublicRouteClass routeClass,
			String currentSessionId) {
		if (currentSessionId != null
				&& currentSessionId.equals(affinity.boundLegacySessionId())) {
			return plan(Action.ROUTE, routeClass, null, null, 0);
		}
		return terminal(affinity, routeClass, "affinity-session-mismatch", 503);
	}

	private static Plan terminal(
			ModernSessionAffinity affinity,
			PublicRouteClass routeClass,
			String reason,
			int status) {
		affinity.failed(reason);
		return plan(Action.FAIL, routeClass, null, reason, status);
	}

	private static Plan plan(
			Action action,
			PublicRouteClass routeClass,
			String pathInside,
			String reason,
			int status) {
		return new Plan(action, routeClass, pathInside, reason, status);
	}
}
