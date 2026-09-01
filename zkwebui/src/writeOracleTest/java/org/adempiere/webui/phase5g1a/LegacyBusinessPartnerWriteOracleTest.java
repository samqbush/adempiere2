package org.adempiere.webui.phase5g1a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.phase5d.BrowserSemanticContract;
import org.adempiere.webui.phase5legacy.LegacyBrowserFlow;
import org.adempiere.webui.phase5legacy.LegacyDatabaseScripts;
import org.adempiere.webui.phase5legacy.StepRendezvous;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Captures the LEGACY Business Partner write flow through the public
 * {@code /webui} origin of the installed Tomcat 9 / ZK 3.6 product.
 *
 * <h2>What this class is, and is not</h2>
 *
 * <p>It is the capture half of the Phase 5g-1a oracle. It drives create, update
 * and deactivate on {@code C_BPartner} and pauses after each save so the
 * orchestrator can take a database snapshot. It ships no modern code and scores
 * no parity.
 *
 * <p>It deliberately does NOT know what the expected effect is. It never reads
 * {@code effect-model.tsv} and never asserts a business value. The moment a
 * driver asserts the answer, the answer becomes whatever the driver was written
 * to expect, and the oracle is scoring itself. The driver's only assertions are
 * about whether the OPERATION happened -- a save that silently failed must not
 * be measured as a save -- and those are UI post-conditions, not business facts.
 *
 * <h2>Why the flow pauses instead of measuring once</h2>
 *
 * <p>A single before/after pair around create then update then deactivate shows
 * only the final deactivated row. A create that stored the wrong value and an
 * update that corrected it would be indistinguishable from a correct run. So
 * each operation is bracketed: the driver executes exactly one operation, waits
 * for its save round trip, asserts the record reports itself saved, and then
 * blocks until the orchestrator acknowledges that it has taken the snapshot.
 */
@Tag("IntegrationTest")
class LegacyBusinessPartnerWriteOracleTest {

	private static final LegacyDatabaseScripts SCRIPTS =
			new LegacyDatabaseScripts("phase5g1a.browser.");

	/**
	 * Generous, because it bounds a browser round trip plus a whole-schema
	 * snapshot on a loaded CI runner. It is a backstop against a hang, not a
	 * performance expectation, and expiring is always a failure.
	 */
	private static final Duration RENDEZVOUS_TIMEOUT = Duration.ofMinutes(10);

	private static final String WINDOW = "Business Partner";

	private final String baseUrl = SCRIPTS.property("baseUrl").replaceFirst("/+$", "");
	private final String user = SCRIPTS.property("user");
	private final String password = SCRIPTS.property("password");
	private final String client = SCRIPTS.property("client");
	private final Path evidenceDir = Path.of(SCRIPTS.property("evidenceDir"));
	private final Path rendezvousDir = Path.of(SCRIPTS.property("rendezvousDir"));
	private final String token = SCRIPTS.property("token");
	private final String recordValue = SCRIPTS.property("recordValue");
	private final String secondUser = SCRIPTS.property("secondUser");
	private final String secondPassword = SCRIPTS.property("secondPassword");

