package org.adempiere.webui.phase5e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * Phase 5e: the public-origin cohort matrix.
 *
 * <p>The browser uses <b>only</b> the Tomcat 9 public origin and the
 * {@code /webui} path. Every request to any other origin - in particular the
 * loopback Tomcat 10 port the modern application actually runs on - is aborted
 * by an interception rule rather than merely recorded, so "the modern runtime
 * stays loopback-only" is enforced by the harness rather than hoped for.
 *
 * <p>Four things this capture deliberately does not take shortcuts on:
 *
 * <ol>
 *   <li><b>Every case drives a real login.</b> No case asserts a routing
 *       decision by reading configuration; each one logs in through the public
 *       form and observes which application answered. The role the login
 *       actually runs as is read back and asserted, so a fixture that
 *       allowlists a role the login never selects fails instead of passing for
 *       the wrong reason.</li>
 *   <li><b>Concurrency is interleaved, not merely parallel.</b> Two identities
 *       with different users, roles and <em>explicitly selected</em> languages
 *       are stepped through login, role selection, menu and window in lockstep
 *       across a barrier, and each interleaved capture is compared against the
 *       same identity's solo capture. A thread-local that leaked between them
 *       is observed rather than averaged away.</li>
 *   <li><b>Failure cases assert the absence of a fallback.</b> A modern session
 *       that hits a ticket failure, an unowned route or a dead backend must get
 *       an explicit status. Receiving the ZK 3.6 login form instead is the
 *       specific defect these rows exist to catch.</li>
 *   <li><b>Lifecycle ends are real server-side destructions.</b> Each of the
 *       three lifecycle rows waits for a recorded session destruction on the
 *       runtimes that must have one, anchored to a log offset taken before the
 *       action, and only then judges the caches - per runtime, on the evidence
 *       that runtime's own listener produced for this session. Clearing a
 *       cookie in the browser proves nothing about a server, and is not
 *       used.</li>
 * </ol>
 */
@Tag("IntegrationTest")
class RoutedCohortMatrixTest {

	private final String baseUrl = required("phase5e.public.baseUrl")
			.replaceFirst("/+$", "");
	private final String modernBaseUrl = required("phase5e.modern.baseUrl")
			.replaceFirst("/+$", "");
	private final String contextPath = required("phase5e.public.contextPath");
	private final Path evidenceDir = Path.of(required("phase5e.evidenceDir"));
	private final String cohortScript = required("phase5e.cohortScript");
	private final String fixtureScript = required("phase5e.fixtureScript");
	private final String laneScript = required("phase5e.laneScript");
	private final String soapFixtureScript = required("phase5e.soapFixtureScript");

	private final Identity primary = new Identity(
			required("phase5e.userA"), required("phase5e.passwordA"),
			required("phase5e.languageA"), required("phase5e.languageLabelA"),
			required("phase5e.roleA"));
	private final Identity secondary = new Identity(
			required("phase5e.userB"), required("phase5e.passwordB"),
			required("phase5e.languageB"), required("phase5e.languageLabelB"),
			required("phase5e.roleB"));

	private final Map<String, String> matrix = new LinkedHashMap<>();

	/**
	 * A login identity, as a browser sees it.
	 *
	 * @param language      the {@code AD_Language} this login must run as
	 * @param languageLabel the label ADempiere's own language list renders for
	 *                      it, which is what the login combobox is driven with
	 * @param role          the {@code AD_Role} name the login must actually
	 *                      select, asserted rather than assumed
	 */
	private record Identity(String user, String password, String language,
			String languageLabel, String role) {
	}

	/** Which application answered a public /webui request. */
	private enum Served {
		LEGACY, MODERN, REFUSED
	}

