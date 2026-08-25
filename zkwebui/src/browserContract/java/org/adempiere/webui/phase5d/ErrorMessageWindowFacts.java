package org.adempiere.webui.phase5d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase 5d semantic derivation for the legacy "Error Message" window step.
 *
 * <p>The live Playwright capture and the database-neutral mutation proofs both
 * call {@link #derive(Observation)}; a mutation proof that re-implemented the
 * derivation would prove nothing about the gate that actually runs.
 *
 * <h2>What "read-only" means here, precisely</h2>
 *
 * The ZK 3.6 "Error Message" window is <em>not</em> dictionary-read-only:
 * {@code AD_Tab} 314 carries {@code IsReadOnly='N'} and {@code IsInsertRecord='Y'},
 * and the rendered toolbar really does enable New Record and Save changes. The
 * contract therefore records that enabled state verbatim instead of claiming a
 * read-only window that the product does not implement, and pins the read-only
 * property that the flow does hold:
 *
 * <ol>
 *   <li>the window renders and is visible;</li>
 *   <li>every record-identity column of {@code AD_Error} carries ADempiere's own
 *       {@code readonly-field} marker
 *       ({@code WEditor.repaintComponent}, zkwebui/WEB-INF/src/org/adempiere/webui/editor/WEditor.java:399-408);</li>
 *   <li>both destructive controls are disabled;</li>
 *   <li>the marker-owned database observes zero writes across the capture.</li>
 * </ol>
 *
 * Only (1)-(4) together are reported as read-only. Overstating this the way the
 * four {@code context-reachability-only} filter facts were nearly overstated
 * would train a reader to trust a claim the evidence does not support.
 */
public final class ErrorMessageWindowFacts {

	public static final String WINDOW_LABEL = "Error Message";

	public static final String FACT_VISIBLE = "window-error-message-visible";
	public static final String FACT_TAB_LABEL = "window-error-message-tab-label";
	public static final String FACT_READONLY_COLUMNS =
			"window-error-message-readonly-columns";
	public static final String FACT_MARKED_COLUMNS =
			"window-error-message-marked-columns";
	public static final String FACT_RECORD_CONTROLS =
			"window-error-message-record-controls";
	public static final String FACT_DELETE_DISABLED =
			"window-error-message-delete-controls-disabled";
	public static final String FACT_DATABASE_WRITES =
			"window-error-message-database-writes";

	/** ADempiere's own read-only field marker, applied by {@code WEditor}. */
	public static final String READONLY_MARKER = "readonly-field";

	/**
	 * Toolbar controls recorded verbatim. The two Delete controls are the
	 * destructive pair; New Record and Save changes are recorded because they
	 * are enabled, and hiding that would make the read-only claim dishonest.
	 */
	public static final List<String> RECORDED_CONTROLS = List.of(
			"Delete Selected Items",
			"Delete record",
			"New Record",
			"Save changes");

	/** Controls that must be disabled for the step to count as read-only. */
	public static final List<String> DESTRUCTIVE_CONTROLS = List.of(
			"Delete Selected Items",
			"Delete record");

	private ErrorMessageWindowFacts() {
	}

	/**
	 * One capture's browser observation of the opened window.
	 *
	 * @param matchingWindowPanels desktop tab panels that contain an
	 *        {@code AD_Error} editor; exactly one is required
	 * @param windowPanelVisible whether that panel has a rendered box
	 * @param visibleTabLabels normalized text of every visible desktop tab
	 * @param columnMarkers {@code AD_Error} column to ADempiere field-state
	 *        marker class
	 * @param controlsDisabled recorded toolbar control to disabled state
	 * @param databaseWriteDelta rows the capture added to the reviewed
	 *        read-only table set
	 */
	public record Observation(
			int matchingWindowPanels,
			boolean windowPanelVisible,
			List<String> visibleTabLabels,
			Map<String, String> columnMarkers,
			Map<String, Boolean> controlsDisabled,
			int databaseWriteDelta) {
	}

	/**
	 * Derives the frozen semantic facts, or fails loudly.
	 *
	 * @throws IllegalStateException when the window is absent, ambiguous, or
	 *         invisible, or when the observation is structurally incomplete
	 */
	public static Map<String, String> derive(Observation observation) {
		if (observation.matchingWindowPanels() != 1) {
			throw new IllegalStateException(
					"Expected exactly one desktop tab panel containing an AD_Error editor, found "
							+ observation.matchingWindowPanels()
							+ "; the '" + WINDOW_LABEL + "' window did not open");
		}
		if (!observation.windowPanelVisible()) {
			throw new IllegalStateException(
					"The '" + WINDOW_LABEL + "' window panel rendered no box; it is not visible");
		}
		long tabMatches = observation.visibleTabLabels().stream()
				.filter(WINDOW_LABEL::equals)
				.count();
		if (tabMatches != 1) {
			throw new IllegalStateException(
					"Expected exactly one visible '" + WINDOW_LABEL + "' desktop tab, found "
							+ tabMatches);
		}
		if (observation.columnMarkers().isEmpty()) {
			throw new IllegalStateException(
					"The '" + WINDOW_LABEL
							+ "' window rendered no AD_Error editor carrying an ADempiere field-state marker");
		}
		List<String> missingControls = new ArrayList<>();
		for (String control : RECORDED_CONTROLS) {
			if (!observation.controlsDisabled().containsKey(control)) {
				missingControls.add(control);
			}
		}
		if (!missingControls.isEmpty()) {
			throw new IllegalStateException(
					"The '" + WINDOW_LABEL + "' window toolbar is missing " + missingControls);
		}

		Map<String, String> markers = new TreeMap<>(observation.columnMarkers());
		TreeSet<String> readOnlyColumns = new TreeSet<>();
		List<String> markerPairs = new ArrayList<>();
		markers.forEach((column, marker) -> {
			markerPairs.add(column + "=" + marker);
			if (READONLY_MARKER.equals(marker)) {
				readOnlyColumns.add(column);
			}
		});

		Map<String, Boolean> controls = new TreeMap<>(observation.controlsDisabled());
		List<String> controlPairs = new ArrayList<>();
		RECORDED_CONTROLS.stream().sorted().forEach(control -> controlPairs.add(
				control + "=" + (controls.get(control) ? "disabled" : "enabled")));
		boolean destructiveDisabled = DESTRUCTIVE_CONTROLS.stream()
				.allMatch(control -> Boolean.TRUE.equals(controls.get(control)));

		Map<String, String> facts = new LinkedHashMap<>();
		facts.put(FACT_VISIBLE, "true");
		facts.put(FACT_TAB_LABEL, WINDOW_LABEL);
		facts.put(FACT_READONLY_COLUMNS, String.join(",", readOnlyColumns));
		facts.put(FACT_MARKED_COLUMNS, String.join(",", markerPairs));
		facts.put(FACT_RECORD_CONTROLS, String.join(",", controlPairs));
		facts.put(FACT_DELETE_DISABLED, Boolean.toString(destructiveDisabled));
		facts.put(FACT_DATABASE_WRITES, Integer.toString(observation.databaseWriteDelta()));
		return facts;
	}

	/**
	 * Converts the raw browser evaluation result into an {@link Observation}.
	 * The live capture and the mutation proofs share this conversion so a
	 * shape change in the browser payload cannot pass a proof and fail a
	 * capture.
	 */
	@SuppressWarnings("unchecked")
	public static Observation fromEvaluation(Object raw, int databaseWriteDelta) {
		if (!(raw instanceof Map)) {
			throw new IllegalStateException(
					"Browser evaluation returned " + raw + " instead of an observation");
		}
		Map<String, Object> payload = (Map<String, Object>) raw;
		int panels = ((Number) required(payload, "panels")).intValue();
		boolean visible = Boolean.TRUE.equals(payload.get("visible"));
		List<String> tabs = new ArrayList<>();
		for (Object tab : (List<Object>) required(payload, "tabs")) {
			tabs.add(BrowserSemanticContract.normalizedText(String.valueOf(tab)));
		}
		Map<String, String> columns = new TreeMap<>();
		((Map<String, Object>) required(payload, "columns"))
				.forEach((column, marker) -> columns.put(column, String.valueOf(marker)));
		Map<String, Boolean> controls = new TreeMap<>();
		((Map<String, Object>) required(payload, "controls"))
				.forEach((control, disabled) ->
						controls.put(control, Boolean.TRUE.equals(disabled)));
		return new Observation(panels, visible, tabs, columns, controls, databaseWriteDelta);
	}

	private static Object required(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value == null) {
			throw new IllegalStateException(
					"Browser observation is missing '" + key + "'");
		}
		return value;
	}

	/**
	 * The browser-side extraction, kept next to the derivation it feeds so the
	 * selector vocabulary and the fact vocabulary are reviewed together.
	 *
	 * <p>Every selector is anchored on an ADempiere-owned name rather than a ZK
	 * internal: {@code desktop-tabpanel} is set by
	 * {@code WindowContainer.java:214}, the {@code unqField_<window>_<tab>_<table>_<column>}
	 * id prefix by {@code WEditor.java:121-125}, the field-state markers by
	 * {@code WEditor.java:369-408}, and the toolbar titles are the translated
	 * {@code AD_Message} texts from {@code CWindowToolbar.java:247-252}. The
	 * trailing digits of every id are the volatile per-desktop ZK component
	 * counter ({@code SahiIdGenerator_v1}) and are matched, never recorded.
	 */
	public static final String BROWSER_EXTRACTION_SCRIPT = """
			() => {
			  const markers = ['readonly-field', 'mandatory-field', 'normal-field',
			      'field-text', 'field-text-dis', 'field-longtext', 'field-longtext-dis',
			      'field-memo', 'field-memo-dis'];
			  const recorded = ['Delete Selected Items', 'Delete record',
			      'New Record', 'Save changes'];
			  const normalize = function (value) {
			    return (value || '').replace(/\\u00a0/g, ' ').replace(/\\s+/g, ' ').trim();
			  };
			  const visible = function (element) {
			    return element.getClientRects().length > 0;
			  };
			  const result = {
			    panels: 0, visible: false, tabs: [], columns: {}, controls: {}
			  };
			  result.tabs = Array.prototype.slice
			      .call(document.querySelectorAll('span.z-tab-text'))
			      .filter(visible)
			      .map(function (element) { return normalize(element.textContent); });
			  const panels = Array.prototype.slice
			      .call(document.querySelectorAll('div.desktop-tabpanel'))
			      .filter(function (panel) {
			        return panel.querySelector("[id*='_AD_Error_']") !== null;
			      });
			  result.panels = panels.length;
			  if (panels.length !== 1) {
			    return result;
			  }
			  const panel = panels[0];
			  result.visible = visible(panel);
			  const pattern = /^unqField_\\d+_\\d+_AD_Error_([A-Za-z_]+?)\\d+$/;
			  Array.prototype.slice
			      .call(panel.querySelectorAll("[id^='unqField_']"))
			      .forEach(function (element) {
			        const match = pattern.exec(element.id);
			        if (match === null) {
			          return;
			        }
			        const classes = (element.getAttribute('class') || '').split(/\\s+/);
			        const found = markers.filter(function (marker) {
			          return classes.indexOf(marker) >= 0;
			        });
			        if (found.length !== 1) {
			          return;
			        }
			        result.columns[match[1]] = found[0];
			      });
			  recorded.forEach(function (title) {
			    const control = panel.querySelector(
			        "a.toolbar-button[title='" + title + "']");
			    if (control === null) {
			      return;
			    }
			    const classes = control.getAttribute('class') || '';
			    result.controls[title] =
			        classes.indexOf('z-toolbar-button-disd') >= 0
			        || control.getAttribute('z.disd') === 'true';
			  });
			  return result;
			}
			""";
}
