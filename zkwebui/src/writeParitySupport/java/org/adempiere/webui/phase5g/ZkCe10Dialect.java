package org.adempiere.webui.phase5g;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.adempiere.webui.phase5d.BrowserSemanticContract;
import org.adempiere.webui.phase5legacy.LegacyBrowserFlow;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * The ZK CE {@code 10.3.0.1-jakarta} dialect, driven through the public routed
 * origin on Tomcat 10.1.
 *
 * <h2>What this class is allowed to differ in</h2>
 *
 * <p>Only in how a control is located, operated and awaited. It emits no fact,
 * decides no step order and reinterprets no product outcome; all of that lives
 * in {@link BusinessPartnerWriteFlow} and is shared with {@link Zk36Dialect}.
 * In particular {@link #attemptSave} reports what the runtime did. A dialect
 * that returned {@code "accepted"} because its own selectors could not find the
 * error dialog would convert the headline parity failure into a pass, which is
 * the one outcome this whole increment exists to make impossible.
 *
 * <h2>Where the selectors come from</h2>
 *
 * <p>They are not guesses at ZK CE 10's markup. Every one of them is either
 *
 * <ul>
 *   <li>already proven against this exact runtime by the Phase 5d modern slice
 *       -- the login rows, the role grid, the OK control, the menu lookup's
 *       {@code input.z-combobox-input} under {@code .adempiere-tree-search},
 *       {@code div.desktop-tabpanel}, the {@code [id*='_<Table>_']} editor ids
 *       and the {@code [title=...]} toolbar controls; or</li>
 *   <li>owned by ADempiere's own source rather than by ZK, and therefore
 *       identical across the two runtimes because both compile the same
 *       {@code WEB-INF/src}.</li>
 * </ul>
 *
 * <p>The second category is the interesting one. ZK 3.6 markup that the legacy
 * dialect depends on -- {@code tr.z-combo-item}, the {@code z.label} attribute,
 * the {@code !pp} / {@code !btn} id suffixes, the {@code -disd} disabled-class
 * suffix -- is ZK's, not ADempiere's, and none of it survives into ZK 10. So
 * this dialect never asks the DOM what ZK named something when it can ask the
 * product instead: the save's pending state is read from the toolbar control's
 * own enabled/disabled state rather than from a version-specific class suffix,
 * and a combo choice is asserted on the editor's value rather than on the popup
 * row that produced it.
 *
 * <h2>How a wrong selector here fails</h2>
 *
 * <p>Loudly, by construction. Every resolution below is exact -- an ambiguous
 * or missing match is an assertion failure, never a {@code first()} that picks
 * something -- because the failure mode a driver must not have is a plausible
 * wrong record. {@link #captureDiagnostics} dumps the live markup for the same
 * reason: the first failing CI round has to be the one that says what ZK CE 10
 * actually rendered, since a round trip through this lane is not cheap.
 */
public final class ZkCe10Dialect implements ZkDialect {

	/** Identical budgets to the legacy dialect: these are hang backstops. */
	private static final Duration SAVE_SETTLE = Duration.ofSeconds(30);

	private static final Duration FIELD_SETTLE = Duration.ofSeconds(15);

	private static final Duration COMBO_OPEN = Duration.ofSeconds(10);

	private static final String WINDOW = "Business Partner";

	private static final String TABLE = "C_BPartner";

	/** ADempiere's own sclass on the menu lookup; proven by the Phase 5d slice. */
	private static final String TREE_SEARCH_SCLASS = "adempiere-tree-search";

	private static final Map<String, String> CAPTIONS =
			Map.of("Value", "Search Key", "Name", "Name");

	private static final String FIND_TITLE_PREFIX = "Lookup Record: ";

	/**
	 * Both spellings, for the same reason the legacy dialect accepts both: the
	 * find dialog's ConfirmPanel and the role panel's confirmation come from
	 * different AD_Message keys, and neither is a ZK concern.
	 */
	private static final String OK_BUTTON = "[title='Ok']:visible, [title='OK']:visible";

	@Override
	public String id() {
		return "zk-ce-10.3.0.1-jakarta-tomcat-10.1-routed";
	}

	/**
	 * The same context policy as the legacy lane, including the foreign-origin
	 * block.
	 *
	 * <p>Shared deliberately. The block is what makes "the browser reached only
	 * the public origin" a property of the capture rather than of the network,
	 * and it is the same property in both lanes. What it must NOT do is block
	 * the loopback modern origin by allowing it: the routed lane's H6 rows
	 * assert the browser never reaches {@code /webui-modern}, and that assertion
	 * would be vacuous if this context had whitelisted it.
	 */
	@Override
	public BrowserContext newContext(Browser browser, String baseUrl) {
		BrowserContext context = LegacyBrowserFlow.newContext(browser);
		LegacyBrowserFlow.blockForeignOrigins(context, baseUrl);
		return context;
	}

	/**
	 * Records requests and browser errors in the SAME normalized shape the legacy
	 * lane recorded them, by delegating to the SAME implementation.
	 *
	 * <p>Delegation, not a copy. The recorded lines are scored against the frozen
	 * {@code network-classes.tsv} and {@code allowed-browser-errors.tsv}, so the
	 * recording FORMAT is part of the compared answer and a per-runtime
	 * reimplementation is a guaranteed false failure however carefully it is
	 * written. A first draft of this method did reimplement it, and differed in
	 * four ways at once -- the {@code page} class was renamed {@code pageerror},
	 * the page URL was dropped from both message classes, and the
	 * {@code Failed to load resource:} filter was lost, which would have emitted
	 * one console row per blocked foreign origin and failed the transport-class
	 * policy on the first request.
	 *
	 * <p>Only the TRAFFIC may differ between runtimes. That is the measurement.
	 */
	@Override
	public void recordTraffic(Page page, List<String> requests, List<String> errors,
			UnaryOperator<String> normalizer) {
		LegacyBrowserFlow.recordTraffic(page, requests, errors, normalizer);
	}

	/**
	 * The modern login handshake: credentials, role panel, desktop.
	 *
	 * <p>Structurally the same three phases as ZK 3.6 and rendered differently
	 * at every one of them, which is why the interface covers the whole
	 * handshake rather than each control. The post-condition is the shared one:
	 * the desktop is rendered for this user and client.
	 */
	@Override
	public void signIn(Page page, String baseUrl, String user, String password, String client,
			String sessionLabel) {
		// The context path is part of the origin under test, not an optional
		// suffix. `phase5g1a.browser.baseUrl` is the ORIGIN only
		// (`http://127.0.0.1:8888`), exactly as the legacy flow receives it, and
		// LegacyBrowserFlow.login appends `/webui/` itself. Run 33584462937
		// navigated to the bare origin instead, which ADempiere redirects to
		// `/admin/`; the capture then spent 30s waiting for a login field on the
		// "Download ADempiere Client" page. Appending it here also means the
		// scored origin cannot drift onto the loopback `/webui-modern` context
		// that ADR decision 6 forbids scoring on.
		Response login = page.navigate(baseUrl + "/webui/",
				new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		assertNotNull(login, "the " + sessionLabel + " login navigation returned no response");
		assertEquals(200, login.status(),
				"the " + sessionLabel + " login page did not respond 200");
		// A redirect away from /webui is the failure above, and without this it
		// surfaces 30 seconds later as an unexplained selector timeout. Each CI
		// iteration of this lane costs half an hour, so the diagnosis belongs at
		// the point of the defect.
		assertTrue(page.url().contains("/webui"),
				"the " + sessionLabel + " login navigation left the /webui origin and landed on "
						+ page.url());

		Locator userInput = page.locator("[id^='rowUser'] input").first();
		userInput.waitFor();
		userInput.fill(user);
		// The Tab is the product's, not the browser's: ADempiere's login panel
		// reloads the client and role lists from the user's own onChange, and a
		// password typed before that round trip is submitted against a panel
		// that has not resolved the user yet.
		userInput.press("Tab");
		page.locator("[id^='rowPassword'] input").first().fill(password);
		okButton(page).click();

		Locator roleGrid = page.locator("[id^='grdChooseRole']").first();
		roleGrid.waitFor();
		okButton(page).click();

		// The desktop identifies itself by the product's own user@client
		// rendering, so this asserts WHICH identity reached the desktop. Four
		// sessions sign in during one capture and two of them use different
		// users; a login that silently landed on the wrong identity would
		// produce a real conflict measurement between a session and itself.
		page.getByText(user + "@" + client, new Page.GetByTextOptions().setExact(false))
				.first().waitFor();
	}

	@Override
	public void logout(Page page) {
		page.getByText("Log Out", new Page.GetByTextOptions().setExact(true))
				.first().click();
		page.locator("[id^='rowUser'] input").first().waitFor();
	}

	@Override
	public String expectedRuntime() {
		return "modern";
	}

	private Locator okButton(Page page) {
		Locator byTitle = page.locator("[title='OK']:visible, [title='Ok']:visible");
		if (byTitle.count() > 0) {
			return byTitle.first();
		}
		return page.locator("[id^='Ok'], [id^='btnOk']").first();
	}

	/**
	 * Opens the capture's window through ADempiere's own menu lookup.
	 *
	 * <p>The value is TYPED for a product reason rather than a ZK one:
	 * {@code TreeSearchPanel} resolves the typed text against an exact node-name
	 * map and opens the ancestor path, and a collapsed tree branch has no box a
	 * browser could click.
	 *
	 * <p>Unlike the legacy dialect, the drop-down row is not waited for. ZK 3.6
	 * rendered the candidate list as {@code tr.z-combo-item} carrying a
	 * {@code z.label} attribute; ZK 10 emits neither, and inventing a
	 * replacement selector for a row that is only ever a waypoint would be a
	 * guess whose failure is a timeout rather than a diagnosis. The exact
	 * post-condition -- the lookup holds the window name, and the AU round trip
	 * carrying that name completed -- is asserted instead, and it is strictly
	 * stronger than the row's presence.
	 */
	@Override
	public void openWindow(Page page, String searchKey) {
		Locator lookup = menuLookup(page);
		lookup.waitFor();
		lookup.click();
		lookup.pressSequentially(WINDOW, new Locator.PressSequentiallyOptions().setDelay(40));
		assertEquals(WINDOW, lookup.inputValue(),
				"the menu lookup does not hold the exact window name");
		String encodedWindow = URLEncoder.encode(WINDOW, StandardCharsets.UTF_8)
				.replace("+", "%20");
		// Matched on the window NAME, for the same reason the legacy dialect
		// matches on it: a bare `onChange` predicate is satisfied by an
		// unrelated blur round trip, and the step then races ahead to wait for a
		// tab nobody asked for.
		page.waitForResponse(
				response -> response.request().url().contains("/zkau")
						&& response.request().postData() != null
						&& response.request().postData().contains("onChange")
						&& (response.request().postData().contains(encodedWindow)
								|| response.request().postData().contains(WINDOW)),
				() -> lookup.press("Enter"));

		// FindWindow is ADempiere source, so the mandatory "Lookup Record"
		// dialog stands between the menu selection and the rendered window in
		// BOTH runtimes.
		enterWindowThroughFindDialog(page, searchKey);
		tabPanel(page).waitFor();
	}

	private Locator menuLookup(Page page) {
		Locator bySclass = page.locator(
				"." + TREE_SEARCH_SCLASS + " input.z-combobox-input");
		if (bySclass.count() > 0) {
			return bySclass.first();
		}
		// The ADempiere-owned tooltip is a translated AD_Message and is stable
		// across both renderings, which is why it is the fallback rather than a
		// second ZK class name.
		return page.locator("xpath=//*[@title='Enter text to search for in tree']"
				+ "/ancestor::*[self::div or self::td][1]"
				+ "/following::input[1]").first();
	}

	/**
	 * Answers the mandatory "Lookup Record" dialog.
	 *
	 * <p>{@code null} must not be answered by cancelling. That is a product
	 * fact, established on the legacy runtime in run 33471012956 -- cancel means
	 * "never mind", not "open it empty" -- and it comes from FindWindow, which
	 * both runtimes compile.
	 */
	private void enterWindowThroughFindDialog(Page page, String searchKey) {
		// Assert WHICH dialog. A modal is not self-identifying, and answering an
		// unexpected one would be an undiagnosable divergence later.
		page.getByText(FIND_TITLE_PREFIX + WINDOW).first().waitFor();
		Locator dialog = findDialog(page);
		if (searchKey != null) {
			commitSearchKey(page, searchKeyField(dialog), searchKey);
		}
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> dialog.locator(OK_BUTTON).first().click());
		dialog.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
	}

	/**
	 * The Find dialog itself, identified by its own caption.
	 *
	 * <p>Taking {@code .first()} of the modal-class union is not safe once the
	 * ADWindow behind the dialog has rendered, and that is what runs 33589524866,
	 * 33591572610 and 33598342557 all died on. The Business Partner window
	 * carries its OWN field captioned "Search Key" -- it is the caption of
	 * {@code C_BPartner.Value} -- so a dialog locator that resolves to the window
	 * instead of to the modal finds a "Search Key" input, fills it, and commits
	 * it with a perfectly real AU request. Nothing looks wrong until the lookup
	 * runs unfiltered and the window opens on the first of eighteen records.
	 *
	 * <p>That is why the failure only ever appeared in {@link #reloadRecord}:
	 * {@link #enterWindowThroughFindDialog} answers the mandatory dialog before
	 * any window exists behind it, so its union has exactly one match, while
	 * reloadRecord opens the dialog over a rendered window and has two.
	 *
	 * <p>So filter the union to the modal that carries the dialog's own caption,
	 * and take the LAST match: Playwright resolves in document order, so when
	 * the dialog is nested inside the window the innermost element is last, and
	 * when it is a sibling the later one is still the dialog.
	 */
	private Locator findDialog(Page page) {
		Locator captioned = captionedDialogs(page);
		captioned.last().waitFor();
		return captioned.last();
	}

	private Locator captionedDialogs(Page page) {
		return modalDialog(page).filter(
				new Locator.FilterOptions().setHasText(FIND_TITLE_PREFIX + WINDOW));
	}

	/**
	 * The Find dialog's Search Key criterion.
	 *
	 * <p>Resolved strictly: filling the wrong "Search Key" input is silent, and
	 * silence is what cost three capture runs.
	 */
	private Locator searchKeyField(Locator dialog) {
		Locator field = dialog.locator(
				"xpath=.//td[normalize-space(.)='Search Key']"
						+ "/following-sibling::td[1]//input[not(@type='hidden')]");
		field.first().waitFor();
		int resolved = field.count();
		assertEquals(1, resolved,
				"the Find dialog's Search Key criterion resolved to "
						+ resolved + " inputs");
		return field.first();
	}

	/**
	 * Modal windows and error popups, in either runtime's class vocabulary.
	 *
	 * <p>ZK renamed the modal window class between 3.6 and 10, and
	 * {@code popup-error} is ADempiere's own. Matching the union is not
	 * leniency: this locator's job is to NOTICE a dialog, and every miss makes
	 * {@link #attemptSave} report a refused save as accepted. Being unable to
	 * see the error dialog is the single most dangerous failure this dialect
	 * has, so it is the one place that deliberately looks in more than one way.
	 */
	private Locator modalDialog(Page page) {
		return page.locator("div.z-window-modal, div.z-window-highlighted, div.popup-error");
	}

	@Override
	public void newRecord(Page page) {
		clickAwaitingServer(page, "New Record");
	}

	@Override
	public void clearActive(Page page) {
		Locator active = columnInput(page, "IsActive");
		active.waitFor();
		assertTrue(active.isChecked(), "the record was not active before deactivation");
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				active::click);
		assertTrue(!active.isChecked(), "the Active control did not clear");
	}

	@Override
	public void fill(Page page, String column, String value) {
		typeInto(page, column, value);
		if (!settledOn(page, column, value)) {
			typeInto(page, column, value);
		}
		assertTrue(settledOn(page, column, value),
				"the editor for column " + column + " did not take the typed value."
						+ " It holds '" + columnInput(page, column).inputValue()
						+ "' and its classes are '" + editorClasses(page, column) + "'");
	}

	private void typeInto(Page page, String column, String value) {
		Locator field = columnInput(page, column);
		field.waitFor();
		field.fill(value);
		field.press("Tab");
	}

	/**
	 * Whether the edit has settled: the editor holds the value AND the window
	 * reports a pending change.
	 *
	 * <p>Both halves, for the legacy dialect's reason: the value alone can sit
	 * in the browser without the runtime having registered it, and the save then
	 * writes the previous value -- a wrong record that looks entirely plausible.
	 */
	private boolean settledOn(Page page, String column, String value) {
		long deadline = System.nanoTime() + FIELD_SETTLE.toNanos();
		do {
			if (savePending(page) && value.equals(columnInput(page, column).inputValue())) {
				return true;
			}
			page.waitForTimeout(250);
		} while (System.nanoTime() < deadline);
		return false;
	}

	/**
	 * Whether the window is holding an unsaved change, read as "the Save control
	 * is enabled".
	 *
	 * <p>Deliberately NOT read from a CSS class. The legacy dialect tests for
	 * ZK 3.6's {@code toolbar-button-disd} suffix, which ZK 10 does not emit;
	 * carrying that string over would make every save look permanently pending
	 * and every field edit look settled the instant it was typed. Playwright's
	 * {@code isEnabled} resolves the state the user sees through whatever markup
	 * the runtime chose, which is the property both dialects actually mean.
	 */
	private boolean savePending(Page page) {
		return !saveSettled(page);
	}

	/**
	 * Whether the Save control has settled into a disabled state.
	 *
	 * <p>Read from the DOM, NOT through Playwright's {@code isEnabled()}.
	 * ADempiere's {@code ToolBarButton} extends {@code org.zkoss.zul.Toolbarbutton},
	 * which ZK CE 10 renders as {@code <a role="button" disabled="disabled">}.
	 * Playwright treats an element as natively disabled only for
	 * BUTTON/INPUT/SELECT/TEXTAREA/OPTION/OPTGROUP, and ZK sets no
	 * {@code aria-disabled}, so {@code isEnabled()} on that anchor is
	 * unconditionally {@code true}.
	 *
	 * <p>That is not a cosmetic bug. With {@code isEnabled()}, this poll can
	 * never return {@code accepted}: it exhausts its budget and returns
	 * {@code rejected-save-still-enabled}, which is byte-identical to the frozen
	 * headline answer for the conflicting save. A dialect that cannot see save
	 * settlement would therefore emit the expected value for the single most
	 * important fact in the oracle.
	 *
	 * <p>What keeps that from being a silent false pass, now and after any later
	 * change here, is that {@link #save} hard-fails on any non-accepted outcome
	 * and runs twice -- on create and on update -- BEFORE the conflicting save
	 * is ever attempted. A detection fault therefore fails loudly at the first
	 * save rather than quietly at the last. The structural property matters more
	 * than this method being right: it is what makes a wrong reading a red lane
	 * instead of a green one.
	 */
	private boolean saveSettled(Page page) {
		Locator saveButton = toolbarButton(page, "Save changes");
		if (saveButton.count() == 0) {
			return false;
		}
		Object disabled = saveButton.evaluate(
				"element => element.hasAttribute('disabled')"
						+ " || element.getAttribute('aria-disabled') === 'true'"
						+ " || /(^|[\\s-])disabled([\\s-]|$)/.test(element.className || '')"
						+ " || /(^|[\\s-])disd([\\s-]|$)/.test(element.className || '')");
		return Boolean.TRUE.equals(disabled);
	}

	private String editorClasses(Page page, String column) {
		String classes = columnInput(page, column).getAttribute("class");
		return classes == null ? "" : classes;
	}

	@Override
	public void save(Page page) {
		String outcome = awaitSaveOutcome(page);
		assertEquals("accepted", outcome, "the record was not saved: " + outcome);
	}

	@Override
	public String attemptSave(Page page) {
		return awaitSaveOutcome(page);
	}

	private String awaitSaveOutcome(Page page) {
		click(page, "Save changes");
		return pollSaveOutcome(page);
	}

	/**
	 * Waits for an already-clicked Save to settle into an outcome.
	 *
	 * <p>Polled rather than read once, and the reason is sharper here than in
	 * the legacy dialect. ZK CE 10 replaced ZK 3.6's Comet transport with
	 * polling, so the interval between the click and the runtime's answer is
	 * governed by a poll period rather than by a held-open channel. A driver
	 * that waited for one {@code /zkau} response and then read the toolbar would
	 * routinely read the pre-save state -- and would report the headline
	 * conflicting save as refused when the runtime had simply not answered yet.
	 *
	 * <p>Exhausting the budget is an outcome, not a failure. A genuinely refused
	 * save never settles, and only {@link #save} treats a non-accepted outcome
	 * as an error.
	 */
	private String pollSaveOutcome(Page page) {
		// The Save control must EXIST before its state is polled. Without this,
		// a window whose toolbar failed to render polls a locator that matches
		// nothing for thirty seconds and then reports the product's refusal --
		// a missing toolbar and a refused save would be indistinguishable, and
		// the refusal is a frozen fact.
		toolbarButton(page, "Save changes").waitFor();
		Locator error = modalDialog(page);
		long deadline = System.nanoTime() + SAVE_SETTLE.toNanos();
		do {
			if (error.count() > 0) {
				return "error-dialog\t"
						+ BrowserSemanticContract.normalizedText(error.first().innerText());
			}
			if (saveSettled(page)) {
				return "accepted";
			}
			page.waitForTimeout(250);
		} while (System.nanoTime() < deadline);
		// The SAME vocabulary the legacy dialect uses, deliberately. The outcome
		// string is part of the compared answer, so a modern-only token would
		// fail parity even where the two runtimes behave identically.
		return "rejected-save-still-enabled";
	}

	/**
	 * Saves once, then re-issues that save's own {@code /zkau} request verbatim.
	 *
	 * <p>Bound to the Save control's own component id, not to the first
	 * {@code /zkau} POST after the click, for the reason the legacy dialect
	 * documents at length: replaying a field assignment is idempotent, so the
	 * step would report a benign status and an empty effect while claiming to
	 * have measured a duplicate submission.
	 *
	 * <p>This step is the one most likely to diverge for a real product reason
	 * rather than a rendering one. ZK's desync and command-sequence handling is
	 * transport-coupled, and the transport changed. That is exactly why 5g-1a-x
	 * froze the legacy answer before this dialect existed.
	 */
	@Override
	public String replaySave(Page page) {
		Locator saveButton = toolbarButton(page, "Save changes");
		saveButton.waitFor();
		String saveComponentId = saveButton.getAttribute("id");
		assertNotNull(saveComponentId,
				"the Save control carries no ZK component id, so the replayed request "
						+ "could not be bound to the save command");

		Request saveRequest = page.waitForRequest(
				request -> request.url().contains("/zkau")
						&& "POST".equals(request.method())
						&& request.postData() != null
						&& request.postData().contains(saveComponentId),
				() -> click(page, "Save changes"));
		assertEquals("accepted", pollSaveOutcome(page),
				"the duplicate-submit step's first save was not accepted, so the "
						+ "replay would not have been a duplicate of a real write");

		String body = saveRequest.postData();
		assertNotNull(body, "the save round trip carried no request body to replay");

		RequestOptions replay = RequestOptions.create()
				.setHeader("content-type", contentTypeOf(saveRequest))
				.setHeader("referer", page.url())
				.setData(body);
		APIResponse replayed = page.request().post(saveRequest.url(), replay);
		String status = Integer.toString(replayed.status());
		replayed.dispose();
		return status;
	}

	private String contentTypeOf(Request request) {
		String declared = request.headers().get("content-type");
		return declared == null
				? "application/x-www-form-urlencoded;charset=UTF-8"
				: declared;
	}

	@Override
	public void focusRecord(Page page, String value) {
		Locator valueField = columnInput(page, "Value");
		valueField.waitFor();
		if (value.equals(valueField.inputValue())) {
			return;
		}
		reloadRecord(page, value);
	}

	/**
	 * Commits a Find-dialog search key and proves the commit reached the server.
	 *
	 * <p>The dialect may express how a control is operated and AWAITED, and this
	 * is that: {@code Tab} blurs the field, which fires the editor's
	 * {@code onChange} as its own AU request, and FindWindow does not hold the
	 * value until that request is applied server-side.
	 *
	 * <p>The legacy dialect types the key and clicks Ok inside a single
	 * {@code /zkau} wait. Under ZK 3.6 that is enough. Under ZK 10 it is not,
	 * for two reasons, and runs 33589524866 and 33591572610 both died on the
	 * same symptom -- {@code expected: <P5G1A-0001> but was:
	 * <Chemical Product, inc>}, with the diagnostic probe showing
	 * {@code Data requeried 1/18}, which is an UNFILTERED query landing on the
	 * first record rather than a missing one.
	 *
	 * <p>First, a bare {@code /zkau} predicate does not identify the request it
	 * is waiting for. ZK 10 keeps its own {@code /zkau} traffic in flight --
	 * polling, echoes, timers -- so the wait can be satisfied by a request that
	 * carries nothing of ours while the Ok click has already been dispatched
	 * against a dialog whose query field is still empty server-side.
	 *
	 * <p>Second, and worse, an uncommitted key fails SILENTLY: the query simply
	 * runs unfiltered and the window opens on someone else's record, which is
	 * only noticed later, somewhere else, as a wrong-record assertion.
	 *
	 * <p>So wait for a response whose REQUEST carried this value. That is direct
	 * evidence the server received the key, it cannot be satisfied by unrelated
	 * traffic, and when the key genuinely never leaves the browser it fails here
	 * with that named cause instead of silently opening the wrong record.
	 *
	 * <p>This changes no step, no emitted fact and no outcome vocabulary. It
	 * only stops the driver from reading the dialog before the product has
	 * finished updating it, and stops a driver defect from being mistaken for a
	 * product divergence.
	 */
	private void commitSearchKey(Page page, Locator search, String value) {
		search.waitFor();
		search.fill(value);
		assertEquals(value, search.inputValue(),
				"the Find dialog's search key did not accept the typed value");
		// Whether the keystroke itself completed. A press that fails its own
		// actionability checks -- a re-rendered dialog, an overlay -- raises the
		// same TimeoutError as an unanswered wait, and reporting that as "the
		// key was never sent" would assert a cause nobody observed.
		boolean[] pressed = {false};
		String[] committed = {null};
		try {
			page.waitForResponse(
					response -> {
						if (!response.request().url().contains("/zkau")
								|| !response.ok()) {
							return false;
						}
						String target = changedWidget(response.request().postData(), value);
						if (target == null) {
							return false;
						}
						committed[0] = target;
						return true;
					},
					new Page.WaitForResponseOptions().setTimeout(FIELD_SETTLE.toMillis()),
					() -> {
						search.press("Tab");
						pressed[0] = true;
					});
		} catch (TimeoutError timedOut) {
			if (!pressed[0]) {
				throw timedOut;
			}
			throw new AssertionError("no accepted /zkau onChange carrying the Find"
					+ " dialog's search key '" + value + "' was observed, so the"
					+ " server never stored the criterion and the lookup would"
					+ " have queried unfiltered", timedOut);
		}

		// WHICH widget the server was told about. The command and the value are
		// not enough on their own: an onChange carrying the right text, sent for
		// the wrong component, is stored somewhere FindWindow never reads, and
		// the lookup then queries unfiltered with every earlier check satisfied
		// -- which is exactly the state run 33631958003 reached.
		//
		// The comparison is possible because a ZK widget's own node carries its
		// uuid as the element id (zk.jar!/web/js/zk/widget.ts, domAttrs_) and a
		// subordinate node carries uuid + "-" + subId. An input is whichever of
		// the two the mold emits -- getInputNode() is $n('real') ?? $n()
		// (zul.jar!/web/js/zul/inp/InputWidget.ts) -- and a uuid can never
		// contain a hyphen, because ComponentsCtrl.checkUuid rejects one. So the
		// prefix up to the first hyphen is the addressable widget either way,
		// and a mismatch names a driver defect instead of leaving it
		// indistinguishable from a product one.
		String filled = search.getAttribute("id");
		assertEquals(uuidOf(filled), committed[0],
				"the Find dialog's search key was committed for a different"
						+ " widget than the one the driver filled");
	}

	/**
	 * The element id of a ZK input, reduced to its owning widget's uuid.
	 *
	 * <p>ZK suffixes the ids of a widget's subordinate DOM nodes with
	 * {@code -<name>}; the uuid itself never contains one, so trimming at the
	 * first hyphen after the uuid yields the addressable widget.
	 */
	private String uuidOf(String elementId) {
		if (elementId == null) {
			return null;
		}
		int suffix = elementId.indexOf('-');
		return suffix < 0 ? elementId : elementId.substring(0, suffix);
	}

	/**
	 * The uuid of the widget an AU request body reports an {@code onChange} for,
	 * when that change carries {@code value}, or {@code null} when the body
	 * contains no such change.
	 *
	 * <p>Matching the body for the value alone is not the proof it looks like.
	 * ZK CE 10 batches an AU request as {@code cmd_N} / {@code uuid_N} /
	 * {@code data_N} triples ({@code zk.jar!/web/js/zk/au.ts:857-870}), and
	 * several commands carry an input's text without applying it to the
	 * component on the server -- {@code onChanging} exists precisely to report a
	 * value that is not committed. A predicate satisfied by any of them would
	 * certify a key the server never stored, which is the failure this guard was
	 * added to make impossible.
	 *
	 * <p>So the command is paired with its own datum by index, and only
	 * {@code onChange} counts. {@code updateChange_} is what emits it
	 * ({@code zul.jar!/web/js/zul/inp/InputWidget.ts}), and {@code onChange} is
	 * what applies the typed text to the server-side input. That is the
	 * transition the dialog's query depends on: {@code FindWindow} does not
	 * listen for it -- its {@code hasValue} family sits inside a block comment
	 * ({@code FindWindow.java:631-641}), so the visible Search Key is a
	 * selection-column {@code WEditor} registering only {@code ON_OK} -- and
	 * {@code cmd_ok_Simple} reads the criterion back at Ok time with
	 * {@code wed.getValue()}. An uncommitted {@code onChange} therefore leaves
	 * that read stale, and the query unfiltered, with nothing else to show for
	 * it.
	 *
	 * <p>Each datum is {@code encodeURIComponent}-encoded, so it is decoded
	 * before matching. That keeps the proof independent of the fixture's
	 * character class rather than of this particular search key.
	 *
	 * <p>The uuid is returned rather than a boolean so the caller can check the
	 * change was reported for the field it filled. A body that says only "some
	 * widget committed this text" cannot distinguish a driver that typed into
	 * the wrong editor from a product that ignored the right one.
	 */
	private String changedWidget(String body, String value) {
		if (body == null) {
			return null;
		}
		Map<String, String> commands = new HashMap<>();
		Map<String, String> data = new HashMap<>();
		Map<String, String> targets = new HashMap<>();
		for (String field : body.split("&")) {
			int split = field.indexOf('=');
			if (split < 0) {
				continue;
			}
			String name = field.substring(0, split);
			String raw = field.substring(split + 1);
			if (name.endsWith("cmd_" + suffix(name))) {
				commands.put(suffix(name), decoded(raw));
			} else if (name.endsWith("data_" + suffix(name))) {
				data.put(suffix(name), decoded(raw));
			} else if (name.endsWith("uuid_" + suffix(name))) {
				targets.put(suffix(name), decoded(raw));
			}
		}
		return commands.entrySet().stream()
				.filter(entry -> "onChange".equals(entry.getValue())
						&& data.getOrDefault(entry.getKey(), "").contains(value))
				.map(entry -> targets.get(entry.getKey()))
				// Stream.findFirst() throws on a null element, and uuid_N is
				// omitted for a desktop-targeted command (zk.jar!/web/js/zk/au.ts).
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	/** The batch index of an AU field name such as {@code cmd_3} or {@code data_3}. */
	private String suffix(String name) {
		int underscore = name.lastIndexOf('_');
		return underscore < 0 ? "" : name.substring(underscore + 1);
	}

	private String decoded(String raw) {
		try {
			return URLDecoder.decode(raw, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException malformed) {
			return raw;
		}
	}

	private void reloadRecord(Page page, String value) {
		click(page, "Lookup Record");
		page.getByText(FIND_TITLE_PREFIX + WINDOW).first().waitFor();
		Locator dialog = findDialog(page);
		Locator search = searchKeyField(dialog);
		commitSearchKey(page, search, value);
		// Read the dialog's own state while it still exists. If the lookup comes
		// back on the wrong record the dialog is already gone, and without this
		// the failure cannot say whether the criterion was ever there.
		String dialogState = "modal candidates=" + modalDialog(page).count()
				+ ", captioned=" + captionedDialogs(page).count()
				+ ", committed criterion=" + quoted(search.inputValue());
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> dialog.locator(OK_BUTTON).first().click());
		dialog.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
		assertEquals(value, columnInput(page, "Value").inputValue(),
				"the window is not positioned on the captured record ("
						+ dialogState + ")");
	}

	private String quoted(String value) {
		return value == null ? "null" : "'" + value + "'";
	}

	/**
	 * Chooses a value in a lookup combo, by column.
	 *
	 * <p>The legacy dialect reaches the popup through ZK 3.6's derived
	 * {@code !pp} and {@code !btn} ids. ZK 10 derives neither, so this drives
	 * the combo through its own editor: type the label and commit it, which is
	 * the path the keyboard takes and which the legacy dialect already uses as
	 * its fallback.
	 *
	 * <p>The assertion is unchanged and is the part that matters. A combo that
	 * shows the right text without having selected the value behind it puts the
	 * record in the wrong organisation -- and that surfaces later as a read-only
	 * form for the second editor rather than as an error here, so it is asserted
	 * on the spot.
	 */
	@Override
	public void selectCombo(Page page, String column, String label) {
		Locator input = columnInput(page, column);
		input.waitFor();
		input.fill(label);
		input.press("Enter");

		long deadline = System.nanoTime() + COMBO_OPEN.toNanos();
		while (!label.equals(columnInput(page, column).inputValue())
				&& System.nanoTime() < deadline) {
			page.waitForTimeout(250);
		}
		assertEquals(label, columnInput(page, column).inputValue(),
				"the combo for column " + column + " did not take '" + label + "'");
	}

	/**
	 * The editor for a dictionary column.
	 *
	 * <p>Resolved by COLUMN, never by proximity, for the reason the legacy
	 * dialect records: a proximity rule that drifts onto a neighbouring field
	 * fails silently, as a plausible wrong record rather than as an error.
	 *
	 * <p>{@code WEditor.java:127-132} asks for
	 * {@code unqField_<window>_<tab>_<table>_<column>}, and that request is
	 * ADempiere's, not ZK's -- the Phase 5d modern slice already resolved
	 * {@code [id*='_AD_Error_']} editors on this runtime. The caption fallback
	 * exists because the request is only honoured for some editor types, which
	 * is a property of ADempiere's own component tree and so is expected to be
	 * the same here.
	 */
	private Locator columnInput(Page page, String column) {
		Locator byId = tabPanel(page).locator(
				"xpath=.//input[contains(@id,'_" + TABLE + "_" + column + "')]"
						+ "[not(@type='hidden')]");
		int byIdCount = byId.count();
		assertTrue(byIdCount <= 1,
				"column " + column + " matched " + byIdCount + " editor ids");
		if (byIdCount == 1) {
			return byId.first();
		}

		String caption = CAPTIONS.get(column);
		assertNotNull(caption,
				"column " + column + " carries no editor id and no declared caption");
		Locator byCaption = tabPanel(page).locator(
				"xpath=.//div[contains(@class,'field-label')]"
						+ "/*[normalize-space(text())='" + caption + "']"
						+ "/ancestor::td[1]/following-sibling::td[1]"
						+ "//input[not(@type='hidden')]");
		int byCaptionCount = byCaption.count();
		assertEquals(1, byCaptionCount, "the caption '" + caption + "' for column "
				+ column + " resolved to " + byCaptionCount + " editors");
		return byCaption.first();
	}

	private void clickAwaitingServer(Page page, String title) {
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> click(page, title));
	}

	private void click(Page page, String title) {
		Locator button = toolbarButton(page, title);
		button.waitFor();
		button.click();
	}

	/**
	 * A record-toolbar control, addressed by its ADempiere tooltip.
	 *
	 * <p>By title alone, without ZK 3.6's {@code a.toolbar-button} class. The
	 * title is a translated AD_Message the product sets; the anchor class is
	 * ZK's rendering of a Toolbarbutton and changed with the version. The Phase
	 * 5d modern slice already located {@code [title='Delete record']} inside
	 * {@code div.desktop-tabpanel} on this runtime this way.
	 */
	private Locator toolbarButton(Page page, String title) {
		return tabPanel(page).locator("[title='" + title + "']").first();
	}

	/**
	 * The open window's content panel.
	 *
	 * <p>Filtered by the window's own editors rather than by the tab caption:
	 * the caption lives in the desktop's tab bar, outside every content panel.
	 * The Phase 5d slice used the same shape with {@code [id*='_AD_Error_']}.
	 * Filtering on the editor ids rather than on the Save control also means a
	 * window whose toolbar failed to render is diagnosed as a missing toolbar
	 * rather than as a missing window.
	 */
	private Locator tabPanel(Page page) {
		return page.locator("div.desktop-tabpanel")
				.filter(new Locator.FilterOptions()
						.setHas(page.locator("[id*='_" + TABLE + "_']")))
				.first();
	}

	/**
	 * Records what the page actually contained when a step failed.
	 *
	 * <p>The probes are chosen for THIS runtime's unknowns. Where the legacy
	 * dialect dumps {@code tr.z-combo-item} and {@code z.label}, this dumps the
	 * class names ZK 10 actually emitted around the combo, the modal and the
	 * toolbar -- because the open question here is not "which row" but "what did
	 * ZK CE 10 call it". Each probe is evaluated and recorded independently, so
	 * one malformed expression cannot cost a whole lane round trip.
	 */
	@Override
	public void captureDiagnostics(Page page, Path evidenceDir, String label) {
		if (page == null) {
			return;
		}
		try {
			page.screenshot(new Page.ScreenshotOptions()
					.setPath(evidenceDir.resolve("failure-" + label + ".png"))
					.setFullPage(true));
		} catch (RuntimeException ignored) {
			// A screenshot is a convenience; the textual dump below is the
			// evidence that actually names a wrong selector.
		}
		Map<String, String> probes = new LinkedHashMap<>();
		probes.put("url", "() => location.href");
		probes.put("tabs", "() => Array.from(document.querySelectorAll("
				+ "'[class*=\"tab-text\"], [class*=\"z-tab\"]'))"
				+ ".slice(0, 60).map(e => e.className + ':' + e.textContent).join('|')");
		probes.put("modals", "() => Array.from(document.querySelectorAll("
				+ "'[class*=\"z-window\"], [class*=\"popup\"]'))"
				+ ".slice(0, 40).map(e => e.className + ':'"
				+ " + e.textContent.slice(0, 120)).join('|')");
		probes.put("titles", "() => Array.from(document.querySelectorAll('[title]'))"
				+ ".map(e => e.tagName.toLowerCase() + '[' + e.className + ']='"
				+ " + e.getAttribute('title')).join('|')");
		probes.put("editorIds",
				"() => Array.from(document.querySelectorAll(\"[id*='_C_BPartner_']\"))"
						+ ".map(e => e.tagName.toLowerCase() + '#' + e.id"
						+ " + '[' + e.className + ']').join('|')");
		// The combo is where ZK 10's vocabulary is least predictable, so the
		// probe reports the DOM around the organisation editor rather than
		// guessing at a popup selector.
		probes.put("orgCombo",
				"() => Array.from(document.querySelectorAll("
						+ "\"[id*='_C_BPartner_AD_Org_ID']\"))"
						+ ".map(e => e.tagName.toLowerCase() + '#' + e.id + '['"
						+ " + e.className + ']=' + (e.value || '')).join('|')");
		probes.put("comboPopups",
				"() => Array.from(document.querySelectorAll("
						+ "'[class*=\"comboitem\"], [class*=\"combo-item\"]'))"
						+ ".slice(0, 60).map(e => e.className + ':'"
						+ " + e.textContent.trim()).join('|')");
		probes.put("saveButton",
				"() => Array.from(document.querySelectorAll(\"[title='Save changes']\"))"
						+ ".map(e => e.tagName.toLowerCase() + '#' + (e.id || '?') + '['"
						+ " + e.className + ']attrDisabled=' + e.hasAttribute('disabled')"
						+ " + ' aria=' + e.getAttribute('aria-disabled')).join('|')");
		// Run 33653427475 clicked an enabled Save control and the server logged
		// no event-carrying /zkau request. That is only explicable client-side,
		// so record what the control actually is: its markup, and whether ZK
		// still has a live widget bound to it that would have sent the event.
		//
		// Read from the widget, not from a single predicate. isListen('onClick')
		// alone cannot answer the question: with no options it also returns true
		// for a purely client-side listener, and false for a server listener
		// registered as deferrable -- which fireX still sends. _asaps['onClick'],
		// the asapOnly form, and inServer are recorded alongside it because
		// fireX gates on all of them.
		probes.put("saveControl", "() => {"
				+ " const e = document.querySelector(\"[title='Save changes']\");"
				+ " if (!e) return 'no-save-control';"
				+ " const html = (e.outerHTML || '').slice(0, 600);"
				+ " let w = 'no-zk';"
				+ " try {"
				+ "  if (window.zk && zk.Widget && zk.Widget.$) {"
				+ "   const g = zk.Widget.$(e, {exact: true});"
				+ "   w = g ? ('uuid=' + g.uuid + ' matchesId=' + (g.uuid === e.id)"
				+ "        + ' class=' + g.className"
				+ "        + ' disabled=' + g._disabled + ' desktop=' + (g.desktop ? 'yes' : 'no')"
				+ "        + ' inServer=' + g.inServer"
				+ "        + ' asapsClick=' + (g._asaps ? g._asaps['onClick'] : 'no-asaps')"
				+ "        + ' listensClickAsap=' + (g.isListen"
				+ "           ? g.isListen('onClick', {asapOnly: true}) : '?')"
				+ "        + ' listensClickAny=' + (g.isListen ? g.isListen('onClick') : '?')"
				+ "        + ' autodisable=' + g._autodisable)"
				+ "     : 'no-widget-bound';"
				+ "  }"
				+ " } catch (err) { w = 'widget-probe-threw:' + err; }"
				+ " return w + ' || ' + html;"
				+ "}");
		// Whether the ZK client is still alive at all. Note what this cannot
		// prove: a client that died mid-session still has zk and zAu defined,
		// so their presence refutes nothing. The load-bearing readings are the
		// processing/mounting flags and the console log now preserved on the
		// failure path.
		probes.put("zkClientState", "() => {"
				+ " const bits = [];"
				+ " bits.push('zk=' + (typeof window.zk));"
				+ " bits.push('zAu=' + (typeof window.zAu));"
				+ " try { bits.push('processing=' + (window.zk && zk.processing)); }"
				+ "  catch (err) { bits.push('processing-threw'); }"
				+ " try { bits.push('mounting=' + (window.zk && zk.mounting)); }"
				+ "  catch (err) { bits.push('mounting-threw'); }"
				+ " bits.push('errorBoxes=' + document.querySelectorAll("
				+ "  '.z-error, .z-messagebox-window, .z-loading').length);"
				+ " return bits.join(' ');"
				+ "}");
		probes.put("tabPanels", "() => Array.from(document.querySelectorAll("
				+ "'div.desktop-tabpanel')).map(e => (e.id || '?') + '#'"
				+ " + e.className + '#titled=' + e.querySelectorAll('[title]').length"
				+ " + '#editors=' + e.querySelectorAll(\"[id*='_C_BPartner_']\").length"
				+ ").join('|')");
		probes.put("fieldLabels", "() => Array.from(document.querySelectorAll("
				+ "'[class*=\"field-label\"]')).slice(0, 200)"
				+ ".map(e => e.className + ':' + e.textContent.trim()).join('|')");
		// Geometry, so an intercepted click is diagnosable from this dump alone.
		// Playwright reports only the class of whatever it hit; that names a
		// container without saying whether the target was collapsed, positioned
		// outside its scroll parent, or genuinely covered. Record each editor's
		// own client rect and what the document returns at its centre point.
		probes.put("editorGeometry", "() => Array.from(document.querySelectorAll("
				+ "\"[id*='_C_BPartner_'][id$='-real'], [id*='_C_BPartner_']\"))"
				+ ".filter(e => e.tagName === 'INPUT' || e.tagName === 'SPAN')"
				+ ".slice(0, 40).map(e => {"
				+ " const r = e.getBoundingClientRect();"
				+ " const cx = r.left + r.width / 2, cy = r.top + r.height / 2;"
				+ " const hit = document.elementFromPoint(cx, cy);"
				+ " return (e.id || '?') + '[' + Math.round(r.left) + ',' + Math.round(r.top)"
				+ "  + ',' + Math.round(r.width) + 'x' + Math.round(r.height) + ']'"
				+ "  + '@' + Math.round(cx) + ',' + Math.round(cy)"
				+ "  + '->' + (hit ? (hit.id || hit.tagName) + '.' + hit.className : 'null');"
				+ "}).join('|')");
		// Which box collapsed is the open question, so walk the whole ancestor
		// chain from one editor to <body> rather than sampling a fixed list of
		// container classes: the grid's internal mesh (z-grid-body, z-rows,
		// z-row, z-cell) is on that chain and is not otherwise observed. The
		// computed display/flex state is recorded because whether ZK CE 10
		// treated a box as a CSS flex item is itself in dispute.
		probes.put("editorAncestry", "() => {"
				+ " const seed = document.querySelector(\"[id*='_C_BPartner_'][id$='-real']\")"
				+ "  || document.querySelector(\"[id*='_C_BPartner_']\");"
				+ " if (!seed) return 'no-editor';"
				+ " const out = []; let e = seed;"
				+ " while (e && e.nodeType === 1 && out.length < 40) {"
				+ "  const r = e.getBoundingClientRect(); const s = getComputedStyle(e);"
				+ "  out.push((e.id || e.tagName) + '#' + (e.className || '-')"
				+ "   + '[' + Math.round(r.width) + 'x' + Math.round(r.height) + ']'"
				+ "   + ' pos=' + s.position + ' disp=' + s.display + ' h=' + s.height"
				+ "   + ' fg=' + s.flexGrow + ' fb=' + s.flexBasis + ' of=' + s.overflow);"
				+ "  e = e.parentElement;"
				+ " }"
				+ " return out.join('|');"
				+ "}");
		probes.put("gridGeometry", "() => Array.from(document.querySelectorAll("
				+ "'div.z-grid, div.adtab-content, div.z-center-body, div.desktop-tabpanel'))"
				+ ".slice(0, 40).map(e => { const r = e.getBoundingClientRect();"
				+ " const s = getComputedStyle(e);"
				+ " return (e.id || '?') + '#' + e.className"
				+ "  + '[' + Math.round(r.left) + ',' + Math.round(r.top)"
				+ "  + ',' + Math.round(r.width) + 'x' + Math.round(r.height) + ']'"
				+ "  + ' pos=' + s.position + ' disp=' + s.display + ' h=' + s.height"
				+ "  + ' fg=' + s.flexGrow + ' of=' + s.overflow"
				+ "  + ' z=' + s.zIndex;"
				+ "}).join('|')");
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, String> probe : probes.entrySet()) {
			String observed;
			try {
				observed = String.valueOf(page.evaluate(probe.getValue()));
			} catch (RuntimeException probeFailure) {
				observed = "<probe failed: " + probeFailure + ">";
			}
			lines.add(probe.getKey() + "\t" + observed.replace("\n", " "));
		}
		try {
			Files.write(evidenceDir.resolve("failure-" + label + ".tsv"), lines,
					StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Nothing further can be reported; the screenshot still stands.
		}
	}
}
