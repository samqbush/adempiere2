package org.adempiere.webui.phase5d;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ZK CE 10 browser vocabulary for the Phase 5d modern slice.
 *
 * <p>This class exists beside {@link ErrorMessageWindowFacts} rather than inside
 * the live test for the same reason the ZK 3.6 extraction does: the selector
 * vocabulary and the fact vocabulary have to be reviewed together, and the
 * database-neutral mutation proofs have to exercise the same code the live
 * capture runs.
 *
 * <h2>Why the selectors differ from the ZK 3.6 ones</h2>
 *
 * The frozen legacy extraction in {@link ErrorMessageWindowFacts} matches
 * {@code tr.z-combo-item[z.label=...]} and {@code z.disd}, both of which are ZK
 * 3.6 client attributes that ZK CE 10 no longer emits. Every selector here is
 * anchored on an ADempiere-owned name instead, so the two vocabularies observe
 * the same product facts through two framework renderings:
 *
 * <ul>
 *   <li>{@code desktop-tabpanel} is set by
 *       {@code WindowContainer.java:214} and is unchanged by the migration;</li>
 *   <li>the {@code unqField_<window>_<tab>_<table>_<column>} id prefix is set by
 *       {@code WEditor.java:121-125};</li>
 *   <li>{@code readonly-field} / {@code mandatory-field} / {@code normal-field}
 *       are applied by {@code WEditor.repaintComponent};</li>
 *   <li>the toolbar titles are the translated {@code AD_Message} texts from
 *       {@code CWindowToolbar.java:247-252}, rendered by ZK CE 10 as the
 *       {@code title} attribute of the tooltip text.</li>
 * </ul>
 *
 * Disabled state is read from ZK CE 10's {@code z-toolbarbutton-disabled} class
 * and from the DOM {@code disabled} attribute, replacing the ZK 3.6
 * {@code z-toolbar-button-disd} class and {@code z.disd} attribute.
 */
public final class ModernWindowExtraction {

	private ModernWindowExtraction() {
	}

	/**
	 * The subset of frozen legacy semantic facts the modern slice is required to
	 * reproduce, loaded from the reviewed contract rather than hard-coded.
	 *
	 * <p>The legacy contract also freezes four {@code filter-*} facts. Those
	 * describe Tomcat 9 contexts (/adempiere/, /mobile/, /webui/, /wstore/) that
	 * the Phase 5d modern runtime deliberately does not deploy, so asserting
	 * them here would be asserting something the slice never claimed. Which
	 * facts are comparable is a reviewed decision, so it lives in the contract
	 * tree and is covered by the contract manifest.
	 */
	public static Set<String> comparableFactNames() throws IOException {
		Set<String> names = new TreeSet<>();
		for (String line : BrowserSemanticContract
				.contractLines("/modern-comparable-facts.tsv")) {
			String[] fields = line.split("\\t", -1);
			if (fields.length < 2) {
				throw new IOException("Malformed comparable-fact row: " + line);
			}
			if ("compare".equals(fields[1])) {
				names.add(fields[0]);
			}
		}
		if (names.isEmpty()) {
			throw new IOException(
					"modern-comparable-facts.tsv declares no comparable fact");
		}
		return names;
	}

