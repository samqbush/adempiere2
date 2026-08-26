package org.adempiere.webui.phase5d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Database-neutral mutation proofs for the Phase 5d <em>modern</em> slice.
 *
 * <p>{@link ErrorMessageWindowFactsTest} already proves the window derivation
 * rejects an absent, invisible, writable, or record-writing window. That
 * derivation is shared verbatim by both renderings, so it is not duplicated
 * here. What this class adds is the part of the modern comparison that has no
 * ZK 3.6 counterpart:
 *
 * <ul>
 *   <li>the reviewed comparable-fact set, so a fact cannot silently drop out of
 *       the modern-versus-legacy comparison;</li>
 *   <li>the login, role and menu facts, which are the three steps before the
 *       window and are otherwise only asserted by a database-backed lane;</li>
 *   <li>modern route classification, so an unexpected or outbound route cannot
 *       be absorbed into a catch-all bucket;</li>
 *   <li>modern URL normalization, so the ZK build hash and desktop id are the
 *       only volatility that is normalized away.</li>
 * </ul>
 *
 * These run in {@code :zkwebui:check}. A proof that only executes on the one
 * machine with a restored seed is not a proof anybody re-runs.
 */
class ModernSliceContractTest {

	@Test
	void theComparableFactSetCoversEveryStepOfTheWalkingSkeleton() throws IOException {
		Set<String> comparable = ModernWindowExtraction.comparableFactNames();

		// login -> role -> menu -> window -> logout. If any of these ever stops
		// being compared, the modern slice could pass while failing a step.
		assertTrue(comparable.contains("role-labels-visible"),
				"Role selection is no longer compared with the frozen legacy baseline");
		assertTrue(comparable.contains("desktop-user"),
				"The authenticated desktop identity is no longer compared");
		assertTrue(comparable.contains("menu-user-browser"),
				"The menu is no longer compared");
		assertTrue(comparable.contains("logout-login-visible"),
				"Logout is no longer compared");
		assertTrue(comparable.containsAll(List.of(
				ErrorMessageWindowFacts.FACT_VISIBLE,
				ErrorMessageWindowFacts.FACT_TAB_LABEL,
				ErrorMessageWindowFacts.FACT_READONLY_COLUMNS,
				ErrorMessageWindowFacts.FACT_MARKED_COLUMNS,
				ErrorMessageWindowFacts.FACT_RECORD_CONTROLS,
				ErrorMessageWindowFacts.FACT_DELETE_DISABLED,
				ErrorMessageWindowFacts.FACT_DATABASE_WRITES)),
				"A read-only window fact is no longer compared: " + comparable);

		// The four Tomcat 9 context facts must stay out: the modern runtime
		// deploys none of those contexts, so comparing them would be asserting
		// something the slice never claimed.
		for (String context : List.of("filter-adempiere", "filter-mobile",
				"filter-webui", "filter-wstore")) {
			assertFalse(comparable.contains(context),
					context + " is a Tomcat 9 context fact and cannot be compared "
							+ "against the modern runtime");
		}

		// Every comparable fact must actually exist in the frozen legacy
		// contract, otherwise the comparison would compare against nothing.
		Map<String, String> frozen = BrowserSemanticContract.facts();
		List<String> unknown = comparable.stream()
				.filter(name -> !frozen.containsKey(name))
				.toList();
		assertTrue(unknown.isEmpty(),
				"The modern comparison names facts the frozen legacy contract does "
						+ "not define: " + unknown);
	}

