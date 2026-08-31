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
}
