package org.adempiere.web.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.adempiere.web.handoff.HandoffProtocol;
import org.adempiere.web.route.BoundedTransfer;
import org.adempiere.web.route.ProxyHeaderPolicy;
import org.adempiere.web.route.ProxyLimits;
import org.adempiere.web.route.PublicRouteClass;
import org.adempiere.web.route.PublicRouteClassifier;

/**
 * Streams one public request to the loopback modern runtime and streams the
 * response back.
 *
 * <p>Streaming rather than buffering is not an optimisation: the ZK client
 * bundles are megabytes, and a buffering proxy on the <em>public</em> ingress
 * turns every concurrent modern session into retained heap on Tomcat 9.
 *
 * <p>Everything the proxy does is bounded and allowlisted:
 *
 * <ul>
 *   <li>method and route class come from {@link PublicRouteClassifier};</li>
 *   <li>request and response headers come from {@link ProxyHeaderPolicy};</li>
 *   <li>the {@code Host} header is replaced with the backend authority;</li>
 *   <li>connect and read timeouts and both byte caps come from
 *       {@link ProxyLimits}, enforced by {@link BoundedTransfer}, which is
 *       neutral code so the caps are asserted by ordinary unit tests rather
 *       than only by pushing an oversized body through a live container;</li>
 *   <li>the modern {@code Set-Cookie} is consumed here and its identifier is
 *       returned to the caller, which keeps it in the Tomcat 9 session. It is
 *       never written to the public response.</li>
 * </ul>
 */
class ModernBackendProxy {

	/** What the proxy did, so the caller can audit and fail correctly. */
	static final class Result {

		private final boolean completed;
		private final String failure;
		private final String modernSessionId;
		private final boolean sessionEnded;

		private Result(
				boolean completed,
				String failure,
				String modernSessionId,
				boolean sessionEnded) {
			this.completed = completed;
			this.failure = failure;
			this.modernSessionId = modernSessionId;
			this.sessionEnded = sessionEnded;
		}

		static Result completed(String modernSessionId) {
			return new Result(true, null, modernSessionId, false);
		}

		static Result failed(String failure) {
			return new Result(false, failure, null, false);
		}

		/**
		 * The modern runtime ended this session server-side and nothing was
		 * written to the public response, so the router still owns it.
		 */
		static Result ended() {
			return new Result(true, null, null, true);
		}

		boolean completed() {
			return completed;
		}

		String failure() {
			return failure;
		}

		String modernSessionId() {
			return modernSessionId;
		}

		boolean sessionEnded() {
			return sessionEnded;
		}
	}

	private final String backend;

	ModernBackendProxy(String backend) {
		this.backend = backend;
	}

