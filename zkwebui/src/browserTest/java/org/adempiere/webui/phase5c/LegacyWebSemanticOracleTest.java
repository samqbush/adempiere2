package org.adempiere.webui.phase5c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.adempiere.webui.phase5d.BrowserSemanticContract;
import org.adempiere.webui.phase5legacy.LegacyBrowserFlow;
import org.adempiere.webui.phase5legacy.LegacyDatabaseScripts;
import org.adempiere.webui.phase5d.ErrorMessageWindowFacts;
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

@Tag("IntegrationTest")
class LegacyWebSemanticOracleTest {

	private static final LegacyDatabaseScripts SCRIPTS =
			new LegacyDatabaseScripts("phase5c.browser.");

	private final String baseUrl = requiredProperty("phase5c.browser.baseUrl")
			.replaceFirst("/+$", "");
	private final String user = requiredProperty("phase5c.browser.user");
	private final String password = requiredProperty("phase5c.browser.password");
	private final Path evidenceDir =
			Path.of(requiredProperty("phase5c.browser.evidenceDir"));

	@Test
	void replaysLegacySemanticContract() throws IOException {
		Files.createDirectories(evidenceDir);
		Path fixture = evidenceDir.resolve("fixture.tsv");

		// First login is not idempotent: it creates the oracle user's
		// AD_Preference rows and AD_Tree_Favorite node and change-logs both, and
		// scripts/phase5/reset-oracle-fixture.sh verify branches on exactly that.
		// Running captures A and B straight onto a freshly restored seed would
		// therefore check a FIRST capture and a REPEAT capture against different
		// rules and pass for the wrong reason. Prime a cold database instead of
		// assuming a warm one, so both captures sit at the same ordinal. This
		// mirrors scripts/phase5/replay-legacy-web-oracle.sh, which hit the same
		// trap on the wire oracle.
		if ("cold".equals(fixtureState())) {
			Path prime = evidenceDir.resolve("prime.tsv");
			runFixture("snapshot", prime);
			replay(evidenceDir.resolve("prime"), false);
			runFixture("reset", prime);
		}

		runFixture("snapshot", fixture);
		// Reset before capture A as well as between A and B. Capture A is not
		// entitled to inherit the AD_RecentItem rows a priming or previous
		// capture left behind: opening a window records a recent item and the
		// desktop renders that list, so without this the two captures would not
		// start from the same fixture.
		runFixture("reset", fixture);

		Replay first = replay(evidenceDir.resolve("A"), true);
		runFixture("verify", fixture);
		runFixture("reset", fixture);

		Replay second = replay(evidenceDir.resolve("B"), true);
		runFixture("verify", fixture);
		runFixture("reset", fixture);

		assertEquals(first.facts(), second.facts(),
				"Semantic facts diverged between isolated captures");
		assertEquals(requestClasses(first.requests()),
				requestClasses(second.requests()),
				"Network request classes diverged between isolated captures");
		assertEquals(stableErrors(first.errors()), stableErrors(second.errors()),
				"Stable browser error classes diverged between isolated captures");
	}

	@Test
	void contractNormalizerRejectsMeaningfulMutations() throws IOException {
		Map<String, String> expectedFacts = expectedFacts();
		Map<String, String> changedFacts = new LinkedHashMap<>(expectedFacts);
		changedFacts.put("desktop-user", "DifferentUser@GardenWorld");
		assertNotEquals(expectedFacts, changedFacts,
				"A changed semantic name was normalized away");

		Set<String> expectedNetwork = expectedNetworkClasses();
		Set<String> changedNetwork = new TreeSet<>(expectedNetwork);
		changedNetwork.remove("context\tGET\t/wstore/");
		changedNetwork.add("context\tGET\t/replacement/");
		assertNotEquals(expectedNetwork, changedNetwork,
				"A changed navigation class was normalized away");

		Set<String> changedErrors = new TreeSet<>(allowedErrors());
		changedErrors.add("page\t/webui/\tReferenceError: mutation");
		changedErrors.removeAll(allowedErrors());
		assertFalse(changedErrors.isEmpty(),
				"A new browser error class was normalized away");

		assertEquals("Garden Admin", normalizedText(" Garden\u00a0  Admin\n"),
				"Approved whitespace volatility was not normalized");
	}

