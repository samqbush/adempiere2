package org.adempiere.web.handoff;

/**
 * The outcome of decoding a presented ticket: either an accepted ticket or a
 * rejection reason. Never both, and never neither.
 */
public record HandoffResult(HandoffTicket ticket, HandoffRejection rejection) {

	public HandoffResult {
		if ((ticket == null) == (rejection == null)) {
			throw new IllegalArgumentException(
					"A handoff result is exactly one of a ticket or a rejection");
		}
	}

	static HandoffResult accepted(HandoffTicket ticket) {
		return new HandoffResult(ticket, null);
	}

	static HandoffResult rejected(HandoffRejection rejection) {
		return new HandoffResult(null, rejection);
	}

	public boolean accepted() {
		return ticket != null;
	}
}
