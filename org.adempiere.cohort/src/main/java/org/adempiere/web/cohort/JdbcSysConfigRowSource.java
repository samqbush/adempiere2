package org.adempiere.web.cohort;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * The JDBC {@link SysConfigRowSource}: one statement, one connection, one
 * consistent read of all three reviewed names at every scope.
 *
 * <p>The connection is supplied rather than opened here so the same class works
 * on the Tomcat 9 bridge classpath - where connections come from ADempiere's own
 * {@code DB} pool - without this module depending on ADempiere at all.
 */
public final class JdbcSysConfigRowSource implements SysConfigRowSource {

	/**
	 * Names are bound rather than interpolated, and the projection is explicit,
	 * so a dictionary change that adds a column cannot change what this reads.
	 */
	private static final String SQL =
			"SELECT Name, Value, AD_Client_ID, AD_Org_ID, IsActive "
			+ "FROM AD_SysConfig WHERE Name IN (?, ?, ?)";

	/** Supplies a connection the caller remains responsible for closing. */
	@FunctionalInterface
	public interface ConnectionSource {
		Connection open() throws Exception;
	}

	private final ConnectionSource connections;

	public JdbcSysConfigRowSource(ConnectionSource connections) {
		if (connections == null) {
			throw new IllegalArgumentException("A connection source is required");
		}
		this.connections = connections;
	}

	@Override
	public List<SysConfigRow> read() throws Exception {
		List<String> names = CohortConfigurationKeys.all();
		try (Connection connection = connections.open();
				PreparedStatement statement = connection.prepareStatement(SQL)) {
			for (int index = 0; index < names.size(); index++) {
				statement.setString(index + 1, names.get(index));
			}
			try (ResultSet rows = statement.executeQuery()) {
				List<SysConfigRow> read = new ArrayList<>();
				while (rows.next()) {
					read.add(new SysConfigRow(
							rows.getString(1),
							rows.getString(2),
							rows.getInt(3),
							rows.getInt(4),
							"Y".equals(rows.getString(5))));
				}
				return read;
			}
		}
	}
}
