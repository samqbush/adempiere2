package org.adempiere.webui.phase5g;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * The ZK 3.6 dialect, as captured against the installed Tomcat 9 legacy
 * product.
 *
 * <h2>This class is closed</h2>
 *
 * <p>Every selector, wait and fallback here was established by a specific
 * failing CI run, and the comments name those runs because the reasoning is not
 * recoverable from the code. This dialect froze the Phase 5g-1a oracle, so
 * changing it changes the meaning of an already-reviewed answer.
 *
 * <p>Modern work therefore adds a sibling implementation of {@link ZkDialect}.
 * It does not edit this one. If a shared behaviour genuinely has to move, it
 * moves into {@link BusinessPartnerWriteFlow} for both runtimes at once, and
 * the legacy freeze-off regression re-proves the legacy answer at that commit.
 */
public final class Zk36Dialect implements ZkDialect {

	/**
	 * How long a save is given to settle. A backstop against a hang, not a
	 * performance expectation; a genuinely refused save spends all of it.
	 */
	private static final Duration SAVE_SETTLE = Duration.ofSeconds(30);

	/** The same backstop for a single field edit. */
	private static final Duration FIELD_SETTLE = Duration.ofSeconds(15);

	/** How long a lookup drop-down is given to open, and its choice to commit. */
	private static final Duration COMBO_OPEN = Duration.ofSeconds(10);

	private static final String WINDOW = "Business Partner";

	/** Editors carry ids of the form {@code unqField_<tab>_<row>_<Table>_<Column><n>}. */
	private static final String TABLE = "C_BPartner";

	/**
	 * Captions for the columns ADempiere does not give a column-named id.
	 *
	 * <p>Declared, not guessed: an undeclared column fails rather than falling
	 * back to a proximity rule. {@code C_BPartner.Value} is captioned "Search
	 * Key" in the dictionary and "Value" matches no cell in the rendered form.
	 */
	private static final Map<String, String> CAPTIONS =
			Map.of("Value", "Search Key", "Name", "Name");

	/** {@code FindWindow.java:274} titles the dialog {@code Msg("Find") + ": " + window}. */
	private static final String FIND_TITLE_PREFIX = "Lookup Record: ";

	/** Accepts either message spelling; see enterWindowThroughFindDialog. */
	private static final String OK_BUTTON = "[title='Ok']:visible, [title='OK']:visible";

	@Override
	public String id() {
		return "zk-3.6-tomcat-9";
	}

	@Override
	public BrowserContext newContext(Browser browser, String baseUrl) {
		BrowserContext context = LegacyBrowserFlow.newContext(browser);
		LegacyBrowserFlow.blockForeignOrigins(context, baseUrl);
		return context;
	}

	@Override
	public void recordTraffic(Page page, List<String> requests, List<String> errors,
			UnaryOperator<String> normalizer) {
		LegacyBrowserFlow.recordTraffic(page, requests, errors, normalizer);
	}

	@Override
	public void signIn(Page page, String baseUrl, String user, String password, String client,
			String sessionLabel) {
		Response login = LegacyBrowserFlow.login(page, baseUrl, user, password);
		assertEquals(200, login.status(),
				"the " + sessionLabel + " login page did not respond 200");
		LegacyBrowserFlow.awaitRolePanel(page, BrowserSemanticContract::normalizedText);
		LegacyBrowserFlow.confirmRole(page);
		LegacyBrowserFlow.awaitDesktop(page, user, client);
	}

	@Override
	public void logout(Page page) {
		LegacyBrowserFlow.logout(page);
	}

