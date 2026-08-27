package org.adempiere.web.route;

/**
 * The bounded resource limits of the Phase 5e proxy.
 *
 * <p>Every one of them is a hard stop rather than a hint. A proxy without a
 * byte cap turns one oversized upload into a Tomcat 9 heap incident, and a
 * proxy without a read timeout turns one wedged backend into an exhausted
 * request-thread pool on the <em>public</em> ingress.
 */
public final class ProxyLimits {

	/** Loopback connect. Anything slower than this means Tomcat 10 is down. */
	public static final int CONNECT_TIMEOUT_MILLIS = 3_000;

	/** Ordinary page, resource and static reads. */
	public static final int READ_TIMEOUT_MILLIS = 30_000;

	/**
	 * ZK asynchronous updates, including polling.
	 *
	 * <p>ZK CE's {@code PollingServerPush} holds a request open while it waits
	 * for server-side activity, so the ordinary read timeout would abort a
	 * healthy poll. This value is longer than ZK's own poll interval and still
	 * far shorter than the 60-minute session timeout, so a wedged backend is
	 * still detected within a minute and a half.
	 */
	public static final int POLLING_READ_TIMEOUT_MILLIS = 90_000;

	/** Streaming copy buffer. */
	public static final int BUFFER_BYTES = 8 * 1024;

	/**
	 * Maximum bytes streamed from the browser to the modern runtime. ZK AU
	 * requests are small; Phase 5e ships no upload route, and Phase 5g owns the
	 * one that will exist.
	 */
	public static final long MAX_REQUEST_BYTES = 8L * 1024 * 1024;

	/** Maximum bytes streamed back. Large enough for the ZK client bundles. */
	public static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;

	private ProxyLimits() {
	}
}
