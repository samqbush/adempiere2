package org.adempiere.phase3;

import java.util.Collection;
import java.util.List;

record MetadataValidationReport(int recordsChecked, List<MetadataFinding> findings) {

	MetadataValidationReport(int recordsChecked, Collection<MetadataFinding> findings) {
		this(recordsChecked, findings.stream().sorted().toList());
	}

	void assertValid() {
		if (recordsChecked <= 0) {
			throw new AssertionError("Phase 3 metadata validation checked zero records");
		}
		if (!findings.isEmpty()) {
			StringBuilder message = new StringBuilder()
				.append("Phase 3 metadata/extension graph validation found ")
				.append(findings.size())
				.append(" problem(s) after checking ")
				.append(recordsChecked)
				.append(" records:");
			findings.forEach(finding -> message.append(System.lineSeparator())
				.append(" - ")
				.append(finding));
			throw new AssertionError(message.toString());
		}
	}
}
