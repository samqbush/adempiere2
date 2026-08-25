package org.adempiere.webui.phase5d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.phase5d.ErrorMessageWindowFacts.Observation;
import org.junit.jupiter.api.Test;

/**
 * Database-neutral mutation proofs for the Phase 5d read-only window step.
 *
 * <p>These run in {@code :zkwebui:check} rather than only in the database-backed
 * browser lane: a proof that the gate rejects an absent or writable window is
 * worthless if it can only be executed on the one machine that also has a
 * restored seed. They mutate the reviewed baseline observation from
 * {@code contracts/legacy-web-browser-v1/window-observation-fixture.tsv} and
 * call the same {@link ErrorMessageWindowFacts#derive} the live capture calls.
 */
class ErrorMessageWindowFactsTest {

	@Test
	void reviewedBaselineDerivesTheFrozenWindowFacts() throws IOException {
		Map<String, String> contract = BrowserSemanticContract.facts();
		Map<String, String> derived =
				ErrorMessageWindowFacts.derive(BrowserSemanticContract.windowObservationFixture());

		Map<String, String> frozenWindowFacts = new LinkedHashMap<>();
		contract.forEach((name, value) -> {
			if (name.startsWith("window-error-message-")) {
				frozenWindowFacts.put(name, value);
			}
		});
		assertEquals(frozenWindowFacts, derived,
				"The reviewed baseline observation no longer derives the frozen window facts");
		assertTrue(frozenWindowFacts.size() >= 7,
				"The frozen contract lost Phase 5d window facts: " + frozenWindowFacts);
	}

	@Test
	void anAbsentErrorMessageWindowFailsTheGate() throws IOException {
		Observation baseline = BrowserSemanticContract.windowObservationFixture();

		Observation neverOpened = new Observation(
				0,
				false,
				List.of("Menu (1)"),
				Map.of(),
				Map.of(),
				baseline.databaseWriteDelta());
		IllegalStateException missingPanel = assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.derive(neverOpened));
		assertTrue(missingPanel.getMessage().contains("did not open"),
				"Absent window was not reported as an absent window: "
						+ missingPanel.getMessage());

