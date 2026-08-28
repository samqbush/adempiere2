package org.adempiere.web.route;

import java.io.Serializable;

/** Context-local public-to-modern session binding for Phase 5f. */
public final class ContextSessionAffinity implements Serializable {

	private static final long serialVersionUID = 1L;
	public static final String ATTRIBUTE =
			"org.adempiere.web.route.ContextSessionAffinity";
	public static final String MODERN_MARKER =
			"org.adempiere.web.route.ContextModernDecision";

	private final String deploymentId;
	private String modernSessionId;
	private String failure;

	public ContextSessionAffinity(String deploymentId, String modernSessionId) {
		if (deploymentId == null || deploymentId.isBlank()
				|| modernSessionId == null || modernSessionId.isBlank()) {
			throw new IllegalArgumentException(
					"A deployment and modern session identifier are required");
		}
		this.deploymentId = deploymentId;
		this.modernSessionId = modernSessionId;
	}

	public String deploymentId() {
		return deploymentId;
	}

	public synchronized String modernSessionId() {
		return modernSessionId;
	}

	/**
	 * Atomically accepts a backend session rotation only when this request used
	 * the affinity value that is still current.
	 */
	public synchronized boolean updateModernSessionId(
			String expectedSessionId, String replacementSessionId) {
		if (failure != null || expectedSessionId == null
				|| replacementSessionId == null
				|| replacementSessionId.isBlank()) {
			return false;
		}
		if (modernSessionId.equals(replacementSessionId)) {
			return true;
		}
		if (!modernSessionId.equals(expectedSessionId)) {
			return false;
		}
		modernSessionId = replacementSessionId;
		return true;
	}

	public synchronized boolean usable() {
		return failure == null;
	}

	public synchronized void failed(String reason) {
		failure = reason == null ? "routing-failed" : reason;
		modernSessionId = null;
	}

	public synchronized String failure() {
		return failure;
	}
}
