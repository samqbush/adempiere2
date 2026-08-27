package org.adempiere.web.cohort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Precedence, stickiness, caching and fail-closed behaviour of the selector. */
@Tag("UnitTest")
@DisplayName("Phase 5e cohort selection")
class CohortSelectorTest {

	private static final CohortIdentity GARDEN_ADMIN =
			new CohortIdentity(101, 102, 11, 11, 103, "en_US");
	private static final CohortIdentity OTHER =
			new CohortIdentity(102, 103, 11, 11, 103, "de_DE");

	private static CohortConfiguration configuration(String enabled, String users, String roles) {
		return CohortConfigurationParser.parse(List.of(
				new SysConfigRow(CohortConfigurationKeys.ENABLED, enabled, 0, 0, true),
				new SysConfigRow(CohortConfigurationKeys.USER_IDS, users, 0, 0, true),
				new SysConfigRow(CohortConfigurationKeys.ROLE_IDS, roles, 0, 0, true)));
	}

	@Test
	@DisplayName("master off selects legacy even when both allowlists match")
	void masterOffBeatsBothAllowlists() {
		CohortDecision decision = CohortSelector.select(
				configuration("N", "101", "102"), GARDEN_ADMIN);
		assertEquals(CohortRuntime.LEGACY, decision.runtime());
		assertEquals(CohortDecision.Reason.MASTER_DISABLED, decision.reason());
	}

	@Test
	@DisplayName("an invalid configuration beats the master switch")
	void invalidConfigurationBeatsEverything() {
		CohortConfiguration invalid = CohortConfigurationParser.parse(List.of(
				new SysConfigRow(CohortConfigurationKeys.ENABLED, "Y", 0, 0, true),
				new SysConfigRow(CohortConfigurationKeys.ENABLED, "Y", 0, 0, true)));
		CohortDecision decision = CohortSelector.select(invalid, GARDEN_ADMIN);
		assertEquals(CohortRuntime.LEGACY, decision.runtime());
		assertEquals(CohortDecision.Reason.CONFIGURATION_INVALID, decision.reason());
	}

	@Test
	@DisplayName("the user allowlist is evaluated before the role allowlist")
	void userAllowlistIsEvaluatedFirst() {
		CohortDecision decision = CohortSelector.select(
				configuration("Y", "101", "102"), GARDEN_ADMIN);
		assertEquals(CohortRuntime.MODERN, decision.runtime());
		assertEquals(CohortDecision.Reason.USER_ALLOWLISTED, decision.reason());
	}

	@Test
	@DisplayName("the role allowlist selects modern when the user is absent")
	void roleAllowlistSelectsModern() {
		CohortDecision decision = CohortSelector.select(
				configuration("Y", "999", "102"), GARDEN_ADMIN);
		assertEquals(CohortRuntime.MODERN, decision.runtime());
		assertEquals(CohortDecision.Reason.ROLE_ALLOWLISTED, decision.reason());
	}

	@Test
	@DisplayName("neither allowlist selects legacy")
	void neitherAllowlistSelectsLegacy() {
		CohortDecision decision = CohortSelector.select(
				configuration("Y", "101", "102"), OTHER);
		assertEquals(CohortRuntime.LEGACY, decision.runtime());
		assertEquals(CohortDecision.Reason.NOT_ALLOWLISTED, decision.reason());
	}

	@Test
	@DisplayName("empty allowlists with the master on still select legacy")
	void emptyAllowlistsSelectLegacy() {
		assertEquals(CohortRuntime.LEGACY,
				CohortSelector.select(configuration("Y", "", ""), GARDEN_ADMIN).runtime());
	}

	@Test
	@DisplayName("a reason can never be paired with the other runtime")
	void reasonAndRuntimeCannotDisagree() {
		assertThrows(IllegalArgumentException.class, () -> new CohortDecision(
				CohortRuntime.MODERN, CohortDecision.Reason.NOT_ALLOWLISTED));
	}

