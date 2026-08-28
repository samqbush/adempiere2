package org.adempiere.web.route;

import java.util.ArrayList;
import java.util.List;

import org.adempiere.web.cohort.SysConfigRow;

/** Fail-closed parser for one Phase 5f context-level system switch. */
public record ContextSwitch(boolean valid, boolean enabled, List<String> problems) {

	public ContextSwitch {
		problems = List.copyOf(problems);
	}

	public static ContextSwitch parse(String key, List<SysConfigRow> rows) {
		if (key == null || key.isBlank() || rows == null) {
			return invalid("the context switch could not be read");
		}
		List<String> problems = new ArrayList<>();
		List<SysConfigRow> activeSystem = rows.stream()
				.filter(row -> {
					if (!key.equals(row.name())) {
						problems.add("unexpected row " + row.name());
						return false;
					}
					return row.active() && row.systemLevel();
				})
				.toList();
		if (activeSystem.size() > 1) {
			problems.add(key + " has " + activeSystem.size()
					+ " active system-level rows");
		}
		if (!problems.isEmpty()) {
			return new ContextSwitch(false, false, problems);
		}
		if (activeSystem.isEmpty()) {
			return new ContextSwitch(true, false, List.of());
		}
		String value = activeSystem.get(0).value();
		if (value == null || (!"Y".equals(value) && !"N".equals(value))) {
			return invalid(key + " must be exactly Y or N");
		}
		return new ContextSwitch(true, "Y".equals(value), List.of());
	}

	public static ContextSwitch invalid(String problem) {
		return new ContextSwitch(false, false, List.of(problem));
	}
}
