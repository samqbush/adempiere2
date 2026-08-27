package org.adempiere.web.route;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import org.adempiere.web.cohort.CohortDecision;
import org.adempiere.web.cohort.CohortIdentity;

/**
 * The server-side state a Tomcat 9 session holds once it has been assigned to
 * the modern runtime.
 *
 * <p>It lives only in the Tomcat 9 session. Nothing here is ever rendered into
 * a response header, a body, a URL, a log line or an evidence file.
 *
 * <p>The state machine is deliberately one-way:
 *
 * <pre>
 *   PENDING_ROTATION --&gt; ROTATING --&gt; AWAITING_BOOTSTRAP --&gt; BOOTSTRAPPING
 *                                                         --&gt; BOOTSTRAPPED
 *   any phase --&gt; FAILED
 * </pre>
 *
 * <p>There is no transition back to the legacy runtime. An established modern
 * session that hits a ticket failure, a missing affinity or an unavailable
 * backend becomes {@link Phase#FAILED} and is told so; it never silently
 * reappears as a legacy session, because that would show a different
 * application to a user who is already logged in to this one.
 *
 * <h2>Concurrency</h2>
 *
 * <p>A browser opens several connections at once, so two requests routinely
 * arrive on one session before either has finished. Reading the phase and then
 * acting on it in two steps let both requests believe they had to rotate: the
 * session identifier was changed twice, two tickets were minted, the second
 * {@code ticketed} call threw {@link IllegalStateException} out of the filter,
 * and the affinity was left bound to an identifier the container no longer
 * used. {@link #admit()} is therefore the single, atomic check-and-transition
 * every caller uses. The loser of a race is told {@link Step#IN_PROGRESS} and
 * refused explicitly, without poisoning the session the winner is still
 * establishing.
 *
 * <h2>Persistence</h2>
 *
 * <p>Tomcat persists sessions across a context stop or restart, and an
 * attribute that is not serializable is dropped silently. A dropped affinity
 * would leave a decided-modern session with no affinity at all, which the
 * router would otherwise have handed to the legacy application - exactly the
 * fallback the {@code modern_fallback=forbidden} rule prohibits. This class is
 * therefore serializable, and the bridge additionally records the decided
 * runtime on the session so a lost affinity still fails closed.
 *
 * <p>The ticket itself is {@code transient}: it is a bearer credential and must
 * not be written to {@code SESSIONS.ser}. Any phase that depends on holding one
 * is unrecoverable after a restore, so {@link #readObject} moves it to
 * {@link Phase#FAILED} with {@link #NOT_RESTORABLE} rather than resuming a
 * handoff whose secret is gone.
 */
