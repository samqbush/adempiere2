package org.adempiere.web.cohort;

import java.util.List;

/**
 * The three {@code AD_SysConfig} names Phase 5e reads, and nothing else.
 *
 * <p>Phase 5e deliberately does not read them through
 * {@code MSysConfig.getValue}. That helper returns the first row a
 * client-scoped query happens to produce and serves it from a shared cache, so
 * it cannot answer the two questions the cohort decision depends on: whether a
 * second active system-level row exists, and whether the value was readable at
 * all. {@link CohortConfigurationRepository} therefore loads all three names in
 * one statement through {@link SysConfigRowSource}.
 */
public final class CohortConfigurationKeys {

	/** Master enable. Only the exact value {@code Y} enables modern routing. */
	public static final String ENABLED = "MODERN_WEB_UI_ENABLED";

	/** Strict comma-separated positive decimal {@code AD_User_ID} values. */
	public static final String USER_IDS = "MODERN_WEB_UI_USER_IDS";

	/** Strict comma-separated positive decimal {@code AD_Role_ID} values. */
	public static final String ROLE_IDS = "MODERN_WEB_UI_ROLE_IDS";

	/** The exact value that enables modern routing. Nothing else does. */
	public static final String ENABLED_VALUE = "Y";

	private static final List<String> ALL = List.of(ENABLED, USER_IDS, ROLE_IDS);

	private CohortConfigurationKeys() {
	}

	/** The reviewed key set, in a stable order. */
	public static List<String> all() {
		return ALL;
	}
}