	@Test
	void publicOriginCohortMatrix() throws Exception {
		Files.createDirectories(evidenceDir);
		Path cohortSnapshot = evidenceDir.resolve("cohort-config.tsv");
		Path oracleFixture = evidenceDir.resolve("oracle-fixture.tsv");
		runCohort("snapshot", cohortSnapshot.toString());
		runFixture("snapshot", oracleFixture);

		try {
			// --- configuration cases -------------------------------------
			record ConfigurationCase(String id, String preset, Served expected) {
			}
			for (ConfigurationCase testCase : List.of(
					new ConfigurationCase("master-off", "master-off", Served.LEGACY),
					new ConfigurationCase("master-absent", "master-absent", Served.LEGACY),
					new ConfigurationCase("user-allowlisted", "user-allowlisted", Served.MODERN),
					new ConfigurationCase("role-allowlisted", "role-allowlisted", Served.MODERN),
					// The role the acting user HOLDS but does not select must not
					// route it. Without this row, role-allowlisted could pass on a
					// decision that read the user's role list.
					new ConfigurationCase("role-unselected", "role-unselected", Served.LEGACY),
					new ConfigurationCase("not-allowlisted", "not-allowlisted", Served.LEGACY),
					new ConfigurationCase("config-duplicate", "duplicate", Served.LEGACY),
					new ConfigurationCase("config-malformed", "malformed", Served.LEGACY),
					new ConfigurationCase("config-client-scoped", "client-scoped", Served.LEGACY),
					new ConfigurationCase("config-inactive-duplicate", "inactive-duplicate", Served.MODERN))) {
				runCohort("apply", testCase.preset());
				Served served = login(testCase.id(), primary);
				record(testCase.id(), served == testCase.expected(),
						testCase.id() + " was served by " + served
								+ ", expected " + testCase.expected());
				runFixture("reset", oracleFixture);
			}

			// The unreadable case revokes SELECT on AD_SysConfig and must be
			// restored whatever happens, or every later case reads a database it
			// has no permission on and the matrix reports nonsense.
			try {
				runCohort("apply", "unreadable");
				Served served = login("config-unreadable", primary);
				record("config-unreadable", served == Served.LEGACY,
						"an unreadable configuration served " + served);
			} finally {
				runCohort("readable");
				runFixture("reset", oracleFixture);
			}

			// --- handoff and routing failure cases ------------------------
			runCohort("apply", "user-allowlisted");
			record("client-supplied-internal-header",
					forgedInternalHeaderIsRejected(),
					"a browser-supplied internal header was not rejected");
			record("missing-affinity", missingAffinityFails(),
					"a request with no affinity did not fail explicitly");
			// The ticket's expiry, tamper, wrong-session and partial-identity
			// rules are NOT asserted here. A browser cannot present a ticket at
			// all - the router refuses the reserved namespace before it routes -
			// so a row that "forged" one would only re-run the row above and
			// report a reserved-header rejection under four other names. Those
			// four rules are proved against the codec itself by
			// HandoffTicketCodecTest in phase5eFinalVerification; see
			// docs/modernization/phase-5e-evidence.md, which says so rather than
			// presenting duplicates as runtime proof.
			record("bootstrap-single-use", bootstrapIsSingleUse(),
					"a second navigation re-bootstrapped the modern session");
			runFixture("reset", oracleFixture);

			// --- the cohort decision must not outlive its session ----------
			record("cohort-reentry-after-logout", cohortIsRedecidedAfterLogout(),
					"a logged-out browser stayed modern after the configuration "
							+ "stopped selecting it");
			runFixture("reset", oracleFixture);

			// --- backend outage: explicit failure, never legacy -----------
			record("backend-unavailable", backendOutageNeverFallsBack(),
					"a modern session fell back to the legacy application");
			runFixture("reset", oracleFixture);

			// --- interceptor omission must fail visibly -------------------
			record("interceptor-mutation", interceptorOmissionIsVisible(),
					"removing the ZK interceptor produced a silent all-legacy pass");
			runFixture("reset", oracleFixture);

			// --- concurrent identity isolation ----------------------------
			record("concurrent-identity-isolation", concurrentIdentitiesStayIsolated(),
					"two concurrent identities did not keep independent contexts");
			runFixture("reset", oracleFixture);

			// --- lifecycle: real server-side ends -------------------------
			record("logout-baseline", lifecycle("logout"),
					"logout did not destroy both runtimes' sessions and return "
							+ "their caches to baseline");
			runFixture("reset", oracleFixture);
			record("timeout-baseline", lifecycle("timeout"),
					"the session-inactivity timeout did not destroy both runtimes' "
							+ "sessions and return their caches to baseline");
			runFixture("reset", oracleFixture);
			record("container-destruction-baseline", lifecycle("destruction"),
					"container-side destruction did not return the caches to baseline");
			runFixture("reset", oracleFixture);

			// --- Phase 4 SOAP while routed modern sessions are open --------
			record("phase4-soap-coexistence", soapCoexistence(),
					"the Phase 4 SOAP corpus did not pass beside routed modern sessions");
			runFixture("reset", oracleFixture);

			// --- secret hygiene over the real logs and evidence -----------
			run("Secret hygiene", List.of(laneScript, "secrets", evidenceDir.toString()));
			record("secret-hygiene", true, "");
		} finally {
			runCohort("readable");
			runCohort("clear");
			runCohort("verify", cohortSnapshot.toString());
			writeMatrix();
		}

		List<String> failed = matrix.entrySet().stream()
				.filter(entry -> !"pass".equals(entry.getValue()))
				.map(Map.Entry::getKey)
				.toList();
		assertTrue(failed.isEmpty(),
				"Public-origin cohort matrix failures: " + failed);
	}

	// -----------------------------------------------------------------------
	// Cases
	// -----------------------------------------------------------------------

