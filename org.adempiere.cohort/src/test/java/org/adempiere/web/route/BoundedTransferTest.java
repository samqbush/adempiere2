package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The proxy's byte caps, asserted at the boundary.
 *
 * <p>Phase 5e used to state the caps in prose and in a constant, and nothing
 * executed them. A cap that is never exercised is indistinguishable from a
 * missing cap, so each rule below is asserted one byte on either side of the
 * limit, and the "nothing past the limit is written" property is asserted
 * against the destination rather than inferred from the return value.
 */
@Tag("UnitTest")
@DisplayName("Phase 5e proxy byte caps")
class BoundedTransferTest {

	@Test
	@DisplayName("a payload exactly at the limit is streamed in full")
	void payloadAtTheLimitIsStreamed() throws IOException {
		byte[] payload = payload(64 * 1024);
		ByteArrayOutputStream sink = new ByteArrayOutputStream();

		assertTrue(BoundedTransfer.copy(
				new ByteArrayInputStream(payload), sink, payload.length));
		assertEquals(payload.length, sink.size());
	}

	@Test
	@DisplayName("one byte past the limit stops the copy and writes nothing past it")
	void oneBytePastTheLimitIsRefused() throws IOException {
		byte[] payload = payload(64 * 1024 + 1);
		ByteArrayOutputStream sink = new ByteArrayOutputStream();

		assertFalse(BoundedTransfer.copy(
				new ByteArrayInputStream(payload), sink, 64L * 1024));
		assertTrue(sink.size() <= 64 * 1024,
				"the destination received " + sink.size()
						+ " bytes, past the 65536 byte cap");
	}

	@Test
	@DisplayName("a cap of zero refuses the first non-empty chunk")
	void zeroCapRefusesEverything() throws IOException {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		assertFalse(BoundedTransfer.copy(
				new ByteArrayInputStream(new byte[] { 1 }), sink, 0L));
		assertEquals(0, sink.size());
		assertTrue(BoundedTransfer.copy(
				new ByteArrayInputStream(new byte[0]), sink, 0L),
				"an empty body is within a zero cap");
	}

	@Test
	@DisplayName("a declared Content-Length past the cap is refused before a byte is read")
	void declaredLengthIsRefusedBeforeReading() throws IOException {
		// The declared-length check takes no stream at all, which is the point:
		// the refusal happens before the proxy opens the request body, so an
		// oversized upload is never buffered anywhere.
		assertFalse(BoundedTransfer.declaredWithin(
				ProxyLimits.MAX_REQUEST_BYTES + 1, ProxyLimits.MAX_REQUEST_BYTES));
		assertTrue(BoundedTransfer.declaredWithin(
				ProxyLimits.MAX_REQUEST_BYTES, ProxyLimits.MAX_REQUEST_BYTES));
		// An absent Content-Length is negative and must not be treated as
		// oversized; the streaming cap covers that case instead.
		assertTrue(BoundedTransfer.declaredWithin(-1L, ProxyLimits.MAX_REQUEST_BYTES));

		// And the streaming cap really is what covers it: a chunked body with no
		// declared length is still stopped at the limit.
		InputStream chunked = new ByteArrayInputStream(payload(4096));
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		assertFalse(BoundedTransfer.copy(chunked, sink, 1024L));
		assertTrue(sink.size() <= 1024);
	}

	@Test
	@DisplayName("the request cap is enforced at the reviewed request limit")
	void requestCapIsEnforcedAtTheReviewedLimit() throws IOException {
		assertEquals(8L * 1024 * 1024, ProxyLimits.MAX_REQUEST_BYTES);
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		assertFalse(BoundedTransfer.copy(
				new ByteArrayInputStream(
						payload((int) ProxyLimits.MAX_REQUEST_BYTES + 1)),
				sink, ProxyLimits.MAX_REQUEST_BYTES));
		assertTrue(sink.size() <= ProxyLimits.MAX_REQUEST_BYTES);
	}

	@Test
	@DisplayName("the response cap is larger than the request cap and is enforced")
	void responseCapIsEnforced() throws IOException {
		assertEquals(64L * 1024 * 1024, ProxyLimits.MAX_RESPONSE_BYTES);
		assertTrue(ProxyLimits.MAX_RESPONSE_BYTES > ProxyLimits.MAX_REQUEST_BYTES,
				"the ZK client bundles are larger than any request Phase 5e proxies");
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		// Exercised against a small explicit limit so the assertion is about the
		// enforcement, not about allocating 64MB in a unit test.
		assertFalse(BoundedTransfer.copy(
				new ByteArrayInputStream(payload(9 * 1024)), sink, 8L * 1024));
		assertTrue(sink.size() <= 8 * 1024);
	}

	private static byte[] payload(int length) {
		byte[] bytes = new byte[length];
		for (int index = 0; index < length; index++) {
			bytes[index] = (byte) (index % 251);
		}
		return bytes;
	}
}