	@Test
	void capturesTheLegacyBusinessPartnerWriteFlow() throws IOException {
		Files.createDirectories(evidenceDir);
		StepRendezvous rendezvous =
				new StepRendezvous(rendezvousDir, token, "driver", "orchestrator");
		rendezvous.announce();

		List<String> requests = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Map<String, String> facts = new LinkedHashMap<>();
		List<String> flow = new ArrayList<>();

		// Assigned inside the try so the failure handler can interrogate the
		// live page; null until the browser exists, which is itself a fact worth
		// recording if the lane dies that early.
		Page diagnosticPage = null;
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium().launch(
						new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = LegacyBrowserFlow.newContext(browser)) {
			LegacyBrowserFlow.blockForeignOrigins(context, baseUrl);
			Page page = context.newPage();
			diagnosticPage = page;
			LegacyBrowserFlow.recordTraffic(page, requests, errors, this::normalizedUrl);

			Response login = LegacyBrowserFlow.login(page, baseUrl, user, password);
			assertEquals(200, login.status(), "the legacy login page did not respond 200");
			LegacyBrowserFlow.awaitRolePanel(page, BrowserSemanticContract::normalizedText);
			LegacyBrowserFlow.confirmRole(page);
			LegacyBrowserFlow.awaitDesktop(page, user, client);
			facts.put("desktop-reached", "true");

			// Step 0 is the baseline: the orchestrator snapshots an authenticated
			// session that has not yet written any business row. Without it, the
			// create step's effect would include the login's own AD_Session write
			// and every table the desktop touches on first render.
			flow.add(step(rendezvous, 0, "authenticated-baseline"));

			openWindow(page);
			facts.put("window-opened", WINDOW);
			flow.add(step(rendezvous, 1, "window-opened"));

			create(page);
			flow.add(step(rendezvous, 2, "create"));

			update(page);
			flow.add(step(rendezvous, 3, "update"));

			deactivate(page);
			flow.add(step(rendezvous, 4, "deactivate"));

			// The concurrency step. The contract requires the LEGACY conflict
			// answer to be captured here so that 5g-1b has something it did not
			// invent to score the modern runtime against.
			//
			// The two editors must be different users: a user racing itself
			// records no UpdatedBy transition, so the capture could not say who
			// won. The fixture asserts role 103's read-write access to window
			// 123 precisely so that an access refusal can never be mistaken for
			// conflict behaviour.
			try (BrowserContext secondContext = LegacyBrowserFlow.newContext(browser)) {
				LegacyBrowserFlow.blockForeignOrigins(secondContext, baseUrl);
				Page second = secondContext.newPage();
				LegacyBrowserFlow.recordTraffic(second, requests, errors, this::normalizedUrl);

				Response secondLogin = LegacyBrowserFlow.login(
						second, baseUrl, secondUser, secondPassword);
				assertEquals(200, secondLogin.status(),
						"the second editor's login page did not respond 200");
				LegacyBrowserFlow.awaitRolePanel(second, BrowserSemanticContract::normalizedText);
				LegacyBrowserFlow.confirmRole(second);
				LegacyBrowserFlow.awaitDesktop(second, secondUser, client);
				facts.put("concurrency-second-editor-desktop-reached", "true");

				// The second editor's FIRST login writes rows of its own --
				// AD_Session, its preferences and their change logs. Those are
				// not concurrency effects, and folding them into the update step
				// would freeze them as though they were. Giving them their own
				// step boundary attributes them where they belong.
				flow.add(step(rendezvous, 5, "concurrency-second-editor-authenticated"));

				openWindow(second);
				focusRecord(second, recordValue);
				fill(second, "Name", recordValue + " Partner By Second Editor");
				save(second);
				facts.put("concurrency-second-editor-saved", "true");
				flow.add(step(rendezvous, 6, "concurrency-second-editor-update"));

				// The primary session still holds the record it loaded before the
				// second editor wrote it. Its save is the conflict.
				//
				// This is the one save in the flow that is NOT asserted to
				// succeed. Whether ZK 3.6 refuses it, silently overwrites, or
				// reloads is the expected answer this increment exists to
				// capture -- asserting either outcome would make the oracle
				// score whatever the driver was written to expect.
				fill(page, "Name", recordValue + " Partner By First Editor");
				facts.put("concurrency-conflicting-save-outcome", attemptSave(page));
				flow.add(step(rendezvous, 7, "concurrency-conflicting-save"));

				LegacyBrowserFlow.logout(second);
			}

			LegacyBrowserFlow.logout(page);
			facts.put("logout-reached", "true");
			flow.add(step(rendezvous, 8, "logged-out"));
		} catch (Throwable failure) {
			// Publish before rethrowing. An orchestrator blocked on the next
			// rendezvous has no other way to learn the browser has died, and a
			// lane that hangs until the CI job times out produces no diagnosis.
			//
			// Throwable, not RuntimeException: every post-condition here is a
			// JUnit assertion, and AssertionFailedError extends Error. Catching
			// only RuntimeException would have published the reason for a
			// Playwright timeout while losing it for the far more likely case --
			// a save that did not take.
			// A CI round trip for this lane costs roughly twelve minutes, and a
			// Playwright timeout names only the locator it gave up on -- never
			// what the page actually contained instead. Capturing that here
			// turns "the tab never appeared" into "these are the tabs that did",
			// so a selector defect is diagnosable from the run's own evidence
			// instead of from another twelve-minute guess.
			captureDiagnostics(diagnosticPage);
			rendezvous.fail(failure.toString());
			throw failure;
		}

		Files.write(evidenceDir.resolve("write-flow.tsv"), flow, StandardCharsets.UTF_8);
		Files.write(evidenceDir.resolve("semantic-facts.tsv"),
				facts.entrySet().stream()
						.map(entry -> entry.getKey() + "\t" + entry.getValue())
						.toList(),
				StandardCharsets.UTF_8);
		Files.write(evidenceDir.resolve("network-requests.tsv"),
				requests, StandardCharsets.UTF_8);
		Files.write(evidenceDir.resolve("browser-errors.tsv"),
				errors, StandardCharsets.UTF_8);
	}

