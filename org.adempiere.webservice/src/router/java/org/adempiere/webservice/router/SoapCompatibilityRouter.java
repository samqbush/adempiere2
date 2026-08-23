package org.adempiere.webservice.router;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.adempiere.webservice.ServiceOperationKey;
import org.adempiere.webservice.SoapOperationRegistry;

public final class SoapCompatibilityRouter extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOGGER =
			Logger.getLogger(SoapCompatibilityRouter.class.getName());
	private static final String MODERN_SESSION_ATTRIBUTE =
			"org.adempiere.webservice.router.ModernJSessionId";
	private static final Set<ServiceOperationKey> OPERATIONS =
			SoapOperationRegistry.requiredOperationKeys();

	private String modernBaseUrl;
	private int maxRequestBytes;
	private int connectTimeoutMillis;
	private int readTimeoutMillis;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		modernBaseUrl = requiredUrl(config, "modernBaseUrl");
		maxRequestBytes = positiveInteger(config, "maxRequestBytes");
		connectTimeoutMillis = positiveInteger(
				config, "connectTimeoutMillis");
		readTimeoutMillis = positiveInteger(config, "readTimeoutMillis");
	}

	@Override
	protected void doGet(
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		String service = requireService(request, response);
		if (service == null) {
			return;
		}
		String query = request.getQueryString();
		if (query == null
				|| !query.toLowerCase(Locale.ROOT).startsWith("wsdl")) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String path = "/contracts/xfire-v1/wsdl/" + service + ".wsdl";
		try (InputStream wsdl = getServletContext().getResourceAsStream(path)) {
			if (wsdl == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			response.setContentType("text/xml;charset=UTF-8");
			response.setHeader("X-ADempiere-Static-WSDL", "true");
			copy(wsdl, response.getOutputStream());
		}
	}

	@Override
	protected void doPost(
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		String service = requireService(request, response);
		if (service == null) {
			return;
		}
		byte[] body = readBounded(request, response);
		if (body == null) {
			return;
		}
		String operation;
		try {
			operation = SoapRequestParser.parse(service, body, OPERATIONS);
		} catch (IllegalArgumentException failure) {
			response.sendError(
					HttpServletResponse.SC_BAD_REQUEST,
					failure.getMessage());
			return;
		}
		LOGGER.info(
				"SOAP route service=" + service
						+ " operation=" + operation
						+ " target=MODERN");
		proxy(request, response, service, body);
	}

	private void proxy(
			HttpServletRequest request,
			HttpServletResponse response,
			String service,
			byte[] body) throws IOException {
		String targetUrl = modernBaseUrl + "/" + service;
		HttpURLConnection connection =
				(HttpURLConnection) new URL(targetUrl).openConnection();
		connection.setConnectTimeout(connectTimeoutMillis);
		connection.setReadTimeout(readTimeoutMillis);
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setInstanceFollowRedirects(false);
		copyRequestHeader(request, connection, "Content-Type");
		copyRequestHeader(request, connection, "SOAPAction");
		HttpSession session = request.getSession(true);
		String sessionId = modernSessionId(session);
		if (sessionId != null) {
			connection.setRequestProperty(
					"Cookie", "JSESSIONID=" + sessionId);
		}
		connection.setFixedLengthStreamingMode(body.length);
		connection.getOutputStream().write(body);

		int status = connection.getResponseCode();
		response.setStatus(status);
		copyResponseHeader(connection, response, "Content-Type");
		copyResponseHeader(connection, response, "Content-Language");
		captureModernSession(
				session, connection.getHeaderFields().get("Set-Cookie"));
		InputStream responseBody = status >= 400
				? connection.getErrorStream()
				: connection.getInputStream();
		if (responseBody != null) {
			response.flushBuffer();
			try (InputStream input = responseBody) {
				copy(input, response.getOutputStream());
			}
		}
		connection.disconnect();
	}

	private byte[] readBounded(
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (request.getContentLengthLong() > maxRequestBytes) {
			response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			return null;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = request.getInputStream().read(buffer)) >= 0) {
			if (body.size() + read > maxRequestBytes) {
				response.sendError(
						HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
				return null;
			}
			body.write(buffer, 0, read);
		}
		return body.toByteArray();
	}

	private String requireService(
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		String path = request.getPathInfo();
		if (path == null || !path.matches("/[^/]+/?")) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return null;
		}
		String service = path.substring(1).replace("/", "");
		boolean known = OPERATIONS.stream()
				.anyMatch(key -> key.getService().equals(service));
		if (!known) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return null;
		}
		return service;
	}

	private static String modernSessionId(HttpSession session) {
		if (session == null) {
			return null;
		}
		Object value = session.getAttribute(MODERN_SESSION_ATTRIBUTE);
		return value instanceof String ? (String) value : null;
	}

	private static void captureModernSession(
			HttpSession session,
			List<String> cookies) {
		if (session == null || cookies == null) {
			return;
		}
		for (String cookie : cookies) {
			if (cookie.startsWith("JSESSIONID=")) {
				int end = cookie.indexOf(';');
				String value = cookie.substring(
						"JSESSIONID=".length(),
						end < 0 ? cookie.length() : end);
				session.setAttribute(MODERN_SESSION_ATTRIBUTE, value);
				return;
			}
		}
	}

	private static void copyRequestHeader(
			HttpServletRequest request,
			HttpURLConnection connection,
			String name) {
		String value = request.getHeader(name);
		if (value != null) {
			connection.setRequestProperty(name, value);
		}
	}

	private static void copyResponseHeader(
			HttpURLConnection connection,
			HttpServletResponse response,
			String name) {
		String value = connection.getHeaderField(name);
		if (value != null) {
			response.setHeader(name, value);
		}
	}

	private static void copy(InputStream input, java.io.OutputStream output)
			throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) >= 0) {
			output.write(buffer, 0, read);
		}
	}

	private static int positiveInteger(
			ServletConfig config,
			String name) throws ServletException {
		String value = config.getInitParameter(name);
		try {
			int parsed = Integer.parseInt(value);
			if (parsed <= 0) {
				throw new NumberFormatException("not positive");
			}
			return parsed;
		} catch (NumberFormatException failure) {
			throw new ServletException(
					name + " must be a positive integer",
					failure);
		}
	}

	private static String requiredUrl(
			ServletConfig config,
			String name) throws ServletException {
		String value = config.getInitParameter(name);
		if (value == null || !value.matches("http://127\\.0\\.0\\.1:[0-9]+/.+")) {
			throw new ServletException(
					name + " must be an explicit loopback HTTP URL");
		}
		return value.replaceFirst("/+$", "");
	}

}
