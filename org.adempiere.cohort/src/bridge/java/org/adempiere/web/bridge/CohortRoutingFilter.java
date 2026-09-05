package org.adempiere.web.bridge;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.adempiere.web.cohort.CohortIdentity;
import org.adempiere.web.cohort.CohortRuntime;
import org.adempiere.web.handoff.HandoffProtocol;
import org.adempiere.web.handoff.HandoffTicket;
import org.adempiere.web.route.ModernSessionAffinity;
import org.adempiere.web.route.PublicRouteClass;
import org.adempiere.web.route.PublicRouteClassifier;
import org.adempiere.web.route.RoutingAudit;
import org.adempiere.web.route.RoutingCore;
import org.adempiere.web.route.RoutingLifecycle;
import org.adempiere.web.route.SessionPathParameters;
import org.adempiere.webui.session.ServerContext;
import org.adempiere.webui.session.SessionManager;
import org.compiere.model.MSession;
import org.compiere.util.CLogger;
import org.compiere.util.Env;

/**
 * The public {@code /webui} cohort router on Tomcat 9.
 *
 * <p>Tomcat 9 stays the only public ingress. This filter decides, per request,
 * whether the request belongs to a session that has been assigned to the modern
 * runtime, and if so streams it to loopback Tomcat 10 rather than to the frozen
 * ZK 3.6 application in the same context.
 *
 * <h2>Order of business, and why</h2>
 *
 * <ol>
 *   <li><b>Reserved headers are rejected before anything else.</b> A browser
 *       that sends anything in {@link HandoffProtocol#RESERVED_HEADER_PREFIX} is
 *       attempting to forge a handoff, and the request is refused rather than
 *       stripped-and-served: stripping would make the attempt invisible.</li>
 *   <li><b>An undecided session is served by the legacy application
 *       unchanged.</b> No header, no cookie, no path and no body is touched, so
 *       the legacy cohort stays byte-comparable with the frozen Phase 5b
 *       oracle. "Undecided" means no recorded decision at all: a session whose
 *       recorded decision was {@code MODERN} but whose affinity is absent is
 *       refused, not served, because a container that dropped or refused to
 *       restore the affinity must not be able to turn a logged-in modern user
 *       back into a legacy one.</li>
 *   <li><b>A decided-modern session rotates its identifier once</b>, before its
 *       first proxied request, and receives a ticket bound to the rotated
 *       identifier. Rotation happens here, on an ordinary request, rather than
 *       inside a ZK asynchronous update. Which request performs it is decided
 *       by one atomic transition in
 *       {@link ModernSessionAffinity#admit()}; concurrent requests are refused
 *       with an explicit status while the winner finishes, and never rotate a
 *       second time.</li>
 *   <li><b>A routed session ends on both runtimes at once.</b> When the modern
 *       runtime reports that it has ended the session, this filter invalidates
 *       the Tomcat 9 session too, so the affinity <em>and</em> the sticky cohort
 *       decision are destroyed together and the next login is decided again from
 *       the current configuration.</li>
 *   <li><b>A modern session never falls back.</b> An unknown route, a ticket
 *       failure, a missing affinity or an unavailable backend produces an
 *       explicit status, never the legacy application. Showing a different
 *       application to a user who is already logged in to this one is the exact
 *       failure this rule exists to prevent.</li>
 * </ol>
 */
public class CohortRoutingFilter implements Filter {

	private static final CLogger log = CLogger.getCLogger(CohortRoutingFilter.class);

	/**
	 * Stable prefix the Phase 5f route smoke harvests out of the Tomcat 9
	 * container log, shared with the Phase 5f context filter. Everything
	 * appended under it is already sanitized or is a non-secret scalar.
	 */
	static final String PROXY_FAILURE_LOG_PREFIX = "PHASE5F-PROXY-FAIL";

	/** One operator error per burst of backstop firings. */
	private static final long BACKSTOP_REPORT_INTERVAL_MILLIS = 60_000L;
	private static final long END_CLEANUP_WAIT_MILLIS = 30_000L;

