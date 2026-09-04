package org.adempiere.webui.phase5g;

import java.nio.file.Path;

import org.adempiere.webui.phase5legacy.LegacyDatabaseScripts;

/**
 * The runtime-independent inputs to a Business Partner write capture.
 *
 * <p>Deliberately carries no ZK version, no selector and no runtime identity.
 * Both the legacy and the modern lane capture the same flow against the same
 * fixture record and the same two identities; what differs between them is how
 * a control is located, operated and awaited, which is
 * {@link ZkDialect}'s concern and not this record's.
 *
 * <p>The property prefix is the only lane-specific value, because each lane
 * hands its own system properties to its own Gradle test task.
 */
public record WriteCaptureConfig(
		String baseUrl,
		String user,
		String password,
		String client,
		Path evidenceDir,
		Path rendezvousDir,
		String token,
		String recordValue,
		String recordOrg,
		String secondUser,
		String secondPassword) {

	/**
	 * Reads the configuration a lane published under {@code prefix}.
	 *
	 * <p>Every value is required. {@link LegacyDatabaseScripts#property} fails
	 * on an absent property rather than defaulting, because a capture that
	 * silently ran against the wrong origin or the wrong record would produce a
	 * plausible wrong answer rather than an error.
	 */
	public static WriteCaptureConfig fromProperties(String prefix) {
		LegacyDatabaseScripts scripts = new LegacyDatabaseScripts(prefix);
		return new WriteCaptureConfig(
				scripts.property("baseUrl").replaceFirst("/+$", ""),
				scripts.property("user"),
				scripts.property("password"),
				scripts.property("client"),
				Path.of(scripts.property("evidenceDir")),
				Path.of(scripts.property("rendezvousDir")),
				scripts.property("token"),
				scripts.property("recordValue"),
				scripts.property("recordOrg"),
				scripts.property("secondUser"),
				scripts.property("secondPassword"));
	}
}
