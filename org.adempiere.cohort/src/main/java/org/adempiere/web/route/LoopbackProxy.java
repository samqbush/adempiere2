package org.adempiere.web.route;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.adempiere.web.handoff.HandoffProtocol;

/**
 * Servlet-free bounded proxy implementation shared by public-ingress adapters.
 */
public final class LoopbackProxy {

	public interface Request {
		String method();
		String contextPath();
		String queryString();
		Iterable<String> headerNames();
		Iterable<String> headers(String name);
		long contentLength();
		InputStream inputStream() throws IOException;
		String scheme();
		String serverName();
		int serverPort();
	}

	public interface Response {
		void status(int status);
		void header(String name, String value);
		OutputStream outputStream() throws IOException;
	}

	private static final Pattern LOOPBACK_ORIGIN = Pattern.compile(
			"(?i)^(https?)://(127\\.0\\.0\\.1|localhost|\\[::1\\])"
			+ "(?::([0-9]+))?(?=[/?#]|$)");

	private final String backend;

	public LoopbackProxy(String backend) {
		this.backend = backend;
	}

	public ProxyResult proxy(
			Request request,
			Response response,
			PublicRouteClass routeClass,
			String pathInside,
			String modernCookie,
			String ticket,
			String boundSessionId) throws IOException {
		return proxy(request, response, routeClass, pathInside, modernCookie,
				ticket, boundSessionId, null);
	}