		Observation missingTab = new Observation(
				baseline.matchingWindowPanels(),
				baseline.windowPanelVisible(),
				List.of("Menu (1)"),
				baseline.columnMarkers(),
				baseline.controlsDisabled(),
				baseline.databaseWriteDelta());
		assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.derive(missingTab),
				"A desktop without the Error Message tab was accepted");

		Observation duplicateWindow = new Observation(
				2,
				baseline.windowPanelVisible(),
				baseline.visibleTabLabels(),
				baseline.columnMarkers(),
				baseline.controlsDisabled(),
				baseline.databaseWriteDelta());
		assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.derive(duplicateWindow),
				"An ambiguous second Error Message window was accepted");
	}

	@Test
	void anInvisibleErrorMessageWindowFailsTheGate() throws IOException {
		Observation baseline = BrowserSemanticContract.windowObservationFixture();
		Observation hidden = new Observation(
				baseline.matchingWindowPanels(),
				false,
				baseline.visibleTabLabels(),
				baseline.columnMarkers(),
				baseline.controlsDisabled(),
				baseline.databaseWriteDelta());

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.derive(hidden));
		assertTrue(failure.getMessage().contains("not visible"),
				"A window rendered with no box was not reported as invisible: "
						+ failure.getMessage());
	}

	@Test
	void aWritableRecordIdentityFailsTheGate() throws IOException {
		Observation baseline = BrowserSemanticContract.windowObservationFixture();
		Map<String, String> frozen = ErrorMessageWindowFacts.derive(baseline);

		Map<String, String> writableIdentity = new LinkedHashMap<>(baseline.columnMarkers());
		String identityColumn = frozen
				.get(ErrorMessageWindowFacts.FACT_READONLY_COLUMNS)
				.split(",")[0];
		writableIdentity.put(identityColumn, "normal-field");
		Map<String, String> mutated = ErrorMessageWindowFacts.derive(new Observation(
				baseline.matchingWindowPanels(),
				baseline.windowPanelVisible(),
				baseline.visibleTabLabels(),
				writableIdentity,
				baseline.controlsDisabled(),
				baseline.databaseWriteDelta()));

		assertNotEquals(frozen, mutated,
				"A writable " + identityColumn + " was normalized away");
		assertNotEquals(
				frozen.get(ErrorMessageWindowFacts.FACT_READONLY_COLUMNS),
				mutated.get(ErrorMessageWindowFacts.FACT_READONLY_COLUMNS),
				"The read-only column set did not react to a writable identity column");
	}

	@Test
	void anEnabledDestructiveControlFailsTheGate() throws IOException {
		Observation baseline = BrowserSemanticContract.windowObservationFixture();
		Map<String, String> frozen = ErrorMessageWindowFacts.derive(baseline);
		assertEquals("true", frozen.get(ErrorMessageWindowFacts.FACT_DELETE_DISABLED),
				"The frozen baseline no longer disables the destructive controls");

		List<Map<String, String>> mutations = new ArrayList<>();
		for (String control : ErrorMessageWindowFacts.DESTRUCTIVE_CONTROLS) {
			Map<String, Boolean> enabled =
					new LinkedHashMap<>(baseline.controlsDisabled());
			enabled.put(control, false);
			Map<String, String> mutated = ErrorMessageWindowFacts.derive(new Observation(
					baseline.matchingWindowPanels(),
					baseline.windowPanelVisible(),
					baseline.visibleTabLabels(),
					baseline.columnMarkers(),
					enabled,
					baseline.databaseWriteDelta()));
			assertEquals("false", mutated.get(ErrorMessageWindowFacts.FACT_DELETE_DISABLED),
					"Enabling " + control + " left the window reported as read-only");
			assertNotEquals(frozen, mutated,
					"Enabling " + control + " was normalized away");
			mutations.add(mutated);
		}
		assertEquals(ErrorMessageWindowFacts.DESTRUCTIVE_CONTROLS.size(), mutations.size());

		Map<String, Boolean> withoutToolbar =
				new LinkedHashMap<>(baseline.controlsDisabled());
		withoutToolbar.remove(ErrorMessageWindowFacts.DESTRUCTIVE_CONTROLS.get(0));
		assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.derive(new Observation(
						baseline.matchingWindowPanels(),
						baseline.windowPanelVisible(),
						baseline.visibleTabLabels(),
						baseline.columnMarkers(),
						withoutToolbar,
						baseline.databaseWriteDelta())),
				"A window missing a destructive control was accepted");
	}

	@Test
	void aDatabaseWriteFailsTheGate() throws IOException {
		Observation baseline = BrowserSemanticContract.windowObservationFixture();
		Map<String, String> frozen = ErrorMessageWindowFacts.derive(baseline);
		assertEquals("0", frozen.get(ErrorMessageWindowFacts.FACT_DATABASE_WRITES),
				"The frozen baseline no longer records a zero-write window step");

		Map<String, String> mutated = ErrorMessageWindowFacts.derive(new Observation(
				baseline.matchingWindowPanels(),
				baseline.windowPanelVisible(),
				baseline.visibleTabLabels(),
				baseline.columnMarkers(),
				baseline.controlsDisabled(),
				1));
		assertNotEquals(frozen, mutated,
				"A row written by the window step was normalized away");
	}

	@Test
	void theReviewedZeroWriteTableSetIsDeclared() throws IOException {
		Map<String, Integer> effects = BrowserSemanticContract.windowReadOnlyEffects();
		assertTrue(effects.keySet().containsAll(
				List.of("AD_Error", "AD_Window", "AD_Tab", "AD_Field")),
				"The read-only effect contract lost a reviewed table: " + effects.keySet());
		effects.forEach((table, delta) -> assertEquals(0, delta,
				table + " no longer declares a zero allowed delta"));
	}

	@Test
	void theBrowserEvaluationShapeIsSharedWithTheLiveCapture() throws IOException {
		Observation baseline = BrowserSemanticContract.windowObservationFixture();
		Map<String, Object> columns = new LinkedHashMap<>(baseline.columnMarkers());
		Map<String, Object> controls = new LinkedHashMap<>(baseline.controlsDisabled());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("panels", baseline.matchingWindowPanels());
		payload.put("visible", baseline.windowPanelVisible());
		payload.put("tabs", new ArrayList<Object>(baseline.visibleTabLabels()));
		payload.put("columns", columns);
		payload.put("controls", controls);

		assertEquals(ErrorMessageWindowFacts.derive(baseline),
				ErrorMessageWindowFacts.derive(ErrorMessageWindowFacts.fromEvaluation(
						payload, baseline.databaseWriteDelta())),
				"The browser payload conversion diverged from the reviewed baseline");

		payload.remove("columns");
		assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.fromEvaluation(
						payload, baseline.databaseWriteDelta()),
				"A truncated browser payload was accepted");
		assertThrows(IllegalStateException.class,
				() -> ErrorMessageWindowFacts.fromEvaluation(
						"not-an-observation", baseline.databaseWriteDelta()),
				"A non-observation browser result was accepted");
	}

	@Test
	void approvedTextVolatilityIsStillNormalized() {
		assertEquals("Error Message",
				BrowserSemanticContract.normalizedText(" Error\u00a0 \n Message  "),
				"Approved whitespace volatility was not normalized");
		assertNotEquals("Error Message",
				BrowserSemanticContract.normalizedText("Error Messages"),
				"A changed semantic name was normalized away");
	}

	@Test
	void approvedUrlVolatilityIsStillNormalized() {
		String base = "http://127.0.0.1:8888";
		assertEquals("/webui/zkau;jsessionid=<SESSION>",
				BrowserSemanticContract.normalizedUrl(base,
						base + "/webui/zkau;jsessionid=0A1B2C3D4E5F"),
				"The container session id was not normalized");
		assertEquals("/webui/zkau/view/<DTID>/zk_comp_<COMPONENT>/img.png",
				BrowserSemanticContract.normalizedUrl(base,
						base + "/webui/zkau/view/z_x1/zk_comp_2977/img.png"),
				"The ZK desktop and component ids were not normalized");
		assertNotEquals("/webui/zkau",
				BrowserSemanticContract.normalizedUrl(base, base + "/webui/zkau2"),
				"A changed route was normalized away");
	}
}
