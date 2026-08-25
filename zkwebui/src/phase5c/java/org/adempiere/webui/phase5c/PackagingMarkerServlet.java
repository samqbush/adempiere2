package org.adempiere.webui.phase5c;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class PackagingMarkerServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		response.getWriter().println("Phase 5c packaging only; modern web UI unavailable");
	}
}