	public ProxyResult proxy(
			Request request,
			Response response,
			PublicRouteClass routeClass,
			String pathInside,
			String modernCookie,
			String ticket,
			String boundSessionId,
			ContextRoutingPolicy contextPolicy) throws IOException {
		URL target = target(request, pathInside);
		HttpURLConnection connection = (HttpURLConnection) target.openConnection();
		connection.setInstanceFollowRedirects(false);
		connection.setConnectTimeout(contextPolicy == null
				? ProxyLimits.CONNECT_TIMEOUT_MILLIS
				: contextPolicy.connectTimeout());
		connection.setReadTimeout(contextPolicy == null
				? (PublicRouteClassifier.polling(routeClass)
				? ProxyLimits.POLLING_READ_TIMEOUT_MILLIS
				: ProxyLimits.READ_TIMEOUT_MILLIS)
				: contextPolicy.readTimeout());
		connection.setRequestMethod(request.method());
		connection.setUseCaches(false);

		copyRequestHeaders(request, connection, contextPolicy);
		connection.setRequestProperty("Host", target.getAuthority());
		String cookieHeader = internalCookies(request, contextPolicy, modernCookie);
		if (cookieHeader != null) {
			connection.setRequestProperty("Cookie", cookieHeader);
		}
		if (contextPolicy != null) {
			connection.setRequestProperty(
					"X-Forwarded-Proto", request.scheme());
		}
		if (ticket != null) {
			connection.setRequestProperty(HandoffProtocol.TICKET_HEADER, ticket);
			connection.setRequestProperty(
					HandoffProtocol.SESSION_HEADER, boundSessionId);
		}

		if ("POST".equalsIgnoreCase(request.method())) {
			connection.setDoOutput(true);
			long declared = request.contentLength();
			if (!BoundedTransfer.declaredWithin(
					declared, requestLimit(contextPolicy))) {
				return ProxyResult.failed(
						"request-too-large",
						"phase=declared declaredBytes=" + declared
							+ " limitBytes=" + requestLimit(contextPolicy));
			}
			if (declared >= 0) {
				connection.setFixedLengthStreamingMode(declared);
			} else {
				connection.setChunkedStreamingMode(ProxyLimits.BUFFER_BYTES);
			}
			try (InputStream from = request.inputStream();
					OutputStream to = connection.getOutputStream()) {
				if (!BoundedTransfer.copy(
						from, to, requestLimit(contextPolicy))) {
					return ProxyResult.failed(
							"request-too-large",
							"phase=streamed limitBytes="
								+ requestLimit(contextPolicy));
				}
			}
		}

		int status;
		try {
			status = connection.getResponseCode();
		} catch (IOException unreachable) {
			connection.disconnect();
			// The exception message can embed the internal origin, so it is
			// reported by type only; the backend is described separately.
			return ProxyResult.failed(
					"backend-unavailable",
					"backend=" + RedirectDescriptor.describe(backend)
						+ " cause=" + unreachable.getClass().getName());
		}
		if (HandoffProtocol.END_VALUE.equals(
				connection.getHeaderField(HandoffProtocol.END_HEADER))) {
			connection.disconnect();
			return ProxyResult.ended();
		}

		String modernSessionId = null;
		List<String> applicationCookies = new ArrayList<>();
		boolean applicationCookieSeen = false;
		response.status(status);
		for (Map.Entry<String, List<String>> header
				: connection.getHeaderFields().entrySet()) {
			String name = header.getKey();
			if (name == null) {
				continue;
			}
			if ("Set-Cookie".equalsIgnoreCase(name)) {
				String identifier = sessionCookie(
						header.getValue(), sessionCookieName(contextPolicy));
				if (identifier != null) {
					modernSessionId = identifier;
				}
				if (contextPolicy != null
						&& contextPolicy.applicationCookie() != null) {
					for (String value : header.getValue()) {
						String application;
						try {
							application = applicationCookie(
									value, contextPolicy, request);
						} catch (IllegalArgumentException invalid) {
							connection.disconnect();
							// The cookie value is a secret; only its name and
							// the rejection reason are reportable.
							return ProxyResult.failed(
									"invalid-application-cookie",
									"cookie="
										+ contextPolicy.applicationCookie());
						}
						if (application != null) {
							if (applicationCookieSeen) {
								connection.disconnect();
								return ProxyResult.failed(
										"duplicate-application-cookie",
										"cookie="
											+ contextPolicy
												.applicationCookie());
							}
							applicationCookieSeen = true;
							applicationCookies.add(application);
							response.header("Set-Cookie", application);
						}
					}
				}
				continue;
			}
			if (!forwardResponseHeader(name, contextPolicy)) {
				continue;
			}
			for (String value : header.getValue()) {
				String outgoing = "Location".equalsIgnoreCase(name)
						? publicLocation(request, value)
						: value;
				if (outgoing == null) {
					connection.disconnect();
					return ProxyResult.failed(
							"internal-location-leak",
							"header=Location backend="
								+ RedirectDescriptor.describe(backend)
								+ " target="
								+ RedirectDescriptor.describe(value));
				}
				response.header(name, outgoing);
			}
		}

		try (InputStream from = status >= 400
				? errorStream(connection)
				: connection.getInputStream()) {
			if (from != null) {
				try (CountingOutputStream to =
						new CountingOutputStream(response.outputStream())) {
					if (!BoundedTransfer.copy(
							from, to, responseLimit(contextPolicy))) {
						return ProxyResult.failed(
								"response-too-large",
								"phase=streamed status=" + status
									+ " limitBytes="
									+ responseLimit(contextPolicy));
					}
					long declared = connection.getContentLengthLong();
					if (declared >= 0 && to.count() != declared) {
						return ProxyResult.failed(
								"backend-stream-interrupted",
								"phase=length status=" + status
									+ " declaredBytes=" + declared
									+ " copiedBytes=" + to.count());
					}
				}
			}
		} catch (DeferredResponseBuffer.ResponseLimitExceededException tooLarge) {
			return ProxyResult.failed(
					"response-too-large",
					"phase=buffered status=" + status + " limitBytes="
						+ responseLimit(contextPolicy));
		} catch (IOException interrupted) {
			// The message can embed the internal origin, so report the type.
			return ProxyResult.failed(
					"backend-stream-interrupted",
					"phase=copy status=" + status + " cause="
						+ interrupted.getClass().getName());
		} finally {
			connection.disconnect();
		}
		return ProxyResult.completed(modernSessionId, applicationCookies);
	}

