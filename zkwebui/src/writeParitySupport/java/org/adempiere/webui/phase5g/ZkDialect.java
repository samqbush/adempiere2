package org.adempiere.webui.phase5g;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

/**
 * How a control is located, operated and awaited in one ZK generation.
 *
 * <h2>The single rule this interface exists to enforce</h2>
 *
 * <p><strong>A dialect may express only how a control is located, operated and
 * awaited. It may never normalize a behavioural difference away.</strong>
 *
 * <p>Step order, the emitted facts, the rendezvous protocol and the outcome
 * vocabulary live in {@link BusinessPartnerWriteFlow} and are shared and
 * invariant across runtimes. That division is what makes the parity comparison
 * mean anything: if a dialect could decide what to record, the modern lane
 * could quietly record the legacy answer and score itself.
 *
 * <p>Concretely, an implementation must not swallow, retry past, or reinterpret
 * a product outcome. {@link #attemptSave} in particular must report what the
 * runtime did -- it is the conflicting save, whose outcome is a captured fact,
 * not an expectation. An implementation that returned {@code "accepted"} because
 * its own selectors could not find the error dialog would turn a genuine parity
 * failure into a pass.
 *
 * <h2>Why the seam is drawn here and not lower</h2>
 *
 * <p>Legacy and modern compile the same application source, so it is tempting
 * to treat the difference as pure rendering. It is not: the ZK implementation,
 * the servlet runtime, the polling transport, session handoff, proxying and
 * deployment composition all differ. The methods below are therefore whole
 * user-level operations ("save and tell me what happened") rather than selector
 * strings, so a runtime whose settlement model differs can implement the
 * operation honestly instead of being forced through a legacy-shaped wait.
 */
public interface ZkDialect {

	/** Identifies the dialect in evidence and provenance. */
	String id();

	/**
	 * A browser context configured for this runtime, with foreign origins
	 * blocked against {@code baseUrl}.
	 */
	BrowserContext newContext(Browser browser, String baseUrl);

	/** Records requests and browser errors from {@code page} into the capture. */
	void recordTraffic(Page page, List<String> requests, List<String> errors,
			UnaryOperator<String> normalizer);

	/**
	 * Signs {@code user} in and leaves the desktop rendered.
	 *
	 * <p>Covers the whole login handshake -- the login form, the role panel and
	 * the desktop -- because ZK CE 10 renders all three differently. The
	 * post-condition is shared: the desktop is reached for this user and client.
	 *
	 * @param sessionLabel names the session in failure messages. The flow opens
	 *                     four sessions and three of them drive their own page,
	 *                     so an unlabelled login failure does not say which one
	 *                     could not sign in.
	 */
	void signIn(Page page, String baseUrl, String user, String password, String client,
			String sessionLabel);

	/** Ends the session through the product's own logout control. */
	void logout(Page page);

	/**
	 * Opens the capture's window, optionally positioned on {@code searchKey}.
	 *
	 * @param searchKey the record to load, or {@code null} to enter the window
	 *                  on an unconstrained query
	 */
	void openWindow(Page page, String searchKey);

	/** Ensures the window's current record is the one identified by {@code value}. */
	void focusRecord(Page page, String value);

	/** Starts a new record and waits for the form to be ready to type into. */
	void newRecord(Page page);

	/** Chooses {@code label} in the lookup combo for {@code column}. */
	void selectCombo(Page page, String column, String label);

	/** Types {@code value} into the editor for {@code column} and lets the runtime see it. */
	void fill(Page page, String column, String value);

	/**
	 * Saves and requires the save to have happened.
	 *
	 * <p>Used for every save whose success is a precondition of the step that
	 * follows. Measuring an operation that silently did not happen would freeze
	 * an empty effect as the expected answer.
	 */
	void save(Page page);

	/**
	 * Saves and REPORTS the outcome instead of requiring success.
	 *
	 * <p>Used only for the conflicting save. The returned vocabulary is shared
	 * across dialects and is part of the compared answer, so an implementation
	 * must map its runtime's behaviour onto it faithfully rather than
	 * conveniently: {@code accepted}, {@code error-dialog<TAB><normalized text>}
	 * or {@code rejected-save-still-enabled}.
	 */
	String attemptSave(Page page);

	/** Clears the record's Active control, leaving the change unsaved. */
	void clearActive(Page page);

	/**
	 * Saves once, then re-issues that save's own request verbatim, and returns
	 * the replay's HTTP status.
	 *
	 * <p>The replay must carry the session's own cookies and must be bound to
	 * the save command rather than to whatever request happens to follow the
	 * click. Replaying a field assignment instead would be idempotent, so the
	 * step would report a benign status while claiming to have measured a
	 * duplicate submission.
	 *
	 * <p>The status is returned; the response body deliberately is not recorded
	 * anywhere, because it echoes ids that differ on every run.
	 */
	String replaySave(Page page);

	/**
	 * Records what the page actually contained when a step failed.
	 *
	 * <p>Best effort by construction: it runs while the lane is already failing,
	 * so a fault here must never replace the real diagnosis with a diagnostic's
	 * own stack trace.
	 */
	void captureDiagnostics(Page page, Path evidenceDir, String label);
}
