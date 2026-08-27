package org.adempiere.web.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import org.adempiere.web.cohort.CohortIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The handoff ticket: signature, version, timestamps, session binding, identity
 * completeness, single use, and key hygiene.
 */
@Tag("UnitTest")
@DisplayName("Phase 5e handoff ticket")
class HandoffTicketCodecTest {

	private static final CohortIdentity IDENTITY =
			new CohortIdentity(101, 102, 11, 11, 103, "en_US");
	private static final String SESSION = "ROTATED-SESSION-0001";

	private static HandoffKey key(byte seed) throws HandoffKeyException {
		byte[] material = new byte[32];
		new SecureRandom(new byte[] {seed}).nextBytes(material);
		return HandoffKey.of(material);
	}

	private HandoffResult decode(
			HandoffTicketCodec codec, String encoded, HandoffKey key,
			String session, long now, ReplayCache cache) {
		return codec.decode(encoded, key, session, now, cache);
	}

	@Test
	@DisplayName("a freshly issued ticket round-trips exactly once")
	void roundTripsOnce() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 1);
		// The cache must share the codec's clock: it expires entries by the same
		// epoch the ticket's expiresAt is expressed in.
		AtomicLong now = new AtomicLong(10_000L);
		ReplayCache cache = new ReplayCache(ReplayCache.DEFAULT_CAPACITY, now::get);
		HandoffTicket issued = codec.issue(SESSION, IDENTITY, now.get());
		String encoded = codec.encode(issued, key);

		now.set(10_001L);
		HandoffResult accepted = decode(codec, encoded, key, SESSION, now.get(), cache);
		assertTrue(accepted.accepted());
		assertEquals(IDENTITY, accepted.ticket().identity());
		assertEquals(SESSION, accepted.ticket().legacySessionId());
		assertEquals(HandoffProtocol.VERSION, accepted.ticket().version());

		now.set(10_002L);
		HandoffResult replayed = decode(codec, encoded, key, SESSION, now.get(), cache);
		assertFalse(replayed.accepted());
		assertEquals(HandoffRejection.REPLAYED, replayed.rejection());
	}

	@Test
	@DisplayName("a ticket signed with another key is refused")
	void wrongKeyIsRefused() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		String encoded = codec.encode(codec.issue(SESSION, IDENTITY, 0L), key((byte) 1));
		HandoffResult result =
				decode(codec, encoded, key((byte) 2), SESSION, 1L, new ReplayCache());
		assertEquals(HandoffRejection.BAD_SIGNATURE, result.rejection());
	}

	@Test
	@DisplayName("any tampering with the payload invalidates the signature")
	void tamperedPayloadIsRefused() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 3);
		String encoded = codec.encode(codec.issue(SESSION, IDENTITY, 0L), key);
		String[] fields = encoded.split("\\.");
		String payload = new String(
				Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
		String elevated = payload.replace("|101|102|", "|101|0|");
		assertNotEquals(payload, elevated, "the fixture must actually change a field");
		String forged = fields[0] + "."
				+ Base64.getUrlEncoder().withoutPadding()
						.encodeToString(elevated.getBytes(StandardCharsets.UTF_8))
				+ "." + fields[2];
		assertEquals(HandoffRejection.BAD_SIGNATURE,
				decode(codec, forged, key, SESSION, 1L, new ReplayCache()).rejection());
	}

	@Test
	@DisplayName("an expired ticket is refused and does not burn its nonce")
	void expiredTicketIsRefusedWithoutConsumingTheNonce() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 4);
		ReplayCache cache = new ReplayCache();
		String encoded = codec.encode(codec.issue(SESSION, IDENTITY, 0L), key);
		assertEquals(HandoffRejection.EXPIRED, decode(codec, encoded, key, SESSION,
				HandoffProtocol.TTL_MILLIS, cache).rejection());
		assertEquals(0, cache.size(),
				"a refused ticket must not consume replay capacity");
	}

	@Test
	@DisplayName("a ticket from the future beyond the tolerated skew is refused")
	void futureTicketIsRefused() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 5);
		String encoded = codec.encode(codec.issue(SESSION, IDENTITY, 100_000L), key);
		assertEquals(HandoffRejection.NOT_YET_VALID,
				decode(codec, encoded, key, SESSION,
						100_000L - HandoffProtocol.CLOCK_SKEW_MILLIS - 1,
						new ReplayCache()).rejection());
	}

	@Test
	@DisplayName("a ticket presented by a different session is refused")
	void sessionBindingIsEnforced() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 6);
		String encoded = codec.encode(codec.issue(SESSION, IDENTITY, 0L), key);
		assertEquals(HandoffRejection.SESSION_MISMATCH,
				decode(codec, encoded, key, "SOME-OTHER-SESSION", 1L,
						new ReplayCache()).rejection());
	}

	@Test
	@DisplayName("an unsupported version prefix is refused before the MAC is trusted")
	void unsupportedVersionIsRefused() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 7);
		String encoded = codec.encode(codec.issue(SESSION, IDENTITY, 0L), key);
		String bumped = "v99" + encoded.substring(encoded.indexOf('.'));
		assertEquals(HandoffRejection.UNSUPPORTED_VERSION,
				decode(codec, bumped, key, SESSION, 1L, new ReplayCache()).rejection());
	}

	@Test
	@DisplayName("malformed input is refused rather than parsed leniently")
	void malformedInputIsRefused() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 8);
		ReplayCache cache = new ReplayCache();
		for (String candidate : new String[] {
				null, "", "   ", "v1", "v1.", "v1..", "a.b.c.d", "v1.@@@.@@@"}) {
			HandoffResult result = decode(codec, candidate, key, SESSION, 1L, cache);
			assertFalse(result.accepted(), "accepted " + candidate);
		}
	}

	@Test
	@DisplayName("a payload missing an identity field is refused after the MAC passes")
	void incompleteIdentityIsRefused() throws Exception {
		HandoffTicketCodec codec = new HandoffTicketCodec();
		HandoffKey key = key((byte) 9);
		// A well-signed payload with the right field count but an empty language.
		String payload = "nonce|0|30000|" + SESSION + "|101|102|11|11|103|";
		String prefix = "v" + HandoffProtocol.VERSION;
		String encodedPayload = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		String signed = signWith(key, prefix + "." + encodedPayload);
		assertEquals(HandoffRejection.INCOMPLETE_IDENTITY,
				decode(codec, prefix + "." + encodedPayload + "." + signed, key,
						SESSION, 1L, new ReplayCache()).rejection());
	}

	private static String signWith(HandoffKey key, String signed) throws Exception {
		javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
		mac.init(new javax.crypto.spec.SecretKeySpec(key.material(), "HmacSHA256"));
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("the replay cache fails closed when full of live entries")
	void replayCacheFailsClosed() {
		AtomicLong now = new AtomicLong(0L);
		ReplayCache cache = new ReplayCache(2, now::get);
		assertEquals(ReplayCache.Outcome.ACCEPTED, cache.consume("a", 1_000L));
		assertEquals(ReplayCache.Outcome.ACCEPTED, cache.consume("b", 1_000L));
		assertEquals(ReplayCache.Outcome.EXHAUSTED, cache.consume("c", 1_000L),
				"a full cache must refuse rather than evict a live nonce");
		now.set(1_000L);
		assertEquals(ReplayCache.Outcome.ACCEPTED, cache.consume("c", 2_000L),
				"expired entries free capacity");
		assertEquals(ReplayCache.Outcome.ACCEPTED, cache.consume("a", 2_000L),
				"an expired nonce is no longer a replay");
	}

	@Test
	@DisplayName("the documented capacity covers the documented login rate")
	void capacityCoversDocumentedRate() {
		long liveCeiling = (HandoffProtocol.TTL_MILLIS
				+ HandoffProtocol.CLOCK_SKEW_MILLIS) / 1000L * 20L;
		assertTrue(ReplayCache.DEFAULT_CAPACITY > liveCeiling,
				"capacity " + ReplayCache.DEFAULT_CAPACITY
						+ " must exceed the documented ceiling " + liveCeiling);
	}

	@Test
	@DisplayName("a key must be a 0600 regular file of at least 32 random bytes")
	void keyHygieneIsEnforced(@TempDir Path directory) throws Exception {
		Path absent = directory.resolve("absent");
		assertThrows(HandoffKeyException.class, () -> HandoffKey.load(absent));

		Path shortKey = write(directory.resolve("short"), new byte[31]);
		assertThrows(HandoffKeyException.class, () -> HandoffKey.load(shortKey));

		Path uniform = write(directory.resolve("uniform"), new byte[48]);
		assertThrows(HandoffKeyException.class, () -> HandoffKey.load(uniform));

		Path placeholder = write(directory.resolve("placeholder"),
				"change-me-change-me-change-me-change-me".getBytes(StandardCharsets.UTF_8));
		assertThrows(HandoffKeyException.class, () -> HandoffKey.load(placeholder));

		byte[] material = new byte[32];
		new SecureRandom().nextBytes(material);
		material[0] = (byte) 0x00;
		material[1] = (byte) 0xFF;
		Path loose = write(directory.resolve("loose"), material);
		Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rw-r--r--"));
		assertThrows(HandoffKeyException.class, () -> HandoffKey.load(loose));

		Path good = write(directory.resolve("good"), material);
		HandoffKey loaded = HandoffKey.load(good);
		assertEquals(32, loaded.material().length);
		assertEquals("HandoffKey[32 bytes]", loaded.toString(),
				"toString must never render key material");
	}

	private static Path write(Path path, byte[] content) throws Exception {
		Files.write(path, content);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		return path;
	}

	@Test
	@DisplayName("the reserved header namespace is matched case-insensitively")
	void reservedNamespaceIsCaseInsensitive() {
		assertTrue(HandoffProtocol.reserved(HandoffProtocol.TICKET_HEADER));
		assertTrue(HandoffProtocol.reserved("x-adempiere-handoff-ticket"));
		assertTrue(HandoffProtocol.reserved("X-ADEMPIERE-HANDOFF-ANYTHING"));
		assertFalse(HandoffProtocol.reserved("X-Requested-With"));
		assertFalse(HandoffProtocol.reserved(null));
	}
}
