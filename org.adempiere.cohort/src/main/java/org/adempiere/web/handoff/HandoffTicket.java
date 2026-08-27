package org.adempiere.web.handoff;

import org.adempiere.web.cohort.CohortIdentity;

/**
 * One single-use, short-lived, server-to-server handoff ticket.
 *
 * <p>The ticket never reaches the browser. It travels once, in a reserved
 * internal request header, on the first proxied bootstrap request of a session,
 * over a loopback connection between two Tomcats running under the same
 * operating-system account.
 *
 * @param version          the protocol version; {@link HandoffProtocol#VERSION}
 * @param nonce            256 bits of randomness, base64url without padding
 * @param issuedAt         epoch milliseconds the ticket was minted
 * @param expiresAt        epoch milliseconds the ticket stops being accepted
 * @param legacySessionId  the <em>rotated</em> Tomcat 9 session identifier the
 *                         ticket is bound to, so a ticket stolen from one
 *                         session cannot bootstrap another
 * @param identity         the complete authenticated identity
 */
public record HandoffTicket(
		int version,
		String nonce,
		long issuedAt,
		long expiresAt,
		String legacySessionId,
		CohortIdentity identity) {

	public HandoffTicket {
		if (nonce == null || nonce.isBlank()) {
			throw new IllegalArgumentException("A ticket needs a nonce");
		}
		if (legacySessionId == null || legacySessionId.isBlank()) {
			throw new IllegalArgumentException("A ticket needs a session binding");
		}
		if (identity == null) {
			throw new IllegalArgumentException("A ticket needs a complete identity");
		}
		if (expiresAt <= issuedAt) {
			throw new IllegalArgumentException("A ticket must expire after it is issued");
		}
	}
}
