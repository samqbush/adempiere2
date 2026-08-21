package org.adempiere.phase2.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.adempiere.phase2.Phase2RuntimeBootstrap;
import org.compiere.util.DB;

public final class VerifyPhase2DatabaseRelease {

	private VerifyPhase2DatabaseRelease() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 7) {
			throw new IllegalArgumentException(
				"Usage: VerifyPhase2DatabaseRelease <home> <dbHost> <dbPort> <dbName> <dbUser> <dbPassword> <expectedReleasePrefix>");
		}

		Phase2RuntimeBootstrap.bootstrapServerRuntime(
			args[0],
			args[1],
			args[2],
			args[3],
			args[4],
			args[5]);

		String releaseNo = DB.getSQLValueStringEx(null,
			"SELECT ReleaseNo FROM AD_System WHERE AD_System_ID=0");
		String version = DB.getSQLValueStringEx(null,
			"SELECT Version FROM AD_System WHERE AD_System_ID=0");

		if (releaseNo == null || !releaseNo.startsWith(args[6])) {
			throw new IllegalStateException(
				"Expected AD_System.ReleaseNo to start with " + args[6] + " but found " + releaseNo);
		}
		if (version == null || version.isBlank()) {
			throw new IllegalStateException("AD_System.Version is blank after the Phase 2 migration loader run");
		}

		String evidence = String.format("AD_System ReleaseNo=%s Version=%s%n", releaseNo, version);
		Path logDirectory = Path.of(args[0], "log");
		Files.createDirectories(logDirectory);
		Files.writeString(logDirectory.resolve("phase2-database-release.txt"), evidence,
			StandardCharsets.UTF_8);
		System.out.print(evidence);
		System.exit(0);
	}
}
