package org.adempiere.webui.phase5d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * Phase 5d Testability Milestone: the modern ZK CE 10.3.0.1-jakarta slice passes
 * login, role selection, menu, and the read-only "Error Message" window against
 * the same marker-owned database the frozen legacy oracle uses, and its semantic
 * facts and database effects match the frozen legacy baseline at matching
 * capture ordinals.
 *
 * <p>Three properties this test deliberately does <em>not</em> take shortcuts on:
 *
 * <ol>
 *   <li><b>Matching capture ordinals.</b> First login is not idempotent: it
 *       creates AD_Preference, AD_Tree_Favorite and AD_ChangeLog rows, and every
 *       opened window records an AD_RecentItem the desktop then renders. A cold
 *       database is primed and the fixture is reset before capture A as well as
 *       between A and B, so both modern captures sit at the same ordinal as the
 *       frozen legacy repeat-login baseline. This is the same trap
 *       scripts/phase5/replay-legacy-web-oracle.sh and the ZK 3.6 browser oracle
 *       already hit.</li>
 *   <li><b>Ordinary credentials only.</b> The capture logs in through the
 *       ordinary login form with the ordinary GardenAdmin credentials. No auth
 *       bypass, no seeded session, no second cookie, no copied desktop state.</li>
 *   <li><b>Coexistence measured while authenticated.</b> The complete Phase 4
 *       SOAP contract gate runs while this test is holding an authenticated ZK
 *       session open in the shared Tomcat 10 JVM. Running it before or after
 *       would prove nothing about coexistence.</li>
 * </ol>
 */
@Tag("IntegrationTest")
class ModernWebSemanticSliceTest {

	private final String baseUrl = requiredProperty("phase5d.modern.baseUrl")
			.replaceFirst("/+$", "");
	private final String contextPath =
			requiredProperty("phase5d.modern.contextPath");
	private final String user = requiredProperty("phase5d.modern.user");
	private final String password = requiredProperty("phase5d.modern.password");
	private final Path evidenceDir =
			Path.of(requiredProperty("phase5d.modern.evidenceDir"));
	private final String legacyPort =
			requiredProperty("phase5d.modern.legacyPort");
	/**
	 * The password the Phase 4 POS fixture rewrites GardenAdmin to
	 * ({@code scripts/phase4/prepare-operation-scenarios.sh:55-57}). Only the
	 * coexistence session uses it, because only that session runs after the
	 * fixture is applied.
	 */
	private final String coexistencePassword =
			requiredProperty("phase5d.modern.coexistencePassword");

	/**
	 * The ADempiere-owned style class on the menu lookup panel
	 * ({@code TreeSearchPanel.SEARCH_PANEL_SCLASS}). It is repeated here rather
	 * than imported because this source set does not compile against the web UI
	 * closure; {@link ModernSliceContractTest} pins the two together.
	 */
	static final String TREE_SEARCH_SCLASS = "adempiere-tree-search";

