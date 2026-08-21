package org.adempiere.phase2.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.compiere.Adempiere;
import org.compiere.db.CConnection;
import org.compiere.util.Ini;
import org.compiere.util.SecureEngine;

public final class WritePhase2RuntimeConfig {

	private WritePhase2RuntimeConfig() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 7) {
			throw new IllegalArgumentException(
				"Usage: WritePhase2RuntimeConfig <home> <dbHost> <dbPort> <dbName> <dbUser> <dbPassword> <dbSystemPassword>");
		}

		Path home = Path.of(args[0]);
		Files.createDirectories(home);

		CConnection connection = CConnection.get(
			"PostgreSQL",
			args[1],
			Integer.parseInt(args[2]),
			args[3],
			args[4],
			args[5]);
		connection.setAppsHost(args[1]);
		connection.setAppsPort(1099);
		connection.setConnectionProfile(CConnection.PROFILE_LAN);

		Properties properties = new Properties();
		properties.setProperty("ADEMPIERE_HOME", home.toAbsolutePath().toString());
		properties.setProperty("JAVA_HOME", System.getProperty("java.home"));
		properties.setProperty("ADEMPIERE_JAVA_TYPE", "oracle");
		properties.setProperty("ADEMPIERE_JAVA_OPTIONS",
			"-Dfile.encoding=UTF-8 -Xms128m -Xmx1024m --add-opens java.base/java.lang=ALL-UNNAMED");
		properties.setProperty("ADEMPIERE_DB_TYPE", "PostgreSQL");
		properties.setProperty("ADEMPIERE_DB_PATH", "PostgreSQL");
		properties.setProperty("ADEMPIERE_DB_SERVER", args[1]);
		properties.setProperty("ADEMPIERE_DB_PORT", args[2]);
		properties.setProperty("ADEMPIERE_DB_NAME", args[3]);
		properties.setProperty("ADEMPIERE_DB_SYSTEM", args[6]);
		properties.setProperty("ADEMPIERE_DB_USER", args[4]);
		properties.setProperty("ADEMPIERE_DB_PASSWORD", args[5]);
		properties.setProperty("ADEMPIERE_APPS_TYPE", "tomcat");
		properties.setProperty("ADEMPIERE_APPS_PATH", home.resolve("tomcat").toString());
		properties.setProperty("ADEMPIERE_APPS_SERVER", args[1]);
		properties.setProperty("ADEMPIERE_JNP_PORT", "1099");
		properties.setProperty("ADEMPIERE_WEB_PORT", "8888");
		properties.setProperty("ADEMPIERE_SSL_PORT", "4444");
		properties.setProperty("ADEMPIERE_KEYSTORE", "keystore/myKeystore");
		properties.setProperty("ADEMPIERE_KEYSTOREWEBALIAS", "adempiere");
		properties.setProperty("ADEMPIERE_KEYSTORECODEALIAS", "adempiere");
		properties.setProperty("ADEMPIERE_KEYSTOREPASS", "phase2-test-only");
		properties.setProperty("ADEMPIERE_CERT_CN", args[1]);
		properties.setProperty("ADEMPIERE_CERT_ORG", "ADempiere Phase 2 Smoke");
		properties.setProperty("ADEMPIERE_CERT_ORG_UNIT", "Disposable Runtime");
		properties.setProperty("ADEMPIERE_CERT_LOCATION", "Local");
		properties.setProperty("ADEMPIERE_CERT_STATE", "Local");
		properties.setProperty("ADEMPIERE_CERT_COUNTRY", "US");
		properties.setProperty("ADEMPIERE_MAIL_SERVER", "127.0.0.1");
		properties.setProperty("ADEMPIERE_ADMIN_EMAIL", "phase2-smoke@example.invalid");
		properties.setProperty("ADEMPIERE_MAIL_USER", "");
		properties.setProperty("ADEMPIERE_MAIL_PASSWORD", "");
		properties.setProperty("ADEMPIERE_FTP_SERVER", "127.0.0.1");
		properties.setProperty("ADEMPIERE_FTP_PREFIX", "phase2");
		properties.setProperty("ADEMPIERE_FTP_USER", "anonymous");
		properties.setProperty("ADEMPIERE_FTP_PASSWORD", "phase2-smoke@example.invalid");
		properties.setProperty("ADEMPIERE_VERSION", Adempiere.MAIN_VERSION);
		properties.setProperty("IMPLEMENTATION_VERSION", Adempiere.getImplementationVersion());
		properties.setProperty("IMPLEMENTATION_VENDOR", Adempiere.getImplementationVendor());
		properties.setProperty(Ini.P_CONNECTION, SecureEngine.encrypt(connection.toStringLong()));

		write(home.resolve("AdempiereEnv.properties"), properties);
		write(home.resolve(Ini.ADEMPIERE_PROPERTY_FILE), properties);
	}

	private static void write(Path path, Properties properties) throws IOException {
		try (OutputStream outputStream = Files.newOutputStream(path)) {
			properties.store(outputStream, "Phase 2 disposable runtime");
		}
	}
}
