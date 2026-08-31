package org.adempiere.web.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.adempiere.web.route.LoopbackProxy;
import org.adempiere.web.route.ProxyResult;
import org.adempiere.web.route.PublicRouteClass;
import org.adempiere.web.route.DeferredResponseBuffer;

/**
 * Javax Servlet adapter for the reusable, framework-neutral loopback proxy.
 */
class ModernBackendProxy {

	private static final long PHASE5E_RESPONSE_LIMIT = 64L << 20;

	static final class Result implements AutoCloseable {

		private final ProxyResult delegate;
		private final DeferredServletResponse deferred;
		private boolean committed;

		private Result(ProxyResult delegate) {
			this(delegate, null);
		}

		private Result(
				ProxyResult delegate, DeferredServletResponse deferred) {
			this.delegate = delegate;
			this.deferred = deferred;
		}

		static Result completed(String modernSessionId) {
			return new Result(ProxyResult.completed(modernSessionId));
		}

		static Result failed(String failure) {
			return new Result(ProxyResult.failed(failure));
		}

		static Result ended() {
			return new Result(ProxyResult.ended());
		}

		boolean completed() {
			return delegate.completed();
		}

		String failure() {
			return delegate.failure();
		}

		String modernSessionId() {
			return delegate.modernSessionId();
		}

		boolean sessionEnded() {
			return delegate.sessionEnded();
		}

		ProxyResult coreResult() {
			return delegate;
		}

		void commitTo(HttpServletResponse response) throws IOException {
			if (committed) {
				throw new IllegalStateException(
						"The staged backend response was already committed");
			}
			if (!delegate.completed() || delegate.sessionEnded()
					|| deferred == null) {
				throw new IllegalStateException(
						"Only an ordinary completed response can be committed");
			}
			deferred.commitTo(response);
			committed = true;
		}

		@Override
		public void close() throws IOException {
			if (deferred != null) {
				deferred.close();
			}
		}
	}

	private final LoopbackProxy delegate;
	private final long responseLimit;

	ModernBackendProxy(String backend) {
		this(backend, PHASE5E_RESPONSE_LIMIT);
	}

	ModernBackendProxy(String backend, long responseLimit) {
		this.delegate = new LoopbackProxy(backend);
		this.responseLimit = responseLimit;
	}

	Result proxy(
			HttpServletRequest request,
			HttpServletResponse response,
			PublicRouteClass routeClass,
			String pathInside,
			String modernCookie,
			String ticket,
			String boundSessionId) throws IOException {
		DeferredServletResponse deferred =
				new DeferredServletResponse(responseLimit);
		try {
			ProxyResult result = delegate.proxy(
					new ServletRequestAdapter(request), deferred, routeClass,
					pathInside, modernCookie, ticket, boundSessionId);
			return new Result(result, deferred);
		} catch (IOException | RuntimeException failure) {
			deferred.close();
			throw failure;
		}
	}

	private static final class ServletRequestAdapter
			implements LoopbackProxy.Request {

		private final HttpServletRequest request;

		private ServletRequestAdapter(HttpServletRequest request) {
			this.request = request;
		}

		@Override
		public String method() {
			return request.getMethod();
		}

		@Override
		public String contextPath() {
			return request.getContextPath();
		}

		@Override
		public String queryString() {
			return request.getQueryString();
		}

		@Override
		public Iterable<String> headerNames() {
			return iterable(request.getHeaderNames());
		}

		@Override
		public Iterable<String> headers(String name) {
			return iterable(request.getHeaders(name));
		}

		@Override
		public long contentLength() {
			return request.getContentLengthLong();
		}

		@Override
		public InputStream inputStream() throws IOException {
			return request.getInputStream();
		}

		@Override
		public String scheme() {
			return request.getScheme();
		}

		@Override
		public String serverName() {
			return request.getServerName();
		}

		@Override
		public int serverPort() {
			return request.getServerPort();
		}
	}

	static final class DeferredServletResponse
			implements LoopbackProxy.Response, AutoCloseable {

		private int status;
		private final List<String[]> headers = new ArrayList<>();
		private final DeferredResponseBuffer body;

		private DeferredServletResponse(long maximumBytes) {
			String catalinaBase = System.getProperty("catalina.base", ".");
			body = new DeferredResponseBuffer(
					Path.of(catalinaBase, "work", "phase5e-response-spool"),
					maximumBytes);
		}

		@Override
		public void status(int status) {
			this.status = status;
		}

		@Override
		public void header(String name, String value) {
			headers.add(new String[] {name, value});
		}

		@Override
		public OutputStream outputStream() {
			return body.outputStream();
		}

		void commitTo(HttpServletResponse response) throws IOException {
			response.setStatus(status);
			for (String[] header : headers) {
				response.addHeader(header[0], header[1]);
			}
			if (body.size() > 0) {
				body.commitTo(response.getOutputStream());
			}
		}

		@Override
		public void close() throws IOException {
			body.close();
		}
	}

	private static <T> Iterable<T> iterable(Enumeration<T> values) {
		return values == null
				? Collections.emptyList()
				: () -> values.asIterator();
	}
}