	@Override
	public String expectedRuntime() {
		return "legacy";
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
	@Override
	public void openWindow(Page page, String searchKey) {
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
		// ZK 3.6 does NOT render the window tab on selection. It opens the modal
		// "Lookup Record" dialog first (FindWindow.java:274), and the tab only
		// appears once that dialog is answered. Waiting for the tab straight
		// after the menu selection therefore always timed out, thirty seconds
		// behind a dialog that was sitting there waiting for input.
		enterWindowThroughFindDialog(page, searchKey);
		page.locator("span.z-tab-text:text-is('" + WINDOW + "')").first().waitFor();
		tabPanel(page).waitFor();
	}

	/**
	 * Answers the mandatory "Lookup Record" dialog that stands between the menu
	 * selection and the rendered window.
	 *
	 * @param searchKey the record to load, or {@code null} to enter the window
	 *                  on an unconstrained query.
	 *
	 * <p>{@code null} must NOT be answered by cancelling. Run 33471012956
	 * proved cancelling aborts opening the window altogether: the dialog closed,
	 * no modal remained, and the desktop was left holding its single
	 * {@code Menu (1)} tab. Cancel means "never mind", not "open it empty".
	 * The create step therefore runs the query the way a user would and then
	 * uses the window's own New Record action.
	 */
	private void enterWindowThroughFindDialog(Page page, String searchKey) {
		Locator dialog = page.locator("div.z-window-modal").first();
		dialog.waitFor();
		// Assert WHICH dialog. A modal is not self-identifying, and answering
		// an unexpected one would be an undiagnosable divergence later.
		page.getByText(FIND_TITLE_PREFIX + WINDOW).first().waitFor();
		// The dialog's controls are ConfirmPanel actions built by WAppsAction,
		// which sets a tooltip and an image and clears the label
		// (WAppsAction.java:96-106). They therefore carry a title attribute and
		// NO text.
		//
		// The title is "Ok", NOT "OK". The role panel's confirmation really is
		// "OK" (LegacyBrowserFlow.confirmRole), so the two are not
		// interchangeable -- run 33471818401 timed out on `[title='OK']` while
		// its own probe recorded the dialog offering "New", "Cancel" and "Ok".
		// Both spellings are accepted here so neither message key can break the
		// capture.
		//
		// :visible is required, not decorative. FindWindow builds a second
		// confirmation pair for the Advanced tab (FindWindow.java:435-441), so
		// an unfiltered .first() can resolve to a button nobody can click.
		if (searchKey != null) {
			Locator search = dialog
					.locator("xpath=.//td[normalize-space(.)='Search Key']"
							+ "/following-sibling::td[1]//input")
					.first();
			search.waitFor();
			search.fill(searchKey);
			search.press("Tab");
		}
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> dialog.locator(OK_BUTTON).first().click());
		dialog.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
	}

	/**
	 * Starts a new record.
	 *
	 * <p>The New Record round trip must COMPLETE before anything is typed. Run
	 * 33477382192 clicked New and filled immediately; ZK's re-render of the
	 * freshly inserted row then landed between the two fills and wiped the
	 * first one, so the record reached save with an empty Search Key and a
	 * correct Name. That is the worst failure mode available to an oracle --
	 * it is not an error, it is a plausible wrong answer -- and it is
	 * intermittent, which is why run 33475295851 created the record
	 * successfully with the same code.
	 */
	@Override
	public void newRecord(Page page) {
		clickAwaitingServer(page, "New Record");
	}

	/**
	 * Clears the Active control.
	 *
	 * <p>Addressed by column, like every other editor. The caption-based rule
	 * this replaced was doubly unsafe here: the Active box PRECEDES its
	 * caption, so a document-order rule walks past it into the next field
	 * and clears the wrong control without erroring.
	 */
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

	/**
	 * Types a value into a labelled field and lets ZK's change event fire.
	 *
	 * <p>The trailing {@code Tab} is not cosmetic. ZK 3.6 posts the field value on
	 * blur; saving without it would save the record without the value that was
	 * just typed, and the capture would record a real -- but wrong -- effect.
	 */
	@Override
	public void fill(Page page, String column, String value) {
		typeInto(page, column, value);
		if (!settledOn(page, column, value)) {
			// One retype absorbs a lost race with a ZK re-render. It cannot
			// absorb a product that refuses the edit, which is why the second
			// attempt is followed by a hard failure rather than another retry.
			typeInto(page, column, value);
		}
		assertTrue(settledOn(page, column, value),
				"the editor for column " + column + " did not take the typed value."
						+ " It holds '" + columnInput(page, column).inputValue()
						+ "' and its classes are '" + editorClasses(page, column) + "'");
	}

