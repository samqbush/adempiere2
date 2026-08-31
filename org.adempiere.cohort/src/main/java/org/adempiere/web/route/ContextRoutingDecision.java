package org.adempiere.web.route;

/** Framework-neutral session pinning and no-fallback decision. */
public final class ContextRoutingDecision {

	public enum Action {
		LEGACY,
		MODERN_SESSIONLESS,
		MODERN_SESSION,
		INVALIDATE,
		FAIL
	}

	private ContextRoutingDecision() {
	}

	public static Action decide(
			boolean sessionPresent,
			boolean recordedModern,
			ContextSessionAffinity affinity,
			boolean switchEnabled,
			String deploymentId) {
		if (!sessionPresent) {
			return switchEnabled ? Action.MODERN_SESSIONLESS : Action.LEGACY;
		}
		if (!recordedModern) {
			// Any session that existed before the switch was observed remains
			// legacy for its complete lifetime.
			return Action.LEGACY;
		}
		if (affinity == null || !affinity.usable()) {
			return Action.FAIL;
		}
		if (!affinity.deploymentId().equals(deploymentId)) {
			// A context restart, outage replacement, or rollback invalidates
			// rather than converts a live modern session.
			return Action.INVALIDATE;
		}
		return Action.MODERN_SESSION;
	}
}