	/**
	 * @param captureDir where this capture's evidence is written
	 * @param strict whether the capture is compared against the frozen contract.
	 *        A priming capture on a cold database is a fixture operation, not a
	 *        measurement; treating it as one would compare a first login against
	 *        the frozen repeat-login oracle.
	 */
	private Replay replay(Path captureDir, boolean strict) throws IOException {
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
				BrowserContext context = LegacyBrowserFlow.newContext(browser)) {
			LegacyBrowserFlow.blockForeignOrigins(context, baseUrl);
			Page page = context.newPage();
			LegacyBrowserFlow.recordTraffic(page, requests, errors, this::normalizedUrl);

			Response login = LegacyBrowserFlow.login(page, baseUrl, user, password);
			assertEquals(200, login.status());

			String roleText = LegacyBrowserFlow.awaitRolePanel(
					page, LegacyWebSemanticOracleTest::normalizedText);
			assertTrue(roleText.contains("Role"));
			assertTrue(roleText.contains("Client"));
			assertTrue(roleText.contains("Organization"));
			assertTrue(roleText.contains("Warehouse"));
			facts.put("role-labels-visible", "true");
			LegacyBrowserFlow.confirmRole(page);

			LegacyBrowserFlow.awaitDesktop(page, user, "GardenWorld");
			facts.put("desktop-user",
					normalizedText(page.getByText(user + "@GardenWorld",
							new Page.GetByTextOptions().setExact(false)).first().innerText()));
			facts.put("menu-user-browser",
					Boolean.toString(page.getByText("User Browser",
							new Page.GetByTextOptions().setExact(true)).count() > 0));

			facts.putAll(openErrorMessageWindow(page, captureDir));

			LegacyBrowserFlow.logout(page);
			facts.put("logout-login-visible", "true");

			assertContext(page, facts, "/adempiere/", "filter-adempiere");
			assertContext(page, facts, "/mobile/", "filter-mobile");
			assertContext(page, facts, "/webui/", "filter-webui");
			assertContext(page, facts, "/wstore/", "filter-wstore");
		}

		// The zero-write proof is taken after the browser closes, so a write
		// still in flight cannot be missed, and before the fixture is reset, so
		// the reset cannot hide one.
		runEffect(effectLog, "counts", countsAfter.toString());
		String comparison = runEffect(effectLog, "compare",
				countsBefore.toString(), countsAfter.toString());
		facts.put(ErrorMessageWindowFacts.FACT_DATABASE_WRITES,
				measuredDelta(comparison));

		Files.write(captureDir.resolve("semantic-facts.tsv"),
				facts.entrySet().stream()
						.map(entry -> entry.getKey() + "\t" + entry.getValue())
						.toList(),
				StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("network-requests.tsv"),
				requests, StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("browser-errors.tsv"),
				errors, StandardCharsets.UTF_8);

		if (!strict) {
			return new Replay(facts, requests, errors);
		}

		assertEquals(expectedFacts(), facts);
		assertTrue(requests.stream().anyMatch(line -> line.contains("/webui/zkau")),
				"Browser flow never reached the ZK AU route");
		assertTrue(requests.stream().anyMatch(
				line -> line.contains("sfx-images.mozilla.org")),
				"Browser flow never attempted the inherited Firefox image request");
		assertTrue(requests.stream().anyMatch(
				line -> line.contains("google.com/calendar")),
				"Browser flow never attempted the inherited calendar request");
		assertEquals(expectedNetworkClasses(), requestClasses(requests),
				"Network request classes changed");

