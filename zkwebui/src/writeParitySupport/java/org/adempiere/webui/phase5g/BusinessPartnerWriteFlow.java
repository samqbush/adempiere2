package org.adempiere.webui.phase5g;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.phase5d.BrowserSemanticContract;
import org.adempiere.webui.phase5legacy.StepRendezvous;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * The Business Partner write flow, shared by every runtime that is captured or
 * scored.
 *
 * <h2>What this class owns, and why that matters</h2>
 *
 * <p>It owns everything that must be <em>identical</em> across runtimes: the
 * step order and step ids, the rendezvous protocol, which facts are emitted
 * under which keys, which session performs which step, and the four evidence
 * files. {@link ZkDialect} owns only how a control is located, operated and
 * awaited.
 *
 * <p>That division is the whole point. Parity is decided by comparing captures
 * produced by this class under two dialects; if a dialect could reorder steps,
 * rename a fact or skip a session, the two captures would no longer be
 * measuring the same thing and a difference could be engineered away in the
 * driver rather than fixed in the product.
 *
 * <h2>It does not know the expected answer</h2>
 *
 * <p>It never reads {@code effect-model.tsv} and never asserts a business
 * value. The moment a driver asserts the answer, the answer becomes whatever
 * the driver was written to expect. Its only assertions are UI post-conditions
 * -- a save that silently failed must not be measured as a save.
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
public final class BusinessPartnerWriteFlow {

	/**
	 * Generous, because it bounds a browser round trip plus a whole-schema
	 * snapshot on a loaded CI runner. It is a backstop against a hang, not a
	 * performance expectation, and expiring is always a failure.
	 */
	private static final Duration RENDEZVOUS_TIMEOUT = Duration.ofMinutes(10);

	private static final String WINDOW = "Business Partner";

	private final ZkDialect dialect;
	private final WriteCaptureConfig config;

	public BusinessPartnerWriteFlow(ZkDialect dialect, WriteCaptureConfig config) {
		this.dialect = dialect;
		this.config = config;
	}

