package org.adempiere.web.handoff;

import java.util.Locale;

/**
 * The wire constants of the Phase 5e internal handoff, and the reserved header
 * namespace that must be stripped from every browser request.
 *
 * <p>The reserved namespace is a single prefix rather than a list of names so
 * the stripping rule cannot be defeated by adding a header later: anything
 * beginning with {@link #RESERVED_HEADER_PREFIX} is internal by definition.
 */
public final class HandoffProtocol {

	/** The protocol version carried in the ticket and in its prefix. */
	public static final int VERSION = 1;

	/** Everything in this namespace is internal and never accepted inbound. */
	public static final String RESERVED_HEADER_PREFIX = "X-ADempiere-Handoff-";

	/** Carries the ticket on the single bootstrap request. */
	public static final String TICKET_HEADER = RESERVED_HEADER_PREFIX + "Ticket";

	/**
	 * Carries the rotated Tomcat 9 session identifier on every routed request.
	 *
	 * <p>On bootstrap, the router asserts the binding before it sends the ticket,
	 * and the modern runtime asserts it again on arrival. After bootstrap, the
	 * binding participates in the modern runtime's fail-closed handling of a
	 * routed request whose modern session is absent. Logout-race recovery also
	 * requires the exact requested modern session identifier to be present in
	 * the runtime's short-lived ended-session record; the binding alone is not
	 * proof of logout. Neither header can be supplied by a browser: the router
	 * refuses any request carrying the reserved namespace, and the modern runtime
	 * accepts them only from loopback.
	 */
	public static final String SESSION_HEADER = RESERVED_HEADER_PREFIX + "Session";

	/**
	 * Set by the modern runtime on the response to a routed request whose
	 * session has just ended server-side.
	 *
	 * <p>It exists because a routed logout ends a session on <em>two</em>
	 * runtimes and only one of them observes the click. Without a signal, the
	 * Tomcat 9 session kept its affinity and its recorded decision after the
	 * user logged out, so the next login on the same browser stayed modern even
	 * when the configuration no longer selected that cohort - a sticky decision
	 * outliving the session it was taken for.
	 *
	 * <p>It is inside {@link #RESERVED_HEADER_PREFIX}, so
	 * {@code ProxyHeaderPolicy} refuses to forward it and a browser can never
	 * send one: the router rejects the whole namespace inbound.
	 */
	public static final String END_HEADER = RESERVED_HEADER_PREFIX + "End";

	/** The only accepted value of {@link #END_HEADER}. */
	public static final String END_VALUE = "session-ended";

	/**
	 * The default lifetime. Thirty seconds is far longer than a loopback
	 * request and far shorter than any human interaction, so a ticket that is
	 * captured is worthless before it can be replayed by hand.
	 */
	public static final long TTL_MILLIS = 30_000L;

	/**
	 * Tolerated clock skew when validating {@code issuedAt}.
	 *
	 * <p>Both runtimes share one host and therefore one clock, so this exists
	 * only to absorb the millisecond-level ordering of two JVM reads, not to
	 * make the ticket usable across machines.
	 */
	public static final long CLOCK_SKEW_MILLIS = 1_000L;

	private HandoffProtocol() {
	}

	/** Whether a header name belongs to the reserved internal namespace. */
	public static boolean reserved(String headerName) {
		return headerName != null && headerName
				.toLowerCase(Locale.ROOT)
				.startsWith(RESERVED_HEADER_PREFIX.toLowerCase(Locale.ROOT));
	}
}