	/** Restricts a fact map to the reviewed comparable set. */
	public static Map<String, String> comparable(Map<String, String> facts)
			throws IOException {
		Set<String> names = comparableFactNames();
		Map<String, String> filtered = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, String> entry : facts.entrySet()) {
			if (names.contains(entry.getKey())) {
				filtered.put(entry.getKey(), entry.getValue());
			}
		}
		List<String> missing = names.stream()
				.filter(name -> !filtered.containsKey(name))
				.toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
					"The capture produced no value for comparable facts " + missing);
		}
		return filtered;
	}

	/**
	 * Approved URL volatility for the modern context.
	 *
	 * <p>Identical in shape to
	 * {@link BrowserSemanticContract#normalizedUrl(String, String)}, but anchored
	 * on {@code /webui-modern/} instead of {@code /webui/}. The container session
	 * id, the ZK desktop id, and the per-desktop component counter are the only
	 * rewritten values.
	 */
	public static String normalizedUrl(String baseUrl, String value) {
		return value.replace(baseUrl, "")
				.replaceAll(";jsessionid=[A-Fa-f0-9]+", ";jsessionid=<SESSION>")
				.replaceAll("(/webui-modern/zkau/view/)[^/]+/(zk_comp_)\\d+",
						"$1<DTID>/$2<COMPONENT>")
				.replaceAll("(/webui-modern/zkau/web/)[0-9a-f]+/", "$1<ZKBUILD>/")
				.replaceAll("([?&]dtid=)[^&]+", "$1<DTID>");
	}

	/**
	 * Reviewed route classes for the modern slice, loaded from the contract.
	 *
	 * <p>The frozen legacy {@code network-classes.tsv} describes four Tomcat 9
	 * contexts the modern runtime does not deploy, so the modern route set is
	 * frozen separately rather than compared against it.
	 */
	public static Set<String> expectedRouteClasses() throws IOException {
		Set<String> classes = new TreeSet<>();
		for (String line : BrowserSemanticContract
				.contractLines("/modern-route-classes.tsv")) {
			String[] fields = line.split("\\t", -1);
			if (fields.length < 4) {
				throw new IOException("Malformed modern route row: " + line);
			}
			String disposition = fields[fields.length - 2];
			if (!"expected".equals(disposition) && !"inherited".equals(disposition)) {
				continue;
			}
			// The class key is everything before the disposition column, so a
			// two-field class such as "zkau\tGET" and a three-field one such as
			// "context\tGET\t/webui-modern/" are both preserved verbatim.
			classes.add(String.join("\t",
					java.util.Arrays.copyOfRange(fields, 0, fields.length - 2)));
		}
		if (classes.isEmpty()) {
			throw new IOException("modern-route-classes.tsv declares no route class");
		}
		return classes;
	}

	/** Route classes the Phase 5d slice must have removed. */
	public static Set<String> removedExternalHosts() {
		return new TreeSet<>(java.util.List.of(
				"fonts.googleapis.com", "www.adempiere.com"));
	}

	/**
	 * Classifies one normalized request into a stable route class.
	 *
	 * <p>The modern slice serves four route classes and nothing else. A request
	 * that matches none of them is reported verbatim so an unexpected route
	 * cannot be absorbed into a catch-all bucket.
	 */
	public static String routeClass(String method, String url) {
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return "external\t" + method + "\t"
					+ java.net.URI.create(url).getHost();
		}
		if (url.startsWith("/webui-modern/zkau/web/")) {
			return "zk-resource\t" + method;
		}
		if (url.startsWith("/webui-modern/zkau")) {
			return "zkau\t" + method;
		}
		if (url.startsWith("/webui-modern/")) {
			return "context\t" + method + "\t/webui-modern/";
		}
		return "unclassified\t" + method + "\t" + url;
	}

	/**
	 * The ZK CE 10 browser-side extraction. Structurally identical to
	 * {@link ErrorMessageWindowFacts#BROWSER_EXTRACTION_SCRIPT} and returns the
	 * same payload shape, so
	 * {@link ErrorMessageWindowFacts#fromEvaluation(Object, int)} and
	 * {@link ErrorMessageWindowFacts#derive} are shared unchanged between the two
	 * renderings.
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
			  const pattern = /^unqField_\\d+_\\d+_AD_Error_([A-Za-z_]+?)\\d*$/;
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
			        "[title='" + title + "'].toolbar-button, "
			        + "a.toolbar-button[title='" + title + "'], "
			        + "[title='" + title + "']");
			    if (control === null) {
			      return;
			    }
			    const classes = control.getAttribute('class') || '';
			    result.controls[title] =
			        classes.indexOf('-disabled') >= 0
			        || classes.indexOf('-disd') >= 0
			        || control.hasAttribute('disabled')
			        || control.getAttribute('aria-disabled') === 'true';
			  });
			  return result;
			}
			""";
}
