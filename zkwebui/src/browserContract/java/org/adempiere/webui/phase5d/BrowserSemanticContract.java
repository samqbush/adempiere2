package org.adempiere.webui.phase5d;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loader and normalizer for {@code contracts/legacy-web-browser-v1/}.
 *
 * <p>The contract tree is placed on the classpath by both the database-neutral
 * {@code browserContract} source set and the database-backed {@code browserTest}
 * source set, so the mutation proofs and the live capture read exactly the same
 * frozen bytes. Duplicating either the loader or the normalizer in the live test
 * is how a normalizer silently stops matching the contract it claims to enforce.
 */
public final class BrowserSemanticContract {

	private BrowserSemanticContract() {
	}

	/** Reviewed browser-visible semantic facts. */
	public static Map<String, String> facts() throws IOException {
		Map<String, String> facts = new LinkedHashMap<>();
		for (String line : lines("/semantic-facts.tsv")) {
			String[] fields = line.split("\\t", 2);
			facts.put(fields[0], fields[1]);
		}
		return facts;
	}

	/** Reviewed stable network request classes. */
	public static Set<String> networkClasses() throws IOException {
		return new TreeSet<>(lines("/network-classes.tsv"));
	}

	/** Reviewed inherited browser error classes. */
	public static Set<String> allowedErrors() throws IOException {
		return new TreeSet<>(lines("/allowed-browser-errors.tsv"));
	}

	/**
	 * Reviewed per-table allowed deltas for the read-only window step, keyed by
	 * table name. The allowed delta is declared in the contract rather than in
	 * the harness so the assertion and the reviewed contract cannot drift apart.
	 */
	public static Map<String, Integer> windowReadOnlyEffects() throws IOException {
		Map<String, Integer> effects = new LinkedHashMap<>();
		for (String line : lines("/window-readonly-effects.tsv")) {
			String[] fields = line.split("\\t", -1);
			if (fields.length < 3) {
				throw new IOException("Malformed window-readonly-effects row: " + line);
			}
			if (!fields[2].startsWith("exactly:")) {
				throw new IOException(
						"No exact allowed_delta declared for " + fields[0]);
			}
			effects.put(fields[0],
					Integer.parseInt(fields[2].substring("exactly:".length())));
		}
		if (effects.isEmpty()) {
			throw new IOException("window-readonly-effects.tsv declares no table");
		}
		return effects;
	}

	/**
	 * The reviewed browser observation the frozen window facts were derived
	 * from. Keeping it in the contract tree rather than in Java means the
	 * mutation proofs mutate a reviewed baseline instead of a fixture some test
	 * author invented, and the manifest covers it.
	 */
	public static ErrorMessageWindowFacts.Observation windowObservationFixture()
			throws IOException {
		int panels = -1;
		Boolean visible = null;
		Integer writes = null;
		List<String> tabs = new ArrayList<>();
		Map<String, String> columns = new LinkedHashMap<>();
		Map<String, Boolean> controls = new LinkedHashMap<>();
		for (String line : lines("/window-observation-fixture.tsv")) {
			String[] fields = line.split("\\t", -1);
			switch (fields[0]) {
				case "panels" -> panels = Integer.parseInt(fields[1]);
				case "visible" -> visible = Boolean.valueOf(fields[1]);
				case "database-writes" -> writes = Integer.valueOf(fields[1]);
				case "tab" -> tabs.add(normalizedText(fields[1]));
				case "column" -> columns.put(fields[1], fields[2]);
				case "control" -> controls.put(fields[1], Boolean.valueOf(fields[2]));
				default -> throw new IOException("Unknown fixture row: " + line);
			}
		}
		if (panels < 0 || visible == null || writes == null) {
			throw new IOException("window-observation-fixture.tsv is incomplete");
		}
		return new ErrorMessageWindowFacts.Observation(
				panels, visible, tabs, columns, controls, writes);
	}

	/**
	 * Approved text volatility: ZK emits non-breaking spaces and layout-driven
	 * line breaks around otherwise identical labels. Collapsing them is the only
	 * text normalization the contract permits; a changed word is never
	 * normalized away.
	 */
	public static String normalizedText(String value) {
		return value.replace('\u00a0', ' ')
				.replaceAll("\\s+", " ")
				.trim();
	}

	/**
	 * Approved URL volatility: the container session id, the ZK desktop id, and
	 * the per-desktop ZK component counter are all newly minted on every
	 * capture. Nothing else is rewritten.
	 */
	public static String normalizedUrl(String baseUrl, String value) {
		return value.replace(baseUrl, "")
				.replaceAll(";jsessionid=[A-Fa-f0-9]+", ";jsessionid=<SESSION>")
				.replaceAll("(/webui/zkau/view/)[^/]+/(zk_comp_)\\d+",
						"$1<DTID>/$2<COMPONENT>")
				.replaceAll("([?&]dtid=)[^&]+", "$1<DTID>");
	}

	private static List<String> lines(String resource) throws IOException {
		return contractLines(resource);
	}

	/**
	 * Reads one frozen contract resource. Exposed so the Phase 5d modern
	 * vocabulary can read reviewed contract files through the same loader rather
	 * than opening the classpath itself.
	 */
	public static List<String> contractLines(String resource) throws IOException {
		try (InputStream input =
				BrowserSemanticContract.class.getResourceAsStream(resource)) {
			if (input == null) {
				throw new IOException("Missing browser contract resource " + resource);
			}
			List<String> rows = new ArrayList<>();
			for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8)
					.split("\\R")) {
				if (!line.isBlank() && !line.startsWith("#")) {
					rows.add(line);
				}
			}
			return rows;
		}
	}
}