	@Test
	void modernSliceReproducesTheFrozenLegacySemanticContract() throws IOException {
		Files.createDirectories(evidenceDir);
		Path fixture = evidenceDir.resolve("fixture.tsv");

		if ("cold".equals(fixtureState())) {
			Path prime = evidenceDir.resolve("prime.tsv");
			runFixture("snapshot", prime);
			replay(evidenceDir.resolve("prime"), Mode.PRIME);
			runFixture("reset", prime);
		}

		runFixture("snapshot", fixture);
		runFixture("reset", fixture);

		Replay first = replay(evidenceDir.resolve("A"), Mode.MEASURED);
		runFixture("verify", fixture);
		runFixture("reset", fixture);

		Replay second = replay(evidenceDir.resolve("B"), Mode.MEASURED);
		runFixture("verify", fixture);
		runFixture("reset", fixture);

		// Self-diff: two fixture-isolated modern captures must be identical.
		assertEquals(first.facts(), second.facts(),
				"Semantic facts diverged between isolated modern captures");
		assertEquals(first.routeClasses(), second.routeClasses(),
				"Modern route classes diverged between isolated modern captures");
		assertEquals(stableErrors(first.errors()), stableErrors(second.errors()),
				"Stable browser error classes diverged between isolated modern captures");

		// Cross-rendering comparison against the frozen legacy baseline.
		Map<String, String> expected = ModernWindowExtraction.comparable(
				BrowserSemanticContract.facts());
		assertEquals(expected, ModernWindowExtraction.comparable(first.facts()),
				"Modern capture A does not reproduce the frozen legacy semantic facts");
		assertEquals(expected, ModernWindowExtraction.comparable(second.facts()),
				"Modern capture B does not reproduce the frozen legacy semantic facts");

		Files.write(evidenceDir.resolve("modern-vs-legacy.tsv"),
				expected.entrySet().stream()
						.map(entry -> entry.getKey() + "\t" + entry.getValue()
								+ "\tmatched")
						.toList(),
				StandardCharsets.UTF_8);

		// The Phase 4 corpus needs its deterministic fixtures, and one of them
		// rewrites GardenAdmin's password (scripts/phase4/prepare-operation-scenarios.sh:55-57,
		// which the POS operation baselines depend on). Applying it BEFORE the
		// measured captures would make them log in with a credential the frozen
		// legacy oracle never used; applying it here keeps captures A and B on
		// exactly the oracle's own credential.
		runDatabaseScript(requiredProperty("phase5d.modern.scenarioScript"),
				"Prepare Phase 4 operation scenarios");

		// Coexistence runs in its own session, AFTER the two measured captures.
		//
		// The Phase 4 SOAP corpus authenticates 44 times, and every ADempiere
		// login writes an AD_Session row. contracts/legacy-web-v1/database-effects.tsv
		// allows a capture exactly one. Folding the corpus into a measured
		// capture would therefore either fail the reviewed fixture assertion or
		// force it to be loosened - and loosening the one assertion that proves
		// the flow logged in exactly once is precisely the kind of edit that
		// makes an oracle stop meaning anything. The coexistence session runs the
		// same login -> role -> menu -> window flow, so the property being tested
		// (a real authenticated modern ZK session in the shared JVM) is the same;
		// only its database effects are outside the compared set.
		Replay coexistence =
				replay(evidenceDir.resolve("coexistence"), Mode.COEXISTENCE);
		runFixture("reset", fixture);
		assertEquals(ModernWindowExtraction.comparable(first.facts()),
				ModernWindowExtraction.comparable(coexistence.facts()),
				"The modern session that hosted the Phase 4 SOAP corpus did not "
						+ "reproduce the same semantic facts");
	}

	/** What a capture is for, which decides how strictly it is judged. */
	private enum Mode {
		/**
		 * Warms a cold database. First login is not idempotent, so this is a
		 * fixture operation, not a measurement; comparing it against the frozen
		 * repeat-login oracle would compare two different ordinals.
		 */
		PRIME,
		/** A compared capture. */
		MEASURED,
		/** Hosts the Phase 4 SOAP corpus while authenticated. */
		COEXISTENCE
	}