	@Test
	void aCaptureMissingAComparableFactFailsTheGate() throws IOException {
		Map<String, String> frozen = BrowserSemanticContract.facts();
		Map<String, String> complete = ModernWindowExtraction.comparable(frozen);
		assertEquals(ModernWindowExtraction.comparableFactNames(),
				complete.keySet(),
				"The comparable projection dropped a reviewed fact");

		for (String step : List.of("role-labels-visible", "desktop-user",
				"menu-user-browser", "logout-login-visible",
				ErrorMessageWindowFacts.FACT_VISIBLE,
				ErrorMessageWindowFacts.FACT_DATABASE_WRITES)) {
			Map<String, String> truncated = new LinkedHashMap<>(frozen);
			truncated.remove(step);
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> ModernWindowExtraction.comparable(truncated),
					"A capture that never produced " + step + " was accepted");
			assertTrue(failure.getMessage().contains(step),
					"The failure did not name the missing step: " + failure.getMessage());
		}
	}

	@Test
	void aChangedLoginRoleOrMenuFactFailsTheGate() throws IOException {
		Map<String, String> frozen = BrowserSemanticContract.facts();
		Map<String, String> expected = ModernWindowExtraction.comparable(frozen);

		Map<String, String> wrongUser = new LinkedHashMap<>(frozen);
		wrongUser.put("desktop-user", "SomeoneElse@GardenWorld");
		assertNotEquals(expected, ModernWindowExtraction.comparable(wrongUser),
				"A capture that authenticated a different user would have passed");

		Map<String, String> noRoles = new LinkedHashMap<>(frozen);
		noRoles.put("role-labels-visible", "false");
		assertNotEquals(expected, ModernWindowExtraction.comparable(noRoles),
				"A capture whose role grid never rendered would have passed");

		Map<String, String> noMenu = new LinkedHashMap<>(frozen);
		noMenu.put("menu-user-browser", "false");
		assertNotEquals(expected, ModernWindowExtraction.comparable(noMenu),
				"A capture whose menu lost a dictionary node would have passed");

		Map<String, String> noLogout = new LinkedHashMap<>(frozen);
		noLogout.put("logout-login-visible", "false");
		assertNotEquals(expected, ModernWindowExtraction.comparable(noLogout),
				"A capture that never returned to the login page would have passed");
	}

	@Test
	void aChangedWindowStateOrDatabaseEffectFailsTheGate() throws IOException {
		Map<String, String> frozen = BrowserSemanticContract.facts();
		Map<String, String> expected = ModernWindowExtraction.comparable(frozen);

		Map<String, String> invisible = new LinkedHashMap<>(frozen);
		invisible.put(ErrorMessageWindowFacts.FACT_VISIBLE, "false");
		assertNotEquals(expected, ModernWindowExtraction.comparable(invisible),
				"A capture whose window never rendered would have passed");

		Map<String, String> writable = new LinkedHashMap<>(frozen);
		writable.put(ErrorMessageWindowFacts.FACT_DELETE_DISABLED, "false");
		assertNotEquals(expected, ModernWindowExtraction.comparable(writable),
				"A capture with enabled destructive controls would have passed");

		Map<String, String> fewerReadOnly = new LinkedHashMap<>(frozen);
		fewerReadOnly.put(ErrorMessageWindowFacts.FACT_READONLY_COLUMNS,
				"AD_Client_ID,AD_Language");
		assertNotEquals(expected, ModernWindowExtraction.comparable(fewerReadOnly),
				"A capture that lost a read-only identity column would have passed");

		Map<String, String> wrote = new LinkedHashMap<>(frozen);
		wrote.put(ErrorMessageWindowFacts.FACT_DATABASE_WRITES, "1");
		assertNotEquals(expected, ModernWindowExtraction.comparable(wrote),
				"A capture that wrote a row would have passed");
	}

	@Test
	void theReviewedModernRouteSetIsCompleteAndClosed() throws IOException {
		Set<String> expected = ModernWindowExtraction.expectedRouteClasses();
		assertTrue(expected.contains("context\tGET\t/webui-modern/"),
				"The modern context route is no longer expected: " + expected);
		assertTrue(expected.contains("zkau\tPOST"),
				"The ZK AU route is no longer expected: " + expected);
		assertTrue(expected.contains("zk-resource\tGET"),
				"The ZK client resource route is no longer expected: " + expected);

		// The three inherited outbound hosts come from ADempiere's own login and
		// desktop markup and are present in the frozen legacy contract too. They
		// are declared, not silently tolerated.
		for (String host : List.of("www.zkoss.org", "sfx-images.mozilla.org",
				"www.google.com")) {
			assertTrue(expected.contains("external\tGET\t" + host),
					"The inherited outbound host " + host
							+ " is no longer declared, so a capture that still "
							+ "attempts it would fail for the wrong reason");
		}

		// The two hosts Phase 5d removed must never be declared expected again.
		for (String removed : ModernWindowExtraction.removedExternalHosts()) {
			assertFalse(expected.contains("external\tGET\t" + removed),
					removed + " was reintroduced into the reviewed modern route set");
		}
		assertTrue(ModernWindowExtraction.removedExternalHosts()
						.contains("fonts.googleapis.com"),
				"The ZK 3.6 .dsp theme's outbound font request is no longer "
						+ "recorded as removed");
	}

	@Test
	void modernRouteClassificationRejectsUnexpectedRoutes() {
		assertEquals("context\tGET\t/webui-modern/",
				ModernWindowExtraction.routeClass("GET", "/webui-modern/"),
				"The modern context route was misclassified");
		assertEquals("zkau\tPOST",
				ModernWindowExtraction.routeClass("POST", "/webui-modern/zkau"),
				"The ZK AU route was misclassified");
		assertEquals("zk-resource\tGET",
				ModernWindowExtraction.routeClass("GET",
						"/webui-modern/zkau/web/<ZKBUILD>/js/zk.wpd"),
				"The ZK client resource route was misclassified");
		assertEquals("external\tGET\tfonts.googleapis.com",
				ModernWindowExtraction.routeClass("GET",
						"https://fonts.googleapis.com/css?family=Open+Sans"),
				"An outbound request was not classified as external");

		// The ZK 3.6 DSP theme imported a Google font on every page load and the
		// login page carried inherited Firefox and calendar buttons. The Phase 5d
		// slice removes all three, and the live capture asserts that no external
		// class appears at all, so the classifier must not hide one.
		assertTrue(ModernWindowExtraction.routeClass("GET",
						"http://sfx-images.mozilla.org/x.png").startsWith("external\t"),
				"An inherited outbound image request was not classified as external");

		// Anything that is neither the modern context nor an outbound host must
		// be reported verbatim rather than absorbed.
		String stray = ModernWindowExtraction.routeClass("GET", "/webui/zkau");
		assertTrue(stray.startsWith("unclassified\t"),
				"A request to the legacy ZK 3.6 context was absorbed into a known "
						+ "route class: " + stray);
		assertTrue(stray.contains("/webui/zkau"),
				"The unclassified route did not name itself: " + stray);
		assertTrue(ModernWindowExtraction.routeClass("GET", "/ADInterface/services")
						.startsWith("unclassified\t"),
				"A request to the colocated Phase 4 SOAP context was absorbed into a "
						+ "modern route class");
	}

	@Test
	void modernUrlNormalizationKeepsMeaningfulChanges() {
		String base = "http://127.0.0.1:8890";
		assertEquals("/webui-modern/zkau;jsessionid=<SESSION>",
				ModernWindowExtraction.normalizedUrl(base,
						base + "/webui-modern/zkau;jsessionid=0A1B2C3D4E5F"),
				"The container session id was not normalized");
		assertEquals("/webui-modern/zkau/web/<ZKBUILD>/js/zk.wpd",
				ModernWindowExtraction.normalizedUrl(base,
						base + "/webui-modern/zkau/web/57aacf5b/js/zk.wpd"),
				"The ZK client build hash was not normalized");
		assertEquals("/webui-modern/zkau/view/<DTID>/zk_comp_<COMPONENT>/img.png",
				ModernWindowExtraction.normalizedUrl(base,
						base + "/webui-modern/zkau/view/z_x1/zk_comp_2977/img.png"),
				"The ZK desktop and component ids were not normalized");
		assertNotEquals("/webui-modern/zkau",
				ModernWindowExtraction.normalizedUrl(base, base + "/webui-modern/zkau2"),
				"A changed route was normalized away");
		assertNotEquals(
				ModernWindowExtraction.normalizedUrl(base, base + "/webui-modern/"),
				ModernWindowExtraction.normalizedUrl(base, base + "/webui/"),
				"The modern and legacy contexts normalized to the same value");
	}

	@Test
	void theModernExtractionReadsAdempiereOwnedNamesOnly() {
		String script = ModernWindowExtraction.BROWSER_EXTRACTION_SCRIPT;
		for (String owned : List.of("desktop-tabpanel", "unqField_", "_AD_Error_",
				"readonly-field", "mandatory-field", "Delete Selected Items",
				"Delete record", "New Record", "Save changes")) {
			assertTrue(script.contains(owned),
					"The modern extraction no longer anchors on the ADempiere-owned "
							+ "name '" + owned + "'");
		}
		for (String zk36 : List.of("z.label", "z.disd", "z-toolbar-button-disd")) {
			assertFalse(script.contains(zk36),
					"The modern extraction still reads the ZK 3.6 client attribute '"
							+ zk36 + "', which ZK CE 10 does not emit");
		}
	}
}
