package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortIdentity;
import org.adempiere.web.cohort.CohortRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The reviewed public affinity unit, header allowlists and audit policy. */
@Tag("UnitTest")
@DisplayName("Phase 5e public routing")
class PublicRouteClassifierTest {

	@ParameterizedTest(name = "{0} {1} -> {2}")
	@CsvSource({
			"GET,     /,                                CONTEXT_ROOT",
			"HEAD,    /,                                CONTEXT_ROOT",
			"GET,     '',                               CONTEXT_ROOT",
			"GET,     /index.zul,                       ZK_PAGE",
			"GET,     /timeout.zul,                     ZK_PAGE",
			"GET,     /page.zhtml,                      ZK_PAGE",
			"POST,    /index.zul,                       ZK_PUBLIC_FORM",
			"GET,     /zkau/web/js/zk.wpd,              ZK_RESOURCE",
			"GET,     /zkau/web/zul/css/zk.wcs,         ZK_RESOURCE",
			"POST,    /zkau,                            ZK_AU",
			"GET,     /zkau,                            ZK_AU",
			"POST,    /zkau/upload,                     ZK_AU",
			"GET,     /zkau/view/z_abc/image.png,       STATIC_ASSET",
			"GET,     /css/phase5d-modern.css,          STATIC_ASSET",
			"GET,     /js/token.js,                     STATIC_ASSET",
			"GET,     /images/logo.png,                 STATIC_ASSET",
			"GET,     /favicon.ico,                     STATIC_ASSET",
	})
	@DisplayName("the reviewed affinity unit classifies exactly as recorded")
	void reviewedRoutesClassify(String method, String path, String expected) {
		assertEquals(PublicRouteClass.valueOf(expected),
				PublicRouteClassifier.classify(method, path));
	}

	@ParameterizedTest(name = "{0} {1} is not proxyable")
	@CsvSource({
			"GET,     /theme.dsp",
			"GET,     /timeline",
			"POST,    /timeline",
			"PUT,     /index.zul",
			"DELETE,  /index.zul",
			"OPTIONS, /",
			"TRACE,   /",
			"PATCH,   /zkau",
			"POST,    /page.zhtml",
			"POST,    /zkau/web/js/zk.wpd",
			"POST,    /favicon.ico",
			"GET,     /WEB-INF/web.xml",
			"GET,     /../../etc/passwd",
			"GET,     relative/path",
			"HEAD,    /timeline",
	})
	@DisplayName("everything outside the affinity unit is UNKNOWN and never proxied")
	void everythingElseIsUnknown(String method, String path) {
		PublicRouteClass routeClass = PublicRouteClassifier.classify(method, path);
		assertEquals(PublicRouteClass.UNKNOWN, routeClass);
		assertFalse(routeClass.proxyable());
	}

	@Test
	@DisplayName("a null method never classifies")
	void nullMethodIsUnknown() {
		assertEquals(PublicRouteClass.UNKNOWN, PublicRouteClassifier.classify(null, "/"));
	}

	@Test
	@DisplayName("a path that still carries a session parameter never classifies")
	void unstrippedSessionParameterIsUnknown() {
		assertEquals(PublicRouteClass.UNKNOWN, PublicRouteClassifier
				.classify("GET", "/index.zul;jsessionid=ABCDEF"));
	}

	@ParameterizedTest(name = "strip(\"{0}\")")
	@CsvSource({
			"/index.zul;jsessionid=ABC,        /index.zul",
			"/zkau;jsessionid=ABC,             /zkau",
			"/a;x=1/b;y=2/c,                   /a/b/c",
			"/,                                /",
			"/plain/path,                      /plain/path",
	})
	@DisplayName("path parameters are removed from every segment")
	void sessionPathParametersAreStripped(String raw, String expected) {
		assertEquals(expected, SessionPathParameters.strip(raw));
	}

	@Test
	@DisplayName("a URL-rewritten session identifier is detectable before stripping")
	void urlRewrittenSessionIsDetectable() {
		assertTrue(SessionPathParameters
				.carriesSessionParameter("/index.zul;JSESSIONID=ABC"));
		assertFalse(SessionPathParameters.carriesSessionParameter("/index.zul"));
	}

