package org.adempiere.webservice.cxf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.xml.ws.Endpoint;

import org.adempiere.webservice.SoapServiceDispatcher;
import org.adempiere.webservice.business.BusinessSoapDispatcher;
import org.adempiere.webservice.business.DefaultBusinessServiceProvider;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.transport.servlet.CXFNonSpringServlet;

public final class CxfSoapServlet extends CXFNonSpringServlet {

	private static final long serialVersionUID = 1L;

	private final List<Endpoint> endpoints = new ArrayList<Endpoint>();
	private int maxRequestBytes;

	@Override
	public void init(ServletConfig config) throws ServletException {
		maxRequestBytes = parseMaxRequestBytes(config);
		super.init(config);
		SoapServiceDispatcher dispatcher = BusinessSoapDispatcher.create(
				new DefaultBusinessServiceProvider());
		publish(
				"ADService",
				new ADServiceProvider(dispatcher));
		publish(
				"ModelADService",
				new ModelADServiceProvider(dispatcher));
		publish(
				"ExternalSales",
				new ExternalSalesProvider(dispatcher));
		publish(
				"WebService",
				new CustomerWebServiceProvider(dispatcher));
	}

	@Override
	public void service(
			ServletRequest servletRequest,
			ServletResponse servletResponse) throws ServletException, IOException {
		if (!(servletRequest instanceof HttpServletRequest)
				|| !(servletResponse instanceof HttpServletResponse)) {
			super.service(servletRequest, servletResponse);
			return;
		}
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;
		response.setHeader("X-ADempiere-SOAP-Runtime", "CXF-4.1.8");
		if ("POST".equals(request.getMethod())) {
			byte[] body = readBoundedBody(request, response);
			if (body == null) {
				return;
			}
			request = new ReplayableRequest(request, body);
			servletRequest = request;
			servletResponse = new LegacyWireResponse(response);
		}
		String service = serviceForWsdlRequest(request);
		if (service == null) {
			try {
				super.service(servletRequest, servletResponse);
			} finally {
				if (servletResponse instanceof LegacyWireResponse) {
					((LegacyWireResponse) servletResponse).finish();
				}
			}
			return;
		}
		String path = "/WEB-INF/wsdl/" + service + ".wsdl";
		try (InputStream wsdl = getServletContext().getResourceAsStream(path)) {
			if (wsdl == null) {
				response.sendError(
						HttpServletResponse.SC_NOT_FOUND,
						"Unknown SOAP service");
				return;
			}
			response.setContentType("text/xml;charset=UTF-8");
			response.setHeader("X-ADempiere-Static-WSDL", "true");
			wsdl.transferTo(response.getOutputStream());
		}
	}

	@Override
	public void destroy() {
		for (Endpoint endpoint : endpoints) {
			endpoint.stop();
		}
		endpoints.clear();
		super.destroy();
	}

	private void publish(
			String path,
			CxfSoapProvider provider) {
		EndpointImpl endpoint = new EndpointImpl(getBus(), provider);
		endpoint.publish("/" + path);
		endpoints.add(endpoint);
	}

	private static String serviceForWsdlRequest(HttpServletRequest request) {
		String query = request.getQueryString();
		if (!"GET".equals(request.getMethod())
				|| query == null
				|| !query.toLowerCase(java.util.Locale.ROOT).startsWith("wsdl")) {
			return null;
		}
		String uri = request.getRequestURI();
		String marker = "/services/";
		int markerIndex = uri.lastIndexOf(marker);
		if (markerIndex < 0) {
			return null;
		}
		String service = uri.substring(markerIndex + marker.length());
		if (service.isEmpty() || service.indexOf('/') >= 0) {
			return null;
		}
		return service;
	}

	private static int parseMaxRequestBytes(ServletConfig config)
			throws ServletException {
		String configured = config.getInitParameter("maxRequestBytes");
		try {
			int value = Integer.parseInt(configured);
			if (value <= 0) {
				throw new NumberFormatException("not positive");
			}
			return value;
		} catch (NumberFormatException failure) {
			throw new ServletException(
					"maxRequestBytes must be a positive integer",
					failure);
		}
	}

