package org.adempiere.webui.phase5legacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a marker-guarded database script with the reviewed connection parameters.
 *
 * <p>Extracted from the Phase 5c legacy semantic oracle driver rather than
 * copied. The Phase 5g write driver needs exactly this behaviour, and two
 * copies of a marker-guarded process launcher would eventually disagree about
 * which database they are allowed to touch -- which is the one thing this class
 * exists to constrain.
 *
 * <p>The connection parameters are read from system properties under a caller
 * supplied prefix, so the read oracle keeps its {@code phase5c.browser.} names
 * and the write oracle keeps its own. Nothing here defaults: a missing property
 * is a hard failure, because a silently defaulted database target is how a
 * capture ends up measuring the wrong instance.
 */
public final class LegacyDatabaseScripts {

	private final String prefix;

	public LegacyDatabaseScripts(String prefix) {
		this.prefix = prefix;
	}

	public String property(String name) {
		String value = System.getProperty(prefix + name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(prefix + name + " is required");
		}
		return value;
	}

	/**
	 * Runs {@code script} with the reviewed connection parameters and returns its
	 * combined output, which is also echoed so a CI failure is readable without
	 * re-running the whole lane.
	 *
	 * @throws IllegalStateException when the script cannot be started or exits
	 *         non-zero. A failed fixture or effect script is never recoverable
	 *         here: continuing would measure an unknown database state.
	 */
	public String run(String script, String label, String... arguments) {
		List<String> command = new ArrayList<>(List.of(
				script,
				property("dbHost"),
				property("dbPort"),
				property("dbName"),
				property("dbUser")));
		command.add(property("dbMarker"));
		command.addAll(List.of(arguments));
		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			String output = new String(process.getInputStream().readAllBytes(),
					StandardCharsets.UTF_8);
			int status = process.waitFor();
			System.out.print(output);
			if (status != 0) {
				throw new IllegalStateException(
						label + " failed with status " + status + ":\n" + output);
			}
			return output;
		} catch (IOException exception) {
			throw new IllegalStateException(label + " could not be started", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(label + " was interrupted", exception);
		}
	}
}