	@Test
	@DisplayName("an unreadable configuration is reported once and never cached")
	void unreadableConfigurationIsReportedOnceAndRetried() {
		AtomicInteger reads = new AtomicInteger();
		List<String> reported = new ArrayList<>();
		AtomicLong now = new AtomicLong(1_000L);
		CohortConfigurationRepository repository = new CohortConfigurationRepository(
				() -> {
					reads.incrementAndGet();
					throw new IllegalStateException("no connection");
				},
				(message, cause) -> reported.add(message),
				now::get,
				CohortConfigurationRepository.DEFAULT_TTL_MILLIS,
				CohortConfigurationRepository.DEFAULT_ERROR_INTERVAL_MILLIS);

		for (int attempt = 0; attempt < 5; attempt++) {
			assertTrue(!repository.current().valid());
		}
		assertEquals(5, reads.get(), "a failure must never be cached");
		assertEquals(1, reported.size(), "the operator error must be rate limited");

		now.addAndGet(CohortConfigurationRepository.DEFAULT_ERROR_INTERVAL_MILLIS + 1);
		repository.current();
		assertEquals(2, reported.size(), "a new burst reports again");
	}

	@Test
	@DisplayName("a successful read is cached for the TTL and dropped by reset")
	void successfulReadIsCachedThenExpires() {
		AtomicInteger reads = new AtomicInteger();
		AtomicLong now = new AtomicLong(0L);
		CohortConfigurationRepository repository = new CohortConfigurationRepository(
				() -> {
					reads.incrementAndGet();
					return List.of(new SysConfigRow(
							CohortConfigurationKeys.ENABLED, "Y", 0, 0, true));
				},
				(message, cause) -> {
					throw new AssertionError("no failure expected");
				},
				now::get,
				1_000L,
				CohortConfigurationRepository.DEFAULT_ERROR_INTERVAL_MILLIS);

		CohortConfiguration first = repository.current();
		assertSame(first, repository.current());
		assertEquals(1, reads.get());

		now.set(1_001L);
		repository.current();
		assertEquals(2, reads.get(), "the entry expires with the TTL");

		repository.reset();
		repository.current();
		assertEquals(3, reads.get(), "reset drops the entry");
	}

	@Test
	@DisplayName("the production repository never serves a cached allowlist after a read failure")
	void productionRepositoryReadsEveryDecision() {
		AtomicInteger reads = new AtomicInteger();
		CohortConfigurationRepository repository =
				new CohortConfigurationRepository(
						() -> {
							if (reads.getAndIncrement() == 0) {
								return List.of(new SysConfigRow(
										CohortConfigurationKeys.ENABLED,
										"Y", 0, 0, true));
							}
							throw new IllegalStateException("permission revoked");
						},
						(message, cause) -> {
						});

		assertTrue(repository.current().enabled());
		assertFalse(repository.current().valid(),
				"an unreadable second decision must fail closed");
		assertEquals(2, reads.get());
	}

	@Test
	@DisplayName("an identity is complete or it is not parsed at all")
	void partialIdentityIsRejected() {
		assertNotNull(CohortIdentity.parse("101", "102", "11", "11", "103", "en_US"));
		assertNull(CohortIdentity.parse("101", "102", "11", "11", "103", null));
		assertNull(CohortIdentity.parse("101", "102", "11", "11", "103", "en-US"));
		assertNull(CohortIdentity.parse("0", "102", "11", "11", "103", "en_US"));
		assertNull(CohortIdentity.parse("101", "-1", "11", "11", "103", "en_US"));
		assertNull(CohortIdentity.parse("101", "102", "11", "11", "", "en_US"));
	}

	@Test
	@DisplayName("System role and System user can never be allowlisted")
	void systemPrincipalsCannotBeAllowlisted() {
		CohortConfiguration configuration = configuration("Y", "0", "0");
		assertTrue(!configuration.valid(),
				"a zero identifier is outside the documented grammar");
	}
}
