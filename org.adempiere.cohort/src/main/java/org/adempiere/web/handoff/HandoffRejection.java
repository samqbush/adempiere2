package org.adempiere.web.handoff;

/**
 * Why a ticket was refused, as a closed set.
 *
 * <p>The reason is safe to log: it names the rule that failed and nothing about
 * the ticket, the session or the tenant.
 */
public enum HandoffRejection {

	/** The header was absent, empty, or not three dot-separated fields. */
	MALFORMED,

	/** The version prefix is not {@link HandoffProtocol#VERSION}. */
	UNSUPPORTED_VERSION,

	/** The MAC did not match. Checked in constant time. */
	BAD_SIGNATURE,

	/** The payload verified but did not carry a complete identity. */
	INCOMPLETE_IDENTITY,

	/** {@code issuedAt} is further in the future than the tolerated skew. */
	NOT_YET_VALID,

	/** {@code expiresAt} has passed. */
	EXPIRED,

	/** The bound session identifier is not the one presenting the ticket. */
	SESSION_MISMATCH,

	/** The nonce was already consumed. */
	REPLAYED,

	/** The replay cache is full of live entries and refuses to grow. */
	CACHE_EXHAUSTED
}