		Set<String> unexpected = new TreeSet<>(errors);
		unexpected.removeAll(allowedErrors());
		assertTrue(unexpected.isEmpty(),
				"Unexpected browser error classes: " + unexpected);
		return new Replay(facts, requests, errors);
	}

	/**
	 * Opens the exact "Error Message" menu item and observes the window.
	 *
	 * <p>The menu tree renders every node, but a collapsed branch has no box, so
	 * clicking the row directly is not an action a browser can perform.
	 * ADempiere's own menu lookup is used instead: it resolves the typed value
	 * against an exact node-name map, opens the ancestor path, selects the item,
	 * and posts the same {@code ON_CLICK} on the {@code Treerow} that
	 * {@code MenuPanel.java:161,180} registers
	 * ({@code TreeSearchPanel.java:215-252}). That is the event the frozen wire
	 * oracle records as {@code onClick(menu-row:Error_Message)}, reached the way
	 * a user reaches it.
	 */
	private Map<String, String> openErrorMessageWindow(Page page, Path captureDir)
			throws IOException {
		Locator lookup = page.locator(
				"xpath=//span[@title='Enter text to search for in tree']"
						+ "/ancestor::div[1]/following-sibling::span"
						+ "[contains(@class,'z-combobox')]"
						+ "//input[contains(@class,'z-combobox-inp')]");
		lookup.waitFor();
		// The value must be typed, not filled. ZK 3.6 drives the lookup from key
		// events; a programmatic value assignment produces no onChanging and the
		// menu lookup never resolves the node.
		lookup.click();
		lookup.pressSequentially(ErrorMessageWindowFacts.WINDOW_LABEL,
				new Locator.PressSequentiallyOptions().setDelay(40));
		// The suggestion carries the menu node's exact name in ZK's own
		// z.label attribute, so waiting for it proves the lookup resolved the
		// exact "Error Message" item rather than a prefix of something else.
		Locator suggestion = page.locator("tr.z-combo-item[z\\.label=\""
				+ ErrorMessageWindowFacts.WINDOW_LABEL + "\"]");
		suggestion.first().waitFor();
		assertEquals(ErrorMessageWindowFacts.WINDOW_LABEL, lookup.inputValue(),
				"The menu lookup does not hold the exact window name");
		// Waiting for the AU round trip that carries the selection is a
		// post-condition, not a sleep: if the key press produced no onChange the
		// step fails here, naming the cause, instead of timing out later on a
		// tab that was never going to appear.
		page.waitForResponse(
				response -> response.request().url().contains("/zkau")
						&& response.request().postData() != null
						&& response.request().postData().contains("=onChange&")
						&& response.request().postData().contains("Error%20Message"),
				() -> lookup.press("Enter"));

		// :text-is is an exact, whitespace-normalized match. A Java Pattern is
		// NOT usable here: Playwright hands the pattern source to a JavaScript
		// RegExp, which does not implement \Q...\E, so a quoted Java pattern
		// silently matches nothing and the step times out for the wrong reason.
		page.locator("span.z-tab-text:text-is('"
				+ ErrorMessageWindowFacts.WINDOW_LABEL + "')").first().waitFor();
		page.locator("div.desktop-tabpanel")
				.filter(new Locator.FilterOptions()
						.setHas(page.locator("[id*='_AD_Error_']")))
				.first()
				.waitFor();
		// The toolbar is built after the tab panel is attached. Waiting for the
		// destructive control the read-only claim depends on keeps the
		// observation deterministic instead of timing-dependent.
		page.locator("div.desktop-tabpanel a.toolbar-button[title='Delete record']")
				.first()
				.waitFor();

		Object raw = page.evaluate(ErrorMessageWindowFacts.BROWSER_EXTRACTION_SCRIPT);
		Files.writeString(captureDir.resolve("window-observation.txt"),
				raw + System.lineSeparator(), StandardCharsets.UTF_8);

		// The zero-write delta is measured against the database after the browser
		// closes, so it is contributed there and stripped here. Deriving it in
		// two places is how the two would eventually disagree.
		Map<String, String> derived = new LinkedHashMap<>(
				ErrorMessageWindowFacts.derive(
						ErrorMessageWindowFacts.fromEvaluation(raw, 0)));
		derived.remove(ErrorMessageWindowFacts.FACT_DATABASE_WRITES);
		return derived;
	}

	private void assertContext(
			Page page,
			Map<String, String> facts,
			String path,
			String factName) {
		Response response = page.navigate(baseUrl + path,
				new Page.NavigateOptions()
						.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		assertEquals(200, response.status(), path);
		facts.put(factName, "200 context-reachability-only");
	}

	private static Map<String, String> expectedFacts() throws IOException {
		return BrowserSemanticContract.facts();
	}

	private static Set<String> allowedErrors() throws IOException {
		return BrowserSemanticContract.allowedErrors();
	}

	private static Set<String> stableErrors(List<String> errors) {
		Set<String> stable = new TreeSet<>();
		for (String error : errors) {
			if (error.startsWith("http\t")) {
				stable.add(error);
			}
		}
		return stable;
	}

	private static Set<String> expectedNetworkClasses() throws IOException {
		return BrowserSemanticContract.networkClasses();
	}

	private static Set<String> requestClasses(List<String> requests) {
		Set<String> classes = new TreeSet<>();
		for (String request : requests) {
			String[] fields = request.split("\\t", 2);
			String method = fields[0];
			String url = fields[1];
			if (url.startsWith("http://") || url.startsWith("https://")) {
				classes.add("external\t" + method + "\t"
						+ URI.create(url).getHost());
			} else if (url.startsWith("/webui/zkau")) {
				classes.add("zkau\t" + method);
			} else {
				for (String context : List.of(
						"/adempiere/", "/mobile/", "/webui/", "/wstore/")) {
					if (url.startsWith(context)) {
						classes.add("context\t" + method + "\t" + context);
					}
				}
			}
		}
		return classes;
	}

	private static String normalizedText(String value) {
		return BrowserSemanticContract.normalizedText(value);
	}

	private String normalizedUrl(String value) {
		return BrowserSemanticContract.normalizedUrl(baseUrl, value);
	}

	private static String measuredDelta(String comparison) {
		for (String line : comparison.split("\\R")) {
			if (line.startsWith("window-readonly-delta=")) {
				return line.substring("window-readonly-delta=".length()).trim();
			}
		}
		throw new IllegalStateException(
				"The read-only effect comparison reported no measured delta:\n" + comparison);
	}

	private void runFixture(String operation, Path fixture) {
		runScript(requiredProperty("phase5c.browser.fixtureScript"),
				"Fixture " + operation,
				operation, fixture.toString());
	}

	private String fixtureState() {
		return runScript(requiredProperty("phase5c.browser.fixtureScript"),
				"Fixture state", "state").trim();
	}

	private String runEffect(Path log, String... arguments) throws IOException {
		String output = runScript(requiredProperty("phase5c.browser.effectScript"),
				"Read-only effect " + arguments[0], arguments);
		Files.writeString(log, output, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return output;
	}

	/**
	 * Runs a marker-guarded database script with the reviewed connection
	 * parameters and returns its combined output, which is also echoed so a CI
	 * failure is readable without re-running the whole lane.
	 */
	private String runScript(String script, String label, String... arguments) {
		return SCRIPTS.run(script, label, arguments);
	}

	private record Replay(
			Map<String, String> facts,
			List<String> requests,
			List<String> errors) {
	}

	private static String requiredProperty(String name) {
		return SCRIPTS.property(name.substring("phase5c.browser.".length()));
	}

}