	@ParameterizedTest(name = "request header {0} is forwarded")
	@ValueSource(strings = {
			"accept", "Accept-Language", "content-type", "user-agent",
			"X-Requested-With", "if-none-match"})
	@DisplayName("only allowlisted request headers cross to the modern runtime")
	void allowlistedRequestHeadersAreForwarded(String header) {
		assertTrue(ProxyHeaderPolicy.forwardRequestHeader(header));
	}

	@ParameterizedTest(name = "request header {0} is dropped")
	@ValueSource(strings = {
			"cookie", "Cookie", "host", "authorization", "connection",
			"transfer-encoding", "upgrade", "te", "referer", "origin",
			"x-forwarded-for", "X-ADempiere-Handoff-Ticket",
			"x-adempiere-handoff-anything"})
	@DisplayName("cookies, hop-by-hop headers and the internal namespace never cross")
	void deniedRequestHeadersAreDropped(String header) {
		assertFalse(ProxyHeaderPolicy.forwardRequestHeader(header));
	}

	@ParameterizedTest(name = "response header {0} is returned")
	@ValueSource(strings = {
			"content-type", "Content-Encoding", "location", "etag", "vary"})
	@DisplayName("only allowlisted response headers reach the browser")
	void allowlistedResponseHeadersAreReturned(String header) {
		assertTrue(ProxyHeaderPolicy.forwardResponseHeader(header));
	}

	@ParameterizedTest(name = "response header {0} is consumed")
	@ValueSource(strings = {
			"set-cookie", "Set-Cookie", "server", "connection", "keep-alive",
			"X-ADempiere-Handoff-Ticket", "transfer-encoding"})
	@DisplayName("the modern Set-Cookie and every hop-by-hop header are consumed")
	void deniedResponseHeadersAreConsumed(String header) {
		assertFalse(ProxyHeaderPolicy.forwardResponseHeader(header));
	}

