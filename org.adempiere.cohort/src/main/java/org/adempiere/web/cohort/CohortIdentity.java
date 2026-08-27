package org.adempiere.web.cohort;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * The complete authenticated identity a Phase 5e session carries across the
 * handoff.
 *
 * <p>Every field is required. A ticket that omits one is rejected rather than
 * defaulted, because a default here is a silent tenant change: an absent
 * {@code AD_Client_ID} would become the System client, and an absent
 * {@code AD_Org_ID} would become {@code *}.
 *
 * <p>{@code AD_Client_ID}, {@code AD_Org_ID}, {@code AD_Role_ID} and
 * {@code M_Warehouse_ID} may legitimately be {@code 0} (System, {@code *},
 * System Administrator and "no warehouse"), so {@code 0} is accepted and
 * {@code -1} - ADempiere's own "unset" sentinel - is not. {@code AD_User_ID}
 * must be greater than zero: the System user never logs in through the web UI.
 *
 * @param userId      {@code #AD_User_ID}
 * @param roleId      {@code #AD_Role_ID}
 * @param clientId    {@code #AD_Client_ID}
 * @param orgId       {@code #AD_Org_ID}
 * @param warehouseId {@code #M_Warehouse_ID}
 * @param adLanguage  {@code #AD_Language}, e.g. {@code en_US}
 */
public record CohortIdentity(
		int userId,
		int roleId,
		int clientId,
		int orgId,
		int warehouseId,
		String adLanguage) implements Serializable {

	/** ADempiere's {@code AD_Language} form: two lower, underscore, two upper. */
	private static final Pattern AD_LANGUAGE = Pattern.compile("[a-z]{2}_[A-Z]{2}");

	public CohortIdentity {
		if (userId <= 0) {
			throw new IllegalArgumentException("AD_User_ID must be positive");
		}
		requireNonNegative(roleId, "AD_Role_ID");
		requireNonNegative(clientId, "AD_Client_ID");
		requireNonNegative(orgId, "AD_Org_ID");
		requireNonNegative(warehouseId, "M_Warehouse_ID");
		if (adLanguage == null || !AD_LANGUAGE.matcher(adLanguage).matches()) {
			throw new IllegalArgumentException(
					"AD_Language must match " + AD_LANGUAGE.pattern());
		}
	}

	private static void requireNonNegative(int value, String field) {
		if (value < 0) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
	}

	/**
	 * Parses an identity without throwing, for decoding untrusted input.
	 *
	 * @return the identity, or {@code null} when any field is absent or invalid
	 */
	public static CohortIdentity parse(
			String userId,
			String roleId,
			String clientId,
			String orgId,
			String warehouseId,
			String adLanguage) {
		try {
			return new CohortIdentity(
					Integer.parseInt(userId),
					Integer.parseInt(roleId),
					Integer.parseInt(clientId),
					Integer.parseInt(orgId),
					Integer.parseInt(warehouseId),
					adLanguage);
		} catch (IllegalArgumentException | NullPointerException rejected) {
			return null;
		}
	}

	/**
	 * A stable rendering that carries no tenant-identifying free text. Used only
	 * inside the signed ticket payload, never in a log line.
	 */
	public String canonical() {
		return userId + ":" + roleId + ":" + clientId + ":" + orgId + ":"
				+ warehouseId + ":" + adLanguage;
	}
}