	/**
	 * Types into an editor and lets ZK see it.
	 *
	 * <p>Deliberately does NOT wait for a round trip. Run 33481777679 filled the
	 * second editor's Name and timed out waiting for one, while the field itself
	 * still held its previous value -- so the round trip was never the thing
	 * worth waiting for. ZK may answer the change event the fill itself
	 * dispatches, leaving the blur with nothing to send, and a driver that waits
	 * for a response it will never receive fails on a session that is perfectly
	 * healthy.
	 */
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
	 * <p>Both halves are needed. The value alone can sit in the browser without
	 * ZK having registered it, and a save then writes the previous value -- a
	 * wrong record that looks entirely plausible. The pending change alone does
	 * not say which value is pending.
	 */
	private boolean settledOn(Page page, String column, String value) {
		Locator saveButton = tabPanel(page)
				.locator("a.toolbar-button[title='Save changes']").first();
		long deadline = System.nanoTime() + FIELD_SETTLE.toNanos();
		do {
			String classes = saveButton.count() > 0 ? saveButton.getAttribute("class") : null;
			boolean pending = classes != null && !classes.contains("toolbar-button-disd");
			if (pending && value.equals(columnInput(page, column).inputValue())) {
				return true;
			}
			page.waitForTimeout(250);
		} while (System.nanoTime() < deadline);
		return false;
	}