	/**
	 * @param pathInside   the stripped path within the context
	 * @param modernCookie the modern session identifier to present, or
	 *                     {@code null} on the bootstrap request
	 * @param ticket       the handoff ticket, or {@code null} after bootstrap
	 * @param boundSessionId the rotated Tomcat 9 session identifier the ticket is
	 *                     bound to; sent alongside the ticket so the modern
	 *                     runtime asserts the binding independently
	 */
	Result proxy(
			HttpServletRequest request,
			HttpServletResponse response,
			PublicRouteClass routeClass,
			String pathInside,
			String modernCookie,
			String ticket,
			String boundSessionId) throws IOException {
		URL target = target(request, pathInside);
		HttpURLConnection connection = (HttpURLConnection) target.openConnection();
		connection.setInstanceFollowRedirects(false);
		connection.setConnectTimeout(ProxyLimits.CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(PublicRouteClassifier.polling(routeClass)
				? ProxyLimits.POLLING_READ_TIMEOUT_MILLIS
				: ProxyLimits.READ_TIMEOUT_MILLIS);
		connection.setRequestMethod(request.getMethod());
		connection.setUseCaches(false);

		copyRequestHeaders(request, connection);
		connection.setRequestProperty("Host", target.getAuthority());
		if (modernCookie != null) {
			connection.setRequestProperty("Cookie", "JSESSIONID=" + modernCookie);
		}
		if (ticket != null) {
			connection.setRequestProperty(HandoffProtocol.TICKET_HEADER, ticket);
			connection.setRequestProperty(
					HandoffProtocol.SESSION_HEADER, boundSessionId);
		}

		if ("POST".equalsIgnoreCase(request.getMethod())) {
			connection.setDoOutput(true);
			long declared = request.getContentLengthLong();
			if (!BoundedTransfer.declaredWithin(
					declared, ProxyLimits.MAX_REQUEST_BYTES)) {
				return Result.failed("request-too-large");
			}
			if (declared >= 0) {
				connection.setFixedLengthStreamingMode(declared);
			} else {
				connection.setChunkedStreamingMode(ProxyLimits.BUFFER_BYTES);
			}
			try (InputStream from = request.getInputStream();
					OutputStream to = connection.getOutputStream()) {
				if (!BoundedTransfer.copy(
						from, to, ProxyLimits.MAX_REQUEST_BYTES)) {
					return Result.failed("request-too-large");
				}
			}
		}

		int status;
		try {
			status = connection.getResponseCode();
		} catch (IOException unreachable) {
			connection.disconnect();
			return Result.failed("backend-unavailable");
		}

		// The end signal is read BEFORE a single byte of status, header or body
		// is written to the public response. The router has to be able to
		// invalidate the Tomcat 9 session and send its own redirect, and it
		// cannot do either once the response is committed.
		if (HandoffProtocol.END_VALUE.equals(
				connection.getHeaderField(HandoffProtocol.END_HEADER))) {
			connection.disconnect();
			return Result.ended();
		}

		String modernSessionId = null;
		response.setStatus(status);
		for (Map.Entry<String, List<String>> header
				: connection.getHeaderFields().entrySet()) {
			String name = header.getKey();
			if (name == null) {
				continue;
			}
			if ("Set-Cookie".equalsIgnoreCase(name)) {
				String identifier = sessionCookie(header.getValue());
				if (identifier != null) {
					modernSessionId = identifier;
				}
				// Consumed. The browser must never see the internal cookie.
				continue;
			}
			if (!ProxyHeaderPolicy.forwardResponseHeader(name)) {
				continue;
			}
			for (String value : header.getValue()) {
				if ("Location".equalsIgnoreCase(name)) {
					response.addHeader(name, publicLocation(request, value));
				} else {
					response.addHeader(name, value);
				}
			}
		}

		try (InputStream from = status >= 400
				? errorStream(connection)
				: connection.getInputStream()) {
			if (from != null) {
				try (OutputStream to = response.getOutputStream()) {
					if (!BoundedTransfer.copy(
							from, to, ProxyLimits.MAX_RESPONSE_BYTES)) {
						return Result.failed("response-too-large");
					}
				}
			}
		} catch (IOException interrupted) {
			return Result.failed("backend-stream-interrupted");
		} finally {
			connection.disconnect();
		}
		return Result.completed(modernSessionId);
	}

	private URL target(HttpServletRequest request, String pathInside)
			throws IOException {
		StringBuilder uri = new StringBuilder(backend)
				.append(request.getContextPath())
				.append(pathInside);
		String query = request.getQueryString();
		if (query != null && !query.isEmpty()) {
			uri.append('?').append(query);
		}
		return new URL(uri.toString());
	}

	private void copyRequestHeaders(
			HttpServletRequest request, HttpURLConnection connection) {
		Enumeration<String> names = request.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			String name = names.nextElement();
			if (!ProxyHeaderPolicy.forwardRequestHeader(name)) {
				continue;
			}
			Enumeration<String> values = request.getHeaders(name);
			while (values != null && values.hasMoreElements()) {
				connection.addRequestProperty(name, values.nextElement());
			}
		}
	}

	/**
	 * Rewrites a backend-absolute redirect back onto the public origin.
	 *
	 * <p>Only the origin changes. The path is identical on both sides by design
	 * (the modern context is mounted at {@code /webui} internally as well as
	 * publicly), so no HTML, JavaScript, CSS or asynchronous-update body is ever
	 * rewritten - which is the property that makes this proxy safe for ZK.
	 */
	private String publicLocation(HttpServletRequest request, String location) {
		if (location == null || !location.startsWith(backend)) {
			return location;
		}
		String path = location.substring(backend.length());
		StringBuilder publicOrigin = new StringBuilder(request.getScheme())
				.append("://")
				.append(request.getServerName());
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
				|| ("https".equals(request.getScheme()) && port == 443);
		if (!defaultPort) {
			publicOrigin.append(':').append(port);
		}
		return publicOrigin.append(path).toString();
	}

	private static InputStream errorStream(HttpURLConnection connection) {
		InputStream errors = connection.getErrorStream();
		if (errors != null) {
			return errors;
		}
		try {
			return connection.getInputStream();
		} catch (IOException absent) {
			return null;
		}
	}

	/** @return the {@code JSESSIONID} value, or {@code null} when absent */
	private static String sessionCookie(List<String> setCookies) {
		for (String cookie : setCookies) {
			if (cookie == null) {
				continue;
			}
			for (String attribute : cookie.split(";")) {
				String trimmed = attribute.trim();
				if (trimmed.regionMatches(true, 0, "JSESSIONID=", 0, 11)) {
					String value = trimmed.substring(11);
					return value.isEmpty() ? null : value;
				}
			}
		}
		return null;
	}

}
