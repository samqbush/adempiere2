package org.adempiere.web.handoff;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A bounded, TTL-scoped, fail-closed record of the nonces already consumed.
 *
 * <h2>Why the capacity is what it is</h2>
 *
 * <p>A nonce only has to be remembered for as long as its ticket can still be
 * accepted, which is {@link HandoffProtocol#TTL_MILLIS} plus the tolerated
 * skew - 31 seconds. The live population is therefore bounded by the accepted
 * login rate, not by the number of sessions.
 *
 * <p>The Phase 5e ADR documents the maximum accepted rate as <b>20 new modern
 * sessions per second</b>, which is already an order of magnitude above the
 * rate an ADempiere login path can sustain (each login opens a database
 * connection, reads the role tree and writes an {@code AD_Session} row).
 * 31&nbsp;s x 20/s = 620 live nonces. {@link #DEFAULT_CAPACITY} is 4096, about
 * 6.6x that ceiling, and costs roughly 300&nbsp;KB.
 *
 * <p>When the cache is full of <em>unexpired</em> entries it refuses to accept
 * a new nonce rather than evicting the oldest. Evicting would make replay
 * possible under load, which is precisely when an attacker would create load.
 * Refusing costs an operator-visible failed handoff and leaves the session on
 * the legacy runtime.
 */
public final class ReplayCache {

	/** See the class comment for the derivation. */
	public static final int DEFAULT_CAPACITY = 4096;

	/** The outcome of trying to consume a nonce. */
	public enum Outcome {
		/** First use; the ticket may proceed. */
		ACCEPTED,
		/** The nonce was already consumed inside its lifetime. */
		REPLAYED,
		/** The cache is full of live entries; the ticket is refused. */
		EXHAUSTED
	}

	private final int capacity;
	private final LongSupplier clock;
	private final Map<String, Long> consumed = new LinkedHashMap<>();

	public ReplayCache() {
		this(DEFAULT_CAPACITY, System::currentTimeMillis);
	}

	public ReplayCache(int capacity, LongSupplier clock) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be positive");
		}
		if (clock == null) {
			throw new IllegalArgumentException("A clock is required");
		}
		this.capacity = capacity;
		this.clock = clock;
	}

	/**
	 * Consumes {@code nonce} until {@code expiresAt}.
	 *
	 * @return {@link Outcome#ACCEPTED} only on the very first use
	 */
	public synchronized Outcome consume(String nonce, long expiresAt) {
		if (nonce == null || nonce.isBlank()) {
			return Outcome.REPLAYED;
		}
		long now = clock.getAsLong();
		purge(now);
		Long existing = consumed.get(nonce);
		if (existing != null) {
			return Outcome.REPLAYED;
		}
		if (consumed.size() >= capacity) {
			return Outcome.EXHAUSTED;
		}
		consumed.put(nonce, expiresAt);
		return Outcome.ACCEPTED;
	}

	/** The number of live entries; used by the lifecycle baseline assertion. */
	public synchronized int size() {
		purge(clock.getAsLong());
		return consumed.size();
	}

	/** Drops every entry. Used only by fixtures between measured captures. */
	public synchronized void clear() {
		consumed.clear();
	}

	private void purge(long now) {
		Iterator<Map.Entry<String, Long>> entries = consumed.entrySet().iterator();
		while (entries.hasNext()) {
			if (entries.next().getValue() <= now) {
				entries.remove();
			}
		}
	}
}