	/**
	 * Hands step {@code sequence} to the orchestrator and blocks until it has
	 * snapshotted. Returns the flow ledger row for the step.
	 */
	private String step(StepRendezvous rendezvous, int sequence, String stepId) {
		rendezvous.request(sequence, stepId);
		rendezvous.awaitAcknowledgement(sequence, stepId, RENDEZVOUS_TIMEOUT);
		return sequence + "\t" + stepId;
	}

	/**
	 * Opens the Business Partner window through ADempiere's own menu lookup.
	 *
	 * <p>Identical mechanics to the Phase 5c read oracle: the menu tree renders
	 * collapsed branches without a clickable box, so the lookup combobox is the
	 * only path a browser can actually take. The value must be TYPED, because ZK
	 * 3.6 drives the lookup from key events and a programmatic assignment fires
	 * no {@code onChanging}.
	 */
	/**
	 * Records what the page actually contained when a step failed.
	 *
	 * <p>Best effort by construction: this runs while the lane is already
	 * failing, so a fault here must never replace the real diagnosis with a
	 * diagnostic's own stack trace.
	 */
	private void captureDiagnostics(Page page) {
		if (page == null) {
			return;
		}
		try {
			page.screenshot(new Page.ScreenshotOptions()
					.setPath(evidenceDir.resolve("failure.png"))
					.setFullPage(true));
		} catch (RuntimeException ignored) {
			// A screenshot is a convenience; the textual dump below is the
			// evidence that actually names a wrong selector.
		}
		try {
			Object dump = page.evaluate(
					"() => JSON.stringify({"
					+ " url: location.href,"
					+ " tabs: Array.prototype.map.call("
					+ "   document.querySelectorAll('span.z-tab-text'), e => e.textContent),"
					+ " comboItems: Array.prototype.map.call("
					+ "   document.querySelectorAll('tr.z-combo-item'),"
					+ "   e => e.getAttribute('z.label') || e.textContent),"
					+ " labels: Array.prototype.slice.call("
					+ "   document.querySelectorAll('span.z-label, td.z-row-cell span'), 0, 200)"
					+ "   .map(e => e.textContent),"
					+ " toolbar: Array.prototype.map.call("
					+ "   document.querySelectorAll('a.toolbar-button'),"
					+ "   e => e.getAttribute('title') + '|' + e.className)"
					+ "}, null, 1)");
			Files.write(evidenceDir.resolve("failure-page.json"),
					String.valueOf(dump).getBytes(StandardCharsets.UTF_8));
		} catch (RuntimeException | IOException ignored) {
			// Same rationale.
		}
	}

	private void openWindow(Page page) {
		Locator lookup = page.locator(
				"xpath=//span[@title='Enter text to search for in tree']"
						+ "/ancestor::div[1]/following-sibling::span"
						+ "[contains(@class,'z-combobox')]"
						+ "//input[contains(@class,'z-combobox-inp')]");
		lookup.waitFor();
		lookup.click();
		lookup.pressSequentially(WINDOW, new Locator.PressSequentiallyOptions().setDelay(40));
		page.locator("tr.z-combo-item[z\\.label=\"" + WINDOW + "\"]").first().waitFor();
		assertEquals(WINDOW, lookup.inputValue(),
				"the menu lookup does not hold the exact window name");
		// The AU response must be matched on the window NAME, not merely on
		// `=onChange&`. ZK 3.6 posts onChange for a plain blur too, so the
		// looser predicate can be satisfied by an unrelated round trip and let
		// the step race ahead to wait for a tab that was never requested.
		String encodedWindow = URLEncoder.encode(WINDOW, StandardCharsets.UTF_8)
				.replace("+", "%20");
		page.waitForResponse(
				response -> response.request().url().contains("/zkau")
						&& response.request().postData() != null
						&& response.request().postData().contains("=onChange&")
						&& response.request().postData().contains(encodedWindow),
				() -> lookup.press("Enter"));
		page.locator("span.z-tab-text:text-is('" + WINDOW + "')").first().waitFor();
		tabPanel(page).waitFor();
	}

	private void create(Page page) {
		click(page, "New record");
		// The C_BPartner.Value column is labelled "Search Key" in the dictionary;
		// "Value" matches no cell in the rendered form.
		fill(page, "Search Key", recordValue);
		fill(page, "Name", recordValue + " Partner");
		save(page);
	}

	private void update(Page page) {
		fill(page, "Name", recordValue + " Partner Updated");
		save(page);
	}

