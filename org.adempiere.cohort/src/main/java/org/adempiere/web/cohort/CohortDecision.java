package org.adempiere.web.cohort;

import java.io.Serializable;

/**
 * The sticky, once-per-session cohort decision and the exact reason for it.
 *
 * <p>The reason is a closed enumeration rather than free text so it can be
 * logged and asserted without ever carrying a credential, a cookie, a ticket,
 * an internal session identifier or a tenant identifier.
 *
 * <p>Serializable because it is reachable from a Tomcat session attribute, and
 * a container that persists sessions drops an attribute it cannot write. See
 * {@code ModernSessionAffinity} for why a silently dropped affinity is a
 * routing defect rather than a cosmetic one.
 */
public record CohortDecision(CohortRuntime runtime, Reason reason)
		implements Serializable {

	public enum Reason {

		/** The configuration was unreadable, duplicated or malformed. */
		CONFIGURATION_INVALID(CohortRuntime.LEGACY),

		/** {@code MODERN_WEB_UI_ENABLED} is absent or not the exact value. */
		MASTER_DISABLED(CohortRuntime.LEGACY),

		/** The authenticated user is on the user allowlist. */
		USER_ALLOWLISTED(CohortRuntime.MODERN),

		/** The selected role is on the role allowlist. */
		ROLE_ALLOWLISTED(CohortRuntime.MODERN),

		/** Neither allowlist matched. */
		NOT_ALLOWLISTED(CohortRuntime.LEGACY);

		private final CohortRuntime runtime;

		Reason(CohortRuntime runtime) {
			this.runtime = runtime;
		}

		/** The only runtime this reason may ever select. */
		public CohortRuntime runtime() {
			return runtime;
		}
	}

	public CohortDecision {
		if (runtime == null || reason == null) {
			throw new IllegalArgumentException("A decision needs a runtime and a reason");
		}
		if (reason.runtime() != runtime) {
			throw new IllegalArgumentException(
					"Reason " + reason + " cannot select " + runtime);
		}
	}

	static CohortDecision of(Reason reason) {
		return new CohortDecision(reason.runtime(), reason);
	}

	/** Whether this session is served by the modern runtime. */
	public boolean modern() {
		return runtime == CohortRuntime.MODERN;
	}
}
