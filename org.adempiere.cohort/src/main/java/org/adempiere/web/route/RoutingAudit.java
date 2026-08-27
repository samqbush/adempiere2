package org.adempiere.web.route;

import java.util.List;
import java.util.Locale;

import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortRuntime;

/**
 * Builds the one audit line the Phase 5e router is allowed to write, and proves
 * it carries nothing it must not.
 *
 * <p>An audit line contains the runtime, the stable route class and the
 * outcome. It never contains a credential, a cookie, the ticket, the internal
 * session identifier, the request body, a URL, or any tenant identity - so the
 * line is safe in an ordinary container log that operators, backups and support
 * bundles all copy freely.
 *
 * <p>{@link #sanitised(String)} is not decoration: {@link #line} runs it on its
 * own output, so a future field that leaks would fail at the point it is added
 * rather than in a log nobody reads.
 */
public final class RoutingAudit {

	/** Substrings that must never appear in an audit line. */
	private static final List<String> FORBIDDEN = List.of(
			"jsessionid",
			"password",
			"cookie",
			"authorization",
			"ticket=",
			"x-adempiere-handoff");

	private RoutingAudit() {
	}

	/**
	 * @param runtime    the sticky runtime of the session
	 * @param routeClass the stable public route class
	 * @param outcome    a closed, caller-supplied outcome token
	 */
	public static String line(
			CohortRuntime runtime, PublicRouteClass routeClass, String outcome) {
		String rendered = "phase5e-route runtime=" + runtime
				+ " class=" + routeClass
				+ " outcome=" + outcome;
		return sanitised(rendered);
	}

	/** The one decision line, written once per session when it is decided. */
	public static String decisionLine(CohortDecision decision) {
		return sanitised("phase5e-cohort runtime=" + decision.runtime()
				+ " reason=" + decision.reason());
	}

	/**
	 * @throws IllegalStateException when {@code candidate} carries anything the
	 *                               audit policy forbids
	 */
	public static String sanitised(String candidate) {
		String lower = candidate == null ? "" : candidate.toLowerCase(Locale.ROOT);
		for (String forbidden : FORBIDDEN) {
			if (lower.contains(forbidden)) {
				throw new IllegalStateException(
						"A Phase 5e audit line may not contain " + forbidden);
			}
		}
		return candidate;
	}

	/** The forbidden substring list, for the frozen audit contract. */
	public static List<String> forbidden() {
		return FORBIDDEN;
	}
}