	@Test
	@DisplayName("an audit line carries the runtime and route class and nothing else")
	void auditLineIsSafe() {
		String line = RoutingAudit.line(
				CohortRuntime.MODERN, PublicRouteClass.ZK_AU, "proxied");
		assertEquals("phase5e-route runtime=MODERN class=ZK_AU outcome=proxied", line);
		for (String forbidden : RoutingAudit.forbidden()) {
			assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains(forbidden));
		}
	}

	@Test
	@DisplayName("a line that would leak a session or a ticket cannot be written")
	void auditPolicyRefusesLeakingLines() {
		assertThrows(IllegalStateException.class, () -> RoutingAudit.line(
				CohortRuntime.MODERN, PublicRouteClass.ZK_AU, "JSESSIONID=ABC"));
		assertThrows(IllegalStateException.class,
				() -> RoutingAudit.sanitised("cookie: a=b"));
		assertThrows(IllegalStateException.class,
				() -> RoutingAudit.sanitised("x-adempiere-handoff-ticket: v1.a.b"));
	}

	@Test
	@DisplayName("the decision line names the reason, never the identity")
	void decisionLineNamesTheReason() {
		assertEquals("phase5e-cohort runtime=MODERN reason=USER_ALLOWLISTED",
				RoutingAudit.decisionLine(new CohortDecision(
						CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED)));
	}

	@Test
	@DisplayName("every AU request gets the longer polling read timeout")
	void auRequestsUseThePollingTimeout() {
		assertTrue(PublicRouteClassifier.polling(PublicRouteClass.ZK_AU));
		assertFalse(PublicRouteClassifier.polling(PublicRouteClass.ZK_RESOURCE));
		assertTrue(ProxyLimits.POLLING_READ_TIMEOUT_MILLIS
				> ProxyLimits.READ_TIMEOUT_MILLIS);
	}

	@Test
	@DisplayName("an established modern session never returns to the legacy runtime")
	void affinityIsOneWay() {
		CohortDecision decision = new CohortDecision(
				CohortRuntime.MODERN, CohortDecision.Reason.USER_ALLOWLISTED);
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				decision, new CohortIdentity(101, 102, 11, 11, 103, "en_US"));
		assertEquals(ModernSessionAffinity.Phase.PENDING_ROTATION, affinity.phase());

		assertEquals(ModernSessionAffinity.Step.ROTATE, affinity.admit().step());
		assertEquals(ModernSessionAffinity.Phase.ROTATING, affinity.phase());
		affinity.ticketed("ROTATED", "v1.a.b");
		assertEquals(ModernSessionAffinity.Phase.AWAITING_BOOTSTRAP, affinity.phase());

		ModernSessionAffinity.Admission bootstrap = affinity.admit();
		assertEquals(ModernSessionAffinity.Step.BOOTSTRAP, bootstrap.step());
		assertEquals("v1.a.b", bootstrap.ticket(),
				"the ticket is handed over by the admission, never read separately");
		assertFalse(affinity.ticketPending(), "a ticket is handed over exactly once");
		assertThrows(IllegalStateException.class,
				() -> affinity.ticketed("ROTATED-AGAIN", "v1.c.d"));

		affinity.bootstrapped("MODERN-SESSION");
		assertEquals(ModernSessionAffinity.Phase.BOOTSTRAPPED, affinity.phase());
		assertTrue(affinity.usable());
		assertEquals(ModernSessionAffinity.Step.PROXY, affinity.admit().step());

		affinity.failed("backend-unavailable");
		assertEquals(ModernSessionAffinity.Phase.FAILED, affinity.phase());
		assertFalse(affinity.usable());
		assertEquals(ModernSessionAffinity.Step.REFUSED, affinity.admit().step());
		assertEquals(CohortRuntime.MODERN, affinity.decision().runtime(),
				"a failed modern session stays modern");
	}

	@Test
	@DisplayName("only one of two concurrent requests may rotate or bootstrap")
	void admissionIsAtomic() throws Exception {
		ModernSessionAffinity affinity = pendingAffinity();
		int racers = 8;
		ExecutorService workers = Executors.newFixedThreadPool(racers);
		try {
			CyclicBarrier start = new CyclicBarrier(racers);
			List<Future<ModernSessionAffinity.Admission>> admissions = new ArrayList<>();
			for (int racer = 0; racer < racers; racer++) {
				admissions.add(workers.submit(() -> {
					start.await(30, TimeUnit.SECONDS);
					return affinity.admit();
				}));
			}
			int rotators = 0;
			int inProgress = 0;
			for (Future<ModernSessionAffinity.Admission> pending : admissions) {
				ModernSessionAffinity.Admission admission =
						pending.get(30, TimeUnit.SECONDS);
				switch (admission.step()) {
					case ROTATE -> rotators++;
					case IN_PROGRESS -> inProgress++;
					default -> throw new AssertionError(
							"An unrotated session admitted " + admission.step());
				}
			}
			// Exactly one rotation is the whole point: two would change the
			// container's session identifier twice, mint two tickets, and leave
			// the affinity bound to an identifier nothing uses.
			assertEquals(1, rotators, "more than one request was told to rotate");
			assertEquals(racers - 1, inProgress,
					"a request that lost the rotation race was not refused");

			affinity.ticketed("ROTATED", "v1.a.b");
			CyclicBarrier second = new CyclicBarrier(racers);
			List<Future<ModernSessionAffinity.Admission>> bootstraps = new ArrayList<>();
			for (int racer = 0; racer < racers; racer++) {
				bootstraps.add(workers.submit(() -> {
					second.await(30, TimeUnit.SECONDS);
					return affinity.admit();
				}));
			}
			int holders = 0;
			for (Future<ModernSessionAffinity.Admission> pending : bootstraps) {
				ModernSessionAffinity.Admission admission =
						pending.get(30, TimeUnit.SECONDS);
				if (admission.step() == ModernSessionAffinity.Step.BOOTSTRAP) {
					holders++;
					assertEquals("v1.a.b", admission.ticket());
				} else {
					assertEquals(ModernSessionAffinity.Step.IN_PROGRESS,
							admission.step());
					assertNull(admission.ticket(),
							"a losing request must not receive the ticket");
				}
			}
			assertEquals(1, holders, "more than one request received the ticket");
			// The losers must NOT have poisoned the session the winner owns.
			assertTrue(affinity.usable(),
					"losing a race must not fail the session the winner is "
							+ "still establishing");
			assertEquals(ModernSessionAffinity.Phase.BOOTSTRAPPING, affinity.phase());
		} finally {
			workers.shutdownNow();
		}
	}

	@Test
	@DisplayName("a persisted affinity survives a restart or fails closed, never falls back")
	void persistedAffinityFailsClosedRatherThanDisappearing() throws Exception {
		// A decided-but-unrotated session has nothing transient, so it resumes.
		ModernSessionAffinity pending = roundTrip(pendingAffinity());
		assertEquals(ModernSessionAffinity.Phase.PENDING_ROTATION, pending.phase());
		assertEquals(CohortRuntime.MODERN, pending.decision().runtime());
		assertEquals(101, pending.identity().userId());

		// An established session keeps its binding: the modern runtime may well
		// still be holding the session it names.
		ModernSessionAffinity established = pendingAffinity();
		established.admit();
		established.ticketed("ROTATED", "v1.a.b");
		established.admit();
		established.bootstrapped("MODERN-SESSION");
		ModernSessionAffinity restored = roundTrip(established);
		assertEquals(ModernSessionAffinity.Phase.BOOTSTRAPPED, restored.phase());
		assertEquals("MODERN-SESSION", restored.modernSessionId());
		assertEquals("ROTATED", restored.boundLegacySessionId());
		assertTrue(restored.usable());

		// A ticketed-but-unbootstrapped session cannot resume: the ticket is a
		// bearer credential and is deliberately never written to SESSIONS.ser.
		ModernSessionAffinity awaiting = pendingAffinity();
		awaiting.admit();
		awaiting.ticketed("ROTATED", "v1.secret.mac");
		assertFalse(new String(serialised(awaiting), StandardCharsets.ISO_8859_1)
						.contains("v1.secret.mac"),
				"the ticket must not be written to the persisted session");
		ModernSessionAffinity lost = roundTrip(awaiting);
		assertEquals(ModernSessionAffinity.Phase.FAILED, lost.phase());
		assertEquals(ModernSessionAffinity.NOT_RESTORABLE, lost.failureReason());
		assertFalse(lost.usable());
		assertEquals(ModernSessionAffinity.Step.REFUSED, lost.admit().step());
		assertEquals(CohortRuntime.MODERN, lost.decision().runtime(),
				"a restored affinity that cannot resume is still modern, never legacy");

		// A session that was mid-rotation when the container stopped is also
		// unrecoverable: resuming it would rotate a second time.
		ModernSessionAffinity rotating = pendingAffinity();
		rotating.admit();
		ModernSessionAffinity abandoned = roundTrip(rotating);
		assertEquals(ModernSessionAffinity.Phase.FAILED, abandoned.phase());
		assertEquals(ModernSessionAffinity.NOT_RESTORABLE, abandoned.failureReason());
	}

	private static ModernSessionAffinity pendingAffinity() {
		return new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.USER_ALLOWLISTED),
				new CohortIdentity(101, 102, 11, 11, 103, "en_US"));
	}

	private static byte[] serialised(ModernSessionAffinity affinity)
			throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(affinity);
		}
		return bytes.toByteArray();
	}

	private static ModernSessionAffinity roundTrip(ModernSessionAffinity affinity)
			throws IOException, ClassNotFoundException {
		try (ObjectInputStream in = new ObjectInputStream(
				new ByteArrayInputStream(serialised(affinity)))) {
			return (ModernSessionAffinity) in.readObject();
		}
	}

	@Test
	@DisplayName("the affinity never renders a session identifier or a ticket")
	void affinityToStringIsSafe() {
		ModernSessionAffinity affinity = new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.MODERN,
						CohortDecision.Reason.ROLE_ALLOWLISTED),
				new CohortIdentity(101, 102, 11, 11, 103, "en_US"));
		affinity.admit();
		affinity.ticketed("ROTATED-SESSION", "v1.secret.mac");
		affinity.admit();
		affinity.bootstrapped("MODERN-SESSION");
		String rendered = affinity.toString();
		assertFalse(rendered.contains("ROTATED-SESSION"));
		assertFalse(rendered.contains("MODERN-SESSION"));
		assertFalse(rendered.contains("secret"));
	}

	@Test
	@DisplayName("only a modern decision can create an affinity")
	void legacyDecisionCannotCreateAffinity() {
		assertThrows(IllegalArgumentException.class, () -> new ModernSessionAffinity(
				new CohortDecision(CohortRuntime.LEGACY,
						CohortDecision.Reason.NOT_ALLOWLISTED),
				new CohortIdentity(101, 102, 11, 11, 103, "en_US")));
	}
}