	private byte[] readBoundedBody(
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (request.getContentLengthLong() > maxRequestBytes) {
			response.sendError(
					HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
					"SOAP request exceeds the configured limit");
			return null;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		try (InputStream input = request.getInputStream()) {
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (body.size() + read > maxRequestBytes) {
					response.sendError(
							HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
							"SOAP request exceeds the configured limit");
					return null;
				}
				body.write(buffer, 0, read);
			}
		}
		return body.toByteArray();
	}

	private static final class ReplayableRequest
			extends HttpServletRequestWrapper {

		private final byte[] body;

		private ReplayableRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body.clone();
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream input = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public boolean isFinished() {
					return input.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException(
							"Asynchronous request reads are not supported");
				}

				@Override
				public int read() {
					return input.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) {
					return input.read(buffer, offset, length);
				}
			};
		}
	}

	private static final class LegacyWireResponse
			extends HttpServletResponseWrapper {

		private ServletOutputStream output;
		private PrintWriter writer;

		private LegacyWireResponse(HttpServletResponse response) {
			super(response);
		}

		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			if (writer != null) {
				throw new IllegalStateException("getWriter() was already called");
			}
			if (output == null) {
				output = new LegacyEmptyElementOutputStream(
						super.getOutputStream());
			}
			return output;
		}

		@Override
		public PrintWriter getWriter() throws IOException {
			if (output != null) {
				throw new IllegalStateException(
						"getOutputStream() was already called");
			}
			if (writer == null) {
				writer = new PrintWriter(new OutputStreamWriter(
						new LegacyEmptyElementOutputStream(
								super.getOutputStream()),
						getCharacterEncoding()));
			}
			return writer;
		}

		private void finish() throws IOException {
			if (writer != null) {
				writer.flush();
			} else if (output != null) {
				output.flush();
			} else {
				flushBuffer();
			}
		}
	}

	private static final class LegacyEmptyElementOutputStream
			extends ServletOutputStream {

		private static final int MAX_TAG_BYTES = 16 * 1024;
		private static final Pattern ATTRIBUTE = Pattern.compile(
				"\\s+([A-Za-z_][A-Za-z0-9_.:-]*)=\"[^\"]*\"");
		private static final List<String> WINDOW_TAB_DATA_ATTRIBUTE_ORDER =
				List.of("xmlns", "NumRows", "TotalRows", "StartRow", "QtyPages");

		private final OutputStream delegate;
		private ByteArrayOutputStream tag;

		private LegacyEmptyElementOutputStream(OutputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener listener) {
			throw new UnsupportedOperationException(
					"Asynchronous response writes are not supported");
		}

		@Override
		public void write(int value) throws IOException {
			if (tag != null) {
				tag.write(value);
				if (tag.size() > MAX_TAG_BYTES) {
					throw new IOException(
							"SOAP response tag exceeds the compatibility limit");
				}
				if (value == '>') {
					writeTag();
				}
				return;
			}
			if (value == '<') {
				tag = new ByteArrayOutputStream();
				tag.write(value);
				return;
			}
			delegate.write(value);
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			if (tag != null) {
				throw new IOException("SOAP response ended inside an XML tag");
			}
			delegate.close();
		}

		private void writeTag() throws IOException {
			String serialized = tag.toString(StandardCharsets.UTF_8);
			tag = null;
			if (serialized.startsWith("<WindowTabData ")) {
				serialized = reorderWindowTabDataAttributes(serialized);
			}
			if (!serialized.startsWith("<soap:Envelope ")) {
				serialized = serialized.replace(
						" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
						"");
			}
			if (serialized.endsWith("/>")) {
				serialized = serialized.substring(0, serialized.length() - 2)
						+ " />";
			}
			delegate.write(serialized.getBytes(StandardCharsets.UTF_8));
		}

		private static String reorderWindowTabDataAttributes(String tag) {
			Matcher matcher = ATTRIBUTE.matcher(tag);
			Map<String, String> attributes = new LinkedHashMap<String, String>();
			while (matcher.find()) {
				attributes.put(matcher.group(1), matcher.group());
			}
			StringBuilder ordered = new StringBuilder("<WindowTabData");
			for (String name : WINDOW_TAB_DATA_ATTRIBUTE_ORDER) {
				String attribute = attributes.remove(name);
				if (attribute != null) {
					ordered.append(attribute);
				}
			}
			for (String attribute : attributes.values()) {
				ordered.append(attribute);
			}
			return ordered.append('>').toString();
		}
	}
}
