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

	public record Outcome(Action action, String failure) {
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
			return new Outcome(Action.FAIL, result.failure());
		}
		if (result.modernSessionId() != null && bootstrap) {
			affinity.bootstrapped(result.modernSessionId());
		} else if (result.modernSessionId() == null && bootstrap) {
			affinity.failed("bootstrap-no-session");
			return new Outcome(Action.FAIL, "bootstrap-no-session");
		}
		return new Outcome(Action.COMPLETE, null);
	}
}
