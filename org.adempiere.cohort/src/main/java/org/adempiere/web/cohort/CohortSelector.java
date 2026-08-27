package org.adempiere.web.cohort;

/**
 * The whole cohort selection rule, in one place.
 *
 * <p>Precedence is fixed and ordered: an invalid configuration beats everything,
 * then the master switch, then the user allowlist, then the role allowlist.
 * Nothing else can select {@link CohortRuntime#MODERN}.
 */
public final class CohortSelector {

	private CohortSelector() {
	}

	/**
	 * @param configuration the fail-closed parsed configuration
	 * @param identity      the completed post-role-selection identity
	 */
	public static CohortDecision select(
			CohortConfiguration configuration, CohortIdentity identity) {
		if (configuration == null || !configuration.valid()) {
			return CohortDecision.of(CohortDecision.Reason.CONFIGURATION_INVALID);
		}
		if (!configuration.enabled()) {
			return CohortDecision.of(CohortDecision.Reason.MASTER_DISABLED);
		}
		if (identity == null) {
			return CohortDecision.of(CohortDecision.Reason.CONFIGURATION_INVALID);
		}
		if (configuration.userIds().contains(identity.userId())) {
			return CohortDecision.of(CohortDecision.Reason.USER_ALLOWLISTED);
		}
		if (configuration.roleIds().contains(identity.roleId())) {
			return CohortDecision.of(CohortDecision.Reason.ROLE_ALLOWLISTED);
		}
		return CohortDecision.of(CohortDecision.Reason.NOT_ALLOWLISTED);
	}
}
