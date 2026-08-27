package org.adempiere.web.cohort;

import java.util.Objects;

/**
 * One {@code AD_SysConfig} row exactly as the database holds it.
 *
 * <p>The row is carried verbatim - including its client, organisation and
 * active flag - so the parser can distinguish "no system row", "one system
 * row", "several system rows" and "only a client/org row". A loader that
 * pre-filtered to a single value would make three of those four cases
 * indistinguishable.
 *
 * @param name     the {@code Name} column; never {@code null}
 * @param value    the {@code Value} column; {@code null} when the column is
 *                 SQL NULL, which the parser treats as malformed
 * @param clientId the {@code AD_Client_ID} column
 * @param orgId    the {@code AD_Org_ID} column
 * @param active   the {@code IsActive} column
 */
public record SysConfigRow(
		String name,
		String value,
		int clientId,
		int orgId,
		boolean active) {

	public SysConfigRow {
		Objects.requireNonNull(name, "name");
	}

	/** A row is system-level only at {@code AD_Client_ID=0, AD_Org_ID=0}. */
	public boolean systemLevel() {
		return clientId == 0 && orgId == 0;
	}

	/** The row scope, for operator-facing diagnostics. */
	public String scope() {
		return systemLevel()
				? "system"
				: "client=" + clientId + ",org=" + orgId;
	}
}
