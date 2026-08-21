package org.adempiere.phase3;

import java.util.Objects;

record MetadataFinding(
	String check,
	String recordType,
	int recordId,
	String recordName,
	String entityType,
	String className,
	String detail) implements Comparable<MetadataFinding> {

	MetadataFinding {
		check = required(check, "check");
		recordType = required(recordType, "recordType");
		recordName = valueOrDash(recordName);
		entityType = valueOrDash(entityType);
		className = valueOrDash(className);
		detail = required(detail, "detail");
	}

	@Override
	public int compareTo(MetadataFinding other) {
		return toString().compareTo(other.toString());
	}

	@Override
	public String toString() {
		return check + ": " + recordType + "[ID=" + recordId + ", name=" + recordName
			+ ", entityType=" + entityType + ", class=" + className + "] " + detail;
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	private static String valueOrDash(String value) {
		return Objects.requireNonNullElse(value, "-").trim().isEmpty() ? "-" : value.trim();
	}
}
