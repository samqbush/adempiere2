package org.adempiere.webui.phase5g1b;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.adempiere.webui.phase5g.WriteCaptureConfig;
import org.adempiere.webui.phase5g.ZkCe10Dialect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * H6 row {@code h6-no-legacy-fallback-mid-write}: a modern session whose
 * backend dies mid-write must receive an explicit failure and must NEVER be
 * handed the legacy application instead.
 *
 * <h2>Why this control exists as its own class</h2>
 *
 * <p>The proven assertion already lives in
 * {@code RoutedCohortMatrixTest.backendOutageNeverFallsBack()} (Phase 5e case
 * {@code backend-unavailable}), but it is a private method inside one large
 * {@code @Test}, so {@code --tests} cannot select it, and driving the whole
 * {@code routedBrowserTest} matrix to reach it would re-run coverage the
 * regression matrix already owns. The H6 matrix therefore needs a focused entry
 * point that makes exactly this assertion and nothing else. This class ports
 * that method's mechanics -- it invents no new markers and no new fault
 * primitive.
 *
 * <h2>Why a browser, and not a shell request</h2>
 *
 * <p>The router pins the cohort at login and keys it to the session. A request
 * that carries no established modern session is served the legacy login page,
 * which itself links {@code .dsp}; a shell {@code curl} would therefore observe
 * {@code .dsp} and wrongly report a legacy fallback. The assertion is only
 * meaningful from a vantage point that actually holds an authenticated modern
 * session, which is why it must run in Playwright on the same context that
 * logged in.
 *
 * <h2>What it must not weaken</h2>
 *
 * <p>The failure this row catches is a logged-in modern user being quietly
 * shown ZK 3.6 when the backend is down. Two conditions together prove that did
 * NOT happen: an explicit server error ({@code status >= 500}) AND the absence
 * of the legacy {@code .dsp} marker. Dropping either -- accepting any answer, or
 * accepting a 200 that merely lacks {@code .dsp} -- would assert a weaker
 * property than the row claims, so both are kept.
 */
@Tag("IntegrationTest")
class ModernNoLegacyFallbackTest {

	/** The modern slice links this; {@code RoutedCohortMatrixTest.servedBy}. */
	private static final String MODERN_MARKER = "phase5d-modern.css";
	/** The legacy ZK 3.6 theme serves these; the fallback this row forbids. */
	private static final String LEGACY_MARKER = ".dsp";
	private static final String ROW_ID = "h6-no-legacy-fallback-mid-write";

	@Test
	void modernSessionNeverFallsBackToLegacyWhenBackendDies() throws IOException {
		WriteCaptureConfig config = WriteCaptureConfig.fromProperties("phase5g1a.browser.");
		ZkCe10Dialect dialect = new ZkCe10Dialect();
		String baseUrl = config.baseUrl();
		// The lane's own backend stop/start primitive, handed in as a property so
		// this control operates the SAME staged Tomcat 10 the proven Phase 5e
		// test does rather than a path it guessed.
		String laneScript = required("phase5g1b.laneScript");

		boolean passed = false;
		String detail;
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium()
						.launch(new BrowserType.LaunchOptions().setHeadless(true));
				// newContext blocks every foreign origin -- including the loopback
				// modern port -- so a fallback cannot hide behind a direct request
				// the browser was never supposed to make.
				BrowserContext context = dialect.newContext(browser, baseUrl)) {
			Page page = context.newPage();
			dialect.signIn(page, baseUrl, config.user(), config.password(),
					config.client(), ROW_ID);

			if (!servedModern(page)) {
				detail = "precondition failed: the session was not served MODERN before"
						+ " the outage, so the no-fallback assertion is not meaningful";
				writeVerdict(config.evidenceDir(), false, detail);
				assertTrue(false, detail);
				return;
			}

			// A deliberate backend outage, taken on the SAME authenticated context.
			//
			// The stop is INSIDE the guarded region, not before it. `backend stop`
			// force-stops Tomcat and then polls, so a stop that actually killed the
			// process but reported a non-zero exit -- or any failure between
			// spawning the child and reading its status -- would throw. Were that
			// throw to happen outside the try, the finally would never run and the
			// modern backend would stay dead for H6 row 5 and the surrounding
			// smoke, which drive a full routed browser write. Restarting is
			// therefore unconditional: an already-running backend is a benign
			// restart, whereas a dead one silently invalidates everything after it.
			try {
				run(laneScript, "backend", "stop");
				Response response = page.navigate(baseUrl + "/webui/",
						new Page.NavigateOptions()
								.setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
				// A null response is an absent observation, not an explicit error,
				// so it scores -1 and fails the `status >= 500` conjunct rather
				// than falling through to a pass.
				int status = response == null ? -1 : response.status();
				boolean explicitError = status >= 500;
				boolean legacyServed = page.content().contains(LEGACY_MARKER);
				passed = explicitError && !legacyServed;
				detail = "status=" + status + " legacyMarkerPresent=" + legacyServed;
			} finally {
				run(laneScript, "backend", "start");
			}
		}

		writeVerdict(config.evidenceDir(), passed, detail);
		assertTrue(passed,
				"a modern session that lost its backend must get status >= 500 and no "
						+ LEGACY_MARKER + " marker: " + detail);
	}

	/**
	 * Identifies the served application from markup only one runtime emits, using
	 * the two markers the Phase 5e routed matrix already tells the runtimes apart
	 * with. Matching both or neither is treated as not-modern rather than
	 * resolved by preference, because guessing here would defeat the only check
	 * that can see the wrong application answering.
	 */
	private boolean servedModern(Page page) {
		String content = page.content();
		return content.contains(MODERN_MARKER) && !content.contains(LEGACY_MARKER);
	}

	/**
	 * Writes the single-row verdict the H6 matrix reads instead of re-deriving
	 * the result. The row id is fixed so a caller cannot mis-key it, and the
	 * file is emitted whether the row passed or failed so the matrix always finds
	 * a real result rather than an empty file it would have to treat as a pass.
	 */
	private void writeVerdict(Path evidenceDir, boolean passed, String detail)
			throws IOException {
		Files.createDirectories(evidenceDir);
		Files.write(evidenceDir.resolve("no-legacy-fallback.tsv"),
				List.of(ROW_ID + "\t" + (passed ? "pass" : "fail") + "\t" + detail),
				StandardCharsets.UTF_8);
	}

	/**
	 * Runs a lane command and fails the test on a non-zero exit, so a backend
	 * stop that silently did nothing cannot let the re-navigation pass against a
	 * still-live backend.
	 */
	private void run(String... command) {
		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			String output = new String(process.getInputStream().readAllBytes(),
					StandardCharsets.UTF_8);
			int status = process.waitFor();
			System.out.print(output);
			assertTrue(status == 0,
					String.join(" ", command) + " failed:\n" + output);
		} catch (IOException failure) {
			throw new IllegalStateException(
					String.join(" ", command) + " could not be started", failure);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
					String.join(" ", command) + " was interrupted", interrupted);
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
