package org.adempiere.phase3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.adempiere.phase2.Phase2RuntimeBootstrap;
import org.compiere.util.DB;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(Phase3MetadataTags.METADATA)
@Tag(Phase3MetadataTags.DATABASE)
class Phase3MetadataDatabaseTest {

	@Test
	void validatesDatabaseBackedMetadataAndExtensionGraph() throws SQLException, IOException {
		Phase2RuntimeBootstrap.bootstrapServerRuntime();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		assertTrue(loader != null, "Phase 3 validation requires a context class loader");

		try (Connection connection = DB.getConnectionRO()) {
			MetadataValidationReport report =
				new MetadataExtensionGraphValidator(connection, loader).validate();
			assertWithQuarantine(report);
		}
	}

	private static void assertWithQuarantine(MetadataValidationReport report) throws IOException {
		Path quarantineFile = Path.of(requiredProperty("phase3.metadata.quarantine"));
		Map<String, QuarantineEntry> quarantine = loadQuarantine(quarantineFile);
		Map<String, MetadataFinding> findings = report.findings().stream()
			.collect(Collectors.toMap(
				Phase3MetadataDatabaseTest::key,
				finding -> finding,
				(left, right) -> {
					throw new IllegalStateException("Duplicate metadata finding: " + left);
				},
				LinkedHashMap::new));

		List<MetadataFinding> unexpected = findings.entrySet().stream()
			.filter(entry -> !quarantine.containsKey(entry.getKey()))
			.map(Map.Entry::getValue)
			.toList();
		new MetadataValidationReport(report.recordsChecked(), unexpected).assertValid();

		Set<String> stale = quarantine.keySet().stream()
			.filter(entry -> !findings.containsKey(entry))
			.collect(Collectors.toSet());
		assertTrue(stale.isEmpty(), "Stale Phase 3 metadata quarantine entries: " + stale);

		Path output = Path.of(requiredProperty("phase3.metadata.report"));
		Files.createDirectories(output.getParent());
		StringBuilder evidence = new StringBuilder()
			.append("# records_checked\t").append(report.recordsChecked()).append('\n')
			.append("# status\trecord_type\trecord_id\tclass_name\tclosing_phase\treason\n");
		quarantine.forEach((entryKey, entry) -> evidence
			.append("quarantined\t")
			.append(entry.recordType()).append('\t')
			.append(entry.recordId()).append('\t')
			.append(entry.className()).append('\t')
			.append(entry.closingPhase()).append('\t')
			.append(entry.reason()).append('\n'));
		Files.writeString(output, evidence, StandardCharsets.UTF_8);
	}

	private static Map<String, QuarantineEntry> loadQuarantine(Path file) throws IOException {
		assertTrue(Files.isRegularFile(file), "Missing metadata quarantine: " + file);
		Map<String, QuarantineEntry> entries = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split("\\t", -1);
			assertTrue(fields.length == 5, "Invalid metadata quarantine row: " + line);
			QuarantineEntry entry = new QuarantineEntry(
				fields[0], Integer.parseInt(fields[1]), fields[2], fields[3], fields[4]);
			assertTrue(entries.put(key(entry.recordType(), entry.recordId(), entry.className()),
				entry) == null, "Duplicate metadata quarantine row: " + line);
		}
		assertTrue(!entries.isEmpty(), "Metadata quarantine must not be empty");
		return entries;
	}

	private static String key(MetadataFinding finding) {
		return key(finding.recordType(), finding.recordId(), finding.className());
	}

	private static String key(String recordType, int recordId, String className) {
		return recordType + '\t' + recordId + '\t' + className;
	}

	private static String requiredProperty(String name) {
		String value = System.getProperty(name);
		assertTrue(value != null && !value.isBlank(), "Missing system property " + name);
		return value;
	}

	private record QuarantineEntry(
		String recordType,
		int recordId,
		String className,
		String closingPhase,
		String reason) {
	}
}
