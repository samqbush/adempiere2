package org.adempiere.web.bridge;

import java.util.Properties;

import org.adempiere.web.cohort.CohortIdentity;
import org.compiere.util.Env;

/**
 * Reads the completed post-role-selection identity out of an ADempiere session
 * context.
 *
 * <p>"Completed" is a state, not an event name. {@link #complete(Properties)}
 * asks the same four questions ADempiere's own
 * {@code SessionManager.isUserLoggedIn} asks, and then two more that the cohort
 * decision genuinely needs: a language, because the modern runtime has to render
 * in it, and a resolvable warehouse, because {@code Login.loadPreferences} sets
 * it during role completion and its absence means role completion has not
 * happened yet.
 *
 * <p>An event-name check would be wrong here in both directions: the OK button
 * on the role panel fires long before {@code loginCompleted()} finishes, and a
 * deployment that reaches the desktop by any other path - an external
 * authentication callback, a role change - would never be seen at all.
 */
public final class LegacyIdentity {

	/** {@code #M_Warehouse_ID} is legitimately absent for a warehouse-less role. */
	private static final int NO_WAREHOUSE = 0;

	private LegacyIdentity() {
	}

	/** Whether the context carries a complete, role-selected identity. */
	public static boolean complete(Properties ctx) {
		return read(ctx) != null;
	}

	/**
	 * @return the identity, or {@code null} when role selection has not
	 *         completed or the context is unusable
	 */
	public static CohortIdentity read(Properties ctx) {
		if (ctx == null) {
			return null;
		}
		Integer userId = integer(ctx, "#AD_User_ID");
		Integer roleId = integer(ctx, "#AD_Role_ID");
		Integer clientId = integer(ctx, "#AD_Client_ID");
		Integer orgId = integer(ctx, "#AD_Org_ID");
		String language = Env.getContext(ctx, Env.LANGUAGE);
		if (userId == null || roleId == null || clientId == null || orgId == null
				|| language == null || language.isBlank()) {
			return null;
		}
		Integer warehouseId = integer(ctx, "#M_Warehouse_ID");
		if (warehouseId == null) {
			// Role completion always writes this key; its absence means
			// Login.loadPreferences has not run yet, so the identity is not
			// complete even though the four core keys are set.
			return null;
		}
		try {
			return new CohortIdentity(
					userId,
					roleId,
					clientId,
					orgId,
					warehouseId < 0 ? NO_WAREHOUSE : warehouseId,
					language);
		} catch (IllegalArgumentException incomplete) {
			return null;
		}
	}

	private static Integer integer(Properties ctx, String key) {
		String value = Env.getContext(ctx, key);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException malformed) {
			return null;
		}
	}
}