	private String editorClasses(Page page, String column) {
		String classes = columnInput(page, column).getAttribute("class");
		return classes == null ? "" : classes;
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
	@Override
	public void save(Page page) {
		String outcome = awaitSaveOutcome(page);
		assertEquals("accepted", outcome, "the record was not saved: " + outcome);
	}

	/**
	 * Clicks Save and REPORTS what happened instead of requiring success.
	 *
	 * <p>Used only for the conflicting save. Every other save in the flow uses
	 * {@link #save(Page)}, because measuring an operation that silently did not
	 * happen would freeze an empty effect as the expected answer. Here the
	 * outcome is itself the fact being captured, so it is recorded, not judged.
	 */
	@Override
	public String attemptSave(Page page) {
		return awaitSaveOutcome(page);
	}

	/**
	 * Clicks Save and waits for the window to settle into a save outcome.
	 *
	 * <p>Polled, not read once. Waiting for a single {@code /zkau} response and
	 * then reading the toolbar reads whatever state the window happened to be in
	 * at that instant: ZK answers with several round trips, and the response the
	 * driver waited for can be one already in flight from the previous field's
	 * blur rather than the save's own. Run 33480634946 filled both fields
	 * correctly, saved, and observed "Inserted" -- the pre-save state -- because
	 * it looked before the save had landed.
	 *
	 * <p>This is the difference between a flaky driver and a wrong oracle. The
	 * same read backs the conflicting save, whose outcome is a captured fact
	 * this increment exists to record; reading it early would freeze "the
	 * product refused the save" when the product had simply not answered yet.
	 *
	 * <p>Exhausting the budget is itself an outcome, not a failure, because a
	 * save that is genuinely refused never settles. Only {@code save} treats a
	 * non-accepted outcome as an error.
	 */
	private String awaitSaveOutcome(Page page) {
		click(page, "Save changes");
		return pollSaveOutcome(page);
	}

	/**
	 * Waits for an already-clicked Save to settle into an outcome.
	 *
	 * <p>Split out of {@link #awaitSaveOutcome} so the duplicate-submit step can
	 * intercept the save's own {@code /zkau} request between the click and the
	 * settle. Every existing caller reaches it through {@code awaitSaveOutcome}
	 * and is unaffected.
	 */
	private String pollSaveOutcome(Page page) {
		Locator error = page.locator("div.z-window-modal, div.popup-error");
		Locator saveButton = tabPanel(page)
				.locator("a.toolbar-button[title='Save changes']").first();
		long deadline = System.nanoTime() + SAVE_SETTLE.toNanos();
		do {
			if (error.count() > 0) {
				return "error-dialog\t"
						+ BrowserSemanticContract.normalizedText(error.first().innerText());
			}
			if (saveButton.count() > 0) {
				String classes = saveButton.getAttribute("class");
				if ((classes != null && classes.contains("toolbar-button-disd"))
						|| !saveButton.isEnabled()) {
					return "accepted";
				}
			}
			page.waitForTimeout(250);
		} while (System.nanoTime() < deadline);
		return "rejected-save-still-enabled";
	}

	/**
	 * Saves once, then re-issues that save's own {@code /zkau} request verbatim.
	 *
	 * <p>This is the legacy answer to the question Phase 5g-1b's H6 matrix must
	 * ask and cannot invent: what does the product do when a non-idempotent save
	 * is submitted twice? A proxy retry, a double-click or a replayed request in
	 * the routed dual-runtime lane all produce exactly this shape, and the
	 * property it probes -- ZK's desync/sequence handling -- is one of the things
	 * that genuinely differs between ZK 3.6's Comet transport and ZK CE 10's
	 * polling transport. Phase 5e's replay coverage does not answer it: that
	 * covers the single-use T5e-1 handoff ticket, which is not a runtime browser
	 * request at all.
	 *
	 * <p>The replay goes through the page's own API request context, so it
	 * carries the same session cookies as the browser that issued the original.
	 * A replay from a fresh client would only prove that an unauthenticated
	 * request is rejected, which nobody doubts.
	 *
	 * <p>The replay's HTTP status is recorded; its response BODY deliberately is
	 * not. ZK echoes desktop and request ids that differ on every run, so
	 * freezing the body would freeze volatility and fail the A/B self-diff. The
	 * business answer -- whether the duplicate submission wrote a second time --
	 * is the step's database effect, which is measured at the step boundary and
	 * is not a fact this driver is allowed to assert.
	 */
	@Override
	public String replaySave(Page page) {
		// The replayed request is bound to the Save button's own ZK component id,
		// not merely to the first /zkau POST that follows the click.
		//
		// ZK 3.6 posts a field's onChange on blur, and clicking Save is what
		// blurs the Name editor. Whether ZK batches that blur into the same AU
		// request as the toolbar command or sends it first is queue- and
		// timing-dependent, so a first-match predicate can capture the field
		// update instead. Replaying a field assignment is idempotent, so the step
		// would report a benign status and an empty effect while claiming to have
		// measured a duplicate submission -- a plausible wrong answer, which is
		// the worst failure mode an oracle has.
		Locator saveButton = tabPanel(page)
				.locator("a.toolbar-button[title='Save changes']").first();
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

	/**
	 * The recorded request's own content type, so the replay is byte-for-byte the
	 * same submission rather than a guess at how ZK encodes one.
	 */
	private String contentTypeOf(Request request) {
		String declared = request.headers().get("content-type");
		return declared == null
				? "application/x-www-form-urlencoded;charset=UTF-8"
				: declared;
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
	 * Re-reads the record from the database through the window's own lookup,
	 * discarding whatever the session was holding.
	 */
	private void reloadRecord(Page page, String value) {
		click(page, "Lookup Record");
		Locator dialog = page.locator("div.z-window-modal").first();
		dialog.waitFor();
		Locator search = dialog
				.locator("xpath=.//td[normalize-space(.)='Search Key']"
						+ "/following-sibling::td[1]//input")
				.first();
		search.waitFor();
		search.fill(value);
		search.press("Tab");
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> dialog.locator(OK_BUTTON).first().click());
		dialog.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
		assertEquals(value, columnInput(page, "Value").inputValue(),
				"the window is not positioned on the captured record");
	}

	/**
	 * Chooses a value in a lookup combo, by column.
	 *
	 * <p>Addressed through the ids ZK derives from the editor's own id --
	 * {@code !btn} opens the drop-down and {@code !pp} is that combo's popup --
	 * so the item clicked is guaranteed to belong to this field. The rendered
	 * page carries the items of every combo on the form, and a global
	 * {@code tr.z-combo-item} match would happily click another field's.
	 */
	@Override
	public void selectCombo(Page page, String column, String label) {
		String marker = "[id*=\"_" + TABLE + "_" + column + "\"]";
		Locator popup = page.locator(marker + "[id$=\"!pp\"]");
		Locator item = popup.locator("xpath=.//tr[contains(@class,'z-combo-item')]"
				+ "[@z.label='" + label + "' or normalize-space(.)='" + label + "']");

		// Preferred path: open the drop-down and click the item, which is how a
		// user chooses an organisation.
		Locator button = tabPanel(page).locator(marker + "[id$=\"!btn\"]").first();
		button.waitFor();
		button.click();
		if (visibleWithin(popup, COMBO_OPEN) && item.count() == 1) {
			item.first().click();
		} else {
			// Fallback: type the label and commit it. Run 33485512079 clicked
			// the drop-down and its popup never became visible, while the
			// popup's own rows -- probed at the moment of failure -- carried
			// exactly the label being looked for. Typing reaches the same ZK
			// selection through the path the keyboard uses.
			Locator input = columnInput(page, column);
			input.fill(label);
			input.press("Enter");
		}

		// Asserted either way. A combo that shows the right text without having
		// selected the value behind it would put the record in the wrong
		// organisation, which is the specific defect this whole change exists to
		// remove -- and it would come back as a read-only form for the second
		// editor rather than as an error here.
		long deadline = System.nanoTime() + COMBO_OPEN.toNanos();
		while (!label.equals(columnInput(page, column).inputValue())
				&& System.nanoTime() < deadline) {
			page.waitForTimeout(250);
		}
		assertEquals(label, columnInput(page, column).inputValue(),
				"the combo for column " + column + " did not take '" + label + "'");
	}

	private boolean visibleWithin(Locator locator, Duration budget) {
		try {
			locator.first().waitFor(new Locator.WaitForOptions()
					.setState(WaitForSelectorState.VISIBLE)
					.setTimeout(budget.toMillis()));
			return true;
		} catch (RuntimeException notVisible) {
			return false;
		}
	}

	/**
	 * The editor for a dictionary column.
	 *
	 * <p>Resolved by COLUMN, never by proximity. Both proximity rules tried
	 * earlier failed on this form, and the second failed silently: the
	 * document-order rule resolved "Search Key" to the Partner Parent combo
	 * beside it, so the driver typed the record's key into the wrong control and
	 * saved a blank one (run 33478439643). A rule that can drift onto a
	 * neighbouring field is unusable here, because its failure is a plausible
	 * wrong record rather than an error.
	 *
	 * <p>Two resolutions, because ADempiere only gives some editors a
	 * column-named id. {@code WEditor.java:127-132} asks for
	 * {@code unqField_<window>_<tab>_<table>_<column>}, and run 33479498656
	 * showed the request is honoured for combos, checkboxes and dates but not
	 * for plain text editors -- {@code IsActive} carries the id and {@code Value}
	 * and {@code Name} do not.
	 *
	 * <p>The fallback is exact rather than approximate.
	 * {@code ADTabPanel.java:488-500} appends the caption div and then the
	 * editor as adjacent children of one {@code Row}, so they are adjacent cells
	 * of that row -- which is what run 33474413799's rule assumed and got wrong
	 * only because it tested {@code text()} on the cell, while the caption lives
	 * in a nested label. Matched on the label itself, the relationship is the one
	 * the layout code actually builds.
	 *
	 * <p>Either way an ambiguous or missing match is a hard failure, never a
	 * {@code first()} that picks something.
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

	/**
	 * Clicks a toolbar control and waits for the round trip it causes.
	 *
	 * <p>Used where the server's response re-renders the form. Typing into a
	 * form that is about to be re-rendered loses whatever was typed first.
	 */
	private void clickAwaitingServer(Page page, String title) {
		page.waitForResponse(
				response -> response.request().url().contains("/zkau"),
				() -> click(page, title));
	}

	private void click(Page page, String title) {
		Locator button = tabPanel(page)
				.locator("a.toolbar-button[title='" + title + "']").first();
		button.waitFor();
		button.click();
	}

	/**
	 * The open window's content panel.
	 *
	 * <p>Filtered by the record toolbar, NOT by the tab caption. The caption
	 * lives in the desktop's tab bar, outside every content panel, so a filter
	 * on it matches nothing -- run 33472649370 opened the window correctly and
	 * then timed out here, with the probes reporting the tab present and no
	 * modal left. The Menu panel carries no record toolbar, so the toolbar is
	 * what actually distinguishes a window panel from it.
	 */
	private Locator tabPanel(Page page) {
		return page.locator("div.desktop-tabpanel")
				.filter(new Locator.FilterOptions()
						.setHas(page.locator("a.toolbar-button[title='Save changes']")))
				.first();
	}

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
		// Each probe is evaluated and recorded INDEPENDENTLY. Run 33470049147
		// wrote the screenshot and then lost the whole JSON dump to a single
		// swallowed exception, so one malformed expression cost a full
		// twelve-minute round trip and taught nothing. A probe that fails now
		// records its own failure text next to the probes that succeeded.
		Map<String, String> probes = new LinkedHashMap<>();
		probes.put("url", "() => location.href");
		probes.put("tabs", "() => Array.from(document.querySelectorAll("
				+ "'span.z-tab-text')).map(e => e.textContent).join('|')");
		probes.put("comboItems", "() => Array.from(document.querySelectorAll("
				+ "'tr.z-combo-item')).map(e => e.getAttribute('z.label')"
				+ " || e.textContent).join('|')");
		probes.put("modals", "() => Array.from(document.querySelectorAll("
				+ "'div.z-window-modal')).map(e => e.textContent.slice(0, 120)).join('|')");
		probes.put("titles", "() => Array.from(document.querySelectorAll('[title]'))"
				+ ".map(e => e.getAttribute('title')).join('|')");
		probes.put("editorIds",
				"() => Array.from(document.querySelectorAll(\"[id*='_C_BPartner_']\"))"
						+ ".map(e => e.tagName.toLowerCase() + '#' + e.id).join('|')");
		probes.put("orgComboItems",
				"() => Array.from(document.querySelectorAll("
						+ "\"[id*='_C_BPartner_AD_Org_ID'][id$='!pp'] tr\"))"
						+ ".map(e => (e.getAttribute('z.label') || '<no z.label>')"
						+ " + '/' + e.className + '/' + e.textContent.trim()).join('|')");
		probes.put("fieldRows", "() => ['Search Key','Name'].map(function (caption) {"
				+ "  var label = Array.from(document.querySelectorAll("
				+ "    'div.field-label > *')).filter(function (e) {"
				+ "      return e.textContent.trim() === caption; })[0];"
				+ "  if (!label) { return caption + '=<no caption>'; }"
				+ "  var row = label.closest('tr');"
				+ "  if (!row) { return caption + '=<no row>'; }"
				+ "  var cells = [], cellIndex = -1, own = label.closest('td');"
				+ "  for (var i = 0; i < row.children.length; i++) {"
				+ "    var td = row.children[i];"
				+ "    if (td === own) { cellIndex = i; }"
				+ "    var input = td.querySelector(\"input:not([type='hidden'])\");"
				+ "    cells.push(input ? ('input#' + (input.id || '?') + '='"
				+ "      + input.value + '[' + (input.className || '') + ']'"
				+ "      + (input.readOnly ? '{readonly}' : '')"
				+ "      + (input.disabled ? '{disabled}' : '')) : 'none'); }"
				+ "  return caption + '=cell' + cellIndex + '/' + row.children.length"
				+ "    + ':' + cells.join(','); }).join(' ~~ ')");
		probes.put("tabPanels", "() => Array.from(document.querySelectorAll("
				+ "'div.desktop-tabpanel')).map(e => (e.id || '?') + '#'"
				+ " + e.className + '#toolbar=' + e.querySelectorAll("
				+ "'a.toolbar-button').length).join('|')");
		probes.put("labels", "() => Array.from(document.querySelectorAll("
				+ "'span.z-label, td.z-row-cell span, td.z-row-cell label, label'))"
				+ ".slice(0, 400)"
				+ ".map(e => e.textContent).join('|')");
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
