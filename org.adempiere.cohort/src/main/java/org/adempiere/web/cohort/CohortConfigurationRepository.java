package org.adempiere.web.cohort;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Loads the fail-closed cohort configuration.
 *
 * <p>Production reads all three rows for every new decision. Serving a cached
 * allowlist after the database becomes unreadable would route a new session
 * modern when the fail-closed contract requires legacy. The injectable TTL is
 * retained for callers that can invalidate it atomically with their source:
 *
 * <ul>
 *   <li>A read failure is never cached. The failure is reported once per
 *       {@code errorIntervalMillis} to the supplied listener so an unreadable
 *       configuration produces one operator error rather than one per login
 *       attempt, and the next login retries the read.</li>
 *   <li>{@link #reset()} drops the entry, which is what the database-backed
 *       gate uses between fixtures.</li>
 * </ul>
 *
 * <p>The cache never changes an existing session's runtime: the decision is
 * taken once and stored in the session. Expiry only affects sessions that have
 * not yet been decided.
 */
public final class CohortConfigurationRepository {

	/** One operator error per unreadable-configuration burst. */
	public static final long DEFAULT_ERROR_INTERVAL_MILLIS = 60_000L;

	/** Optional cache TTL for sources with an atomic invalidation mechanism. */
	public static final long DEFAULT_TTL_MILLIS = 15_000L;
	/** Production must observe an unreadable source on the very next decision. */
	public static final long PRODUCTION_TTL_MILLIS = 0L;

	/** Receives one rate-limited message when the configuration is unreadable. */
	@FunctionalInterface
	public interface FailureListener {
		void unreadable(String message, Throwable cause);
	}

	private record Entry(CohortConfiguration configuration, long expiresAt) {
	}

	private final SysConfigRowSource source;
	private final LongSupplier clock;
	private final long ttlMillis;
	private final long errorIntervalMillis;
	private final FailureListener failures;
	private final AtomicReference<Entry> cached = new AtomicReference<>();
	private final AtomicReference<Long> lastReported = new AtomicReference<>();

	public CohortConfigurationRepository(
			SysConfigRowSource source, FailureListener failures) {
		this(source, failures, System::currentTimeMillis, PRODUCTION_TTL_MILLIS,
				DEFAULT_ERROR_INTERVAL_MILLIS);
	}

	public CohortConfigurationRepository(
			SysConfigRowSource source,
			FailureListener failures,
			LongSupplier clock,
			long ttlMillis,
			long errorIntervalMillis) {
		if (source == null || failures == null || clock == null) {
			throw new IllegalArgumentException(
					"A source, a failure listener and a clock are required");
		}
		if (ttlMillis < 0 || errorIntervalMillis < 0) {
			throw new IllegalArgumentException("Intervals must not be negative");
		}
		this.source = source;
		this.failures = failures;
		this.clock = clock;
		this.ttlMillis = ttlMillis;
		this.errorIntervalMillis = errorIntervalMillis;
	}

	/** The current configuration, never {@code null}, fail-closed on error. */
	public CohortConfiguration current() {
		long now = clock.getAsLong();
		Entry entry = cached.get();
		if (entry != null && now < entry.expiresAt()) {
			return entry.configuration();
		}
		List<SysConfigRow> rows;
		try {
			rows = source.read();
		} catch (Exception failure) {
			report(now, failure);
			// Deliberately not cached: the next login retries the read.
			return CohortConfiguration.invalid(
					List.of("the cohort configuration could not be read"), List.of());
		}
		CohortConfiguration configuration = CohortConfigurationParser.parse(rows);
		cached.set(new Entry(configuration, now + ttlMillis));
		return configuration;
	}

	/** Drops the cached entry so the next call re-reads the database. */
	public void reset() {
		cached.set(null);
		lastReported.set(null);
	}

	private void report(long now, Throwable failure) {
		Long previous = lastReported.get();
		if (previous != null && now - previous < errorIntervalMillis) {
			return;
		}
		if (lastReported.compareAndSet(previous, now)) {
			failures.unreadable(
					"The Phase 5e cohort configuration could not be read; every new "
					+ "session stays on the legacy runtime until it can be",
					failure);
		}
	}
}
