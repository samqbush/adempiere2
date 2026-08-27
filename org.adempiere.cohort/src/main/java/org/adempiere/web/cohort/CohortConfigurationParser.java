package org.adempiere.web.cohort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Parses the three reviewed {@code AD_SysConfig} rows into a fail-closed
 * {@link CohortConfiguration}.
 *
 * <p>The grammar is deliberately narrow, and every deviation from it
 * invalidates the <em>complete</em> configuration rather than only the offending
 * key. Routing a user to a different runtime is a security-relevant decision, so
 * a configuration that cannot be read exactly as written must not be
 * half-applied.
 *
 * <h2>Grammar</h2>
 * <ul>
 *   <li>Only rows at {@code AD_Client_ID=0, AD_Org_ID=0} are considered.
 *       Client- or organisation-scoped rows are ignored and reported.</li>
 *   <li>Inactive rows are ignored entirely; they are not "a second row".</li>
 *   <li>Each key permits at most one active system-level row. Two rows for the
 *       same key invalidate the configuration.</li>
 *   <li>{@code MODERN_WEB_UI_ENABLED} enables modern routing only on the exact
 *       value {@code Y}. Any other non-null value disables it. An absent row
 *       disables it. Neither is an error.</li>
 *   <li>{@code MODERN_WEB_UI_USER_IDS} and {@code MODERN_WEB_UI_ROLE_IDS} are
 *       either empty or a comma-separated list of positive decimal integers
 *       with no sign, no whitespace, no leading zero and no repetition. An
 *       absent row is an empty allowlist.</li>
 *   <li>A SQL {@code NULL} value on a present row is malformed.</li>
 * </ul>
 *
 * <p>Because the identifier grammar requires a <em>positive</em> value, the
 * System role ({@code AD_Role_ID=0}) and the System user ({@code AD_User_ID=0})
 * can never be allowlisted. That is intentional and is recorded in the Phase 5e
 * ADR.
 */
public final class CohortConfigurationParser {

	/**
	 * Empty, or positive decimal integers without sign, whitespace or leading
	 * zeros, separated by single commas.
	 */
	private static final Pattern ID_LIST =
			Pattern.compile("|[1-9][0-9]{0,8}(,[1-9][0-9]{0,8})*");

	private CohortConfigurationParser() {
	}

	/**
	 * @param rows every {@code AD_SysConfig} row whose {@code Name} is one of
	 *             {@link CohortConfigurationKeys#all()}, in any order
	 */
	public static CohortConfiguration parse(List<SysConfigRow> rows) {
		if (rows == null) {
			return CohortConfiguration.invalid(
					List.of("the configuration could not be read"), List.of());
		}

		List<String> problems = new ArrayList<>();
		List<String> ignored = new ArrayList<>();
		List<SysConfigRow> system = new ArrayList<>();

		for (SysConfigRow row : rows) {
			if (!CohortConfigurationKeys.all().contains(row.name())) {
				problems.add("unexpected row " + row.name()
						+ " was supplied to the cohort parser");
				continue;
			}
			if (!row.active()) {
				continue;
			}
			if (row.systemLevel()) {
				system.add(row);
			} else {
				ignored.add(row.name() + " at " + row.scope());
			}
		}

		SysConfigRow enabledRow = single(system, CohortConfigurationKeys.ENABLED, problems);
		SysConfigRow userRow = single(system, CohortConfigurationKeys.USER_IDS, problems);
		SysConfigRow roleRow = single(system, CohortConfigurationKeys.ROLE_IDS, problems);

		boolean enabled = false;
		if (enabledRow != null) {
			if (enabledRow.value() == null) {
				problems.add(CohortConfigurationKeys.ENABLED + " has a null value");
			} else {
				enabled = CohortConfigurationKeys.ENABLED_VALUE.equals(enabledRow.value());
			}
		}

		Set<Integer> userIds = identifiers(userRow, CohortConfigurationKeys.USER_IDS, problems);
		Set<Integer> roleIds = identifiers(roleRow, CohortConfigurationKeys.ROLE_IDS, problems);

		if (!problems.isEmpty()) {
			return CohortConfiguration.invalid(problems, ignored);
		}
		return CohortConfiguration.usable(enabled, userIds, roleIds, ignored);
	}

	private static SysConfigRow single(
			List<SysConfigRow> system, String name, List<String> problems) {
		List<SysConfigRow> matches = system.stream()
				.filter(row -> name.equals(row.name()))
				.toList();
		if (matches.size() > 1) {
			problems.add(name + " has " + matches.size()
					+ " active system-level rows; exactly one is permitted");
			return null;
		}
		return matches.isEmpty() ? null : matches.get(0);
	}

	private static Set<Integer> identifiers(
			SysConfigRow row, String name, List<String> problems) {
		if (row == null) {
			return Set.of();
		}
		String value = row.value();
		if (value == null) {
			problems.add(name + " has a null value");
			return Set.of();
		}
		if (!ID_LIST.matcher(value).matches()) {
			problems.add(name + " is not a strict comma-separated list of positive "
					+ "decimal identifiers");
			return Set.of();
		}
		if (value.isEmpty()) {
			return Set.of();
		}
		String[] fields = value.split(",", -1);
		Set<Integer> parsed = new LinkedHashSet<>();
		for (String field : fields) {
			int identifier = Integer.parseInt(field, 10);
			if (!parsed.add(identifier)) {
				problems.add(name + " repeats identifier " + identifier);
				return Set.of();
			}
		}
		return new TreeSet<>(parsed);
	}

	/**
	 * The exact grammar, rendered for the reviewed contract file and for
	 * operator documentation. Kept here so the documented grammar and the
	 * implemented grammar cannot drift.
	 */
	public static String identifierGrammar() {
		return ID_LIST.pattern();
	}
}