	/**
	 * Deactivation is a checkbox, not a button, so it is driven by clicking the
	 * Active control and saving. Hard delete is explicitly out of scope for this
	 * increment and is recorded in {@code exclusions.tsv}.
	 */
	private void deactivate(Page page) {
		Locator active = tabPanel(page)
				.locator("xpath=.//td[normalize-space(text())='Active']"
						+ "/following-sibling::td[1]//input[@type='checkbox']")
				.first();
		active.waitFor();
		assertTrue(active.isChecked(), "the record was not active before deactivation");
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				active::click);
		assertTrue(!active.isChecked(), "the Active control did not clear");
		save(page);
	}

	/**
	 * Types a value into a labelled field and lets ZK's change event fire.
	 *
	 * <p>The trailing {@code Tab} is not cosmetic. ZK 3.6 posts the field value on
	 * blur; saving without it would save the record without the value that was
	 * just typed, and the capture would record a real -- but wrong -- effect.
	 */
	private void fill(Page page, String label, String value) {
		Locator field = labelledInput(page, label);
		field.waitFor();
		field.fill(value);
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> field.press("Tab"));
	}

	/**
	 * Saves and asserts the save actually happened.
	 *
	 * <p>Waiting for the AU round trip alone is not enough: ZK answers a failed
	 * save with a round trip too. The post-condition is that the window reports
	 * no pending change afterwards, which is what makes "the step completed" a
	 * measured fact rather than an assumption. Measuring an unsaved record would
	 * freeze an empty effect as the expected answer.
	 */
	private void save(Page page) {
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> click(page, "Save changes"));
		Locator error = page.locator("div.z-window-modal, div.popup-error");
		assertEquals(0, error.count(), "the save raised a modal error dialog");
		Locator saveButton = tabPanel(page)
				.locator("a.toolbar-button[title='Save changes']").first();
		saveButton.waitFor();
		assertTrue(saveButton.getAttribute("class").contains("toolbar-button-disd")
						|| !saveButton.isEnabled(),
				"the Save control is still enabled, so the record was not saved");
	}

	/**
	 * Brings the record identified by {@code value} into the window's current
	 * record for a session that did not create it.
	 *
	 * <p>The second editor opens the window after the record exists, so the
	 * window may already be positioned on it or on some other row. This asserts
	 * the position rather than assuming it, and falls back to the Find dialog
	 * otherwise. Editing the wrong row would produce a real -- but entirely
	 * meaningless -- conflict measurement.
	 */
	private void focusRecord(Page page, String value) {
		Locator valueField = labelledInput(page, "Search Key");
		valueField.waitFor();
		if (value.equals(valueField.inputValue())) {
			return;
		}
		click(page, "Find record");
		Locator dialog = page.locator("div.z-window-modal").first();
		dialog.waitFor();
		Locator search = dialog
				.locator("xpath=.//td[normalize-space(text())='Search Key']"
						+ "/following-sibling::td[1]//input")
				.first();
		search.waitFor();
		search.fill(value);
		search.press("Tab");
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> dialog.locator("button:text-is('OK')").first().click());
		dialog.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
		assertEquals(value, labelledInput(page, "Search Key").inputValue(),
				"the window is not positioned on the captured record");
	}

	/**
	 * Clicks Save and REPORTS what happened instead of requiring success.
	 *
	 * <p>Used only for the conflicting save. Every other save in the flow uses
	 * {@link #save(Page)}, because measuring an operation that silently did not
	 * happen would freeze an empty effect as the expected answer. Here the
	 * outcome is itself the fact being captured, so it is recorded, not judged.
	 */
	private String attemptSave(Page page) {
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> click(page, "Save changes"));
		Locator error = page.locator("div.z-window-modal, div.popup-error");
		if (error.count() > 0) {
			return "error-dialog\t"
					+ BrowserSemanticContract.normalizedText(error.first().innerText());
		}
		Locator saveButton = tabPanel(page)
				.locator("a.toolbar-button[title='Save changes']").first();
		saveButton.waitFor();
		boolean disabled = saveButton.getAttribute("class").contains("toolbar-button-disd")
				|| !saveButton.isEnabled();
		return disabled ? "accepted" : "rejected-save-still-enabled";
	}

	private Locator labelledInput(Page page, String label) {
		return tabPanel(page)
				.locator("xpath=.//td[normalize-space(text())='" + label + "']"
						+ "/following-sibling::td[1]//input")
				.first();
	}

	private void click(Page page, String title) {
		Locator button = tabPanel(page)
				.locator("a.toolbar-button[title='" + title + "']").first();
		button.waitFor();
		button.click();
	}

	private Locator tabPanel(Page page) {
		return page.locator("div.desktop-tabpanel")
				.filter(new Locator.FilterOptions()
						.setHas(page.locator("span.z-tab-text:text-is('" + WINDOW + "')")))
				.first();
	}

	private String normalizedUrl(String value) {
		return BrowserSemanticContract.normalizedUrl(baseUrl, value);
	}
}
