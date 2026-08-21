package org.adempiere.phase2;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.compiere.Adempiere;
import org.compiere.db.CConnection;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.SecureEngine;
import org.compiere.util.Trx;

public final class Phase2RuntimeBootstrap {

	private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

	private Phase2RuntimeBootstrap() {
	}

	public static synchronized void bootstrapServerRuntime() {
		bootstrapServerRuntime(
			Phase2SmokeConfig.runtimeHome().toString(),
			Phase2SmokeConfig.dbHost(),
			Phase2SmokeConfig.dbPort(),
			Phase2SmokeConfig.dbName(),
			Phase2SmokeConfig.dbUser(),
			Phase2SmokeConfig.dbPassword());
	}

	public static synchronized void bootstrapServerRuntime(
		String home,
		String host,
		String port,
		String dbName,
		String dbUser,
		String dbPassword) {

		if (BOOTSTRAPPED.get()) {
			return;
		}

		Path runtimeHome = Path.of(home);
		Path propertyFile = runtimeHome.resolve("AdempiereEnv.properties");
		if (!Files.isDirectory(runtimeHome) || !Files.isRegularFile(propertyFile)) {
			throw new IllegalStateException(
				"Phase 2 runtime home is missing or incomplete at " + runtimeHome +
					". Run :base:preparePhase2AdempiereHome and :base:writePhase2RuntimeConfig first.");
		}

		System.setProperty(Ini.ADEMPIERE_HOME, runtimeHome.toAbsolutePath().toString());
		System.setProperty("PropertyFile", propertyFile.toAbsolutePath().toString());
		System.setProperty(Ini.P_CONNECTION,
			SecureEngine.encrypt(connection(host, port, dbName, dbUser, dbPassword).toStringLong()));

		injectConnection(connection(host, port, dbName, dbUser, dbPassword));

		if (!Adempiere.startupEnvironment(false)) {
			throw new IllegalStateException(
				"ADempiere startupEnvironment(false) failed against the prepared Phase 2 runtime.");
		}

		Properties ctx = Env.getCtx();
		Env.setContext(ctx, "#AD_Client_ID", 11);
		Env.setContext(ctx, "#AD_Org_ID", 11);
		Env.setContext(ctx, "#AD_User_ID", 100);
		Env.setContext(ctx, "#AD_Language", "en_US");

		BOOTSTRAPPED.set(true);
	}

	public static Set<String> snapshotTransactionNames() {
		try {
			Field cacheField = Trx.class.getDeclaredField("s_cache");
			cacheField.setAccessible(true);
			Object value = cacheField.get(null);
			if (value instanceof Map<?, ?> cache) {
				TreeSet<String> keys = new TreeSet<>();
				cache.keySet().forEach(key -> keys.add(String.valueOf(key)));
				return keys;
			}
			return Collections.emptySet();
		}
		catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Unable to inspect Trx cache", exception);
		}
	}

	private static CConnection connection(
		String host,
		String port,
		String dbName,
		String dbUser,
		String dbPassword) {

		CConnection connection = CConnection.get(
			"PostgreSQL",
			host,
			Integer.parseInt(port),
			dbName,
			dbUser,
			dbPassword);
		connection.setAppsHost(host);
		connection.setAppsPort(1099);
		connection.setConnectionProfile(CConnection.PROFILE_LAN);
		return connection;
	}

	private static void injectConnection(CConnection connection) {
		try {
			Field field = CConnection.class.getDeclaredField("s_cc");
			field.setAccessible(true);
			field.set(null, connection);
			DB.setDBTarget(connection);
		}
		catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Unable to seed CConnection for Phase 2 runtime", exception);
		}
	}
}
