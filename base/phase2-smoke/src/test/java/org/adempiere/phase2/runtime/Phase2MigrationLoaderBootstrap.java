package org.adempiere.phase2.runtime;

import org.adempiere.phase2.Phase2RuntimeBootstrap;
import org.adempiere.process.MigrationLoader;

public final class Phase2MigrationLoaderBootstrap {

	private Phase2MigrationLoaderBootstrap() {
	}

	public static void main(String[] args) {
		if (args.length != 7) {
			throw new IllegalArgumentException(
				"Usage: Phase2MigrationLoaderBootstrap <home> <dbHost> <dbPort> <dbName> <dbUser> <dbPassword> <migrationPath>");
		}

		Phase2RuntimeBootstrap.bootstrapServerRuntime(
			args[0],
			args[1],
			args[2],
			args[3],
			args[4],
			args[5]);
		MigrationLoader.main(new String[] { args[6] });
		System.exit(0);
	}
}