	/**
	 * Drives the whole twelve-step flow and writes the capture's evidence files.
	 */
	public void capture() throws IOException {
		Path evidenceDir = config.evidenceDir();
		Files.createDirectories(evidenceDir);
		StepRendezvous rendezvous =
				new StepRendezvous(config.rendezvousDir(), config.token(), "driver", "orchestrator");
		rendezvous.announce();

		List<String> requests = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Map<String, String> facts = new LinkedHashMap<>();
		List<String> flow = new ArrayList<>();

		String baseUrl = config.baseUrl();
		String recordValue = config.recordValue();

		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium().launch(
						new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = dialect.newContext(browser, baseUrl)) {
			Page page = context.newPage();
			dialect.recordTraffic(page, requests, errors, this::normalizedUrl);

			// Diagnostics are captured HERE, inside the resource scope, not in
			// the outer handler. A try-with-resources closes its resources
			// BEFORE any catch clause runs, so an outer handler interrogates a
			// browser that is already gone -- which is exactly what happened in
			// run 33469214157: the capture ran, every Playwright call threw
			// against a closed context, and the best-effort swallow left no
			// evidence at all.
			try {

			dialect.signIn(page, baseUrl, config.user(), config.password(), config.client(),
					"legacy");
			facts.put("desktop-reached", "true");

			// WHICH application served this session, recorded before any
			// business step. Not a compared fact and not part of `facts`: it is
			// written to its own file, so the frozen answer is unchanged and the
			// legacy freeze-off regression still scores clean.
			//
			// It is here because every other observation in this flow is
			// runtime-blind -- same public origin, normalized URLs, the
			// product's own database effects -- so a routed lane that fell back
			// to the legacy application would score a perfect green against the
			// legacy oracle and report modern parity.
			dialect.identifyServingRuntime(page, evidenceDir);

			// Step 0 is the baseline: the orchestrator snapshots an authenticated
			// session that has not yet written any business row. Without it, the
			// create step's effect would include the login's own AD_Session write
			// and every table the desktop touches on first render.
			flow.add(step(rendezvous, 0, "authenticated-baseline"));

			dialect.openWindow(page, null);
			facts.put("window-opened", WINDOW);
			flow.add(step(rendezvous, 1, "window-opened"));

			create(page);
			flow.add(step(rendezvous, 2, "create"));

			dialect.fill(page, "Name", recordValue + " Partner Updated");
			dialect.save(page);
			flow.add(step(rendezvous, 3, "update"));

			// Deactivation is deliberately LAST, after the concurrency step.
			// Run 33475295851 established that ADempiere renders an inactive
			// record read-only: the second editor loaded the deactivated row and
			// found the entire form greyed out, Save included. Deactivating
			// before the concurrency step therefore does not measure a conflict
			// at all -- it measures the product refusing to edit a disabled
			// record, which is a different fact and one this increment does not
			// claim to capture.
			// The concurrency step. The contract requires the LEGACY conflict
			// answer to be captured here so that 5g-1b has something it did not
			// invent to score the modern runtime against.
			//
			// The two editors must be different users: a user racing itself
			// records no UpdatedBy transition, so the capture could not say who
			// won. The fixture asserts role 103's read-write access to window
			// 123 precisely so that an access refusal can never be mistaken for
			// conflict behaviour.
			try (BrowserContext secondContext = dialect.newContext(browser, baseUrl)) {
				Page second = secondContext.newPage();
				dialect.recordTraffic(second, requests, errors, this::normalizedUrl);

				// Inner guard for the same reason as the primary session's: a
				// try-with-resources closes its context BEFORE any catch runs,
				// so a handler outside this block could only interrogate a dead
				// page.
				try {

				dialect.signIn(second, baseUrl, config.secondUser(), config.secondPassword(),
						config.client(), "second editor's");
				facts.put("concurrency-second-editor-desktop-reached", "true");

				// The second editor's FIRST login writes rows of its own --
				// AD_Session, its preferences and their change logs. Those are
				// not concurrency effects, and folding them into the update step
				// would freeze them as though they were. Giving them their own
				// step boundary attributes them where they belong.
				flow.add(step(rendezvous, 4, "concurrency-second-editor-authenticated"));

				dialect.openWindow(second, recordValue);
				dialect.focusRecord(second, recordValue);
				dialect.fill(second, "Name", recordValue + " Partner By Second Editor");
				dialect.save(second);
				facts.put("concurrency-second-editor-saved", "true");
				flow.add(step(rendezvous, 5, "concurrency-second-editor-update"));

				// The primary session still holds the record it loaded before the
				// second editor wrote it. Its save is the conflict.
				//
				// This is the one save in the flow that is NOT asserted to
				// succeed. Whether the runtime refuses it, silently overwrites,
				// or reloads is the expected answer this flow exists to
				// capture -- asserting either outcome would make the oracle
				// score whatever the driver was written to expect.
				dialect.fill(page, "Name", recordValue + " Partner By First Editor");
				facts.put("concurrency-conflicting-save-outcome", dialect.attemptSave(page));
				flow.add(step(rendezvous, 6, "concurrency-conflicting-save"));

				dialect.logout(second);
				} catch (Throwable concurrencyFailure) {
					// The second editor drives its OWN page. Run 33476264790
					// failed here and the handler captured the primary page,
					// which was perfectly healthy -- so the evidence described a
					// session that was not the one that failed, and the
					// screenshot sent the diagnosis in the wrong direction.
					dialect.captureDiagnostics(second, evidenceDir, "second-editor");
					throw concurrencyFailure;
				}
			}

			// The duplicate submission is measured from its OWN session, for the
			// same reason deactivation is: if replaying an AU request desyncs or
			// invalidates the session that issued it, that must not silently
			// corrupt any other step's measurement. A session that has only read
			// the current row, saved once and replayed that save measures the
			// duplicate submission and nothing else.
			//
			// It runs BEFORE deactivation because a deactivated record renders
			// read-only, so a duplicate submit attempted afterwards would measure
			// the product refusing to edit a disabled row -- a different fact.
			//
			// It logs in as the SECOND editor, not the primary one. The frozen
			// deactivate step records a c_bpartner updatedby transition from 102
			// to 101, which exists only because the last writer before
			// deactivation was the second editor. Inserting a primary-user write
			// here would erase that transition and silently weaken a step this
			// increment is not claiming to change.
			try (BrowserContext duplicateContext = dialect.newContext(browser, baseUrl)) {
				Page fourth = duplicateContext.newPage();
				dialect.recordTraffic(fourth, requests, errors, this::normalizedUrl);
				try {
					dialect.signIn(fourth, baseUrl, config.secondUser(),
							config.secondPassword(), config.client(),
							"duplicate-submitting session's");
					flow.add(step(rendezvous, 7, "duplicate-submit-editor-authenticated"));

					dialect.openWindow(fourth, recordValue);
					dialect.focusRecord(fourth, recordValue);
					dialect.fill(fourth, "Name", recordValue + " Partner By Duplicate Submitter");
					facts.put("duplicate-submit-replay-http-status", dialect.replaySave(fourth));
					flow.add(step(rendezvous, 8, "duplicate-submit"));

					dialect.logout(fourth);
				} catch (Throwable duplicateFailure) {
					dialect.captureDiagnostics(fourth, evidenceDir, "duplicate-submit-editor");
					throw duplicateFailure;
				}
			}

			// Deactivation is measured from a THIRD session rather than from the
			// primary one.
			//
			// The primary session's state after the conflicting save is exactly
			// what the conflict step exists to observe, and is not a sound basis
			// for measuring a further write. Run 33486692102 showed it is not a
			// usable one either: the window it leaves behind reports "Current
			// record was changed by another user, please ReQuery" and will not
			// reopen its own lookup, so the reload that was supposed to clear
			// the conflicted state cannot run from inside it.
			//
			// A session that has only ever read the current row measures
			// deactivation and nothing else, which is what the step claims. Its
			// login writes rows of its own, so those get their own step boundary
			// rather than being folded into the deactivation, exactly as the
			// second editor's do.
			try (BrowserContext deactivateContext = dialect.newContext(browser, baseUrl)) {
				Page third = deactivateContext.newPage();
				dialect.recordTraffic(third, requests, errors, this::normalizedUrl);
				try {
					dialect.signIn(third, baseUrl, config.user(), config.password(),
							config.client(), "deactivating session's");
					flow.add(step(rendezvous, 9, "deactivate-editor-authenticated"));

					dialect.openWindow(third, recordValue);
					dialect.focusRecord(third, recordValue);
					dialect.clearActive(third);
					dialect.save(third);
					facts.put("deactivated", "true");
					flow.add(step(rendezvous, 10, "deactivate"));

					dialect.logout(third);
				} catch (Throwable deactivateFailure) {
					dialect.captureDiagnostics(third, evidenceDir, "deactivate-editor");
					throw deactivateFailure;
				}
			}

			dialect.logout(page);
			facts.put("logout-reached", "true");
			flow.add(step(rendezvous, 11, "logged-out"));
			} catch (Throwable failure) {
				dialect.captureDiagnostics(page, evidenceDir, "primary");
				throw failure;
			}
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
	 * Creates the fixture record.
	 *
	 * <p>The record is created in an organisation BOTH capture identities can
	 * write, rather than in the primary session's login default.
	 *
	 * <p>Run 33482988481 created it in the shared '*' org and the second
	 * editor, logged into Fertilizer, was served a read-only form -- every
	 * editor carried z-textbox-readonly and the combos were disabled. That is
	 * ADempiere's org access control working correctly, and it is exactly the
	 * outcome fixture.sql's window-access assertions were written to rule out:
	 * they check AD_Window_Access, which was never the constraint. Left alone,
	 * the concurrency step would have captured an access refusal and frozen it
	 * as the product's conflict behaviour.
	 */
	private void create(Page page) {
		dialect.newRecord(page);
		dialect.selectCombo(page, "AD_Org_ID", config.recordOrg());
		// The C_BPartner.Value column is labelled "Search Key" in the dictionary;
		// "Value" matches no cell in the rendered form.
		dialect.fill(page, "Value", config.recordValue());
		dialect.fill(page, "Name", config.recordValue() + " Partner");
		dialect.save(page);
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

	private String normalizedUrl(String value) {
		return BrowserSemanticContract.normalizedUrl(config.baseUrl(), value);
	}
}
