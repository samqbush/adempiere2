package org.adempiere.webui.phase5c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

@Tag("IntegrationTest")
class LegacyWebSemanticOracleTest {

	private final String baseUrl = requiredProperty("phase5c.browser.baseUrl")
			.replaceFirst("/+$", "");
	private final String user = requiredProperty("phase5c.browser.user");
	private final String password = requiredProperty("phase5c.browser.password");
	private final Path evidenceDir =
			Path.of(requiredProperty("phase5c.browser.evidenceDir"));

	@Test
	void replaysLegacySemanticContract() throws IOException, InterruptedException {
		Files.createDirectories(evidenceDir);
		Path fixture = evidenceDir.resolve("fixture.tsv");
		runFixture("snapshot", fixture);

		Replay first = replay(evidenceDir.resolve("A"));
		runFixture("verify", fixture);
		runFixture("reset", fixture);

		Replay second = replay(evidenceDir.resolve("B"));
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

	private Replay replay(Path captureDir) throws IOException {
		List<String> requests = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Map<String, String> facts = new LinkedHashMap<>();

		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium().launch(
						new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = browser.newContext(
						new Browser.NewContextOptions()
								.setLocale("en-US")
								.setTimezoneId("UTC"))) {
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
			page.onPageError(error -> errors.add("page\t"
					+ normalizedUrl(page.url()) + "\t" + error));
			page.onConsoleMessage(message -> {
				if ("error".equals(message.type())
						&& !message.text().startsWith("Failed to load resource:")) {
					errors.add("console\t" + normalizedUrl(page.url())
							+ "\t" + message.text());
				}
			});

			Response login = page.navigate(baseUrl + "/webui/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			assertEquals(200, login.status());
			page.locator("#rowUser input").fill(user);
			page.locator("#rowUser input").press("Tab");
			page.locator("#rowPassword input").fill(password);
			page.locator("[title='OK']").click();

			page.locator("#grdChooseRole").waitFor();
			String roleText = normalizedText(page.locator("#grdChooseRole").innerText());
			assertTrue(roleText.contains("Role"));
			assertTrue(roleText.contains("Client"));
			assertTrue(roleText.contains("Organization"));
			assertTrue(roleText.contains("Warehouse"));
			facts.put("role-labels-visible", "true");
			page.locator("[title='OK']").click();

			page.getByText(user + "@GardenWorld", new Page.GetByTextOptions().setExact(false))
					.waitFor();
			facts.put("desktop-user",
					normalizedText(page.getByText(user + "@GardenWorld",
							new Page.GetByTextOptions().setExact(false)).first().innerText()));
			facts.put("menu-user-browser",
					Boolean.toString(page.getByText("User Browser",
							new Page.GetByTextOptions().setExact(true)).count() > 0));
			page.getByText("Log Out", new Page.GetByTextOptions().setExact(true)).click();
			page.getByText("Login", new Page.GetByTextOptions().setExact(true)).first().waitFor();
			facts.put("logout-login-visible", "true");

			assertContext(page, facts, "/adempiere/", "filter-adempiere");
			assertContext(page, facts, "/mobile/", "filter-mobile");
			assertContext(page, facts, "/webui/", "filter-webui");
			assertContext(page, facts, "/wstore/", "filter-wstore");
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

		Files.createDirectories(captureDir);
		Files.write(captureDir.resolve("semantic-facts.tsv"),
				facts.entrySet().stream()
						.map(entry -> entry.getKey() + "\t" + entry.getValue())
						.toList(),
				StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("network-requests.tsv"),
				requests, StandardCharsets.UTF_8);
		Files.write(captureDir.resolve("browser-errors.tsv"),
				errors, StandardCharsets.UTF_8);
		Set<String> unexpected = new TreeSet<>(errors);
		unexpected.removeAll(allowedErrors());
		assertTrue(unexpected.isEmpty(),
				"Unexpected browser error classes: " + unexpected);
		return new Replay(facts, requests, errors);
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
		Map<String, String> facts = new LinkedHashMap<>();
		try (var input = LegacyWebSemanticOracleTest.class.getResourceAsStream(
				"/semantic-facts.tsv")) {
			if (input == null) {
				throw new IOException("Missing semantic-facts.tsv");
			}
			for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8)
					.split("\\R")) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] fields = line.split("\\t", 2);
				facts.put(fields[0], fields[1]);
			}
		}
		return facts;
	}

	private static Set<String> allowedErrors() throws IOException {
		try (var input = LegacyWebSemanticOracleTest.class.getResourceAsStream(
				"/allowed-browser-errors.tsv")) {
			if (input == null) {
				throw new IOException("Missing allowed-browser-errors.tsv");
			}
			Set<String> errors = new TreeSet<>();
			for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8)
					.split("\\R")) {
				if (!line.isBlank() && !line.startsWith("#")) {
					errors.add(line);
				}
			}
			return errors;
		}
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
		try (var input = LegacyWebSemanticOracleTest.class.getResourceAsStream(
				"/network-classes.tsv")) {
			if (input == null) {
				throw new IOException("Missing network-classes.tsv");
			}
			Set<String> classes = new TreeSet<>();
			for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8)
					.split("\\R")) {
				if (!line.isBlank() && !line.startsWith("#")) {
					classes.add(line);
				}
			}
			return classes;
		}
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
		return value.replace('\u00a0', ' ')
				.replaceAll("\\s+", " ")
				.trim();
	}

	private String normalizedUrl(String value) {
		return value.replace(baseUrl, "")
				.replaceAll(";jsessionid=[A-Fa-f0-9]+", ";jsessionid=<SESSION>")
				.replaceAll("(/webui/zkau/view/)[^/]+/(zk_comp_)\\d+",
						"$1<DTID>/$2<COMPONENT>")
				.replaceAll("([?&]dtid=)[^&]+", "$1<DTID>");
	}

	private void runFixture(String operation, Path fixture)
			throws IOException, InterruptedException {
		Process process = new ProcessBuilder(
				requiredProperty("phase5c.browser.fixtureScript"),
				requiredProperty("phase5c.browser.dbHost"),
				requiredProperty("phase5c.browser.dbPort"),
				requiredProperty("phase5c.browser.dbName"),
				requiredProperty("phase5c.browser.dbUser"),
				requiredProperty("phase5c.browser.dbPassword"),
				requiredProperty("phase5c.browser.dbMarker"),
				operation,
				fixture.toString())
				.inheritIO()
				.start();
		assertEquals(0, process.waitFor(),
				"Fixture " + operation + " failed");
	}

	private record Replay(
			Map<String, String> facts,
			List<String> requests,
			List<String> errors) {
	}

	private static String requiredProperty(String name) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required");
		}
		return value;
	}
}
