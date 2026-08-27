package org.adempiere.web.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortIdentity;
import org.adempiere.web.cohort.CohortRuntime;
import org.adempiere.web.handoff.HandoffProtocol;
import org.adempiere.web.route.ModernSessionAffinity;
import org.adempiere.webui.IWebClient;
import org.adempiere.webui.desktop.IDesktop;
import org.adempiere.webui.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Javax/ZK 3.6 bridge, on the pinned frozen closure.
 *
 * <p>These tests run against the same {@code javax.servlet} API and the same
 * ZK 3.6 and ADempiere classes the derived {@code webui.war} deploys, so a
 * bridge that would not link inside the frozen archive cannot pass here.
 */
@Tag("UnitTest")
@DisplayName("Phase 5e legacy bridge")
class CohortRoutingFilterTest {

	private static final CohortIdentity IDENTITY =
			new CohortIdentity(101, 102, 11, 11, 103, "en_US");

	@AfterEach
	void clearBridge() {
		CohortBridge.install(null, null, null, null);
		CohortBridge.shutdown();
	}

	private static Enumeration<String> names(String... values) {
		return new Vector<>(List.of(values)).elements();
	}

	@Test
	@DisplayName("a browser-supplied internal header is rejected, not stripped")
	void reservedHeaderIsRejected() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames())
				.thenReturn(names("Accept", HandoffProtocol.TICKET_HEADER));

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	@DisplayName("an undecided session is served by the legacy application untouched")
	void undecidedSessionPassesThrough() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(null);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt());
		verify(response, never()).addCookie(any());
	}

	@Test
	@DisplayName("a modern session asking for an unowned route gets 404, never legacy")
	void modernSessionNeverFallsBackToLegacy() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpSession session = mock(HttpSession.class);
		ModernSessionAffinity affinity = bootstrappedAffinity();
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE)).thenReturn(affinity);
		when(session.getId()).thenReturn("ROTATED");

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("GET");
		when(request.getContextPath()).thenReturn("/webui");
		when(request.getRequestURI()).thenReturn("/webui/timeline");

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
		verify(chain, never()).doFilter(any(), any());
		assertTrue(affinity.usable(),
				"an unowned route must not poison the session");
	}

	@Test
	@DisplayName("an inbound URL-rewritten session identifier fails the modern request")
	void urlRewrittenSessionFailsClosed() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpSession session = mock(HttpSession.class);
		ModernSessionAffinity affinity = bootstrappedAffinity();
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE)).thenReturn(affinity);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("GET");
		when(request.getContextPath()).thenReturn("/webui");
		when(request.getRequestURI())
				.thenReturn("/webui/index.zul;jsessionid=FORGED");

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST);
		verify(chain, never()).doFilter(any(), any());
		assertFalse(affinity.usable(), "the session must not stay routable");
		assertEquals(CohortRuntime.MODERN, affinity.decision().runtime(),
				"a failed modern session stays modern");
	}

	@Test
	@DisplayName("a failed modern session is refused rather than served by legacy")
	void failedAffinityIsRefused() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpSession session = mock(HttpSession.class);
		ModernSessionAffinity affinity = bootstrappedAffinity();
		affinity.failed("backend-unavailable");
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE)).thenReturn(affinity);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	@DisplayName("the filter refuses to initialise without the startup listener")
	void filterRequiresTheStartupListener() {
		CohortBridge.shutdown();
		assertThrows(ServletException.class,
				() -> new CohortRoutingFilter().init(mock(FilterConfig.class)));
	}

	@Test
	@DisplayName("a deployment missing the router registration fails loudly")
	void missingFilterRegistrationFailsDeployment() {
		ServletContext context = mock(ServletContext.class);
		when(context.getFilterRegistration(eq("phase5eCohortRouter")))
				.thenReturn(null);
		when(context.getResourceAsStream("/WEB-INF/zk.xml")).thenReturn(
				new java.io.ByteArrayInputStream(
						CohortDecisionInterceptor.class.getName().getBytes()));
		ServletContextEvent event = new ServletContextEvent(context);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new CohortBridgeStartupListener().contextInitialized(event));
		assertTrue(failure.getMessage().contains("phase5eCohortRouter"));
	}

	@Test
	@DisplayName("a deployment missing the ZK interceptor fails loudly")
	void missingInterceptorFailsDeployment() {
		ServletContext context = mock(ServletContext.class);
		when(context.getFilterRegistration(eq("phase5eCohortRouter")))
				.thenReturn(mock(FilterRegistration.class));
		when(context.getResourceAsStream("/WEB-INF/zk.xml")).thenReturn(
				new java.io.ByteArrayInputStream(
						"<zk><listener/></zk>".getBytes()));
		ServletContextEvent event = new ServletContextEvent(context);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> new CohortBridgeStartupListener().contextInitialized(event));
		assertTrue(failure.getMessage()
				.contains(CohortDecisionInterceptor.class.getName()));
	}

	@Test
	@DisplayName("a completed role selection is a state, not an event name")
	void identityCompletenessIsStateBased() {
		Properties ctx = new Properties();
		assertNull(LegacyIdentity.read(ctx));

		ctx.setProperty("#AD_User_ID", "101");
		ctx.setProperty("#AD_Role_ID", "102");
		ctx.setProperty("#AD_Client_ID", "11");
		ctx.setProperty("#AD_Org_ID", "11");
		assertNull(LegacyIdentity.read(ctx),
				"the four core keys alone are not a completed role selection");

		ctx.setProperty("#AD_Language", "en_US");
		assertNull(LegacyIdentity.read(ctx),
				"loadPreferences has not written the warehouse yet");

		ctx.setProperty("#M_Warehouse_ID", "103");
		CohortIdentity identity = LegacyIdentity.read(ctx);
		assertNotNull(identity);
		assertEquals(IDENTITY, identity);
		assertTrue(LegacyIdentity.complete(ctx));
	}

	@Test
	@DisplayName("a warehouse-less role is complete with no warehouse")
	void warehouselessRoleIsComplete() {
		Properties ctx = new Properties();
		ctx.setProperty("#AD_User_ID", "101");
		ctx.setProperty("#AD_Role_ID", "0");
		ctx.setProperty("#AD_Client_ID", "0");
		ctx.setProperty("#AD_Org_ID", "0");
		ctx.setProperty("#AD_Language", "en_US");
		ctx.setProperty("#M_Warehouse_ID", "0");
		CohortIdentity identity = LegacyIdentity.read(ctx);
		assertNotNull(identity);
		assertEquals(0, identity.warehouseId());
		assertEquals(0, identity.roleId());
	}

	@Test
	@DisplayName("a malformed context value never produces a partial identity")
	void malformedContextIsNotPartiallyAccepted() {
		Properties ctx = new Properties();
		ctx.setProperty("#AD_User_ID", "not-a-number");
		ctx.setProperty("#AD_Role_ID", "102");
		ctx.setProperty("#AD_Client_ID", "11");
		ctx.setProperty("#AD_Org_ID", "11");
		ctx.setProperty("#AD_Language", "en_US");
		ctx.setProperty("#M_Warehouse_ID", "103");
		assertNull(LegacyIdentity.read(ctx));
		assertNull(LegacyIdentity.read(null));
	}

	@Test
	@DisplayName("a decided-modern session with no affinity is refused, never served legacy")
	void decidedModernWithoutAffinityFailsClosed() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpSession session = mock(HttpSession.class);
		// Exactly the shape a container restart produces when it drops or
		// refuses to restore the affinity attribute: the decision is still
		// recorded, the affinity is gone.
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE)).thenReturn(null);
		when(session.getAttribute(CohortDecisionInterceptor.DECIDED_ATTRIBUTE))
				.thenReturn(CohortRuntime.MODERN.name());

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	@DisplayName("a decided-legacy session with no affinity is served by the legacy application")
	void decidedLegacySessionIsServed() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(CohortDecisionInterceptor.DECIDED_ATTRIBUTE))
				.thenReturn(CohortRuntime.LEGACY.name());

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).sendError(anyInt());
	}

	@Test
	@DisplayName("the deciding AU response releases the redirect barrier only after the legacy chain returns")
	void decidingRequestReleasesRedirectBarrierAfterResponse() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				IDENTITY);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE))
				.thenReturn(null, affinity);
		when(session.getAttribute(
				CohortDecisionInterceptor.REDIRECT_PENDING_ATTRIBUTE))
				.thenReturn(Boolean.TRUE);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(session).removeAttribute(
				CohortDecisionInterceptor.REDIRECT_PENDING_ATTRIBUTE);
		verify(request, never()).changeSessionId();
	}

	@Test
	@DisplayName("a resource racing the deciding AU response cannot consume the handoff")
	void requestDuringRedirectResponseIsRefused() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				IDENTITY);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE))
				.thenReturn(affinity);
		when(session.getAttribute(
				CohortDecisionInterceptor.REDIRECT_PENDING_ATTRIBUTE))
				.thenReturn(Boolean.TRUE);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("GET");
		when(request.getContextPath()).thenReturn("/webui");
		when(request.getRequestURI()).thenReturn(
				"/webui/zkau/web/_zv09110309/_zcb/js/zul/keylistener.js");

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
		verify(request, never()).changeSessionId();
		assertEquals(ModernSessionAffinity.Phase.PENDING_ROTATION, affinity.phase());
	}

	@Test
	@DisplayName("the frozen key-listener request navigates to the only bootstrap-eligible route")
	void keyListenerTransitionsToContextRootBeforeRotation() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				IDENTITY);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE))
				.thenReturn(affinity);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		StringWriter body = new StringWriter();
		when(response.getWriter()).thenReturn(new PrintWriter(body));
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("GET");
		when(request.getContextPath()).thenReturn("/webui");
		when(request.getRequestURI()).thenReturn(
				"/webui/zkau/web/_zv09110309/_zcb/js/zul/keylistener.js");

		filter.doFilter(request, response, chain);

		verify(response).setStatus(HttpServletResponse.SC_OK);
		verify(response).setContentType("application/javascript");
		verify(response).setHeader("Cache-Control", "no-store");
		verify(chain, never()).doFilter(any(), any());
		verify(request, never()).changeSessionId();
		assertEquals("window.top.location.replace('/webui/');", body.toString());
		assertEquals(ModernSessionAffinity.Phase.PENDING_ROTATION, affinity.phase());
	}

	@Test
	@DisplayName("a request that loses the handoff race is refused without failing the session")
	void concurrentRequestIsRefusedNotFailed() throws Exception {
		CohortRoutingFilter filter = armedFilter();
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				IDENTITY);
		// The winner is mid-rotation. This request is the loser.
		affinity.admit();

		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE)).thenReturn(affinity);
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("GET");
		when(request.getContextPath()).thenReturn("/webui");
		when(request.getRequestURI()).thenReturn("/webui/");

		filter.doFilter(request, response, chain);

		verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		verify(chain, never()).doFilter(any(), any());
		verify(request, never()).changeSessionId();
		assertTrue(affinity.usable(),
				"the loser of a race must not destroy the winner's session");
		assertEquals(ModernSessionAffinity.Phase.ROTATING, affinity.phase());
	}

	@Test
	@DisplayName("a routed session end destroys the Tomcat 9 session and redirects")
	void routedSessionEndInvalidatesTheLegacySession() throws Exception {
		ModernSessionAffinity affinity = bootstrappedAffinity();
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(ModernSessionAffinity.ATTRIBUTE)).thenReturn(affinity);
		when(session.getId()).thenReturn("ROTATED");

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		when(request.getHeaderNames()).thenReturn(names("Accept"));
		when(request.getSession(false)).thenReturn(session);
		when(request.getMethod()).thenReturn("GET");
		when(request.getContextPath()).thenReturn("/webui");
		when(request.getRequestURI()).thenReturn("/webui/index.zul");

		CohortRoutingFilter filter = armedFilter(backend ->
				new EndingProxy(backend));
		filter.doFilter(request, response, chain);

		// Invalidation is the assertion: it destroys the affinity AND the sticky
		// cohort decision in one step, so the next login on this browser is
		// decided again instead of inheriting the previous user's cohort.
		verify(session).invalidate();
		verify(response).sendRedirect("/webui/");
		verify(chain, never()).doFilter(any(), any());
		verify(response, never()).sendError(anyInt());
	}

	@Test
	@DisplayName("rotation can discard every legacy cache without a ZK execution")
	void rotatedSessionStateIsDiscardedWithoutUiTeardown() {
		String sessionId = "ABANDONED";
		SessionManager.getSessionCache().put(sessionId, mock(HttpSession.class));
		SessionManager.getSessionContextCache().put(sessionId, new Properties());
		SessionManager.getAppicationCache().put(
				sessionId, new WeakReference<IWebClient>(null));
		SessionManager.getDesktopCache().put(
				sessionId, new WeakReference<IDesktop>(null));

		CohortRoutingFilter.discardLegacyCaches(sessionId);

		assertFalse(SessionManager.getSessionCache().containsKey(sessionId));
		assertFalse(SessionManager.getSessionContextCache().containsKey(sessionId));
		assertFalse(SessionManager.getAppicationCache().containsKey(sessionId));
		assertFalse(SessionManager.getDesktopCache().containsKey(sessionId));
	}

	/** A backend that reports the modern session has ended. */
	private static final class EndingProxy extends ModernBackendProxy {

		EndingProxy(String backend) {
			super(backend);
		}

		@Override
		Result proxy(
				HttpServletRequest request,
				HttpServletResponse response,
				org.adempiere.web.route.PublicRouteClass routeClass,
				String pathInside,
				String modernCookie,
				String ticket,
				String boundSessionId) {
			return Result.ended();
		}
	}

	private static ModernSessionAffinity bootstrappedAffinity() {
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				IDENTITY);
		affinity.admit();
		affinity.ticketed("ROTATED", "v1.payload.mac");
		affinity.admit();
		affinity.bootstrapped("MODERN");
		return affinity;
	}

	/** A filter whose bridge is armed with a usable key and backend. */
	private static CohortRoutingFilter armedFilter() throws Exception {
		return armedFilter(null);
	}

	/**
	 * A filter whose bridge is armed, optionally with a stubbed backend.
	 *
	 * @param proxies the seam {@code CohortRoutingFilter.backendProxy} uses, or
	 *                {@code null} for the real one
	 */
	private static CohortRoutingFilter armedFilter(
			java.util.function.Function<String, ModernBackendProxy> proxies)
			throws Exception {
		byte[] material = new byte[32];
		for (int index = 0; index < material.length; index++) {
			material[index] = (byte) (index * 7 + 3);
		}
		CohortBridge.install(
				new org.adempiere.web.cohort.CohortConfigurationRepository(
						Collections::emptyList,
						(message, cause) -> {
						}),
				new org.adempiere.web.handoff.HandoffTicketCodec(),
				org.adempiere.web.handoff.HandoffKey.of(material),
				"http://127.0.0.1:19999");
		CohortRoutingFilter filter = proxies == null
				? new CohortRoutingFilter()
				: new CohortRoutingFilter() {
					@Override
					ModernBackendProxy backendProxy(String backend) {
						return proxies.apply(backend);
					}
				};
		FilterConfig config = mock(FilterConfig.class);
		when(config.getServletContext()).thenReturn(mock(ServletContext.class));
		filter.init(config);
		return filter;
	}
}
