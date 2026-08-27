/**
 * Copyright (C) 2003-2022, e-Evolution, http://www.e-evolution.com
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * Email: victor.perez@e-evolution.com, http://www.e-evolution.com , http://github.com/e-Evolution
 * Created by victor.perez@e-evolution.com , www.e-evolution.com
 */

package org.adempiere.webui.session;

import org.compiere.util.CLogger;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;


public class SessionTimeoutFilter implements Filter {

    private static final CLogger logger = CLogger.getCLogger(SessionTimeoutFilter.class);

    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        Map<String, String[]> param = httpServletRequest.getParameterMap();
        HttpSession httpSession = httpServletRequest.getSession();
        if (!SessionManager.existsSession(httpSession.getId())) {
            // Phase 5e: this branch used to fall off the end of doFilter without
            // calling the chain, so the client received an empty HTTP 200 and
            // no log line. The refusal is deliberate - an unregistered session
            // means SessionManagerListener did not run and no ADempiere context
            // exists - but it has to be visible, so it is now an explicit status
            // instead of a silent success.
            logger.warning("Refusing a request whose session was never registered: "
                    + httpSession.getId());
            if (response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).sendError(
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
            return;
        }

        boolean isRealRequest = true;
        for (Object key : param.keySet()) {
            if (key.toString().startsWith("cmd")
                    && "onTimer".equals(((String[]) param.get(key))[0])) {
                // not real request
                isRealRequest = false;
                // try get last real request time
                Long lastRealRequest = (Long) httpSession.getAttribute("LAST_REAL_REQUEST");
                if (lastRealRequest == null) {
                    // init if no previous real request
                    lastRealRequest = System.currentTimeMillis();
                    httpSession.setAttribute("LAST_REAL_REQUEST", lastRealRequest);
                } else if ((System.currentTimeMillis() - lastRealRequest) > 20000) {
                    // invalidate session if only poll request for a long time
                    logger.info("Invalidate Session : " +  httpSession.getId());
                    httpSession.invalidate();
                    // Phase 5e: the original code invalidated and then carried on
                    // into chain.doFilter and setAttribute on the very session it
                    // had just destroyed, which throws IllegalStateException on
                    // the way out and hides the timeout behind a stack trace.
                    if (response instanceof HttpServletResponse) {
                        ((HttpServletResponse) response).sendError(
                                HttpServletResponse.SC_REQUEST_TIMEOUT);
                    }
                    return;
                }
            }
        }

        // process request
        chain.doFilter(request, response);

        // update LAST_REAL_REQUEST if this is a real request
        if (isRealRequest) {
            // record last real request time
            long now = System.currentTimeMillis();
            try {
                httpSession.setAttribute("LAST_REAL_REQUEST", now);
            } catch (IllegalStateException destroyed) {
                // The request itself logged the user out. Recording the time of
                // a session that no longer exists is not an error worth failing
                // an otherwise successful response for.
                logger.fine("Session " + "was destroyed during its own request");
            }
        }
    }

    @Override
    public void destroy() {
    }
}
