package org.adempiere.web.context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.adempiere.web.cohort.SysConfigRow;
import org.adempiere.web.route.ContextRoutingPolicy;
import org.adempiere.web.route.ContextSwitch;
import org.compiere.util.DB;

/** Process-local collaborators for a Phase 5f public-context adapter. */
final class ContextRoutingBridge {

	static final String BACKEND_PROPERTY = "adempiere.phase5f.modernBackend";
	static final String BACKEND_ENVIRONMENT = "ADEMPIERE_PHASE5F_MODERN_BACKEND";

	private static volatile ContextRoutingBridge instance;

	private final String backend;
	private final String deploymentId;
	private final Function<String, ContextSwitch> switches;

	private ContextRoutingBridge(
			String backend,
			String deploymentId,
			Function<String, ContextSwitch> switches) {
		this.backend = normalise(backend);
		this.deploymentId = deploymentId;
		this.switches = switches;
	}

	static ContextRoutingBridge initialise() {
		String backend = System.getProperty(BACKEND_PROPERTY);
		if (backend == null || backend.isBlank()) {
			backend = System.getenv(BACKEND_ENVIRONMENT);
		}
		instance = new ContextRoutingBridge(
				backend, UUID.randomUUID().toString(),
				ContextRoutingBridge::readSwitch);
		return instance;
	}

	static void install(
			String backend,
			String deploymentId,
			Function<String, ContextSwitch> switches) {
		instance = new ContextRoutingBridge(backend, deploymentId, switches);
	}

	static ContextRoutingBridge current() {
		return instance;
	}

	static void shutdown() {
		instance = null;
	}

	boolean routingPossible() {
		return backend != null;
	}

	String backend() {
		return backend;
	}

	String deploymentId() {
		return deploymentId;
	}

	ContextSwitch currentSwitch(ContextRoutingPolicy policy) {
		if (!policy.eligibleInPhase5f()) {
			return new ContextSwitch(true, false, List.of());
		}
		if (!routingPossible()) {
			return ContextSwitch.invalid("the modern backend is not configured");
		}
		try {
			return switches.apply(policy.enableKey());
		} catch (RuntimeException unreadable) {
			return ContextSwitch.invalid("the context switch could not be read");
		}
	}

	private static ContextSwitch readSwitch(String key) {
		String sql = "SELECT Name, Value, AD_Client_ID, AD_Org_ID, IsActive "
				+ "FROM AD_SysConfig WHERE Name = ?";
		try (Connection connection = DB.getConnectionRO();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, key);
			try (ResultSet rows = statement.executeQuery()) {
				List<SysConfigRow> values = new ArrayList<>();
				while (rows.next()) {
					values.add(new SysConfigRow(
							rows.getString(1), rows.getString(2),
							rows.getInt(3), rows.getInt(4),
							"Y".equals(rows.getString(5))));
				}
				return ContextSwitch.parse(key, values);
			}
		} catch (Exception failure) {
			return ContextSwitch.invalid("the context switch could not be read");
		}
	}

	private static String normalise(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String result = value.trim();
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}
}