	private static final class CountingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long count;

		private CountingOutputStream(OutputStream delegate) {
			this.delegate = delegate;
		}

		public void write(int value) throws IOException {
			delegate.write(value);
			count++;
		}

		public void write(byte[] bytes, int offset, int length)
				throws IOException {
			delegate.write(bytes, offset, length);
			count += length;
		}

		public void flush() throws IOException {
			delegate.flush();
		}

		public void close() throws IOException {
			delegate.close();
		}

		private long count() {
			return count;
		}
	}

	private URL target(Request request, String pathInside) throws IOException {
		StringBuilder uri = new StringBuilder(backend)
				.append(request.contextPath())
				.append(pathInside);
		String query = request.queryString();
		if (query != null && !query.isEmpty()) {
			uri.append('?').append(query);
		}
		return new URL(uri.toString());
	}

	private void copyRequestHeaders(
			Request request,
			HttpURLConnection connection,
			ContextRoutingPolicy contextPolicy) {
		for (String name : request.headerNames()) {
			if (!forwardRequestHeader(name, contextPolicy)) {
				continue;
			}
			for (String value : request.headers(name)) {
				if (contextPolicy != null
						&& contextPolicy.sameOriginOnly(name)
						&& !sameOrigin(request, value)) {
					continue;
				}
				connection.addRequestProperty(name, value);
			}
		}
	}

	/**
	 * Reports whether {@code location} begins with {@code origin} and ends that
	 * origin there.
	 *
	 * <p>A bare {@code startsWith} is not an origin test. {@code backend} is
	 * normalized without a trailing slash, so {@code startsWith} alone also
	 * accepts a {@code Location} that merely shares a textual prefix with it:
	 * {@code http://127.0.0.1:8890@evil.example/x} would have its origin
	 * stripped and the remainder {@code @evil.example/x} appended to the public
	 * origin, which a browser parses as userinfo and follows to
	 * {@code evil.example}. That is an open redirect emitted by the ingress
	 * under its own origin. {@code http://127.0.0.1:88900/x} is the same defect
	 * without the attacker: it would be rewritten to a spliced port.
	 *
	 * <p>Requiring a delimiter or end-of-string means the origin must actually
	 * end where the prefix does.
	 */
	private static boolean startsWithOrigin(String location, String origin) {
		if (!location.startsWith(origin)) {
			return false;
		}
		if (location.length() == origin.length()) {
			return true;
		}
		char next = location.charAt(origin.length());
		return next == '/' || next == '?' || next == '#';
	}

	/**
	 * Reports whether an absolute {@code Location} carries userinfo.
	 *
	 * <p>Userinfo exists in a redirect almost exclusively to make one origin
	 * read as another: a browser resolves
	 * {@code http://127.0.0.1:8890@evil.example/x} to {@code evil.example}
	 * while the internal origin is what a human reads. Neither ADempiere
	 * runtime emits credentials in a redirect, so there is no legitimate value
	 * to preserve, and both other dispositions are wrong - rewriting splices
	 * the public origin into the userinfo and emits an open redirect under our
	 * own origin, and relaying discloses the internal origin.
	 */
	private static boolean hasUserinfo(String location) {
		int schemeEnd = location.indexOf("://");
		if (schemeEnd < 0) {
			return false;
		}
		int start = schemeEnd + 3;
		for (int index = start; index < location.length(); index++) {
			char character = location.charAt(index);
			if (character == '/' || character == '?' || character == '#') {
				return false;
			}
			if (character == '@') {
				return true;
			}
		}
		return false;
	}

	private String publicLocation(Request request, String location) {
		if (location == null) {
			return location;
		}
		if (hasUserinfo(location)) {
			return null;
		}
		String path;
		if (startsWithOrigin(location, backend)) {
			path = location.substring(backend.length());
		} else {
			Matcher target = LOOPBACK_ORIGIN.matcher(location);
			if (!target.find()) {
				// A genuinely external or relative Location is the backend's
				// own decision and is relayed unchanged. It cannot disclose
				// the internal origin: an absolute value that embedded it
				// would have to do so as userinfo, which already failed
				// closed above.
				return location;
			}
			Matcher internal = LOOPBACK_ORIGIN.matcher(backend);
			if (!internal.find()
					|| !target.group(1).equalsIgnoreCase(internal.group(1))
					|| !target.group(2).equalsIgnoreCase(internal.group(2))
					|| (target.group(3) != null
							&& !target.group(3).equals(internal.group(3)))) {
				return null;
			}
			path = location.substring(target.end());
		}
		if (path.isEmpty()) {
			path = "/";
		}
		StringBuilder publicOrigin = new StringBuilder(request.scheme())
				.append("://")
				.append(request.serverName());
		int port = request.serverPort();
		boolean defaultPort = ("http".equals(request.scheme()) && port == 80)
				|| ("https".equals(request.scheme()) && port == 443);
		if (!defaultPort) {
			publicOrigin.append(':').append(port);
		}
		return publicOrigin.append(path).toString();
	}

	private static boolean forwardRequestHeader(
			String name, ContextRoutingPolicy policy) {
		return policy == null
				? ProxyHeaderPolicy.forwardRequestHeader(name)
				: policy.forwardRequestHeader(name)
					&& !org.adempiere.web.handoff.HandoffProtocol.reserved(name);
	}

	private static boolean forwardResponseHeader(
			String name, ContextRoutingPolicy policy) {
		return policy == null
				? ProxyHeaderPolicy.forwardResponseHeader(name)
				: policy.forwardResponseHeader(name)
					&& !org.adempiere.web.handoff.HandoffProtocol.reserved(name);
	}

	private static long requestLimit(ContextRoutingPolicy policy) {
		return policy == null ? ProxyLimits.MAX_REQUEST_BYTES : policy.requestLimit();
	}

	private static long responseLimit(ContextRoutingPolicy policy) {
		return policy == null ? ProxyLimits.MAX_RESPONSE_BYTES : policy.responseLimit();
	}

	private static boolean sameOrigin(Request request, String origin) {
		if (origin == null) {
			return false;
		}
		StringBuilder expected = new StringBuilder(request.scheme())
				.append("://").append(request.serverName());
		boolean defaultPort = ("http".equals(request.scheme())
				&& request.serverPort() == 80)
				|| ("https".equals(request.scheme()) && request.serverPort() == 443);
		if (!defaultPort) {
			expected.append(':').append(request.serverPort());
		}
		return expected.toString().equals(origin);
	}

	private static String internalCookies(
			Request request,
			ContextRoutingPolicy policy,
			String modernSessionId) {
		List<String> values = new ArrayList<>();
		if (modernSessionId != null) {
			values.add(sessionCookieName(policy) + "=" + modernSessionId);
		}
		if (policy != null && policy.applicationCookie() != null) {
			String wanted = policy.applicationCookie() + "=";
			for (String name : request.headerNames()) {
				if (!"cookie".equalsIgnoreCase(name)) {
					continue;
				}
				for (String header : request.headers(name)) {
					for (String cookie : header.split(";")) {
						String trimmed = cookie.trim();
						if (trimmed.startsWith(wanted)) {
							values.add(trimmed);
						}
					}
				}
			}
		}
		return values.isEmpty() ? null : String.join("; ", values);
	}

	private static String applicationCookie(
			String setCookie,
			ContextRoutingPolicy policy,
			Request request) {
		if (setCookie == null) {
			return null;
		}
		String[] parts = setCookie.split(";", -1);
		String first = parts[0].trim();
		int separator = first.indexOf('=');
		if (separator < 0
				|| !first.substring(0, separator).equals(
						policy.applicationCookie())) {
			return null;
		}
		String value = first.substring(separator + 1);
		if (!validCookieValue(value)) {
			throw new IllegalArgumentException("Invalid application cookie value");
		}
		String maxAge = null;
		String expires = null;
		Set<String> seen = new HashSet<>();
		for (int index = 1; index < parts.length; index++) {
			String attribute = parts[index].trim();
			if (attribute.isEmpty()) {
				throw new IllegalArgumentException("Empty cookie attribute");
			}
			int equals = attribute.indexOf('=');
			String name = (equals < 0 ? attribute
					: attribute.substring(0, equals)).trim()
					.toLowerCase(Locale.ROOT);
			String attributeValue = equals < 0 ? null
					: attribute.substring(equals + 1).trim();
			if (!seen.add(name)) {
				throw new IllegalArgumentException(
						"Duplicate cookie attribute " + name);
			}
			switch (name) {
				case "max-age":
					if (attributeValue == null
							|| !attributeValue.matches("-?[0-9]+")) {
						throw new IllegalArgumentException("Invalid Max-Age");
					}
					try {
						maxAge = Long.toString(Long.parseLong(attributeValue));
					} catch (NumberFormatException invalid) {
						throw new IllegalArgumentException(
								"Invalid Max-Age", invalid);
					}
					break;
				case "expires":
					if (attributeValue == null) {
						throw new IllegalArgumentException("Invalid Expires");
					}
					try {
						expires = DateTimeFormatter.RFC_1123_DATE_TIME.format(
								ZonedDateTime.parse(
										attributeValue,
										DateTimeFormatter.RFC_1123_DATE_TIME)
										.withZoneSameInstant(ZoneOffset.UTC));
					} catch (DateTimeParseException invalid) {
						throw new IllegalArgumentException(
								"Invalid Expires", invalid);
					}
					break;
				case "path":
				case "samesite":
					if (attributeValue == null || attributeValue.isEmpty()) {
						throw new IllegalArgumentException(
								"Invalid " + name);
					}
					break;
				case "secure":
				case "httponly":
					if (attributeValue != null) {
						throw new IllegalArgumentException(
								"Invalid " + name);
					}
					break;
				default:
					throw new IllegalArgumentException(
							"Unsafe cookie attribute " + name);
			}
		}
		String path = policy.contextPath().isEmpty() ? "/" : policy.contextPath();
		StringBuilder result = new StringBuilder()
				.append(policy.applicationCookie()).append('=').append(value);
		if (maxAge != null) {
			result.append("; Max-Age=").append(maxAge);
		}
		if (expires != null) {
			result.append("; Expires=").append(expires);
		}
		return result.append("; Path=").append(path)
				.append("; HttpOnly; SameSite=Lax")
				+ ("https".equalsIgnoreCase(request.scheme()) ? "; Secure" : "");
	}

	private static boolean validCookieValue(String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character <= 0x20 || character >= 0x7f
					|| character == '"' || character == ','
					|| character == ';' || character == '\\') {
				return false;
			}
		}
		return true;
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

	private static String sessionCookieName(ContextRoutingPolicy policy) {
		return policy == null ? "JSESSIONID" : policy.sessionCookie();
	}

	private static String sessionCookie(
			List<String> setCookies, String sessionCookieName) {
		String prefix = sessionCookieName + "=";
		for (String cookie : setCookies) {
			if (cookie == null) {
				continue;
			}
			for (String attribute : cookie.split(";")) {
				String trimmed = attribute.trim();
				if (trimmed.regionMatches(
						true, 0, prefix, 0, prefix.length())) {
					String value = trimmed.substring(prefix.length());
					return value.isEmpty() ? null : value;
				}
			}
		}
		return null;
	}
}
