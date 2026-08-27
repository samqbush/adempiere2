package org.adempiere.web.cohort;

import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The parsed, fail-closed Phase 5e cohort configuration.
 *
 * <p>An instance is either {@linkplain #valid() valid} or it is not. An invalid
 * configuration selects {@link CohortRuntime#LEGACY} for every new session and
 * carries the operator-facing reasons that made it invalid. It never carries a
 * partially usable allowlist, because "half a fail-closed allowlist" is exactly
 * the shape that silently routes the wrong people.
 */
public final class CohortConfiguration {

	private final boolean valid;
	private final boolean enabled;
	private final NavigableSet<Integer> userIds;
	private final NavigableSet<Integer> roleIds;
	private final List<String> problems;
	private final List<String> ignoredScopedRows;

	private CohortConfiguration(
			boolean valid,
			boolean enabled,
			Set<Integer> userIds,
			Set<Integer> roleIds,
			List<String> problems,
			List<String> ignoredScopedRows) {
		this.valid = valid;
		this.enabled = enabled;
		this.userIds = Collections.unmodifiableNavigableSet(new TreeSet<>(userIds));
		this.roleIds = Collections.unmodifiableNavigableSet(new TreeSet<>(roleIds));
		this.problems = List.copyOf(problems);
		this.ignoredScopedRows = List.copyOf(ignoredScopedRows);
	}

	static CohortConfiguration usable(
			boolean enabled,
			Set<Integer> userIds,
			Set<Integer> roleIds,
			List<String> ignoredScopedRows) {
		return new CohortConfiguration(
				true, enabled, userIds, roleIds, List.of(), ignoredScopedRows);
	}

	static CohortConfiguration invalid(
			List<String> problems, List<String> ignoredScopedRows) {
		if (problems.isEmpty()) {
			throw new IllegalArgumentException(
					"An invalid cohort configuration must name at least one problem");
		}
		return new CohortConfiguration(
				false, false, Set.of(), Set.of(), problems, ignoredScopedRows);
	}

	/** Whether the complete configuration was readable and well formed. */
	public boolean valid() {
		return valid;
	}

	/** Whether the master switch holds the exact enabling value. */
	public boolean enabled() {
		return enabled;
	}

	/** The allowlisted {@code AD_User_ID} values; empty when none apply. */
	public NavigableSet<Integer> userIds() {
		return userIds;
	}

	/** The allowlisted {@code AD_Role_ID} values; empty when none apply. */
	public NavigableSet<Integer> roleIds() {
		return roleIds;
	}

	/** Operator-facing reasons the configuration is unusable. */
	public List<String> problems() {
		return problems;
	}

	/**
	 * Client- or organisation-scoped rows that were ignored.
	 *
	 * <p>They are reported rather than silently dropped: an operator who wrote
	 * a client-scoped row expecting it to take effect has to be able to see
	 * that it did not.
	 */
	public List<String> ignoredScopedRows() {
		return ignoredScopedRows;
	}

	@Override
	public String toString() {
		if (!valid) {
			return "CohortConfiguration[invalid, problems=" + problems + "]";
		}
		return "CohortConfiguration[enabled=" + enabled
				+ ", users=" + userIds.size()
				+ ", roles=" + roleIds.size()
				+ ", ignoredScopedRows=" + ignoredScopedRows.size() + "]";
	}
}
