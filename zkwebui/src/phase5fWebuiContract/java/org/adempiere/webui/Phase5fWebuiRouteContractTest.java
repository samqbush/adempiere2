package org.adempiere.webui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Phase 5f /webui route contract")
class Phase5fWebuiRouteContractTest {

	private static final String TIMELINE = "/webui::timelineFeed::/timeline";
	private static final String DSP = "/webui::dspLoader::*.dsp";

	@Test
	@DisplayName("timeline retains the exact GET route and read-only database effect")
	void timelineClassificationIsExact() throws Exception {
		String[] route = row("route-validation.tsv", TIMELINE);
		assertEquals("/webui", route[1]);
		assertEquals("GET", route[2]);
		assertEquals("/webui/timeline", route[3]);
		assertEquals("200", route[4]);
		assertEquals("exact-servlet-dispatch", route[5]);
		assertEquals("end-user-authenticated", route[6]);
		assertEquals("application-session-check", route[7]);
		assertEquals("migrate-source-native-jakarta", route[8]);
		assertEquals("read-only", route[13]);

		String[] effect = row("database-effect-ownership.tsv", TIMELINE);
		assertEquals("GET", effect[2]);
		assertEquals("read-only", effect[3]);
		assertEquals("AD_RecentItem", effect[4]);
	}

	@Test
	@DisplayName("DSP compatibility is only the historical GET/HEAD static asset")
	void dspClassificationIsExact() throws Exception {
		String[] route = row("route-validation.tsv", DSP);
		assertEquals("GET", route[2]);
		assertEquals("/webui/theme/default/css/theme.css.dsp", route[3]);
		assertEquals("replace-one-historical-resource-with-static-css", route[8]);
		assertEquals(
				"GET-and-HEAD-exact-historical-theme-path=200-static-Phase5d-CSS;"
						+ "all-other-dsp=404",
				route[9]);
		assertEquals("no-write", route[13]);
	}

	private static String[] row(String resource, String id) throws Exception {
		InputStream stream = Phase5fWebuiRouteContractTest.class
				.getClassLoader().getResourceAsStream(resource);
		assertNotNull(stream, resource);
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			List<String[]> matches = reader.lines()
					.skip(1)
					.map(line -> line.split("\t", -1))
					.filter(fields -> fields[0].equals(id))
					.toList();
			assertEquals(1, matches.size(), id);
			return matches.get(0);
		}
	}
}
