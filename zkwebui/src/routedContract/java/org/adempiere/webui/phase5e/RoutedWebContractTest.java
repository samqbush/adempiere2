package org.adempiere.webui.phase5e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.adempiere.web.cohort.CohortConfigurationKeys;
import org.adempiere.web.cohort.CohortConfigurationParser;
import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortIdentity;
import org.adempiere.web.cohort.CohortRuntime;
import org.adempiere.web.handoff.HandoffProtocol;
import org.adempiere.web.handoff.ReplayCache;
import org.adempiere.web.route.BoundedTransfer;
import org.adempiere.web.route.ModernSessionAffinity;
import org.adempiere.web.route.ProxyHeaderPolicy;
import org.adempiere.web.route.ProxyLimits;
import org.adempiere.web.route.PublicRouteClass;
import org.adempiere.web.route.PublicRouteClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reviewed Phase 5e contract, asserted against the implementation without a
 * database, a container or a browser.
 *
 * <p>This is the database-neutral half of the Phase 5e proof. The contract files
 * and the neutral protocol classes are both on this source set's classpath, so
 * a contract row that no longer describes the code fails here rather than in a
 * nine-minute database gate.
 */
@Tag("UnitTest")
@DisplayName("Phase 5e routed web contract")
class RoutedWebContractTest {

	@Test
	@DisplayName("every reviewed public route classifies exactly as recorded")
	void routesClassifyAsRecorded() {
		for (RoutedWebContract.Route route : RoutedWebContract.routes()) {
			PublicRouteClass actual =
					PublicRouteClassifier.classify(route.method(), route.path());
			assertEquals(route.routeClass(), actual.name(),
					route.method() + " " + route.path());
			assertEquals(route.proxyable(), actual.proxyable(),
					route.method() + " " + route.path() + " proxyable");
		}
	}

	@Test
	@DisplayName("the affinity unit is closed, not merely populated")
	void affinityUnitIsClosed() {
		List<RoutedWebContract.Route> routes = RoutedWebContract.routes();
		assertTrue(routes.stream().anyMatch(route -> !route.proxyable()),
				"a contract with no refused route would make 'closed' vacuous");
		// The two routes Phase 5f owns must be named refusals, so pulling them
		// into Phase 5e would fail this test rather than quietly widen scope.
		for (String phase5f : List.of("/theme.dsp", "/timeline")) {
			assertTrue(routes.stream().anyMatch(route ->
							route.path().equals(phase5f) && !route.proxyable()),
					"the contract does not refuse the Phase 5f route " + phase5f);
		}
	}

	@Test
	@DisplayName("every reviewed header rule matches the proxy policy")
	void headerRulesMatchThePolicy() {
		for (RoutedWebContract.Header header : RoutedWebContract.headers()) {
			boolean actual = "request".equals(header.direction())
					? ProxyHeaderPolicy.forwardRequestHeader(header.name())
					: ProxyHeaderPolicy.forwardResponseHeader(header.name());
			assertEquals(header.forwarded(), actual,
					header.direction() + " header " + header.name());
		}
	}

	@Test
	@DisplayName("no cookie crosses the boundary in either direction")
	void noCookieCrosses() {
		assertFalse(ProxyHeaderPolicy.forwardRequestHeader("cookie"));
		assertFalse(ProxyHeaderPolicy.forwardResponseHeader("set-cookie"));
		Set<String> declared = Set.copyOf(RoutedWebContract.headers().stream()
				.filter(header -> !header.forwarded())
				.map(RoutedWebContract.Header::name)
				.toList());
		assertTrue(declared.containsAll(Set.of("cookie", "set-cookie")),
				"the contract must record both cookie rules explicitly");
	}

	@Test
	@DisplayName("the documented grammar is the implemented grammar")
	void documentedGrammarIsTheImplementedOne() {
		assertEquals(RoutedWebContract.required("identifier_grammar"),
				CohortConfigurationParser.identifierGrammar());
	}

