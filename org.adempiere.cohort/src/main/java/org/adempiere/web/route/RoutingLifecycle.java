package org.adempiere.web.route;

/**
 * Neutral post-proxy lifecycle policy.
 *
 * <p>Container invalidation and redirects remain adapter work; deciding
 * whether to end, bind, continue, or fail the sticky modern session does not.
 */
public final class RoutingLifecycle {

	public enum Action {
		COMPLETE,
		END_SESSION,
		FAIL
	}

	public enum EndResponse {
		NONE,
		HTTP_REDIRECT,
		ZK_AU_REDIRECT
	}

	/**
	 * The lifecycle decision.
	 *
	 * <p>{@code failure} is the stable reason code that drives affinity and
	 * the routing contracts. {@code diagnostic} is the same code plus any
	 * already-sanitized descriptor the proxy recorded; it is for logs and
	 * evidence only and must never be asserted on or sent to a client.
	 */
	public record Outcome(Action action, String failure, String diagnostic) {

		public Outcome(Action action, String failure) {
			this(action, failure, failure);
		}
	}

	public record EndOutcome(boolean cleanupOwner, EndResponse response) {
	}

	private RoutingLifecycle() {
	}

	public static Outcome apply(
			ModernSessionAffinity affinity,
			boolean bootstrap,
			ProxyResult result) {
		if (result.sessionEnded()) {
			return new Outcome(Action.END_SESSION, null);
		}
		if (!result.completed()) {
			affinity.failed(result.failure());
			return new Outcome(
					Action.FAIL, result.failure(), result.diagnostic());
		}
		if (result.modernSessionId() != null && bootstrap) {
			affinity.bootstrapped(result.modernSessionId());
		} else if (result.modernSessionId() == null && bootstrap) {
			affinity.failed("bootstrap-no-session");
			return new Outcome(Action.FAIL, "bootstrap-no-session");
		}
		return new Outcome(Action.COMPLETE, null);
	}

	/**
	 * Chooses the route-appropriate response after END and atomically claims
	 * cleanup plus per-transport navigation ownership on the shared affinity.
	 */
	public static EndOutcome end(
			ModernSessionAffinity affinity,
			String method,
			PublicRouteClass routeClass,
			boolean responseCommitted) {
		EndResponse candidate = EndResponse.NONE;
		if (!responseCommitted && routeClass == PublicRouteClass.ZK_AU) {
			candidate = EndResponse.ZK_AU_REDIRECT;
		} else if (!responseCommitted
				&& "GET".equalsIgnoreCase(method)
				&& (routeClass == PublicRouteClass.CONTEXT_ROOT
						|| routeClass == PublicRouteClass.ZK_PAGE)) {
			candidate = EndResponse.HTTP_REDIRECT;
		}
		ModernSessionAffinity.EndClaim claim =
				affinity.claimEnd(switch (candidate) {
					case HTTP_REDIRECT -> ModernSessionAffinity.EndNavigation.HTTP;
					case ZK_AU_REDIRECT -> ModernSessionAffinity.EndNavigation.ZK_AU;
					case NONE -> ModernSessionAffinity.EndNavigation.NONE;
				});
		return new EndOutcome(
				claim.cleanupOwner(),
				claim.navigationOwner() ? candidate : EndResponse.NONE);
	}
}
