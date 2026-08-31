package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import org.adempiere.web.cohort.SysConfigRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ContextRoutingDecisionTest {

	@Test
	void everyContextHasAnIndependentPolicyAndKey() {
		assertEquals("MODERN_WEB_ADMIN_ENABLED",
				ContextRoutingPolicy.forContext("/admin").enableKey());
		assertEquals("MODERN_WEB_ROOT_ENABLED",
				ContextRoutingPolicy.forContext("").enableKey());
		assertEquals("JSESSIONID_ADMIN",
				ContextRoutingPolicy.forContext("/admin").sessionCookie());
		assertEquals("JSESSIONID_ROOT",
				ContextRoutingPolicy.forContext("").sessionCookie());
		assertEquals("JSESSIONID_MOBILE",
				ContextRoutingPolicy.forContext("/mobile").sessionCookie());
		assertEquals("JSESSIONID_ADEMPIERE",
				ContextRoutingPolicy.forContext("/adempiere").sessionCookie());
		assertEquals("JSESSIONID_WSTORE",
				ContextRoutingPolicy.forContext("/wstore").sessionCookie());
		assertEquals(32L << 20,
				ContextRoutingPolicy.forContext("/wstore").requestLimit());
		assertEquals(64L << 20,
				ContextRoutingPolicy.forContext("/adempiere").requestLimit());
		assertFalse(ContextRoutingPolicy.forContext("/admin")
				.forwardRequestHeader("Origin"));
		assertTrue(ContextRoutingPolicy.forContext("/admin")
				.forwardRequestHeader("Authorization"));
		assertTrue(ContextRoutingPolicy.forContext("/wstore")
				.confidential("/orderServlet"));
		assertTrue(ContextRoutingPolicy.forContext("/wstore")
				.confidential("/login.jsp/account"));
		assertFalse(ContextRoutingPolicy.forContext("/wstore")
				.confidential("/login.jspx"));
	}

	@Test
	void switchIsStrictAndFailClosed() {
		String key = "MODERN_WEB_WSTORE_ENABLED";
		assertTrue(ContextSwitch.parse(key,
				List.of(new SysConfigRow(key, "Y", 0, 0, true))).enabled());
		assertFalse(ContextSwitch.parse(key,
				List.of(new SysConfigRow(key, "true", 0, 0, true))).valid());
		assertFalse(ContextSwitch.parse(key, List.of(
				new SysConfigRow(key, "Y", 0, 0, true),
				new SysConfigRow(key, "Y", 0, 0, true))).valid());
		assertFalse(ContextSwitch.parse(key,
				List.of(new SysConfigRow(key, "Y", 11, 0, true))).enabled());
	}

	@Test
	void sessionsArePinnedAndModernNeverFallsBack() {
		assertEquals(ContextRoutingDecision.Action.MODERN_SESSIONLESS,
				ContextRoutingDecision.decide(
						false, false, null, true, "DEPLOY"));
		assertEquals(ContextRoutingDecision.Action.LEGACY,
				ContextRoutingDecision.decide(
						true, false, null, true, "DEPLOY"));
		assertEquals(ContextRoutingDecision.Action.FAIL,
				ContextRoutingDecision.decide(
						true, true, null, false, "DEPLOY"));
		ContextSessionAffinity affinity =
				new ContextSessionAffinity("DEPLOY", "MODERN");
		assertEquals(ContextRoutingDecision.Action.MODERN_SESSION,
				ContextRoutingDecision.decide(
						true, true, affinity, false, "DEPLOY"));
		assertEquals(ContextRoutingDecision.Action.INVALIDATE,
				ContextRoutingDecision.decide(
						true, true, affinity, true, "REPLACEMENT"));
	}

	@Test
	void backendSessionRotationIsAtomicAndSurvivesRestart() throws Exception {
		ContextSessionAffinity affinity =
				new ContextSessionAffinity("DEPLOY", "OLD");
		CyclicBarrier barrier = new CyclicBarrier(3);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				barrier.await();
				return affinity.updateModernSessionId("OLD", "NEW-A");
			});
			var second = executor.submit(() -> {
				barrier.await();
				return affinity.updateModernSessionId("OLD", "NEW-B");
			});
			barrier.await();
			assertTrue(first.get() ^ second.get());
		}
		String winner = affinity.modernSessionId();
		assertTrue(winner.equals("NEW-A") || winner.equals("NEW-B"));
		assertTrue(affinity.updateModernSessionId(winner, "RESTARTED"));
		ContextSessionAffinity restored = roundTrip(affinity);
		assertEquals("RESTARTED", restored.modernSessionId());
		assertFalse(restored.updateModernSessionId(winner, "STALE"));
		assertEquals("RESTARTED", restored.modernSessionId());
	}

	private static ContextSessionAffinity roundTrip(
			ContextSessionAffinity affinity) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(affinity);
		}
		try (ObjectInputStream input = new ObjectInputStream(
				new ByteArrayInputStream(bytes.toByteArray()))) {
			return (ContextSessionAffinity) input.readObject();
		}
	}
}
