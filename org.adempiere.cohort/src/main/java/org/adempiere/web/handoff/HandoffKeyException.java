package org.adempiere.web.handoff;

/**
 * Raised when the shared handoff key is absent, unreadable, too weak, or
 * carries a placeholder value.
 *
 * <p>No constructor, message or cause may ever carry key material. The messages
 * this class is constructed with are operator-facing and describe the file, its
 * length and its mode - never its content.
 */
public class HandoffKeyException extends Exception {

	private static final long serialVersionUID = 1L;

	public HandoffKeyException(String message) {
		super(message);
	}

	public HandoffKeyException(String message, Throwable cause) {
		super(message, cause);
	}
}
