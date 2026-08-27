package org.adempiere.web.cohort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Phase 5e cohort configuration grammar, case by case.
 *
 * <p>Every row of the reviewed decision table in the Phase 5e ADR has a named
 * test here. A mutation to precedence, to the strictness of the identifier
 * grammar, or to the duplicate rule must make one of them fail.
 */
@Tag("UnitTest")
@DisplayName("Phase 5e cohort configuration")
class CohortConfigurationParserTest {

	private static SysConfigRow system(String name, String value) {
		return new SysConfigRow(name, value, 0, 0, true);
	}

	@Test
	@DisplayName("an absent master switch is valid and selects legacy")
	void absentMasterSwitchIsValidAndDisabled() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(List.of());
		assertTrue(configuration.valid());
		assertFalse(configuration.enabled());
		assertEquals(Set.of(), configuration.userIds());
		assertEquals(Set.of(), configuration.roleIds());
	}

	@Test
	@DisplayName("only the exact value Y enables modern routing")
	void onlyExactYEnables() {
		assertTrue(CohortConfigurationParser
				.parse(List.of(system(CohortConfigurationKeys.ENABLED, "Y")))
				.enabled());
	}

	@ParameterizedTest(name = "MODERN_WEB_UI_ENABLED={0} stays legacy")
	@ValueSource(strings = {"y", "YES", "true", "N", " Y", "Y ", "1", ""})
	@DisplayName("anything other than Y disables modern routing without erroring")
	void otherValuesDisable(String value) {
		CohortConfiguration configuration = CohortConfigurationParser
				.parse(List.of(system(CohortConfigurationKeys.ENABLED, value)));
		assertTrue(configuration.valid(), "an unexpected value is not a parse error");
		assertFalse(configuration.enabled());
	}

	@Test
	@DisplayName("a null master value invalidates the whole configuration")
	void nullMasterValueIsMalformed() {
		CohortConfiguration configuration = CohortConfigurationParser
				.parse(List.of(system(CohortConfigurationKeys.ENABLED, null)));
		assertFalse(configuration.valid());
		assertTrue(configuration.problems().get(0).contains("null value"));
	}

	@Test
	@DisplayName("a duplicate active system row invalidates the whole configuration")
	void duplicateSystemRowIsInvalid() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(List.of(
				system(CohortConfigurationKeys.ENABLED, "Y"),
				system(CohortConfigurationKeys.ENABLED, "N"),
				system(CohortConfigurationKeys.USER_IDS, "101")));
		assertFalse(configuration.valid());
		assertFalse(configuration.enabled());
		assertEquals(Set.of(), configuration.userIds(),
				"an invalid configuration must not expose a partial allowlist");
		assertTrue(configuration.problems().get(0)
				.contains("2 active system-level rows"));
	}

	@Test
	@DisplayName("an inactive duplicate is ignored rather than counted")
	void inactiveDuplicateIsIgnored() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(List.of(
				system(CohortConfigurationKeys.ENABLED, "Y"),
				new SysConfigRow(CohortConfigurationKeys.ENABLED, "N", 0, 0, false)));
		assertTrue(configuration.valid());
		assertTrue(configuration.enabled());
	}

	@Test
	@DisplayName("client and org rows are ignored and reported")
	void scopedRowsAreIgnoredAndReported() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(List.of(
				new SysConfigRow(CohortConfigurationKeys.ENABLED, "Y", 11, 0, true),
				new SysConfigRow(CohortConfigurationKeys.USER_IDS, "101", 11, 12, true)));
		assertTrue(configuration.valid());
		assertFalse(configuration.enabled(),
				"a client-scoped master switch must not enable modern routing");
		assertEquals(Set.of(), configuration.userIds());
		assertEquals(
				List.of("MODERN_WEB_UI_ENABLED at client=11,org=0",
						"MODERN_WEB_UI_USER_IDS at client=11,org=12"),
				configuration.ignoredScopedRows());
	}

	@Test
	@DisplayName("a valid system row still applies when a client row exists")
	void systemRowWinsOverIgnoredScopedRow() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(List.of(
				system(CohortConfigurationKeys.ENABLED, "Y"),
				system(CohortConfigurationKeys.USER_IDS, "101"),
				new SysConfigRow(CohortConfigurationKeys.USER_IDS, "9999", 11, 0, true)));
		assertTrue(configuration.valid());
		assertEquals(Set.of(101), configuration.userIds());
		assertEquals(1, configuration.ignoredScopedRows().size());
	}

	@ParameterizedTest(name = "identifier list \"{0}\" parses")
	@ValueSource(strings = {"", "101", "101,102", "1,2,3", "999999999"})
	@DisplayName("the identifier grammar accepts exactly the documented forms")
	void identifierGrammarAccepts(String value) {
		assertTrue(CohortConfigurationParser
				.parse(List.of(system(CohortConfigurationKeys.USER_IDS, value)))
				.valid());
	}

	@ParameterizedTest(name = "identifier list \"{0}\" is malformed")
	@ValueSource(strings = {
			"0", "-1", "+1", "01", " 101", "101 ", "101, 102", "101,,102", "101,",
			",101", "101;102", "1e3", "abc", "101,abc", "1234567890", "101,101"})
	@DisplayName("the identifier grammar rejects everything else, fail-closed")
	void identifierGrammarRejects(String value) {
		CohortConfiguration configuration = CohortConfigurationParser.parse(
				List.of(system(CohortConfigurationKeys.ENABLED, "Y"),
						system(CohortConfigurationKeys.USER_IDS, value)));
		assertFalse(configuration.valid(), value + " must be rejected");
		assertFalse(configuration.enabled(),
				"an invalid configuration must not stay enabled");
	}

	@Test
	@DisplayName("a malformed role list invalidates the user list too")
	void oneMalformedKeyInvalidatesEverything() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(List.of(
				system(CohortConfigurationKeys.ENABLED, "Y"),
				system(CohortConfigurationKeys.USER_IDS, "101"),
				system(CohortConfigurationKeys.ROLE_IDS, "oops")));
		assertFalse(configuration.valid());
		assertEquals(Set.of(), configuration.userIds());
	}

	@Test
	@DisplayName("a null row list is an unreadable configuration")
	void nullRowsAreUnreadable() {
		CohortConfiguration configuration = CohortConfigurationParser.parse(null);
		assertFalse(configuration.valid());
	}

	@Test
	@DisplayName("an unexpected key is a parse error, not a silent ignore")
	void unexpectedKeyIsAnError() {
		CohortConfiguration configuration = CohortConfigurationParser
				.parse(List.of(system("MODERN_WEB_UI_SOMETHING_ELSE", "Y")));
		assertFalse(configuration.valid());
	}
}