	/**
	 * Drives an ordinary login through the public origin and reports which
	 * application answered.
	 *
	 * <p>The two applications are told apart by an ADempiere-owned marker rather
	 * than by a header the router could accidentally add: the ZK CE 10 slice
	 * carries the Phase 5d stylesheet {@code css/phase5d-modern.css} and the ZK
	 * 3.6 product carries the DSP theme. Neither can be produced by the other.
	 */
	private Served login(String caseId, Identity identity) throws IOException {
		Path capture = evidenceDir.resolve(caseId);
		Files.createDirectories(capture);
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, identity)) {
			Page page = context.newPage();
			Response response = page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			assertNotNull(response, "The public /webui origin returned no response");
			if (response.status() >= 400) {
				Files.writeString(capture.resolve("status.txt"),
						String.valueOf(response.status()), StandardCharsets.UTF_8);
				return Served.REFUSED;
			}
			String selectedRole = signIn(page, identity);
			Files.writeString(capture.resolve("selected-role.txt"), selectedRole,
					StandardCharsets.UTF_8);
			// The cohort fixture allowlists an AD_Role_ID, and a role allowlist
			// can only be tested by a login that actually runs as that role.
			assertEquals(identity.role(), selectedRole,
					"The " + identity.user() + " login must run as "
							+ identity.role() + " for the role fixtures to mean "
							+ "anything");
			Served served = servedBy(page);
			assertSingleCookie(context, capture);
			assertNoInternalIdentifier(page, capture);
			Files.writeString(capture.resolve("served.txt"), served.name(),
					StandardCharsets.UTF_8);
			logout(page);
			return served;
		}
	}

	private BrowserContext newContext(Browser browser, Identity identity) {
		BrowserContext context = browser.newContext(new Browser.NewContextOptions()
				.setLocale(identity.language().replace('_', '-'))
				.setTimezoneId("UTC"));
		// Loopback Tomcat 10 is not reachable from this browser, by construction.
		// Restricting interception to that forbidden origin also keeps ordinary
		// public ZK polling out of Playwright's route callback during teardown.
		context.route(modernBaseUrl + "/**", route -> route.abort());
		return context;
	}

	/**
	 * Signs in and returns the {@code AD_Role} name the session actually
	 * selected.
	 *
	 * <p>The language is chosen from ADempiere's own language list before the
	 * credentials are submitted. That order matters: entering a user identifier
	 * fires {@code LoginPanel.onUserIdChange}, which resets the language from
	 * that user's {@code AD_Preference}, so a selection made earlier would be
	 * silently overwritten.
	 */
	private String signIn(Page page, Identity identity) {
		page.locator("[id^='rowUser'] input").first().waitFor();
		page.locator("[id^='rowUser'] input").first().fill(identity.user());
		page.locator("[id^='rowUser'] input").first().press("Tab");
		page.locator("[id^='rowPassword'] input").first().fill(identity.password());
		selectLanguage(page, identity);
		okButton(page).click();
		Locator roleGrid = page.locator("[id^='grdChooseRole']");
		roleGrid.first().waitFor();
		String selectedRole = selectedRole(page);
		okButton(page).click();
		page.getByText(identity.user() + "@GardenWorld",
				new Page.GetByTextOptions().setExact(false)).first().waitFor();
		return selectedRole;
	}

	/**
	 * Chooses the login language from the combobox ADempiere populates.
	 *
	 * <p>It has to be a real selection. {@code LoginPanel} listens for
	 * {@code onSelect} only, so typing a language name and pressing Enter fires
	 * {@code onChange}, changes nothing on the server, and leaves the session in
	 * the System client's default language - which is how a concurrency capture
	 * ends up comparing two sessions that are both {@code en_US} and calling the
	 * result isolation. The value is therefore taken from the drop-down list,
	 * and the ADempiere-owned label the choice produces is asserted after login
	 * rather than assumed here.
	 */
	private void selectLanguage(Page page, Identity identity) {
		// The frozen ZK 3.6 renderer preserves the row ID but replaces the
		// server-side combobox ID with a generated UUID. The modern renderer
		// preserves both, so the row and widget class are the shared contract.
		Locator combo = page.locator(
				"[id^='rowLanguage'] .z-combobox").first();
		combo.waitFor();
		combo.locator("input").first().click();
		Locator openControl = combo.locator("i, a, span.z-combobox-btn");
		if (openControl.count() > 0) {
			openControl.first().click();
		} else {
			combo.locator("input").first().press("ArrowDown");
		}
		// ZK 3.6 reparents an open popup under the document body, so it is no
		// longer a descendant of the combobox after the click.
		Locator popup = page.locator(".z-combobox-pp:visible");
		Locator item = popup.count() > 0
				? popup.first().getByText(identity.languageLabel(),
						new Locator.GetByTextOptions().setExact(true))
				: page.getByText(identity.languageLabel(),
						new Page.GetByTextOptions().setExact(true));
		item.last().click();
	}

	/** The role the role panel is about to submit, read from its own combobox. */
	private String selectedRole(Page page) {
		Locator role = page.locator(
				"[id^='rowRole'] .z-combobox input");
		if (role.count() > 0) {
			return role.first().inputValue().trim();
		}
		throw new IllegalStateException(
				"The role panel rendered no role selector, so the role the "
						+ "session runs as cannot be observed");
	}

	/**
	 * Which application rendered the desktop.
	 *
	 * <p>The ZK CE 10 slice links {@code css/phase5d-modern.css}; the frozen ZK
	 * 3.6 product links its {@code .dsp} theme. Both markers are owned by
	 * ADempiere, so neither can be faked by the router.
	 */
	private Served servedBy(Page page) {
		String content = page.content();
		boolean modern = content.contains("phase5d-modern.css");
		boolean legacy = content.contains(".dsp");
		if (modern == legacy) {
			throw new IllegalStateException(
					"The served application could not be identified: modern=" + modern
							+ " legacy=" + legacy);
		}
		return modern ? Served.MODERN : Served.LEGACY;
	}

	private void assertSingleCookie(BrowserContext context, Path capture)
			throws IOException {
		List<Cookie> cookies = context.cookies();
		Files.write(capture.resolve("cookies.tsv"),
				cookies.stream()
						.map(cookie -> cookie.name + "\t" + cookie.path + "\t"
								+ cookie.httpOnly + "\t" + cookie.sameSite)
						.toList(),
				StandardCharsets.UTF_8);
		List<Cookie> sessionCookies = cookies.stream()
				.filter(cookie -> "JSESSIONID".equals(cookie.name))
				.toList();
		assertEquals(1, sessionCookies.size(),
				"The browser must hold exactly one public session cookie, held "
						+ sessionCookies.size());
		assertTrue(sessionCookies.get(0).httpOnly,
				"The public session cookie must be HttpOnly");
		assertTrue(sessionCookies.get(0).path.startsWith(contextPath),
				"The public session cookie must be scoped to " + contextPath);
	}

	private void assertNoInternalIdentifier(Page page, Path capture)
			throws IOException {
		String url = page.url();
		String content = page.content();
		Files.writeString(capture.resolve("url.txt"), url, StandardCharsets.UTF_8);
		assertFalse(url.toLowerCase().contains("jsessionid"),
				"The browser URL carries a session identifier: " + url);
		assertFalse(content.toLowerCase().contains(";jsessionid="),
				"The rendered page carries a URL-rewritten session identifier");
		assertFalse(content.contains("X-ADempiere-Handoff"),
				"The rendered page carries an internal handoff header");
	}

	/**
	 * The header control ADempiere labels from {@code AD_Message} "Logout".
	 *
	 * <p>Located by markup rather than by the words "Log Out": the label is a
	 * translated dictionary message, so a text locator only ever finds it for a
	 * session running in the base language. That is also why it is worth
	 * capturing - it is a server-rendered, per-session, application-dictionary
	 * fact that differs between two sessions in two languages.
	 */
	private Locator logoutControl(Page page) {
		Locator anchors = page.locator("a.desktop-header-font");
		if (anchors.count() > 0) {
			return anchors.last();
		}
		return page.getByText("Log Out",
				new Page.GetByTextOptions().setExact(true)).first();
	}

	private String logoutLabel(Page page) {
		Locator logout = logoutControl(page);
		return logout.count() > 0 ? logout.first().innerText().trim() : "";
	}

	private void logout(Page page) {
		Locator logout = logoutControl(page);
		if (logout.count() > 0) {
			logout.first().click();
			page.locator("[id^='rowUser'] input").first().waitFor();
		}
	}

	/** A browser that forges an internal header must be refused, not stripped. */
	private boolean forgedInternalHeaderIsRejected() {
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			context.setExtraHTTPHeaders(Map.of(
					"X-ADempiere-Handoff-Ticket", "v1.forged.payload"));
			Page page = context.newPage();
			Response response = page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			return response != null && response.status() == 400;
		}
	}

	/**
	 * A session whose affinity was destroyed server-side must fail explicitly.
	 *
	 * <p>The lane script restarts the modern backend, which discards its
	 * sessions while the Tomcat 9 affinity still points at one. The contract is
	 * an explicit status, never the legacy login form.
	 */
	private boolean missingAffinityFails() throws IOException {
		runCohort("apply", "user-allowlisted");
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			if (servedBy(page) != Served.MODERN) {
				return false;
			}
			run("Backend restart", List.of(laneScript, "backend", "stop"));
			run("Backend restart", List.of(laneScript, "backend", "start"));
			Response response = page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			// The critical assertion is the absence of a fallback: an explicit
			// failure is correct, and the legacy login form is not.
			return response != null && response.status() >= 400;
		}
	}

	/**
	 * The bootstrap is single use, observed end to end.
	 *
	 * <p>What this row proves is narrow and stated precisely: after a session
	 * has been bootstrapped, a second navigation reuses the SAME modern session
	 * rather than acquiring a second one. That is observable without any
	 * internal access - the public session cookie keeps its value, there is
	 * still exactly one of it, and the modern desktop is still the one
	 * answering.
	 *
	 * <p>It is deliberately not called a replay test. A replay is a
	 * <em>consumed ticket presented again</em>, and a browser can never present
	 * a ticket: the router refuses the whole reserved header namespace before it
	 * routes. The replay rule itself - nonce recorded on first acceptance,
	 * second presentation refused, bounded cache, fail-closed on exhaustion - is
	 * proved directly against the codec by {@code HandoffTicketCodecTest}, and
	 * the router's own refusal to admit a second request while a bootstrap is in
	 * flight by {@code CohortRoutingFilterTest}.
	 */
	private boolean bootstrapIsSingleUse() throws IOException {
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			if (servedBy(page) != Served.MODERN) {
				return false;
			}
			String before = sessionCookieValue(context);
			page.reload(new Page.ReloadOptions()
					.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			boolean stillModern = servedBy(page) == Served.MODERN;
			String after = sessionCookieValue(context);
			long cookies = context.cookies().stream()
					.filter(cookie -> "JSESSIONID".equals(cookie.name))
					.count();
			// An unchanged identifier is the observable form of "no second
			// rotation and therefore no second ticket": the router rotates
			// exactly once, when a session is assigned to the modern cohort.
			return stillModern && cookies == 1 && before != null
					&& before.equals(after);
		}
	}

	/**
	 * A cohort decision must not outlive the session it was taken for.
	 *
	 * <p>The decision is sticky per session on purpose - a configuration change
	 * must never move a user who is already working. What must NOT happen is
	 * that it survives a logout: the same browser, logging in again after the
	 * configuration has stopped selecting it, has to be decided again and land
	 * on the legacy runtime. Before the routed logout destroyed the Tomcat 9
	 * session, it stayed modern indefinitely, and no amount of configuration
	 * could get it back.
	 */
	private boolean cohortIsRedecidedAfterLogout() throws IOException {
		runCohort("apply", "user-allowlisted");
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			if (servedBy(page) != Served.MODERN) {
				return false;
			}
			String modernCookie = sessionCookieValue(context);
			logout(page);

			// Same browser, same cookie jar, a configuration that no longer
			// selects this user.
			runCohort("apply", "not-allowlisted");
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			boolean legacy = servedBy(page) == Served.LEGACY;
			String reentryCookie = sessionCookieValue(context);
			long cookies = context.cookies().stream()
					.filter(cookie -> "JSESSIONID".equals(cookie.name))
					.count();
			Files.write(evidenceDir.resolve("cohort-reentry.tsv"),
					List.of("first-cohort\tMODERN",
							"reentry-cohort\t" + (legacy ? "LEGACY" : "MODERN"),
							"session-replaced\t"
									+ (modernCookie != null
											&& !modernCookie.equals(reentryCookie)),
							"public-cookies\t" + cookies),
					StandardCharsets.UTF_8);
			logout(page);
			// A new container session is the observable form of "the affinity and
			// the decision were both destroyed": the router invalidates the
			// Tomcat 9 session when the modern runtime reports the end.
			return legacy && cookies == 1 && modernCookie != null
					&& !modernCookie.equals(reentryCookie);
		}
	}

	/** A dead backend produces an explicit failure, never the legacy product. */
	private boolean backendOutageNeverFallsBack() throws IOException {
		runCohort("apply", "user-allowlisted");
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			if (servedBy(page) != Served.MODERN) {
				return false;
			}
			run("Backend outage", List.of(laneScript, "backend", "stop"));
			try {
				Response response = page.navigate(baseUrl + contextPath + "/",
						new Page.NavigateOptions()
								.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
				return response != null && response.status() >= 500
						&& !page.content().contains(".dsp");
			} finally {
				run("Backend recovery", List.of(laneScript, "backend", "start"));
			}
		}
	}

	/**
	 * Removing the ZK interceptor from the DEPLOYED archive must not produce a
	 * quiet all-legacy pass.
	 */
	private boolean interceptorOmissionIsVisible() throws IOException {
		run("Interceptor mutation", List.of(laneScript, "interceptor", "disable"));
		try {
			// The deployment-completeness listener refuses to start a context
			// whose zk.xml lost the interceptor, so the public origin either
			// fails to serve /webui at all or serves it with the backstop
			// firing. Both are visible; a silent legacy pass is not.
			try (Playwright playwright = Playwright.create();
					Browser browser = playwright.chromium()
							.launch(new BrowserType.LaunchOptions().setHeadless(true));
					BrowserContext context = newContext(browser, primary)) {
				Page page = context.newPage();
				Response response = page.navigate(baseUrl + contextPath + "/",
						new Page.NavigateOptions()
								.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
				if (response == null || response.status() >= 400) {
					return true;
				}
				if (page.locator("[id^='rowUser'] input").count() == 0) {
					// Tomcat can answer while the rejected context is still
					// redeploying. No application login is a visible failure,
					// not a silent legacy pass.
					return true;
				}
				signIn(page, primary);
				if (servedBy(page) == Served.MODERN) {
					// The interceptor is gone yet routing still happened, which
					// means something else is taking the decision.
					return false;
				}
				// Legacy was served: the backstop must have reported it.
				run("Backstop evidence",
						List.of(laneScript, "sessions", evidenceDir.toString(),
								"interceptor-mutation"));
				return backstopReported();
			}
		} finally {
			run("Interceptor restore", List.of(laneScript, "interceptor", "enable"));
		}
	}

	private boolean backstopReported() throws IOException {
		Path log = Path.of(required("phase5e.publicLog"));
		if (!Files.isReadable(log)) {
			return false;
		}
		return Files.readString(log, StandardCharsets.ISO_8859_1)
				.contains("Phase 5e backstop");
	}

	/**
	 * Two identities, deliberately interleaved.
	 *
	 * <p>Each thread waits at a barrier before every step, so the two sessions
	 * are inside the same phase of the login at the same time on pooled request
	 * threads. That is what makes a leaked {@code ServerContext} or a leaked ZK
	 * {@code Locales} thread local observable.
	 *
	 * <p>What is observed is a per-session, server-produced ADempiere fact for
	 * each axis - the user the desktop reports, the role the session runs as,
	 * the runtime that answered, and the header control ADempiere labels from
	 * the {@code AD_Message} dictionary in the session's own language. The
	 * language axis previously read {@code document.documentElement.lang}, which
	 * neither ZK version renders, so it compared two empty strings and could
	 * never have failed.
	 *
	 * <p>Nothing here hard-codes a translated string. Each identity is captured
	 * ALONE first, and the interleaved capture must reproduce its own solo
	 * capture exactly; the two solo captures must also differ on the language
	 * axis, so a selection that silently did nothing fails the row instead of
	 * making it vacuous.
	 */
	private boolean concurrentIdentitiesStayIsolated() throws Exception {
		runCohort("apply", "user-allowlisted");
		Map<String, String> referenceA = soloSession(primary);
		Map<String, String> referenceB = soloSession(secondary);
		Files.write(evidenceDir.resolve("concurrent-references.tsv"),
				List.of("reference\t" + primary.user() + "\t" + referenceA,
						"reference\t" + secondary.user() + "\t" + referenceB),
				StandardCharsets.UTF_8);
		if (referenceA.get("language").isEmpty()
				|| referenceA.get("language").equals(referenceB.get("language"))) {
			// The explicit language selection did not reach the server, so the
			// interleaved comparison below could not distinguish the two
			// sessions. Report it rather than pass.
			return false;
		}

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			List<Future<Map<String, String>>> results = new ArrayList<>();
			for (Identity identity : List.of(primary, secondary)) {
				results.add(workers.submit(() -> interleavedSession(identity, barrier)));
			}
			Map<String, String> first = results.get(0).get(10, TimeUnit.MINUTES);
			Map<String, String> second = results.get(1).get(10, TimeUnit.MINUTES);
			Files.write(evidenceDir.resolve("concurrent-identities.tsv"),
					List.of("identity\t" + primary.user() + "\t" + first,
							"identity\t" + secondary.user() + "\t" + second),
					StandardCharsets.UTF_8);
			return referenceA.equals(first) && referenceB.equals(second);
		} finally {
			workers.shutdownNow();
		}
	}

	/** The same observation set as the interleaved run, taken alone. */
	private Map<String, String> soloSession(Identity identity) {
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, identity)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			Map<String, String> observed = observe(page, identity, signIn(page, identity));
			logout(page);
			return observed;
		}
	}

	private Map<String, String> interleavedSession(
			Identity identity, CyclicBarrier barrier) throws Exception {
		Map<String, String> observed = new LinkedHashMap<>();
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, identity)) {
			Page page = context.newPage();
			barrier.await(5, TimeUnit.MINUTES);
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

			barrier.await(5, TimeUnit.MINUTES);
			page.locator("[id^='rowUser'] input").first().waitFor();
			page.locator("[id^='rowUser'] input").first().fill(identity.user());
			page.locator("[id^='rowUser'] input").first().press("Tab");
			page.locator("[id^='rowPassword'] input").first().fill(identity.password());
			selectLanguage(page, identity);

			barrier.await(5, TimeUnit.MINUTES);
			okButton(page).click();
			page.locator("[id^='grdChooseRole']").first().waitFor();
			String selectedRole = selectedRole(page);

			barrier.await(5, TimeUnit.MINUTES);
			okButton(page).click();
			page.getByText(identity.user() + "@GardenWorld",
					new Page.GetByTextOptions().setExact(false)).first().waitFor();

			barrier.await(5, TimeUnit.MINUTES);
			observed.putAll(observe(page, identity, selectedRole));

			barrier.await(5, TimeUnit.MINUTES);
			logout(page);
		}
		return observed;
	}

	/** The per-session ADempiere-produced facts both captures compare. */
	private Map<String, String> observe(
			Page page, Identity identity, String selectedRole) {
		Map<String, String> observed = new LinkedHashMap<>();
		observed.put("user", identity.user());
		observed.put("desktop", page.getByText(identity.user() + "@GardenWorld",
				new Page.GetByTextOptions().setExact(false)).first().innerText()
				.trim());
		observed.put("role", selectedRole);
		// AD_Message "Logout", rendered by ADempiere in the SESSION's language.
		observed.put("language", logoutLabel(page));
		observed.put("served", servedBy(page).name());
		return observed;
	}

	/**
	 * A lifecycle end must be a real server-side destruction, and must leave
	 * every cache each runtime reports back at its marked baseline.
	 *
	 * <p>Four properties this makes explicit that the earlier version did not:
	 *
	 * <ol>
	 *   <li><b>The end is caused, not hoped for.</b> Clearing the browser's
	 *       cookies leaves the container session exactly where it was, so the
	 *       destruction case used to assert nothing at all. Each kind below
	 *       drives a mechanism the product itself owns: the Log Out control, the
	 *       session-inactivity interval ADempiere applies to every session it
	 *       creates, and the container's own context lifecycle.</li>
	 *   <li><b>Both readings come from the same point.</b> The mark records a
	 *       log offset and the newest destruction record; the observation reads
	 *       only destruction records written after that offset. The creation-time
	 *       log lines the earlier version read are written BEFORE the session is
	 *       inserted, so comparing them with a post-removal reading compared two
	 *       different points in the lifecycle.</li>
	 *   <li><b>A routed end cleans both runtimes.</b> `await` names the runtimes
	 *       that must record a destruction, and fails if one of them does
	 *       not.</li>
	 *   <li><b>Each runtime is judged on evidence it produced for THIS
	 *       session.</b> See {@link #settled(String)}: the frozen Tomcat 9
	 *       listener writes no cache lines for a routed session, because the
	 *       rotation already unregistered it, so that session owns a destruction
	 *       record and no census. Its row asserts the destruction rather than
	 *       reaching past it for another session's numbers.</li>
	 * </ol>
	 */
	private boolean lifecycle(String kind) throws Exception {
		runCohort("apply", "user-allowlisted");
		String mark = kind + "-before";
		runLane("lifecycle", "mark", evidenceDir.toString(), mark);
		String publicSessionDigest;
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			if (servedBy(page) != Served.MODERN) {
				return false;
			}
			publicSessionDigest = sessionDigest(context);
			switch (kind) {
				case "logout" -> {
					logout(page);
					// The routed logout ends the modern session AND the public
					// one. Naming both runtimes is the assertion.
					runLane("lifecycle", "await", evidenceDir.toString(), mark,
							"public,modern", "120", publicSessionDigest);
				}
				case "timeout" -> {
					// Closing the context stops every poll, so nothing but the
					// inactivity interval ADempiere applied to these sessions at
					// creation can end them.
					context.close();
					runLane("lifecycle", "await", evidenceDir.toString(), mark,
							"public,modern", "420", publicSessionDigest);
				}
				case "destruction" -> {
					// The container's own lifecycle: stopping the modern runtime
					// expires every session its manager holds.
					run("Container destruction", List.of(laneScript, "backend", "stop"));
					runLane("lifecycle", "await", evidenceDir.toString(), mark,
							"modern", "180");
					Response response = page.navigate(baseUrl + contextPath + "/",
							new Page.NavigateOptions()
									.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
					boolean refused = response != null && response.status() >= 500
							&& !page.content().contains(".dsp");
					run("Container recovery", List.of(laneScript, "backend", "start"));
					if (!refused) {
						return false;
					}
					// The public session is terminally failed and is left to the
					// container, which is the only correct end for it: recycling
					// it here would be the legacy fallback this phase forbids.
					context.close();
					runLane("lifecycle", "await", evidenceDir.toString(), mark,
							"public", "420", publicSessionDigest);
				}
				default -> throw new IllegalArgumentException(kind);
			}
		}
		runLane("lifecycle", "observe", evidenceDir.toString(), kind + "-after",
				mark, publicSessionDigest);
		return settled(kind);
	}

	private String sessionDigest(BrowserContext context) {
		String sessionId = sessionCookieValue(context);
		assertNotNull(sessionId, "The routed session must retain its public cookie");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(sessionId.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required by Java", impossible);
		}
	}

	/**
	 * Whether the lifecycle action left both runtimes settled.
	 *
	 * <p>Each runtime is judged on evidence <em>the routed session itself
	 * owns</em>, which is not the same evidence on both ends:
	 *
	 * <ul>
	 *   <li>The <b>modern</b> runtime writes a census line after the mutation
	 *       unconditionally, so it owns a post-removal reading on both ends.
	 *       Its census must be present at the mark and at the observation, and
	 *       its seven values must be identical. That is the cache-balance
	 *       assertion.</li>
	 *   <li>The <b>public</b> runtime runs the frozen Tomcat 9 listener, which
	 *       prints cache lines only for a session {@code SessionManager} still
	 *       holds. A routed session was unregistered by
	 *       {@code CohortRoutingFilter.discardLegacySessionState} at rotation
	 *       time, so its destruction block carries no cache lines at all and
	 *       the session owns <em>no</em> census. The evidence it does own is the
	 *       destruction record, and that is what is asserted. Reaching past it
	 *       for an older session's numbers, or for the pre-insertion lines of
	 *       whichever {@code sessionCreated} happened to follow, would be
	 *       borrowing another session's reading - which is exactly how a
	 *       correctly cleaned-up routed logout previously read as either a
	 *       balanced comparison of values it never produced, or as a missing
	 *       destruction.</li>
	 * </ul>
	 *
	 * <p>So a runtime's cache values are compared only when both the mark and
	 * the observation carry a census; when they do not, the observation must say
	 * so with {@code none} - a recorded destruction with no cache lines - rather
	 * than {@code absent}, which means no post-mutation record was found at all
	 * and is always a failure.
	 */
	private boolean settled(String kind) throws IOException {
		String before = kind + "-before";
		String after = kind + "-after";
		if (!destroyed(after)) {
			return false;
		}
		Map<String, String> markSource = censusSource(before);
		Map<String, String> seenSource = censusSource(after);
		// The modern listener writes its census unconditionally, so a missing
		// one is a broken capture, not an expected shape.
		if (!hasCensus(markSource.get("modern")) || !hasCensus(seenSource.get("modern"))) {
			return false;
		}
		Map<String, String> markCaches = caches(before);
		Map<String, String> seenCaches = caches(after);
		for (String runtime : List.of("public", "modern")) {
			if (hasCensus(markSource.get(runtime)) && hasCensus(seenSource.get(runtime))) {
				if (!cachesOf(markCaches, runtime).equals(cachesOf(seenCaches, runtime))) {
					return false;
				}
			} else if (!"none".equals(seenSource.get(runtime))) {
				return false;
			}
		}
		return true;
	}

	/** Whether a census provenance names a reading with values behind it. */
	private boolean hasCensus(String source) {
		return "census-line".equals(source) || "destruction-block".equals(source);
	}

	/** The `census` provenance row per runtime. */
	private Map<String, String> censusSource(String label) throws IOException {
		Map<String, String> sources = new LinkedHashMap<>();
		for (String line : evidence(label)) {
			String[] fields = line.split("\t", -1);
			if (fields.length == 3 && "census".equals(fields[1])) {
				sources.put(fields[0], fields[2]);
			}
		}
		return sources;
	}

	/** The seven cache sizes per runtime; offsets and labels are not compared. */
	private Map<String, String> caches(String label) throws IOException {
		Map<String, String> values = new LinkedHashMap<>();
		for (String line : evidence(label)) {
			String[] fields = line.split("\t", -1);
			if (fields.length == 3 && fields[1].endsWith("Cache")) {
				values.put(fields[0] + "/" + fields[1], fields[2]);
			}
		}
		return values;
	}

	/** One runtime's slice of a cache reading. */
	private Map<String, String> cachesOf(Map<String, String> values, String runtime) {
		Map<String, String> slice = new LinkedHashMap<>();
		values.forEach((key, value) -> {
			if (key.startsWith(runtime + "/")) {
				slice.put(key, value);
			}
		});
		return slice;
	}

	/** Whether every runtime that reported at all reported a destruction. */
	private boolean destroyed(String label) throws IOException {
		boolean any = false;
		for (String line : evidence(label)) {
			String[] fields = line.split("\t", -1);
			if (fields.length == 3 && "destruction".equals(fields[1])) {
				any = true;
				if (!"observed".equals(fields[2])) {
					return false;
				}
			}
		}
		return any;
	}

	private List<String> evidence(String label) throws IOException {
		Path file = evidenceDir.resolve("session-caches-" + label + ".tsv");
		if (!Files.isReadable(file)) {
			return List.of();
		}
		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	/** The Phase 4 corpus, run while routed modern sessions are authenticated. */
	private boolean soapCoexistence() throws Exception {
		runCohort("apply", "user-allowlisted");
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = newContext(browser, primary)) {
			Page page = context.newPage();
			page.navigate(baseUrl + contextPath + "/",
					new Page.NavigateOptions()
							.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			signIn(page, primary);
			if (servedBy(page) != Served.MODERN) {
				return false;
			}
			prepareSoapFixtures();
			runLane("soap", evidenceDir.toString());
			// The routed session must survive the corpus.
			page.reload(new Page.ReloadOptions()
					.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			boolean survived = servedBy(page) == Served.MODERN;
			page.close();
			return survived;
		}
	}

	// -----------------------------------------------------------------------
	// Plumbing
	// -----------------------------------------------------------------------

	private Locator okButton(Page page) {
		Locator byTitle = page.locator("[title='OK']");
		if (byTitle.count() > 0) {
			return byTitle.first();
		}
		return page.locator("[id^='Ok'], [id^='btnOk']").first();
	}

	private void record(String id, boolean passed, String failureDetail) {
		matrix.put(id, passed ? "pass" : "fail: " + failureDetail);
	}

	private void writeMatrix() throws IOException {
		Files.createDirectories(evidenceDir);
		List<String> lines = new ArrayList<>();
		lines.add("# case\toutcome");
		matrix.forEach((id, outcome) -> lines.add(id + "\t" + outcome));
		Files.write(evidenceDir.resolve("cohort-matrix.tsv"), lines,
				StandardCharsets.UTF_8);
	}

	private String sessionCookieValue(BrowserContext context) {
		return context.cookies().stream()
				.filter(cookie -> "JSESSIONID".equals(cookie.name))
				.map(cookie -> cookie.value)
				.findFirst()
				.orElse(null);
	}

	private void runCohort(String... arguments) {
		List<String> command = new ArrayList<>(List.of(
				cohortScript,
				required("phase5e.dbHost"),
				required("phase5e.dbPort"),
				required("phase5e.dbName"),
				required("phase5e.dbUser"),
				required("phase5e.dbMarker")));
		command.addAll(List.of(arguments));
		run("Cohort configuration " + arguments[0], command);
	}

	private void runFixture(String operation, Path fixture) {
		run("Oracle fixture " + operation, List.of(
				fixtureScript,
				required("phase5e.dbHost"),
				required("phase5e.dbPort"),
				required("phase5e.dbName"),
				required("phase5e.dbUser"),
				required("phase5e.dbMarker"),
				operation,
				fixture.toString()));
	}

	private void prepareSoapFixtures() {
		run("Phase 4 SOAP fixtures", List.of(
				soapFixtureScript,
				required("phase5e.dbHost"),
				required("phase5e.dbPort"),
				required("phase5e.dbName"),
				required("phase5e.dbUser"),
				required("phase5e.dbMarker")));
	}

	private void runLane(String... arguments) {
		List<String> command = new ArrayList<>();
		command.add(laneScript);
		command.addAll(List.of(arguments));
		run("Routed lane " + arguments[0], command);
	}

	private String run(String label, List<String> command) {
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
		} catch (IOException failure) {
			throw new IllegalStateException(label + " could not be started", failure);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(label + " was interrupted", interrupted);
		}
	}

	private static String required(String name) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required");
		}
		return value;
	}
}
