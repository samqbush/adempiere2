package org.adempiere.webui.phase5e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reviewed Phase 5e public contract, read from
 * {@code contracts/phase5e-routed-web-v1/}.
 *
 * <p>The contract files are on this source set's runtime classpath, so both the
 * contract test and the database-backed browser matrix read the same reviewed
 * rows. Nothing here paraphrases them.
 */
public final class RoutedWebContract {

	private RoutedWebContract() {
	}

	/** One reviewed public route row. */
	public record Route(String method, String path, String routeClass, boolean proxyable) {
	}

	/** One reviewed proxy header row. */
	public record Header(String name, String direction, boolean forwarded) {
	}

	/** The closed public affinity unit, including the refused rows. */
	public static List<Route> routes() {
		List<Route> routes = new ArrayList<>();
		for (String[] fields : rows("public-route-classes.tsv")) {
			routes.add(new Route(fields[0], fields[1], fields[2],
					Boolean.parseBoolean(fields[3])));
		}
		return List.copyOf(routes);
	}

	/** The request and response header allowlists. */
	public static List<Header> headers() {
		List<Header> headers = new ArrayList<>();
		for (String[] fields : rows("proxy-header-policy.tsv")) {
			headers.add(new Header(fields[0], fields[1],
					Boolean.parseBoolean(fields[2])));
		}
		return List.copyOf(headers);
	}

	/** The reviewed configuration, ticket, key and cookie parameters. */
	public static Map<String, String> configuration() {
		Map<String, String> values = new LinkedHashMap<>();
		for (String[] fields : rows("cohort-configuration.tsv")) {
			values.put(fields[0], fields[1]);
		}
		return Map.copyOf(values);
	}

	/** The reviewed derived-artifact difference set. */
	public static Map<String, String> derivedArtifactDiff() {
		Map<String, String> values = new LinkedHashMap<>();
		for (String[] fields : rows("derived-artifact-diff.tsv")) {
			values.put(fields[0], fields[1]);
		}
		return Map.copyOf(values);
	}

	/** A required contract value, or an explicit failure naming what is absent. */
	public static String required(String key) {
		String value = configuration().get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					"contracts/phase5e-routed-web-v1/cohort-configuration.tsv "
							+ "does not declare " + key);
		}
		return value;
	}

	private static List<String[]> rows(String resource) {
		try (InputStream stream = RoutedWebContract.class.getClassLoader()
				.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException(
						"The Phase 5e contract resource " + resource
								+ " is not on the classpath");
			}
			List<String[]> rows = new ArrayList<>();
			for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8)
					.split("\\R")) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				rows.add(line.split("\t", -1));
			}
			if (rows.isEmpty()) {
				throw new IllegalStateException(resource + " declares no row");
			}
			return rows;
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}
}
