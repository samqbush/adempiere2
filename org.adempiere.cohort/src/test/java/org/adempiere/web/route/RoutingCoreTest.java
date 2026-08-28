package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortIdentity;
import org.adempiere.web.cohort.CohortRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Focused contract for the reusable routing and lifecycle seam. */
@Tag("UnitTest")
@DisplayName("Framework-neutral routing core")
class RoutingCoreTest {

	@Test
	@DisplayName("a missing modern affinity fails closed while an undecided request stays legacy")
	void missingAffinityNeverFallsBack() {
		RoutingCore.Plan undecided = RoutingCore.withoutAffinity(false);
		assertEquals(RoutingCore.Action.LEGACY, undecided.action());

		RoutingCore.Plan modern = RoutingCore.withoutAffinity(true);
		assertEquals(RoutingCore.Action.FAIL, modern.action());
		assertEquals("decided-modern-without-affinity", modern.reason());
		assertEquals(503, modern.status());
	}

	@Test
	@DisplayName("preflight owns route refusal and the one bootstrap transition")
	void preflightClassifiesBeforeTheAdapterActs() {
		ModernSessionAffinity affinity = pendingAffinity();

		RoutingCore.Plan unknown = RoutingCore.preflight(
				true, affinity, "GET", "/timeline");
		assertEquals(RoutingCore.Action.NOT_FOUND, unknown.action());
		assertEquals(PublicRouteClass.UNKNOWN, unknown.routeClass());
		assertTrue(affinity.usable(),
				"an unowned route refuses one request without poisoning the session");

		RoutingCore.Plan transition = RoutingCore.preflight(
				true, affinity, "GET",
				"/zkau/web/_zv09110309/_zcb/js/zul/keylistener.js");
		assertEquals(RoutingCore.Action.TRANSITION, transition.action());
		assertEquals(PublicRouteClass.ZK_RESOURCE, transition.routeClass());

		RoutingCore.Plan bootstrap = RoutingCore.preflight(
				true, affinity, "GET", "/");
		assertEquals(RoutingCore.Action.ROUTE, bootstrap.action());
		assertEquals(PublicRouteClass.CONTEXT_ROOT, bootstrap.routeClass());
	}

	@Test
	@DisplayName("unsafe paths and unavailable routing terminally fail the modern affinity")
	void terminalFailuresStayModern() {
		ModernSessionAffinity rewritten = bootstrappedAffinity();
		RoutingCore.Plan badPath = RoutingCore.preflight(
				true, rewritten, "GET", "/index.zul;jsessionid=FORGED");
		assertEquals(RoutingCore.Action.FAIL, badPath.action());
		assertEquals(400, badPath.status());
		assertFalse(rewritten.usable());
		assertEquals(CohortRuntime.MODERN, rewritten.decision().runtime());

		ModernSessionAffinity unavailable = bootstrappedAffinity();
		RoutingCore.Plan noBackend = RoutingCore.preflight(
				false, unavailable, "GET", "/index.zul");
		assertEquals(RoutingCore.Action.FAIL, noBackend.action());
		assertEquals("handoff-unavailable", noBackend.reason());
		assertFalse(unavailable.usable());
	}

	@Test
	@DisplayName("binding and post-proxy lifecycle are independent of Servlet and ZK")
	void lifecycleBindsFailsAndEnds() {
		ModernSessionAffinity binding = bootstrappedAffinity();
		assertEquals(RoutingCore.Action.ROUTE, RoutingCore.validateBinding(
				binding, PublicRouteClass.ZK_PAGE, "ROTATED").action());
		assertEquals(RoutingCore.Action.FAIL, RoutingCore.validateBinding(
				binding, PublicRouteClass.ZK_PAGE, "OTHER").action());
		assertFalse(binding.usable());

		ModernSessionAffinity bootstrap = ticketedAffinity();
		RoutingLifecycle.Outcome bound = RoutingLifecycle.apply(
				bootstrap, true, ProxyResult.completed("MODERN"));
		assertEquals(RoutingLifecycle.Action.COMPLETE, bound.action());
		assertEquals("MODERN", bootstrap.modernSessionId());

		ModernSessionAffinity missing = ticketedAffinity();
		RoutingLifecycle.Outcome failed = RoutingLifecycle.apply(
				missing, true, ProxyResult.completed(null));
		assertEquals(RoutingLifecycle.Action.FAIL, failed.action());
		assertEquals("bootstrap-no-session", failed.failure());
		assertFalse(missing.usable());

		ModernSessionAffinity ended = bootstrappedAffinity();
		RoutingLifecycle.Outcome end = RoutingLifecycle.apply(
				ended, false, ProxyResult.ended());
		assertEquals(RoutingLifecycle.Action.END_SESSION, end.action());
		assertNull(end.failure());
		assertTrue(ended.usable(),
				"the container adapter owns invalidation after the end signal");
	}

	private static ModernSessionAffinity pendingAffinity() {
		return new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				new CohortIdentity(101, 102, 11, 11, 103, "en_US"));
	}

	private static ModernSessionAffinity ticketedAffinity() {
		ModernSessionAffinity affinity = pendingAffinity();
		affinity.admit();
		affinity.ticketed("ROTATED", "v1.payload.mac");
		affinity.admit();
		return affinity;
	}

	private static ModernSessionAffinity bootstrappedAffinity() {
		ModernSessionAffinity affinity = ticketedAffinity();
		affinity.bootstrapped("MODERN");
		return affinity;
	}
}