public final class ModernSessionAffinity implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The session attribute the bridge stores this under. */
	public static final String ATTRIBUTE =
			"org.adempiere.web.route.ModernSessionAffinity";

	/** Closed failure token for an in-flight affinity that was persisted. */
	public static final String NOT_RESTORABLE = "affinity-not-restorable";

	public enum Phase {
		/** Decided modern; the Tomcat 9 session id has not been rotated yet. */
		PENDING_ROTATION,
		/** One request won the rotation race and is rotating right now. */
		ROTATING,
		/** Rotated and ticketed; the bootstrap request has not been made yet. */
		AWAITING_BOOTSTRAP,
		/** One request holds the ticket and is bootstrapping right now. */
		BOOTSTRAPPING,
		/** The modern runtime issued a session and it is bound here. */
		BOOTSTRAPPED,
		/** Terminal. The session is modern and unusable; it must not fall back. */
		FAILED
	}

	/** What the caller that called {@link #admit()} must do next. */
	public enum Step {
		/** This caller owns the rotation. Nobody else may rotate. */
		ROTATE,
		/** This caller owns the bootstrap and holds the only ticket. */
		BOOTSTRAP,
		/** Established. Proxy with the bound modern session identifier. */
		PROXY,
		/**
		 * Another request owns the rotation or the bootstrap. This one is
		 * refused explicitly and the session is left usable for the winner.
		 */
		IN_PROGRESS,
		/** Terminal failure. Refuse, and never fall back to legacy. */
		REFUSED
	}

	/**
	 * One admission decision.
	 *
	 * @param step   what the admitted caller must do
	 * @param ticket the ticket, present only for {@link Step#BOOTSTRAP}
	 */
	public record Admission(Step step, String ticket) {
	}

	private final CohortDecision decision;
	private final CohortIdentity identity;
	private Phase phase = Phase.PENDING_ROTATION;
	private String boundLegacySessionId;
	private transient String ticket;
	private String modernSessionId;
	private String failureReason;

	public ModernSessionAffinity(CohortDecision decision, CohortIdentity identity) {
		if (decision == null || !decision.modern()) {
			throw new IllegalArgumentException(
					"Only a modern decision creates a modern affinity");
		}
		if (identity == null) {
			throw new IllegalArgumentException("A modern affinity needs an identity");
		}
		this.decision = decision;
		this.identity = identity;
	}

	public CohortDecision decision() {
		return decision;
	}

	public CohortIdentity identity() {
		return identity;
	}

	public synchronized Phase phase() {
		return phase;
	}

	/**
	 * Atomically decides what this request may do, and claims it.
	 *
	 * <p>This is the only way a caller may learn the phase in order to act on
	 * it. Two concurrent requests can never both be told to rotate or both be
	 * given the ticket: the first transitions the phase inside this monitor and
	 * the second is told {@link Step#IN_PROGRESS}.
	 */
	public synchronized Admission admit() {
		switch (phase) {
			case PENDING_ROTATION:
				phase = Phase.ROTATING;
				return new Admission(Step.ROTATE, null);
			case AWAITING_BOOTSTRAP:
				if (ticket == null) {
					// The only way to reach this is a restored session whose
					// transient ticket did not survive. Nothing may present a
					// ticket that no longer exists.
					failed(NOT_RESTORABLE);
					return new Admission(Step.REFUSED, null);
				}
				String issued = ticket;
				ticket = null;
				phase = Phase.BOOTSTRAPPING;
				return new Admission(Step.BOOTSTRAP, issued);
			case BOOTSTRAPPED:
				return new Admission(Step.PROXY, null);
			case ROTATING:
			case BOOTSTRAPPING:
				return new Admission(Step.IN_PROGRESS, null);
			case FAILED:
			default:
				return new Admission(Step.REFUSED, null);
		}
	}

	/**
	 * Records the rotated Tomcat 9 session id and the ticket bound to it.
	 *
	 * <p>Only the request that was admitted with {@link Step#ROTATE} may call
	 * this, and only once: the phase guard is the same monitor {@link #admit()}
	 * used to hand out that step.
	 */
	public synchronized void ticketed(String rotatedLegacySessionId, String ticket) {
		if (phase != Phase.ROTATING) {
			throw new IllegalStateException(
					"Only the admitted rotating request may ticket a session, not "
							+ "one in phase " + phase);
		}
		if (rotatedLegacySessionId == null || rotatedLegacySessionId.isBlank()
				|| ticket == null || ticket.isBlank()) {
			throw new IllegalArgumentException(
					"Ticketing needs a rotated session id and a ticket");
		}
		this.boundLegacySessionId = rotatedLegacySessionId;
		this.ticket = ticket;
		this.phase = Phase.AWAITING_BOOTSTRAP;
	}

	/** Whether the ticket is still available to be handed over. */
	public synchronized boolean ticketPending() {
		return ticket != null;
	}

	/** The rotated Tomcat 9 session id the ticket was bound to. */
	public synchronized String boundLegacySessionId() {
		return boundLegacySessionId;
	}

	/**
	 * Binds the modern runtime's session identifier.
	 *
	 * <p>Only the request admitted with {@link Step#BOOTSTRAP} reaches this, so
	 * a second bootstrap cannot overwrite the first one's binding.
	 */
	public synchronized void bootstrapped(String modernSessionId) {
		if (phase != Phase.BOOTSTRAPPING) {
			throw new IllegalStateException(
					"Only the admitted bootstrapping request may bind a modern "
							+ "session, not one in phase " + phase);
		}
		if (modernSessionId == null || modernSessionId.isBlank()) {
			throw new IllegalArgumentException("The modern session id is required");
		}
		this.modernSessionId = modernSessionId;
		this.phase = Phase.BOOTSTRAPPED;
	}

	/** The modern session identifier; never leaves the Tomcat 9 session. */
	public synchronized String modernSessionId() {
		return modernSessionId;
	}

	/** Terminal failure. The session stays modern and stays broken. */
	public synchronized void failed(String reason) {
		this.phase = Phase.FAILED;
		this.failureReason = reason;
		this.ticket = null;
	}

	/** The closed failure token, safe to log. */
	public synchronized String failureReason() {
		return failureReason;
	}

	/** Whether this session may still be proxied. */
	public synchronized boolean usable() {
		return phase != Phase.FAILED;
	}

	@Override
	public synchronized String toString() {
		// Deliberately excludes the ticket, both session identifiers and the
		// identity: this object is reachable from a session dump.
		return "ModernSessionAffinity[phase=" + phase
				+ ", reason=" + decision.reason() + "]";
	}

	/**
	 * Fails closed for every phase a restored session cannot honestly resume.
	 *
	 * <p>{@link Phase#PENDING_ROTATION} and {@link Phase#BOOTSTRAPPED} carry no
	 * transient state and are restored as they were. Every other non-terminal
	 * phase either was mid-flight when the container stopped or depended on a
	 * ticket that was deliberately not persisted, and resuming it would either
	 * rotate a second time or present nothing at all.
	 */
	private void readObject(ObjectInputStream in)
			throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		if (phase == Phase.ROTATING
				|| phase == Phase.AWAITING_BOOTSTRAP
				|| phase == Phase.BOOTSTRAPPING) {
			failed(NOT_RESTORABLE);
		}
	}
}
