package org.adempiere.web.bridge;

import java.util.Properties;
import java.util.logging.Level;

import javax.servlet.http.HttpSession;

import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortIdentity;
import org.adempiere.web.cohort.CohortRuntime;
import org.adempiere.web.cohort.CohortSelector;
import org.adempiere.web.route.ModernSessionAffinity;
import org.adempiere.web.route.RoutingAudit;
import org.adempiere.webui.session.ServerContext;
import org.compiere.util.CLogger;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.util.EventInterceptor;

/**
 * Takes the cohort decision once, on the first ZK event after which the session
 * context carries a completed role selection.
 *
 * <p>Registered from the derived {@code WEB-INF/zk.xml} as an ordinary ZK 3.6
 * listener. It observes <em>state</em>, never an event name:
 * {@link LegacyIdentity#read} decides whether role selection has completed, so
 * the interceptor is equally correct for the ordinary login flow, an external
 * authentication callback and a role change, and cannot be defeated by ZK
 * renaming or reordering an event.
 *
 * <p>The decision is stored on the container session and never revisited. When
 * it selects the modern runtime the browser is redirected to the context root;
 * the redirect is served by {@link CohortRoutingFilter}, which rotates the
 * session identifier and mints the ticket outside any ZK execution.
 */
public class CohortDecisionInterceptor implements EventInterceptor {

	/** Marks a session whose decision has been taken, whatever it was. */
	public static final String DECIDED_ATTRIBUTE =
			"org.adempiere.web.bridge.cohortDecision";
	static final String REDIRECT_PENDING_ATTRIBUTE =
			"org.adempiere.web.bridge.cohortRedirectPending";

	private static final CLogger log =
			CLogger.getCLogger(CohortDecisionInterceptor.class);

	/**
	 * Whether this session's recorded decision selected the modern runtime.
	 *
	 * <p>The attribute holds the runtime's name - a {@link String}, which every
	 * container can persist - rather than the {@link ModernSessionAffinity}
	 * itself. That is deliberate: when a container restart drops or fails to
	 * restore the affinity, this marker still says the session was decided
	 * modern, and the router refuses it instead of quietly handing a logged-in
	 * modern user the legacy application.
	 */
	public static boolean decidedModern(HttpSession session) {
		return session != null && CohortRuntime.MODERN.name()
				.equals(session.getAttribute(DECIDED_ATTRIBUTE));
	}

	/** Records the decision on the session, in a form a container can persist. */
	static void record(HttpSession session, CohortRuntime runtime) {
		session.setAttribute(DECIDED_ATTRIBUTE, runtime.name());
	}

	@Override
	public Event beforeSendEvent(Event event) {
		return event;
	}

	@Override
	public Event beforePostEvent(Event event) {
		return event;
	}

	@Override
	public Event beforeProcessEvent(Event event) {
		return event;
	}

	@Override
	public void afterProcessEvent(Event event) {
		try {
			decide(event);
		} catch (RuntimeException failure) {
			// A defect here must never break the legacy UI. The session simply
			// stays legacy and the backstop in the routing filter reports that a
			// decision was expected and never taken.
			log.log(Level.SEVERE,
					"The Phase 5e cohort decision could not be taken", failure);
		}
	}

	private void decide(Event event) {
		CohortBridge bridge = CohortBridge.current();
		if (bridge == null) {
			return;
		}
		Execution execution = Executions.getCurrent();
		if (execution == null || execution.getDesktop() == null) {
			return;
		}
		Session session = execution.getDesktop().getSession();
		if (session == null) {
			return;
		}
		Object nativeSession = session.getNativeSession();
		if (!(nativeSession instanceof HttpSession)) {
			return;
		}
		HttpSession httpSession = (HttpSession) nativeSession;
		if (httpSession.getAttribute(DECIDED_ATTRIBUTE) != null) {
			return;
		}
		Properties ctx = ServerContext.getCurrentInstance();
		CohortIdentity identity = LegacyIdentity.read(ctx);
		if (identity == null) {
			return;
		}

		CohortDecision decision =
				CohortSelector.select(bridge.repository().current(), identity);
		if (decision.modern() && !bridge.routingPossible()) {
			// The allowlist selected modern but the handoff is not usable. This
			// must be visible, and it must not route. The session is recorded as
			// LEGACY rather than MODERN on purpose: no affinity exists, so the
			// router must serve it rather than refuse it.
			log.severe("Phase 5e selected the modern runtime but cannot hand over: "
					+ bridge.keyFailure());
			record(httpSession, CohortRuntime.LEGACY);
			return;
		}

		record(httpSession, decision.runtime());
		log.info(RoutingAudit.decisionLine(decision));
		if (!decision.modern()) {
			return;
		}
		httpSession.setAttribute(ModernSessionAffinity.ATTRIBUTE,
				new ModernSessionAffinity(decision, identity));
		httpSession.setAttribute(REDIRECT_PENDING_ATTRIBUTE, Boolean.TRUE);
		// ZK 3.6 drops redirect responses while loginCompleted replaces the
		// role-selection desktop. The router releases this barrier only after
		// that AU response is complete, then turns the frozen desktop's deferred
		// key-listener script into a top-level navigation. Rotation and ticket
		// minting happen only on the resulting ordinary context-root GET.
	}
}