	@Test
	@DisplayName("the documented configuration keys are the implemented keys")
	void documentedKeysAreTheImplementedOnes() {
		assertEquals(CohortConfigurationKeys.ENABLED,
				RoutedWebContract.required("master_enable_key"));
		assertEquals(CohortConfigurationKeys.USER_IDS,
				RoutedWebContract.required("user_allowlist_key"));
		assertEquals(CohortConfigurationKeys.ROLE_IDS,
				RoutedWebContract.required("role_allowlist_key"));
		assertEquals(CohortConfigurationKeys.ENABLED_VALUE, "Y");
	}

	@Test
	@DisplayName("the documented ticket parameters are the implemented ones")
	void documentedTicketParametersAreTheImplementedOnes() {
		assertEquals(HandoffProtocol.TTL_MILLIS,
				Long.parseLong(RoutedWebContract.required("ticket_ttl_millis")));
		assertEquals(HandoffProtocol.CLOCK_SKEW_MILLIS,
				Long.parseLong(RoutedWebContract.required("ticket_clock_skew_millis")));
		assertEquals(HandoffProtocol.RESERVED_HEADER_PREFIX,
				RoutedWebContract.required("reserved_header_namespace"));
		assertEquals(ReplayCache.DEFAULT_CAPACITY,
				Integer.parseInt(RoutedWebContract.required("replay_cache_capacity")));
	}

	@Test
	@DisplayName("the documented byte caps are the enforced byte caps")
	void documentedByteCapsAreTheEnforcedOnes() {
		long requestCap =
				Long.parseLong(RoutedWebContract.required("max_request_bytes"));
		long responseCap =
				Long.parseLong(RoutedWebContract.required("max_response_bytes"));
		assertEquals(ProxyLimits.MAX_REQUEST_BYTES, requestCap);
		assertEquals(ProxyLimits.MAX_RESPONSE_BYTES, responseCap);

		// Documenting a cap and enforcing it are different claims, so the
		// enforcement is exercised here too: one byte past the documented
		// request cap must stop the copy, and nothing past it may be written.
		byte[] payload = new byte[9 * 1024];
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		try {
			assertFalse(BoundedTransfer.copy(
					new ByteArrayInputStream(payload), sink, 8L * 1024));
		} catch (IOException impossible) {
			throw new AssertionError(impossible);
		}
		assertTrue(sink.size() <= 8 * 1024);
		assertFalse(BoundedTransfer.declaredWithin(requestCap + 1, requestCap));
		assertTrue(BoundedTransfer.declaredWithin(requestCap, requestCap));
	}

	@Test
	@DisplayName("the replay capacity exceeds the documented login-rate ceiling")
	void replayCapacityExceedsTheDocumentedCeiling() {
		long rate = Long.parseLong(
				RoutedWebContract.required("max_accepted_login_rate_per_second"));
		long ceiling = (HandoffProtocol.TTL_MILLIS
				+ HandoffProtocol.CLOCK_SKEW_MILLIS) / 1000L * rate;
		assertTrue(ReplayCache.DEFAULT_CAPACITY > ceiling,
				"capacity " + ReplayCache.DEFAULT_CAPACITY
						+ " must exceed the documented ceiling " + ceiling);
	}

	@Test
	@DisplayName("the derived artifact may differ in exactly three reviewed entries")
	void derivedArtifactDiffIsExactlyThreeEntries() {
		Map<String, String> diff = RoutedWebContract.derivedArtifactDiff();
		assertEquals(Set.of(
						"WEB-INF/web.xml",
						"WEB-INF/zk.xml",
						"WEB-INF/lib/webui-cohort-bridge.jar"),
				diff.keySet());
		assertEquals("replaced", diff.get("WEB-INF/web.xml"));
		assertEquals("replaced", diff.get("WEB-INF/zk.xml"));
		assertEquals("added", diff.get("WEB-INF/lib/webui-cohort-bridge.jar"));
	}

