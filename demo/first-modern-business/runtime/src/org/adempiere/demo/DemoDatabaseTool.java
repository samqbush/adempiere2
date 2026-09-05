package org.adempiere.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

public final class DemoDatabaseTool {
	private static final String EXPECTED_PROJECT = "adempiere-first-modern-demo";
	private static final String EXPECTED_MARKER = "adempiere-first-modern-demo-v1";

	private DemoDatabaseTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			throw new IllegalArgumentException(
				"Usage: DemoDatabaseTool {guard|configure-cohort|pristine}");
		}
		requireExactEnvironment();
		try (Connection connection = DriverManager.getConnection(
				"jdbc:postgresql://" + required("ADEMPIERE_DB_SERVER") + ":"
					+ required("ADEMPIERE_DB_PORT") + "/" + required("ADEMPIERE_DB_NAME"),
				required("ADEMPIERE_DB_USER"), required("ADEMPIERE_DB_PASSWORD"))) {
			assertMarker(connection);
			switch (args[0]) {
				case "guard":
					System.out.println("Demo database ownership guard passed");
					break;
				case "configure-cohort":
					configureCohort(connection);
					System.out.println("Modern cohort configured for demo users");
					break;
				case "pristine":
					assertPristine(connection);
					System.out.println("Demo database is pristine");
					break;
				default:
					throw new IllegalArgumentException("Unknown command: " + args[0]);
			}
		}
	}

	private static void requireExactEnvironment() {
		String instance = required("ADEMPIERE_INSTANCE_ID");
		if (!instance.matches("[0-9a-f]{32}")
				|| !(EXPECTED_PROJECT + "-" + instance).equals(
					required("ADEMPIERE_DEMO_PROJECT"))
				|| !(EXPECTED_MARKER + ":" + instance).equals(
					required("ADEMPIERE_DEMO_MARKER"))
				|| !"database".equals(required("ADEMPIERE_DB_SERVER"))
				|| !"5432".equals(required("ADEMPIERE_DB_PORT"))
				|| !"adempiere_demo".equals(required("ADEMPIERE_DB_NAME"))
				|| !"adempiere_demo".equals(required("ADEMPIERE_DB_USER"))) {
			throw new IllegalStateException(
				"Refusing database mutation outside the exact first-demo target");
		}
	}

	private static void assertMarker(Connection connection) throws Exception {
		String expected = required("ADEMPIERE_DEMO_MARKER");
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT shobj_description(oid, 'pg_database') "
					+ "FROM pg_database WHERE datname = current_database()");
				ResultSet result = statement.executeQuery()) {
			if (!result.next() || !expected.equals(result.getString(1))) {
				throw new IllegalStateException("Database ownership marker is absent");
			}
		}
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT shobj_description(oid, 'pg_authid') "
					+ "FROM pg_roles WHERE rolname = current_user");
				ResultSet result = statement.executeQuery()) {
			if (!result.next() || !expected.equals(result.getString(1))) {
				throw new IllegalStateException("Database role ownership marker is absent");
			}
		}
	}

	private static void configureCohort(Connection connection) throws Exception {
		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.executeUpdate(
				"DELETE FROM AD_SysConfig WHERE Name IN "
					+ "('MODERN_WEB_UI_ENABLED','MODERN_WEB_UI_USER_IDS',"
					+ "'MODERN_WEB_UI_ROLE_IDS')");
		}
		Map<String, String> values = Map.of(
			"MODERN_WEB_UI_ENABLED", "Y",
			"MODERN_WEB_UI_USER_IDS", "101,102",
			"MODERN_WEB_UI_ROLE_IDS", "");
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO AD_SysConfig (AD_SysConfig_ID, AD_Client_ID, AD_Org_ID, "
					+ "IsActive, Created, CreatedBy, Updated, UpdatedBy, Name, Value, "
					+ "EntityType) VALUES ((SELECT coalesce(max(AD_SysConfig_ID), "
					+ "1000000) + 1 FROM AD_SysConfig), 0, 0, 'Y', now(), 100, now(), "
					+ "100, ?, ?, 'D')")) {
			for (Map.Entry<String, String> entry : values.entrySet()) {
				statement.setString(1, entry.getKey());
				statement.setString(2, entry.getValue());
				statement.executeUpdate();
			}
		}
		connection.commit();
	}

	private static void assertPristine(Connection connection) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT count(*) FROM C_BPartner WHERE Value LIKE 'DEMO-%'");
				ResultSet result = statement.executeQuery()) {
			result.next();
			if (result.getInt(1) != 0) {
				throw new IllegalStateException(
					"Reset database contains Business Partners from an earlier demo");
			}
		}
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Required environment variable is absent: " + name);
		}
		return value;
	}
}