	private final AtomicLong lastBackstopReport = new AtomicLong(0);
	private FilterConfig config;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.config = filterConfig;
		CohortBridge bridge = CohortBridge.current();
		if (bridge == null) {
			throw new ServletException(
					"The Phase 5e cohort bridge was not initialised. "
					+ CohortBridgeStartupListener.class.getName()
					+ " must be declared before this filter.");
		}
	}

	@Override
	public void destroy() {
		this.config = null;
	}

	@Override
	public void doFilter(
			ServletRequest servletRequest,
			ServletResponse servletResponse,
			FilterChain chain) throws IOException, ServletException {
		if (!(servletRequest instanceof HttpServletRequest)
				|| !(servletResponse instanceof HttpServletResponse)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		if (carriesReservedHeader(request)) {
			log.severe(RoutingAudit.line(CohortRuntime.LEGACY,
					PublicRouteClass.UNKNOWN, "reserved-header-rejected"));
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		if (SessionPathParameters.carriesSessionParameter(
				request.getRequestURI())) {
			log.severe(RoutingAudit.line(CohortRuntime.LEGACY,
					PublicRouteClass.UNKNOWN, "url-rewritten-session"));
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		HttpSession session = request.getSession(false);
		ModernSessionAffinity affinity;
		boolean decidedModern;
		boolean decisionRecorded;
		boolean redirectPending;
		String initialSessionId;
		try {
			if (session == null) {
				affinity = null;
				decidedModern = false;
				decisionRecorded = false;
				redirectPending = false;
				initialSessionId = null;
			} else {
				affinity = (ModernSessionAffinity) session.getAttribute(
						ModernSessionAffinity.ATTRIBUTE);
				Object decision = session.getAttribute(
						CohortDecisionInterceptor.DECIDED_ATTRIBUTE);
				decidedModern = CohortRuntime.MODERN.name().equals(decision);
				decisionRecorded = decision != null;
				redirectPending = Boolean.TRUE.equals(session.getAttribute(
						CohortDecisionInterceptor.REDIRECT_PENDING_ATTRIBUTE));
				initialSessionId = session.getId();
			}
		} catch (IllegalStateException concurrentlyInvalidated) {
			PublicRouteClass routeClass = PublicRouteClassifier.classify(
					request.getMethod(), pathInside(request));
			if (!handleFreshLoginAfterEnd(
					request, response, chain, null, routeClass)) {
				refuse(response, routeClass, "session-ended-during-route",
						HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
			return;
		}
		if (affinity == null) {
			RoutingCore.Plan plan = RoutingCore.withoutAffinity(decidedModern);
			// A session that was decided modern and has no affinity is NOT an
			// undecided session. It is a session whose affinity the container
			// dropped or refused to restore, and handing it to the legacy
			// application would show a different product to a user who is
			// already logged in to this one. Fail closed instead.
			if (decidedModern) {
				log.severe(RoutingAudit.line(CohortRuntime.MODERN,
						plan.routeClass(), plan.reason()));
				response.sendError(plan.status());
				return;
			}
			backstop(request, decisionRecorded, initialSessionId);
			chain.doFilter(servletRequest, servletResponse);
			releaseRedirectBarrier(request);
			return;
		}

		if (redirectPending) {
			RoutingCore.Plan pending = RoutingCore.redirectPending(
					request.getMethod(), pathInside(request));
			if (pending.action() == RoutingCore.Action.PASS_THROUGH) {
				log.info(RoutingAudit.line(CohortRuntime.MODERN,
						pending.routeClass(), pending.reason()));
				chain.doFilter(servletRequest, servletResponse);
				return;
			}
			refuse(response, pending.routeClass(), pending.reason(),
					pending.status());
			return;
		}
		route(request, response, session, affinity, chain);
	}

	private void releaseRedirectBarrier(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return;
		}
		try {
			if (Boolean.TRUE.equals(session.getAttribute(
					CohortDecisionInterceptor.REDIRECT_PENDING_ATTRIBUTE))
					&& session.getAttribute(ModernSessionAffinity.ATTRIBUTE) != null) {
				session.removeAttribute(
						CohortDecisionInterceptor.REDIRECT_PENDING_ATTRIBUTE);
			}
		} catch (IllegalStateException alreadyDestroyed) {
			log.fine("The cohort decision session ended before its redirect completed");
		}
	}

	private void route(
			HttpServletRequest request,
			HttpServletResponse response,
			HttpSession session,
			ModernSessionAffinity affinity,
			FilterChain chain) throws IOException, ServletException {
		CohortBridge bridge = CohortBridge.current();
		String rawPath = pathInside(request);
		RoutingCore.Plan plan = RoutingCore.preflight(
				bridge != null && bridge.routingPossible(),
				affinity, request.getMethod(), rawPath);
		PublicRouteClass routeClass = plan.routeClass();
		String pathInside = plan.pathInside();
		switch (plan.action()) {
			case FAIL:
				fail(response, affinity, routeClass, plan.reason(), plan.status());
				return;
			case NOT_FOUND:
				log.info(RoutingAudit.line(
						CohortRuntime.MODERN, routeClass, plan.reason()));
				response.sendError(plan.status());
				return;
			case TRANSITION:
				sendTransitionScript(request, response, routeClass);
				return;
			case REFUSE:
				refuse(response, routeClass, plan.reason(), plan.status());
				return;
			case PASS_THROUGH:
				log.info(RoutingAudit.line(
						CohortRuntime.MODERN, routeClass, plan.reason()));
				chain.doFilter(request, response);
				return;
			case ROUTE:
				break;
			case LEGACY:
			default:
				throw new IllegalStateException(
						"A modern affinity produced " + plan.action());
		}

		String ticket = null;
		ModernSessionAffinity.Admission admission = affinity.admit();
		if (admission.step() == ModernSessionAffinity.Step.ROTATE) {
			if (!rotateAndTicket(request, response, session, affinity, bridge)) {
				fail(response, affinity, routeClass, "rotation-failed",
						HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			// Re-admission, not a second read of the phase: this call is what
			// hands the ticket over, and it is the same atomic transition a
			// concurrent request would have lost.
			admission = affinity.admit();
		}
		switch (admission.step()) {
			case BOOTSTRAP:
				ticket = admission.ticket();
				break;
			case PROXY:
				break;
			case IN_PROGRESS:
				// Another request owns the rotation or the bootstrap. Refusing
				// this one is explicit and, unlike failing the affinity, leaves
				// the session usable for the request that won the race.
				refuse(response, routeClass, "handoff-in-progress",
						HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			case ROTATE:
			case REFUSED:
			default:
				fail(response, affinity, routeClass,
						affinity.failureReason() == null
								? "affinity-not-admissible"
								: affinity.failureReason(),
						HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
		}

		String currentSessionId;
		try {
			HttpSession currentSession = request.getSession(false);
			currentSessionId = currentSession == null
					? null
					: currentSession.getId();
		} catch (IllegalStateException concurrentlyInvalidated) {
			currentSessionId = null;
		}
		if (currentSessionId == null) {
			if (!handleFreshLoginAfterEnd(
					request, response, chain, affinity, routeClass)) {
				refuse(response, routeClass, "session-ended-during-route",
						HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
			return;
		}
		RoutingCore.Plan binding = RoutingCore.validateBinding(
				affinity, routeClass, currentSessionId);
		if (binding.action() == RoutingCore.Action.FAIL) {
			fail(response, affinity, routeClass,
					binding.reason(), binding.status());
			return;
		}

		try (ModernBackendProxy.Result result = backendProxy(bridge.backend())
				.proxy(request, response, routeClass, pathInside,
						affinity.modernSessionId(), ticket,
						affinity.boundLegacySessionId())) {
			RoutingLifecycle.Outcome lifecycle = RoutingLifecycle.apply(
					affinity, ticket != null, result.coreResult());
			if (result.sessionEnded()) {
				endRoutedSession(
						request, response, session, affinity, routeClass, chain);
				return;
			}
			if (lifecycle.action() == RoutingLifecycle.Action.FAIL) {
				fail(response, affinity, routeClass, lifecycle.failure(),
						lifecycle.diagnostic(),
						HttpServletResponse.SC_BAD_GATEWAY);
				return;
			}
			result.commitTo(response);
		}
		log.fine(RoutingAudit.line(CohortRuntime.MODERN, routeClass, "proxied"));
	}

	private void sendTransitionScript(
			HttpServletRequest request,
			HttpServletResponse response,
			PublicRouteClass routeClass) throws IOException {
		String context = request.getContextPath();
		String target = (context == null ? "" : context) + "/";
		response.setStatus(HttpServletResponse.SC_OK);
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/javascript");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write("window.top.location.replace('"
				+ target.replace("'", "\\'") + "');");
		log.info(RoutingAudit.line(
				CohortRuntime.MODERN, routeClass, "transition-to-context-root"));
	}

	/** Test seam. The proxy is constructed here and nowhere else. */
	ModernBackendProxy backendProxy(String backend) {
		return new ModernBackendProxy(backend);
	}

	/**
	 * Ends a routed session on the Tomcat 9 side, because the modern runtime
	 * has already ended it on its own.
	 *
	 * <p>Invalidating rather than merely removing the affinity is the point.
	 * The cohort decision is sticky per session, so a session that kept both
	 * its affinity and its recorded decision after a logout would carry the old
	 * decision into the next login on the same browser: a user the
	 * configuration no longer selects would stay modern indefinitely.
	 * Invalidation destroys the affinity, the decision and every legacy
	 * {@code SessionManager} entry in one step, through the container's own
	 * lifecycle, so the next login starts undecided and takes a fresh
	 * fail-closed cohort decision.
	 *
	 * <p>The browser is then redirected to the public context root, where a
	 * brand-new session is created and the legacy login form is served.
	 */
	private void endRoutedSession(
			HttpServletRequest request,
			HttpServletResponse response,
			HttpSession session,
			ModernSessionAffinity affinity,
			PublicRouteClass routeClass,
			FilterChain chain) throws IOException, ServletException {
		RoutingLifecycle.EndOutcome ending = RoutingLifecycle.end(
				affinity, request.getMethod(), routeClass,
				response.isCommitted());
		if (ending.cleanupOwner()) {
			// Before the invalidation, not after: the container calls
			// sessionDestroyed on this thread, and the frozen listener's cleanup
			// resolves its work through the thread's ServerContext. Cleaning here
			// installs the abandoned session's own context for the duration.
			discardLegacySessionState(session.getId());
			try {
				session.invalidate();
			} catch (IllegalStateException alreadyGone) {
				log.fine("A routed session was already destroyed at its end");
			}
			affinity.completeEndCleanup();
			log.info(RoutingAudit.line(
					CohortRuntime.MODERN, routeClass, "routed-session-ended"));
		} else {
			log.fine(RoutingAudit.line(
					CohortRuntime.MODERN, routeClass,
					"routed-session-end-duplicate"));
		}

		switch (ending.response()) {
			case HTTP_REDIRECT:
				log.info(RoutingAudit.line(
						CohortRuntime.MODERN, routeClass,
						"routed-session-http-redirect"));
				response.sendRedirect(contextRoot(request));
				return;
			case ZK_AU_REDIRECT:
				log.info(RoutingAudit.line(
						CohortRuntime.MODERN, routeClass,
						"routed-session-au-redirect"));
				sendAuRedirect(response, contextRoot(request));
				return;
			case FRESH_LOGIN:
				handleFreshLoginAfterEnd(
						request, response, chain, affinity, routeClass);
				return;
			case NONE:
			default:
				if (!response.isCommitted()) {
					response.setStatus(HttpServletResponse.SC_NO_CONTENT);
					response.setHeader("Cache-Control", "no-store");
				}
		}
	}

	private static String contextRoot(HttpServletRequest request) {
		String context = request.getContextPath();
		return (context == null || context.isEmpty() ? "" : context) + "/";
	}

	private static boolean freshLoginRequest(
			HttpServletRequest request, PublicRouteClass routeClass) {
		return routeClass == PublicRouteClass.CONTEXT_ROOT
				&& "GET".equalsIgnoreCase(request.getMethod());
	}

	private boolean handleFreshLoginAfterEnd(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain,
			ModernSessionAffinity affinity,
			PublicRouteClass routeClass) throws IOException, ServletException {
		if (!freshLoginRequest(request, routeClass)) {
			return false;
		}
		if (affinity != null
				&& !affinity.awaitEndCleanup(END_CLEANUP_WAIT_MILLIS)) {
			log.severe(RoutingAudit.line(
					CohortRuntime.MODERN, routeClass,
					"routed-session-cleanup-timeout"));
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return true;
		}
		log.info(RoutingAudit.line(
				CohortRuntime.MODERN, routeClass,
				"routed-session-fresh-login"));
		chain.doFilter(request, response);
		return true;
	}

	/**
	 * Emits the wire representation of ZK's {@code AuSendRedirect}. An HTTP
	 * redirect returned to an AU/XHR request does not navigate the top-level
	 * browser page.
	 */
	private static void sendAuRedirect(
			HttpServletResponse response, String target) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		String escaped = target.replace("\\", "\\\\").replace("\"", "\\\"");
		response.getWriter().write(
				"{\"rs\":[[\"redirect\",[\"" + escaped + "\",\"\"]]]}");
	}

	/**
	 * Rotates the Tomcat 9 session identifier, discards the abandoned legacy ZK
	 * state, and mints the ticket bound to the new identifier.
	 *
	 * <p>Rotation is the session-fixation protection: an identifier a user held
	 * before authenticating is never the identifier a modern session is bound
	 * to. It happens exactly once per session, only when the session is assigned
	 * to the modern cohort, so the legacy cohort's observable behaviour is
	 * unchanged. Exactly one request reaches this method per session: the caller
	 * has already been admitted with {@code Step.ROTATE}, and a concurrent
	 * request is told the handoff is in progress rather than rotating a second
	 * time.
	 *
	 * @return whether the session was rotated and ticketed
	 */
	private boolean rotateAndTicket(
			HttpServletRequest request,
			HttpServletResponse response,
			HttpSession session,
			ModernSessionAffinity affinity,
			CohortBridge bridge) {
		String previousId = session.getId();
		String rotatedId;
		try {
			rotatedId = request.changeSessionId();
		} catch (IllegalStateException noSession) {
			log.log(Level.SEVERE, "The Phase 5e session could not be rotated",
					noSession);
			return false;
		}
		discardLegacySessionState(previousId);

		CohortIdentity identity = affinity.identity();
		HandoffTicket minted = bridge.codec()
				.issue(rotatedId, identity, System.currentTimeMillis());
		String encoded = bridge.codec().encode(minted, bridge.key());
		affinity.ticketed(rotatedId, encoded);
		harmonisePublicCookie(request, response, rotatedId);
		return true;
	}

	/**
	 * Removes every {@code SessionManager} entry the abandoned legacy identifier
	 * owned, exactly once.
	 *
	 * <p>The legacy ZK desktop for this session is being discarded, so its
	 * ADempiere session is logged out and its caches are dropped. Without this
	 * the rotated session would leave a permanently unreachable entry in seven
	 * static maps and an open {@code AD_Session} row.
	 *
	 * <p>This runs on an ordinary request thread outside any ZK execution, so
	 * the abandoned session's own context is installed for the duration and
	 * removed again in a {@code finally}. UI teardown is deliberately not
	 * attempted here: ZK permits component detach only in an event listener.
	 * The non-UI discard removes all seven cache entries directly. The
	 * {@code AD_Session} row is closed from the abandoned context, so it is
	 * logged out even when the ZK application reference has already been
	 * collected.
	 */
	private void discardLegacySessionState(String previousId) {
		if (previousId == null || !SessionManager.existsSession(previousId)) {
			return;
		}
		Properties abandoned = SessionManager.getSessionContext(previousId);
		Properties restore = ServerContext.getCurrentInstance();
		boolean hadContext = restore != null && !restore.isEmpty();
		try {
			if (abandoned != null) {
				ServerContext.setCurrentInstance(abandoned);
				closeAdempiereSession(abandoned);
			}
			discardLegacyCaches(previousId);
		} catch (RuntimeException partial) {
			// The rotation itself has already happened; reporting is the only
			// useful action left, and failing the request here would strand a
			// session that is now modern with no way to reach either runtime.
			log.log(Level.SEVERE,
					"The abandoned legacy session state was not fully discarded",
					partial);
		} finally {
			if (hadContext) {
				ServerContext.setCurrentInstance(restore);
			} else {
				ServerContext.dispose();
			}
		}
	}

	/**
	 * Drops the frozen runtime's cache entries without invoking ZK component
	 * teardown outside an event listener.
	 */
	static void discardLegacyCaches(String sessionId) {
		SessionManager.getSessionCache().remove(sessionId);
		SessionManager.getSessionContextCache().remove(sessionId);
		SessionManager.getAppicationCache().remove(sessionId);
		SessionManager.getDesktopCache().remove(sessionId);
		SessionManager.getExecutionCarryOverCache().remove(sessionId);
		SessionManager.getSessionUserPreferenceCache().remove(sessionId);
		SessionManager.getUserAuthenticationCache().remove(sessionId);
	}

	/**
	 * Closes the {@code AD_Session} row the abandoned legacy context owns.
	 *
	 * <p>Explicit rather than inherited from {@code SessionManager.clearSession}:
	 * that method only logs out when a live {@code IWebClient} is still reachable
	 * through a weak reference, and the row has to be closed whether or not the
	 * ZK application survived.
	 */
	private static void closeAdempiereSession(Properties abandoned) {
		int adempiereSessionId = Env.getContextAsInt(abandoned, "#AD_Session_ID");
		if (adempiereSessionId <= 0) {
			return;
		}
		try {
			new MSession(abandoned, adempiereSessionId, null).logout();
		} catch (RuntimeException notClosed) {
			log.log(Level.SEVERE,
					"The abandoned legacy AD_Session row was not closed", notClosed);
		}
	}

	/**
	 * Re-asserts the single public session cookie with the reviewed attributes.
	 *
	 * <p>Tomcat writes its own cookie on rotation; this replaces it with one
	 * that is {@code HttpOnly}, {@code SameSite=Lax}, path-scoped to the
	 * context, and {@code Secure} whenever the public request arrived over
	 * HTTPS. There is still exactly one cookie: the same name, the same path.
	 */
	private void harmonisePublicCookie(
			HttpServletRequest request,
			HttpServletResponse response,
			String rotatedId) {
		Cookie cookie = new Cookie("JSESSIONID", rotatedId);
		String path = request.getContextPath();
		cookie.setPath(path == null || path.isEmpty() ? "/" : path);
		cookie.setHttpOnly(true);
		cookie.setSecure(request.isSecure());
		response.addCookie(cookie);
		appendSameSite(response);
	}

	/**
	 * The backstop.
	 *
	 * <p>If the master switch is on and the session is fully authenticated but
	 * no cohort decision was ever recorded, the event interceptor did not run.
	 * That is exactly the shape a mutation that removes the interceptor
	 * produces, and it must be visible rather than presenting as "everybody
	 * happens to be legacy today".
	 */
	private void backstop(
			HttpServletRequest request,
			boolean decisionRecorded,
			String sessionId) {
		if (sessionId == null || decisionRecorded) {
			return;
		}
		if (!"GET".equals(request.getMethod())
				|| !"/".equals(pathInside(request))) {
			return;
		}
		CohortBridge bridge = CohortBridge.current();
		if (bridge == null || !bridge.routingPossible()) {
			return;
		}
		if (!bridge.repository().current().enabled()) {
			return;
		}
		if (!LegacyIdentity.complete(
				SessionManager.getSessionContext(sessionId))) {
			return;
		}
		if (config != null) {
			Object previous = config.getServletContext()
					.getAttribute(CohortBridge.BACKSTOP_ATTRIBUTE);
			long count = previous instanceof Long ? (Long) previous : 0L;
			config.getServletContext()
					.setAttribute(CohortBridge.BACKSTOP_ATTRIBUTE, count + 1);
		}
		long now = System.currentTimeMillis();
		long previousReport = lastBackstopReport.get();
		if (now - previousReport > BACKSTOP_REPORT_INTERVAL_MILLIS
				&& lastBackstopReport.compareAndSet(previousReport, now)) {
			log.severe("Phase 5e backstop: a fully authenticated session reached "
					+ "the router with no recorded cohort decision. The ZK event "
					+ "interceptor is not registered.");
		}
	}

	private void fail(
			HttpServletResponse response,
			ModernSessionAffinity affinity,
			PublicRouteClass routeClass,
			String reason,
			int status) throws IOException {
		fail(response, affinity, routeClass, reason, reason, status);
	}

	/**
	 * Fails one exchange closed.
	 *
	 * <p>{@code reason} is the stable audited reason code. {@code diagnostic}
	 * adds the already-sanitized descriptor the proxy recorded, under the
	 * prefix the Phase 5f route smoke harvests from the container log; a 502
	 * carries no indication of its own cause, so without it a route failure
	 * cannot be attributed.
	 */
	private void fail(
			HttpServletResponse response,
			ModernSessionAffinity affinity,
			PublicRouteClass routeClass,
			String reason,
			String diagnostic,
			int status) throws IOException {
		affinity.failed(reason);
		log.severe(RoutingAudit.line(CohortRuntime.MODERN, routeClass, reason));
		if (diagnostic != null && !diagnostic.equals(reason)) {
			log.severe(PROXY_FAILURE_LOG_PREFIX
					+ " routeClass=" + routeClass
					+ " reason=" + diagnostic);
		}
		if (!response.isCommitted()) {
			response.sendError(status);
		}
	}

	/**
	 * Refuses one request without poisoning the session.
	 *
	 * <p>The distinction from {@link #fail} matters: the only reason to refuse
	 * without failing is that another request on the same session is mid-handoff
	 * and is expected to succeed. Marking the affinity {@code FAILED} here would
	 * let the loser of a race destroy the winner's session, which is a worse
	 * outcome than the race itself. It is still an explicit status, never the
	 * legacy application.
	 */
	private void refuse(
			HttpServletResponse response,
			PublicRouteClass routeClass,
			String reason,
			int status) throws IOException {
		log.info(RoutingAudit.line(CohortRuntime.MODERN, routeClass, reason));
		if (!response.isCommitted()) {
			response.sendError(status);
		}
	}

	private static boolean carriesReservedHeader(HttpServletRequest request) {
		Enumeration<String> names = request.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			if (HandoffProtocol.reserved(names.nextElement())) {
				return true;
			}
		}
		return false;
	}

	private static String pathInside(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null || uri.isEmpty()) {
			return "/";
		}
		String context = request.getContextPath();
		String inside = context != null && !context.isEmpty() && uri.startsWith(context)
				? uri.substring(context.length())
				: uri;
		return inside.isEmpty() ? "/" : inside;
	}

	/**
	 * Adds {@code SameSite=Lax} to the session cookie.
	 *
	 * <p>Servlet 4.0's {@code Cookie} has no SameSite accessor, and Tomcat 9's
	 * own {@code CookieProcessor} configuration is a context-wide setting the
	 * derived WAR must not silently change for the legacy cohort. Appending the
	 * attribute to the header this filter just wrote keeps the change scoped to
	 * the one cookie the router re-asserts.
	 */
	private static void appendSameSite(HttpServletResponse response) {
		java.util.Collection<String> cookies = response.getHeaders("Set-Cookie");
		if (cookies.isEmpty()) {
			return;
		}
		boolean first = true;
		for (String cookie : cookies) {
			String value = cookie.contains("SameSite=")
					? cookie
					: cookie + "; SameSite=Lax";
			if (first) {
				response.setHeader("Set-Cookie", value);
				first = false;
			} else {
				response.addHeader("Set-Cookie", value);
			}
		}
	}
}
