package org.adempiere.web.route;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The bounded streaming copy the Phase 5e proxy performs, expressed as neutral
 * code so it can be asserted directly.
 *
 * <p>It lives here rather than inside the bridge for one reason: a byte cap that
 * is only exercised by pushing an oversized body through a live container is a
 * cap nobody tests, and an untested cap is indistinguishable from no cap. The
 * enforcement is therefore in the transport-neutral closure, next to the limits
 * it enforces, where {@code BoundedTransferTest} asserts the exact boundary in
 * both directions.
 *
 * <p>Two properties matter and both are asserted:
 *
 * <ul>
 *   <li>the limit is a hard stop, not a warning: the first byte beyond it ends
 *       the copy;</li>
 *   <li>nothing past the limit is ever written to the destination. A proxy that
 *       wrote the oversized chunk and then reported failure would already have
 *       forwarded it.</li>
 * </ul>
 */
public final class BoundedTransfer {

	private BoundedTransfer() {
	}

	/**
	 * Whether a declared {@code Content-Length} is acceptable.
	 *
	 * <p>A declared length is checked before a single byte is read, so an
	 * oversized upload is refused without being buffered anywhere.
	 *
	 * @param declaredLength the declared length, or negative when absent
	 */
	public static boolean declaredWithin(long declaredLength, long limit) {
		return declaredLength <= limit;
	}

	/**
	 * Streams {@code from} to {@code to}, stopping at {@code limit}.
	 *
	 * @return {@code false} when {@code limit} would be exceeded, in which case
	 *         no byte beyond the limit has been written
	 */
	public static boolean copy(InputStream from, OutputStream to, long limit)
			throws IOException {
		byte[] buffer = new byte[ProxyLimits.BUFFER_BYTES];
		long copied = 0;
		for (int read = from.read(buffer); read >= 0; read = from.read(buffer)) {
			copied += read;
			if (copied > limit) {
				return false;
			}
			to.write(buffer, 0, read);
		}
		to.flush();
		return true;
	}
}