	@Test
	@DisplayName("the documented failure policy forbids a modern-to-legacy fallback")
	void modernNeverFallsBack() {
		assertEquals("forbidden", RoutedWebContract.required("modern_fallback"));
		assertEquals("1", RoutedWebContract.required("public_cookie_count"));
		assertEquals("none", RoutedWebContract.required("internal_cookie_visibility"));
		assertEquals("modern-assignment-only",
				RoutedWebContract.required("session_rotation"));
		assertEquals("fail-closed",
				RoutedWebContract.required("replay_cache_exhaustion"));
		assertEquals("deployment-failure",
				RoutedWebContract.required("handoff_key_invalid_tomcat10"));
		assertEquals("legacy-only",
				RoutedWebContract.required("handoff_key_absent_tomcat9"));
		assertEquals("fail-closed",
				RoutedWebContract.required("decided_without_affinity"));
	}

	@Test
	@DisplayName("the documented session-end header is the implemented one, and never crosses")
	void sessionEndHeaderIsInternalOnly() {
		assertEquals("destroys-both-runtimes",
				RoutedWebContract.required("routed_session_end"));
		String header = RoutedWebContract.required("session_end_header");
		assertEquals(HandoffProtocol.END_HEADER, header);
		assertTrue(HandoffProtocol.reserved(header),
				"the end signal must be inside the reserved internal namespace");
		assertFalse(ProxyHeaderPolicy.forwardResponseHeader(header),
				"the end signal must never reach the browser");
		assertFalse(ProxyHeaderPolicy.forwardRequestHeader(header),
				"the end signal must never be accepted from the browser");
	}

	@Test
	@DisplayName("a persisted affinity is restorable or fails closed, and never carries the ticket")
	void persistedAffinityMatchesTheContract() throws Exception {
		assertEquals("serializable-ticket-transient",
				RoutedWebContract.required("affinity_persistence"));
		assertTrue(Serializable.class.isAssignableFrom(ModernSessionAffinity.class),
				"a container that persists sessions drops what it cannot write");

		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				new CohortIdentity(101, 102, 11, 11, 103, "en_US"));
		affinity.admit();
		affinity.ticketed("ROTATED", "v1.secret.mac");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(affinity);
		}
		assertFalse(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
						.contains("v1.secret.mac"),
				"a bearer ticket must not be written to the persisted session");

		ModernSessionAffinity restored;
		try (ObjectInputStream in = new ObjectInputStream(
				new ByteArrayInputStream(bytes.toByteArray()))) {
			restored = (ModernSessionAffinity) in.readObject();
		}
		assertEquals(ModernSessionAffinity.Phase.FAILED, restored.phase());
		assertEquals(ModernSessionAffinity.NOT_RESTORABLE, restored.failureReason());
		assertEquals(CohortRuntime.MODERN, restored.decision().runtime(),
				"a restored affinity that cannot resume is still modern, never legacy");
	}

	@Test
	@DisplayName("the handoff admits exactly one rotation and one ticket holder")
	void handoffAdmissionIsSingle() {
		assertEquals("single-atomic-transition",
				RoutedWebContract.required("handoff_admission"));
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.ROLE_ALLOWLISTED),
				new CohortIdentity(101, 102, 11, 11, 103, "en_US"));
		assertEquals(ModernSessionAffinity.Step.ROTATE, affinity.admit().step());
		assertEquals(ModernSessionAffinity.Step.IN_PROGRESS, affinity.admit().step());
		affinity.ticketed("ROTATED", "v1.a.b");
		assertEquals("v1.a.b", affinity.admit().ticket());
		assertEquals(ModernSessionAffinity.Step.IN_PROGRESS, affinity.admit().step());
		assertTrue(affinity.usable(),
				"a refused concurrent request must not fail the session");
	}
}
