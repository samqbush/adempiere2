package org.adempiere.webui.phase5legacy;

import java.util.List;
import java.util.function.UnaryOperator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * The legacy ZK 3.6 browser primitives shared by every Phase 5 legacy driver.
 *
 * <p>These operations were private to the Phase 5c semantic oracle driver. The
 * Phase 5g-1a write oracle needs the same login, the same role selection, the
 * same public-origin blocking and the same traffic recording, and cloning them
 * would produce two drivers that drift: the moment one learns that the ZK login
 * form needs a {@code Tab} to fire {@code onChange}, and the other does not, the
 * two oracles stop observing the same product.
 *
 * <p>Nothing here asserts a semantic fact. These are navigation mechanics only.
 * What the captured answer must BE is the contract's business, not this class's,
 * and mixing the two is how a driver ends up asserting the thing it was supposed
 * to measure.
 */
public final class LegacyBrowserFlow {

	private LegacyBrowserFlow() {
	}

	/**
	 * Blocks every request that does not target the public origin under test.
	 *
	 * <p>This is a measurement control, not a convenience. The legacy desktop
	 * inherits third-party requests, and letting them resolve would make the
	 * captured network classes depend on the CI runner's egress rather than on
	 * the product. The attempts are still recorded -- they are a real property of
	 * the page -- they simply never leave the machine.
	 */
	public static void blockForeignOrigins(BrowserContext context, String baseUrl) {
		context.route("**/*", route -> {
			if (route.request().url().startsWith(baseUrl)) {
				route.resume();
			} else {
				route.abort();
			}
		});
	}

	/**
	 * Records request classes and browser-visible errors into caller-owned lists.
	 *
	 * @param normalizer collapses a URL to its comparable form; supplied by the
	 *        caller because each oracle freezes its own normalization policy
	 */
	public static void recordTraffic(
			Page page,
			List<String> requests,
			List<String> errors,
			UnaryOperator<String> normalizer) {
		page.onRequest(request ->
				requests.add(request.method() + "\t" + normalizer.apply(request.url())));
		page.onResponse(response -> {
			if (response.status() >= 400) {
				errors.add("http\t" + response.status() + "\t"
						+ normalizer.apply(response.url()));
			}
		});
		page.onPageError(error ->
				errors.add("page\t" + normalizer.apply(page.url()) + "\t" + error));
		page.onConsoleMessage(message -> {
			// "Failed to load resource" duplicates the HTTP status already
			// recorded above, and it is emitted for every blocked foreign origin,
			// so keeping it would make the error set a property of the block rule.
			if ("error".equals(message.type())
					&& !message.text().startsWith("Failed to load resource:")) {
				errors.add("console\t" + normalizer.apply(page.url())
						+ "\t" + message.text());
			}
		});
	}

	public static BrowserContext newContext(Browser browser) {
		return browser.newContext(new Browser.NewContextOptions()
				.setLocale("en-US")
				.setTimezoneId("UTC"));
	}

	/**
	 * Navigates to the legacy {@code /webui/} login and submits the credentials.
	 *
	 * <p>The {@code Tab} after the user field is load-bearing: ZK 3.6 drives the
	 * login form from change events, and without it the user field's value is
	 * never posted.
	 *
	 * @return the navigation response, so the caller can assert its own status
	 *         expectation rather than inheriting one from here
	 */
	public static Response login(Page page, String baseUrl, String user, String password) {
		Response response = page.navigate(baseUrl + "/webui/",
				new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
		page.locator("#rowUser input").fill(user);
		page.locator("#rowUser input").press("Tab");
		page.locator("#rowPassword input").fill(password);
		page.locator("[title='OK']").click();
		return response;
	}

	/** Waits for the role/organization panel and returns its normalized text. */
	public static String awaitRolePanel(Page page, UnaryOperator<String> normalizer) {
		page.locator("#grdChooseRole").waitFor();
		return normalizer.apply(page.locator("#grdChooseRole").innerText());
	}

	public static void confirmRole(Page page) {
		page.locator("[title='OK']").click();
	}

	/** Waits for the desktop to render the {@code user@client} identity label. */
	public static void awaitDesktop(Page page, String user, String client) {
		page.getByText(user + "@" + client, new Page.GetByTextOptions().setExact(false))
				.waitFor();
	}

	public static void logout(Page page) {
		page.getByText("Log Out", new Page.GetByTextOptions().setExact(true)).click();
		page.getByText("Login", new Page.GetByTextOptions().setExact(true)).first().waitFor();
	}
}