	private Replay replay(Path captureDir, Mode mode) throws IOException {
		boolean strict = mode != Mode.PRIME;
		List<String> requests = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Map<String, String> facts = new LinkedHashMap<>();

		Files.createDirectories(captureDir);
		Path countsBefore = captureDir.resolve("database-before.tsv");
		Path countsAfter = captureDir.resolve("database-after.tsv");
		Path effectLog = captureDir.resolve("database-effect.txt");
		Files.deleteIfExists(effectLog);
		runEffect(effectLog, "counts", countsBefore.toString());

		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium().launch(
						new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = browser.newContext(
						new Browser.NewContextOptions()
								.setLocale("en-US")
								.setTimezoneId("UTC"))) {
			// The modern lane is loopback-only and must stay that way. Anything
			// that is not this base URL is aborted rather than merely recorded.
			context.route("**/*", route -> {
				if (route.request().url().startsWith(baseUrl)) {
					route.resume();
				} else {
					route.abort();
				}
			});
			Page page = context.newPage();
			page.onRequest(request -> requests.add(request.method() + "\t"
					+ normalizedUrl(request.url())));
			page.onResponse(response -> {
				if (response.status() >= 400) {
					errors.add("http\t" + response.status() + "\t"
							+ normalizedUrl(response.url()));
				}
			});
			page.onRequestFailed(request -> errors.add("network\t"
					+ request.method() + "\t" + normalizedUrl(request.url())
					+ "\t" + request.failure()));
			page.onPageError(error -> errors.add("page\t"
					+ normalizedUrl(page.url()) + "\t" + error));
			page.onConsoleMessage(message -> {
				if ("error".equals(message.type())
						&& !message.text().startsWith("Failed to load resource:")) {
					errors.add("console\t" + normalizedUrl(page.url())
							+ "\t" + message.text());
				}
			});

			try {
				// --- login -------------------------------------------------
				Response login = page.navigate(baseUrl + contextPath + "/",
						new Page.NavigateOptions()
								.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
				assertNotNull(login, "The modern context returned no response");
				assertEquals(200, login.status(),
						"The modern context did not serve the login page");
				page.locator("[id^='rowUser'] input").first().waitFor();
				page.locator("[id^='rowUser'] input").first().fill(user);
				page.locator("[id^='rowUser'] input").first().press("Tab");
				page.locator("[id^='rowPassword'] input").first()
						.fill(mode == Mode.COEXISTENCE ? coexistencePassword : password);
				okButton(page).click();

				// --- role selection ----------------------------------------
				Locator roleGrid = page.locator("[id^='grdChooseRole']");
				roleGrid.first().waitFor();
				String roleText = normalizedText(roleGrid.first().innerText());
				for (String label : List.of(
						"Role", "Client", "Organization", "Warehouse")) {
					assertTrue(roleText.contains(label),
							"The modern role grid does not render '" + label + "'");
				}
				facts.put("role-labels-visible", "true");
				okButton(page).click();

				// --- desktop and menu --------------------------------------
				page.getByText(user + "@GardenWorld",
						new Page.GetByTextOptions().setExact(false)).first().waitFor();
				facts.put("desktop-user",
						normalizedText(page.getByText(user + "@GardenWorld",
								new Page.GetByTextOptions().setExact(false))
								.first().innerText()));
				facts.put("menu-user-browser",
						Boolean.toString(page.getByText("User Browser",
								new Page.GetByTextOptions().setExact(true)).count() > 0));

				// --- read-only window --------------------------------------
				facts.putAll(openErrorMessageWindow(page, captureDir));

				// --- Phase 4 SOAP coexistence, session still authenticated ---
				if (mode == Mode.COEXISTENCE) {
					captureCoexistence(captureDir);
					// The session must survive the SOAP corpus. If the CXF WAR
					// had disturbed the shared JVM, the ZK desktop would be gone
					// and this assertion would be the first thing to notice.
					assertEquals(1, page.locator("div.desktop-tabpanel")
							.filter(new Locator.FilterOptions()
									.setHas(page.locator("[id*='_AD_Error_']")))
							.count(),
							"The modern ZK desktop did not survive the Phase 4 SOAP corpus");
				}

				// --- logout ------------------------------------------------
				page.getByText("Log Out",
						new Page.GetByTextOptions().setExact(true)).first().click();
				page.locator("[id^='rowUser'] input").first().waitFor();
				facts.put("logout-login-visible", "true");
				// Logout can close the ZK desktop while its final AU request is
				// still completing. Remove interception while the context is
				// alive so BrowserContext.close() does not race that request and
				// replace a successful capture with TargetClosedError.
				context.unroute("**/*");
			} catch (RuntimeException | AssertionError failure) {
				Files.writeString(captureDir.resolve("page-on-failure.html"),
						page.content(), StandardCharsets.UTF_8);
				// Geometry, not just markup. Every layout defect this capture has
				// found so far - the clipped menu lookup, the collapsed header -
				// presented as "element is visible, enabled and stable" followed
				// by "intercepts pointer events", which is unreadable without the
				// boxes.
				try {
					Files.writeString(captureDir.resolve("geometry-on-failure.json"),
							String.valueOf(page.evaluate(GEOMETRY_SCRIPT)),
							StandardCharsets.UTF_8);
				} catch (RuntimeException ignored) {
					// A page that cannot be evaluated has already been captured
					// as markup above; losing the geometry must not replace the
					// real failure.
				}
				Files.write(captureDir.resolve("requests-on-failure.tsv"),
						requests, StandardCharsets.UTF_8);
				Files.write(captureDir.resolve("errors-on-failure.tsv"),
						errors, StandardCharsets.UTF_8);
				throw failure;
			}
		}

		runEffect(effectLog, "counts", countsAfter.toString());
		String comparison = runEffect(effectLog, "compare",
				countsBefore.toString(), countsAfter.toString());
		facts.put(ErrorMessageWindowFacts.FACT_DATABASE_WRITES,
				measuredDelta(comparison));

		Set<String> routeClasses = routeClasses(requests);
		Files.write(captureDir.resolve("semantic-facts.tsv"),
				facts.entrySet().stream()
						.map(entry -> entry.getKey() + "\t" + entry.getValue())
						.toList(),
				StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("network-requests.tsv"),
				requests, StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("route-classes.tsv"),
				new ArrayList<>(routeClasses), StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("browser-errors.tsv"),
				errors, StandardCharsets.UTF_8);

		if (!strict) {
			return new Replay(facts, requests, routeClasses, errors);
		}

		// Route-class assertions against the reviewed modern contract. The
		// contract, not the test, decides which routes are expected, which are
		// inherited from ADempiere's own markup, and which the slice removed.
		assertTrue(routeClasses.contains("zkau\tPOST"),
				"The modern flow never reached the ZK AU route");
		assertTrue(routeClasses.contains("zk-resource\tGET"),
				"The modern flow never loaded a ZK client resource");
		assertTrue(routeClasses.contains("context\tGET\t/webui-modern/"),
				"The modern flow never requested the modern context itself");
		assertEquals(ModernWindowExtraction.expectedRouteClasses(), routeClasses,
				"The modern slice's route classes changed");
		for (String removed : ModernWindowExtraction.removedExternalHosts()) {
			assertTrue(routeClasses.stream()
							.noneMatch(name -> name.endsWith("\t" + removed)),
					"The modern slice reintroduced the outbound host " + removed);
		}

		Set<String> unexpected = new TreeSet<>(stableErrors(errors));
		assertTrue(unexpected.isEmpty(),
				"Unexpected modern browser error classes: " + unexpected);
		return new Replay(facts, requests, routeClasses, errors);
	}

	/**
	 * Opens the exact "Error Message" menu item and observes the window.
	 *
	 * <p>Reached the way a user reaches it: the value is typed into ADempiere's
	 * own menu lookup, which resolves it against an exact node-name map, opens
	 * the ancestor path and selects the item
	 * ({@code TreeSearchPanel.java:215-252}). A collapsed tree branch has no box,
	 * so clicking the tree row directly is not an action a browser can perform.
	 */
	private Map<String, String> openErrorMessageWindow(Page page, Path captureDir)
			throws IOException {
		Locator lookup = page.locator(
				"." + TREE_SEARCH_SCLASS + " input.z-combobox-input");
		if (lookup.count() == 0) {
			// Fall back to the ADempiere-owned tooltip text, which is a
			// translated AD_Message and is stable across both renderings.
			lookup = page.locator(
					"xpath=//*[@title='Enter text to search for in tree']"
							+ "/ancestor::*[self::div or self::td][1]"
							+ "/following::input[1]");
		}
		final Locator menuLookup = lookup.first();
		menuLookup.waitFor();
		menuLookup.click();
		menuLookup.pressSequentially(ErrorMessageWindowFacts.WINDOW_LABEL,
				new Locator.PressSequentiallyOptions().setDelay(40));
		assertEquals(ErrorMessageWindowFacts.WINDOW_LABEL,
				menuLookup.inputValue(),
				"The modern menu lookup does not hold the exact window name");
		// Waiting for the AU round trip that carries the selection is a
		// post-condition, not a sleep: if the key press produced no onChange the
		// step fails here, naming the cause.
		page.waitForResponse(
				response -> response.request().url().contains("/zkau")
						&& response.request().postData() != null
						&& response.request().postData().contains("onChange")
						&& response.request().postData().contains("Error"),
				() -> menuLookup.press("Enter"));

		page.locator("div.desktop-tabpanel")
				.filter(new Locator.FilterOptions()
						.setHas(page.locator("[id*='_AD_Error_']")))
				.first()
				.waitFor();
		page.locator("div.desktop-tabpanel [title='Delete record']")
				.first()
				.waitFor();

		Object raw = page.evaluate(
				ModernWindowExtraction.BROWSER_EXTRACTION_SCRIPT);
		Files.writeString(captureDir.resolve("window-observation.txt"),
				raw + System.lineSeparator(), StandardCharsets.UTF_8);

		Map<String, String> derived = new LinkedHashMap<>(
				ErrorMessageWindowFacts.derive(
						ErrorMessageWindowFacts.fromEvaluation(raw, 0)));
		derived.remove(ErrorMessageWindowFacts.FACT_DATABASE_WRITES);
		return derived;
	}

	/**
	 * Runs the complete Phase 4 SOAP contract gate and records listener, heap,
	 * classloader and ADEMPIERE_HOME evidence, all while this test holds an
	 * authenticated modern ZK session in the same Tomcat 10 JVM.
	 */
	private void captureCoexistence(Path captureDir) {
		String script = requiredProperty("phase5d.modern.coexistenceScript");
		runProcess("Coexistence runtime (before SOAP)",
				List.of(script, "runtime", captureDir.toString(),
						"before-soap"));
		runProcess("Phase 4 SOAP corpus during modern session",
				List.of(script, "soap", captureDir.toString()));
		runProcess("Coexistence runtime (after SOAP)",
				List.of(script, "runtime", captureDir.toString(), "after-soap"));
		runProcess("Lane isolation",
				List.of(script, "isolation", captureDir.toString(), legacyPort,
						requiredProperty("phase5d.modern.dbHost"),
						requiredProperty("phase5d.modern.dbPort"),
						requiredProperty("phase5d.modern.dbName"),
						requiredProperty("phase5d.modern.dbUser"),
						requiredProperty("phase5d.modern.dbMarker")));
	}

	private Locator okButton(Page page) {
		Locator byTitle = page.locator("[title='OK']");
		if (byTitle.count() > 0) {
			return byTitle.first();
		}
		return page.locator("[id^='Ok'], [id^='btnOk']").first();
	}

	private static Set<String> stableErrors(List<String> errors) throws IOException {
		Set<String> stable = new TreeSet<>();
		for (String error : errors) {
			String[] fields = error.split("\\t", 4);
			if (fields.length == 4 && "network".equals(fields[0])) {
				String routeClass =
						ModernWindowExtraction.routeClass(fields[1], fields[2]);
				if (routeClass.startsWith("external\t")
						&& ModernWindowExtraction.expectedRouteClasses()
								.contains(routeClass)) {
					continue;
				}
			}
			stable.add(error);
		}
		return stable;
	}

	private static Set<String> routeClasses(List<String> requests) {
		Set<String> classes = new TreeSet<>();
		for (String request : requests) {
			String[] fields = request.split("\\t", 2);
			classes.add(ModernWindowExtraction.routeClass(fields[0], fields[1]));
		}
		return classes;
	}

	private static String normalizedText(String value) {
		return BrowserSemanticContract.normalizedText(value);
	}

	private String normalizedUrl(String value) {
		return ModernWindowExtraction.normalizedUrl(baseUrl, value);
	}

	private static String measuredDelta(String comparison) {
		for (String line : comparison.split("\\R")) {
			if (line.startsWith("window-readonly-delta=")) {
				return line.substring("window-readonly-delta=".length()).trim();
			}
		}
		throw new IllegalStateException(
				"The read-only effect comparison reported no measured delta:\n"
						+ comparison);
	}

	private void runFixture(String operation, Path fixture) {
		runDatabaseScript(requiredProperty("phase5d.modern.fixtureScript"),
				"Fixture " + operation, operation, fixture.toString());
	}

	private String fixtureState() {
		return runDatabaseScript(requiredProperty("phase5d.modern.fixtureScript"),
				"Fixture state", "state").trim();
	}

	private String runEffect(Path log, String... arguments) throws IOException {
		String output = runDatabaseScriptWithoutPasswordArgument(
				requiredProperty("phase5d.modern.effectScript"),
				"Read-only effect " + arguments[0], arguments);
		Files.writeString(log, output, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return output;
	}

	private String runDatabaseScript(
			String script, String label, String... arguments) {
		return runDatabaseScriptWithoutPasswordArgument(script, label, arguments);
	}

	private String runDatabaseScriptWithoutPasswordArgument(
			String script, String label, String... arguments) {
		List<String> command = new ArrayList<>(List.of(
				script,
				requiredProperty("phase5d.modern.dbHost"),
				requiredProperty("phase5d.modern.dbPort"),
				requiredProperty("phase5d.modern.dbName"),
				requiredProperty("phase5d.modern.dbUser"),
				requiredProperty("phase5d.modern.dbMarker")));
		command.addAll(List.of(arguments));
		return runProcess(label, command);
	}

	private String runProcess(String label, List<String> command) {
		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			String output = new String(process.getInputStream().readAllBytes(),
					StandardCharsets.UTF_8);
			int status = process.waitFor();
			System.out.print(output);
			assertEquals(0, status, label + " failed:\n" + output);
			return output;
		} catch (IOException exception) {
			throw new IllegalStateException(label + " could not be started", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(label + " was interrupted", exception);
		}
	}

	private record Replay(
			Map<String, String> facts,
			List<String> requests,
			Set<String> routeClasses,
			List<String> errors) {
	}

	/**
	 * Records the bounding box and the element actually on top for the controls
	 * this flow depends on. A "visible but intercepts pointer events" failure is
	 * a geometry failure and cannot be diagnosed from markup alone.
	 */
	private static final String GEOMETRY_SCRIPT = """
			() => {
			  const report = function (label, element) {
			    if (!element) {
			      return {label: label, present: false};
			    }
			    const box = element.getBoundingClientRect();
			    const top = document.elementFromPoint(
			        box.left + box.width / 2, box.top + box.height / 2);
			    return {
			      label: label,
			      present: true,
			      id: element.id,
			      cls: element.getAttribute('class'),
			      box: [Math.round(box.left), Math.round(box.top),
			            Math.round(box.width), Math.round(box.height)],
			      topmost: top ? (top.id || top.getAttribute('class')) : null
			    };
			  };
			  const byText = function (text) {
			    const all = Array.prototype.slice.call(
			        document.querySelectorAll('a, span, div, button'));
			    for (let i = 0; i < all.length; i++) {
			      if ((all[i].textContent || '').trim() === text) {
			        return all[i];
			      }
			    }
			    return null;
			  };
			  return JSON.stringify([
			    report('log-out', byText('Log Out')),
			    report('change-role', byText('Change Role')),
			    report('north-body', document.querySelector('.z-north-body')),
			    report('desktop-header', document.querySelector('.desktop-header')),
			    report('tree-search',
			        document.querySelector('.adempiere-tree-search'))
			  ], null, 1);
			}
			""";

	private static String requiredProperty(String name) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required");
		}
		return value;
	}

}
