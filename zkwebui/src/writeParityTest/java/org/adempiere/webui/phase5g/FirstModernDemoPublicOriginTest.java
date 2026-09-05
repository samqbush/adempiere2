package org.adempiere.webui.phase5g;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

class FirstModernDemoPublicOriginTest {
	@Test
	void createsBusinessPartnerThroughAuthenticatedPublicModernUi() throws Exception {
		String baseUrl = requiredProperty("firstModernDemo.baseUrl")
			.replaceFirst("/+$", "");
		String recordValue = requiredProperty("firstModernDemo.recordValue");
		Path evidenceDir = Path.of(requiredProperty("firstModernDemo.evidenceDir"));
		Files.createDirectories(evidenceDir);

		ZkDialect dialect = new ZkCe10Dialect();
		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.chromium().launch(
					new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = dialect.newContext(browser, baseUrl);
				Page page = context.newPage()) {
			dialect.signIn(page, baseUrl, "GardenAdmin", "GardenAdmin",
				"GardenWorld", "demo");
			dialect.identifyServingRuntime(page, evidenceDir, "demo");
			dialect.openWindow(page, null);
			dialect.newRecord(page);
			dialect.selectCombo(page, "AD_Org_ID", "Fertilizer");
			dialect.fill(page, "Value", recordValue);
			dialect.fill(page, "Name", recordValue + " Public Modern UI");
			dialect.save(page);
			dialect.readBackRecord(page, recordValue);
			page.screenshot(new Page.ScreenshotOptions()
				.setPath(evidenceDir.resolve("business-partner-created.png"))
				.setFullPage(true));
			dialect.logout(page);
		}
	}

	private static String requiredProperty(String name) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Required property is absent: " + name);
		}
		return value;
	}
}
