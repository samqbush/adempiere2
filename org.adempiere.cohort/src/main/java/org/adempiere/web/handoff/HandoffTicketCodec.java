package org.adempiere.web.handoff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.adempiere.web.cohort.CohortIdentity;

/**
 * Encodes and verifies the versioned, HMAC-SHA-256 authenticated handoff
 * ticket.
 *
 * <h2>Wire format</h2>
 *
 * <pre>{@code v<version>.<payload-base64url>.<mac-base64url>}</pre>
 *
 * <p>The payload is the pipe-separated canonical field list
 * {@code nonce|issuedAt|expiresAt|legacySessionId|identity}, encoded once and
 * signed as encoded, so verification never has to re-serialise a decoded value
 * to reproduce what was signed.
 *
 * <p>The MAC covers the version prefix as well as the payload, so a ticket
 * cannot be replayed against a future version whose payload happens to parse.
 *
 * <p>{@code javax.crypto} is Java SE, not Jakarta EE. It is identical on both
 * runtimes and is deliberately <em>not</em> subject to the {@code javax} to
 * {@code jakarta} rename.
 */
public final class HandoffTicketCodec {

	private static final String ALGORITHM = "HmacSHA256";
	private static final char FIELD = '|';
	private static final int NONCE_BYTES = 32;

	private final SecureRandom random;

	public HandoffTicketCodec() {
		this(new SecureRandom());
	}

	public HandoffTicketCodec(SecureRandom random) {
		if (random == null) {
			throw new IllegalArgumentException("A random source is required");
		}
		this.random = random;
	}

	/** Mints a ticket bound to {@code legacySessionId} and valid for the TTL. */
	public HandoffTicket issue(
			String legacySessionId, CohortIdentity identity, long nowMillis) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		return new HandoffTicket(
				HandoffProtocol.VERSION,
				Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
				nowMillis,
				nowMillis + HandoffProtocol.TTL_MILLIS,
				legacySessionId,
				identity);
	}

	/** Renders and signs a ticket. */
	public String encode(HandoffTicket ticket, HandoffKey key) {
		String prefix = "v" + ticket.version();
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
				canonical(ticket).getBytes(StandardCharsets.UTF_8));
		String mac = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac(key, prefix + "." + payload));
		return prefix + "." + payload + "." + mac;
	}

	/**
	 * Verifies signature, version, timestamps, session binding and identity
	 * completeness, then consumes the nonce exactly once.
	 *
	 * <p>The order matters: the MAC is checked before anything in the payload is
	 * believed, and the nonce is consumed only after every other rule has
	 * passed, so a malformed or expired ticket can never burn a nonce a valid
	 * ticket would have needed.
	 *
	 * @param presentedSessionId the rotated Tomcat 9 session identifier the
	 *                           bootstrap request actually arrived with
	 */
	public HandoffResult decode(
			String encoded,
			HandoffKey key,
			String presentedSessionId,
			long nowMillis,
			ReplayCache replayCache) {
		if (encoded == null || encoded.isBlank()) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}
		String[] fields = encoded.split("\\.", -1);
		if (fields.length != 3 || fields[0].isEmpty() || fields[1].isEmpty()
				|| fields[2].isEmpty()) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}
		if (!fields[0].equals("v" + HandoffProtocol.VERSION)) {
			return HandoffResult.rejected(HandoffRejection.UNSUPPORTED_VERSION);
		}

		byte[] presented;
		try {
			presented = Base64.getUrlDecoder().decode(fields[2]);
		} catch (IllegalArgumentException malformed) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}
		byte[] expected = mac(key, fields[0] + "." + fields[1]);
		if (!MessageDigest.isEqual(expected, presented)) {
			return HandoffResult.rejected(HandoffRejection.BAD_SIGNATURE);
		}

		String payload;
		try {
			payload = new String(
					Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException malformed) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}
		String[] parts = payload.split("\\" + FIELD, -1);
		if (parts.length != 10) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}

		long issuedAt;
		long expiresAt;
		try {
			issuedAt = Long.parseLong(parts[1]);
			expiresAt = Long.parseLong(parts[2]);
		} catch (NumberFormatException malformed) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}
		CohortIdentity identity = CohortIdentity.parse(
				parts[4], parts[5], parts[6], parts[7], parts[8], parts[9]);
		if (identity == null || parts[0].isEmpty() || parts[3].isEmpty()) {
			return HandoffResult.rejected(HandoffRejection.INCOMPLETE_IDENTITY);
		}
		if (expiresAt <= issuedAt) {
			return HandoffResult.rejected(HandoffRejection.MALFORMED);
		}
		if (issuedAt - HandoffProtocol.CLOCK_SKEW_MILLIS > nowMillis) {
			return HandoffResult.rejected(HandoffRejection.NOT_YET_VALID);
		}
		if (nowMillis >= expiresAt) {
			return HandoffResult.rejected(HandoffRejection.EXPIRED);
		}
		if (presentedSessionId == null || !presentedSessionId.equals(parts[3])) {
			return HandoffResult.rejected(HandoffRejection.SESSION_MISMATCH);
		}

		ReplayCache.Outcome outcome = replayCache.consume(parts[0], expiresAt);
		if (outcome == ReplayCache.Outcome.REPLAYED) {
			return HandoffResult.rejected(HandoffRejection.REPLAYED);
		}
		if (outcome == ReplayCache.Outcome.EXHAUSTED) {
			return HandoffResult.rejected(HandoffRejection.CACHE_EXHAUSTED);
		}
		return HandoffResult.accepted(new HandoffTicket(
				HandoffProtocol.VERSION, parts[0], issuedAt, expiresAt, parts[3],
				identity));
	}

	private static String canonical(HandoffTicket ticket) {
		CohortIdentity identity = ticket.identity();
		return ticket.nonce()
				+ FIELD + ticket.issuedAt()
				+ FIELD + ticket.expiresAt()
				+ FIELD + ticket.legacySessionId()
				+ FIELD + identity.userId()
				+ FIELD + identity.roleId()
				+ FIELD + identity.clientId()
				+ FIELD + identity.orgId()
				+ FIELD + identity.warehouseId()
				+ FIELD + identity.adLanguage();
	}

	private static byte[] mac(HandoffKey key, String signed) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(key.material(), ALGORITHM));
			return mac.doFinal(signed.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException | java.security.InvalidKeyException impossible) {
			// HmacSHA256 is required of every Java SE implementation, and the key
			// was length-validated on load.
			throw new IllegalStateException(
					"HMAC-SHA-256 is unavailable on this runtime", impossible);
		}
	}
}
