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
import org.compiere.util.Ini;
import org.zkoss.zk.ui.http.HttpSessionListener;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import java.util.Enumeration;
import java.util.Optional;
import java.util.logging.Level;


public class SessionManagerListener extends HttpSessionListener {

    private static CLogger log = CLogger.getCLogger(SessionManagerListener.class);

    public void sessionCreated(HttpSessionEvent httpSessionEvent) {
        super.sessionCreated(httpSessionEvent);
        final HttpSession httpSession = httpSessionEvent.getSession();
        log.info("        Create Session Id : " + httpSession.getId());
        log.info("------------------------------------------------");
        log.info("             Event Source : " + httpSessionEvent.getSource());
        log.info("            Session Cache : " +  SessionManager.getSessionCache().size());
        log.info("    Session Context Cache : " +  SessionManager.getSessionContextCache().size());
        log.info("        Application Cache : " +  SessionManager.getAppicationCache().size());
        log.info("            Desktop Cache : " +  SessionManager.getDesktopCache().size());
        log.info("Execution CarryOver Cache : " +  SessionManager.getExecutionCarryOverCache().size());
        log.info("    User Preference Cache : " +  SessionManager.getSessionUserPreferenceCache().size());
        log.info("User Authentication Cache : " +  SessionManager.getUserAuthenticationCache().size());
        log.info("------------------------------------------------");
        log.info(" ");
        //Setting Ephemeral session
        Optional<Integer> maybeMaxInactiveInterval = Optional.ofNullable((Integer) httpSession.getAttribute("MaxInactiveInterval"));
        if (maybeMaxInactiveInterval.isEmpty()) {
            httpSession.setAttribute("MaxInactiveInterval", httpSession.getMaxInactiveInterval());
            Optional<String> maybeEphemeralMaxInactiveInterval = Optional.of(Ini.getProperty("EphemeralSessionMaxInactiveInterval"));
            maybeEphemeralMaxInactiveInterval
                    .filter(ephemeralMaxInactiveInterval -> !ephemeralMaxInactiveInterval.isEmpty())
                    .ifPresent(ephemeralMaxInactiveInterval -> {
                        httpSession.setMaxInactiveInterval(Integer.parseInt(ephemeralMaxInactiveInterval));
                        httpSession.setAttribute("MaxInactiveInterval", Integer.parseInt(ephemeralMaxInactiveInterval));
                        log.log(Level.INFO, "Ephemeral Session Max Inactive Interval = " + ephemeralMaxInactiveInterval);
                    });
        }
        log.info(" Max Inactive Interval : " +  httpSession.getMaxInactiveInterval());
        Enumeration<String> attributeNames = httpSession.getAttributeNames();
        while (attributeNames.hasMoreElements()) {
            String attrubuteName = attributeNames.nextElement();
            log.info(" Attribute Name : " + attrubuteName +  " - Value : " + httpSession.getAttribute(attrubuteName));
        }
        log.info(" ");
        SessionManager.createSession(httpSession);
        log.info(census("after-create", httpSession.getId()));
    }

    /**
     * One machine-readable line naming every {@code SessionManager} cache size,
     * emitted at the SAME point in the lifecycle on both ends.
     *
     * <p>The historical per-cache log lines above are written <em>before</em>
     * {@code createSession} inserts anything and <em>after</em> the destruction
     * path has removed it, so a "before" reading taken from a creation and an
     * "after" reading taken from a destruction are not the same measurement:
     * comparing them compared two different points in the lifecycle and could
     * report a leak as balanced, or balance as a leak. This line is written
     * after the mutation in both cases, so the two readings are comparable, and
     * it carries the session identifier so a capture can prove which session's
     * end it is reading rather than reading whichever event happened last.
     */
    static String census(String point, String sessionId) {
        return "SessionManager cache census"
                + " point=" + point
                + " session=" + sessionId
                + " Session-Cache=" + SessionManager.getSessionCache().size()
                + " Session-Context-Cache=" + SessionManager.getSessionContextCache().size()
                + " Application-Cache=" + SessionManager.getAppicationCache().size()
                + " Desktop-Cache=" + SessionManager.getDesktopCache().size()
                + " Execution-CarryOver-Cache=" + SessionManager.getExecutionCarryOverCache().size()
                + " User-Preference-Cache=" + SessionManager.getSessionUserPreferenceCache().size()
                + " User-Authentication-Cache=" + SessionManager.getUserAuthenticationCache().size();
    }

    public void sessionDestroyed(HttpSessionEvent httpSessionEvent) {
        super.sessionDestroyed(httpSessionEvent);
        final HttpSession httpSession = httpSessionEvent.getSession();
        log.info(" Destroyed Session Id : " + httpSession.getId());
        log.info("------------------------------------------------");
        if (SessionManager.existsSession(httpSession.getId())){
            SessionManager.clearSession(httpSession.getId());
            SessionManager.removeUserAuthentication(httpSession.getId());
            SessionManager.removeSessionUserPreference(httpSession.getId());
            SessionManager.cleanSessionBackground(httpSession.getId());
            SessionManager.removeSession(httpSession.getId());
            log.info("             Event Source : " + httpSessionEvent.getSource());
            log.info("            Session Cache : " + SessionManager.getSessionCache().size());
            log.info("    Session Context Cache : " + SessionManager.getSessionContextCache().size());
            log.info("        Application Cache : " + SessionManager.getAppicationCache().size());
            log.info("            Desktop Cache : " + SessionManager.getDesktopCache().size());
            log.info("Execution CarryOver Cache : " + SessionManager.getExecutionCarryOverCache().size());
            log.info("    User Preference Cache : " + SessionManager.getSessionUserPreferenceCache().size());
            log.info("User Authentication Cache : " + SessionManager.getUserAuthenticationCache().size());
        }
        log.info("------------------------------------------------");
        // Phase 5e: sessionDestroyed is the container telling us the session is
        // already being destroyed. Calling invalidate() here re-enters
        // destruction on some containers and throws IllegalStateException on the
        // rest, which aborts this listener before the caches above are reported
        // and leaves the next listener in the chain unrun. The container owns
        // the invalidation; this listener owns the caches.
        //
        // The census is written unconditionally and after the cleanup, so a
        // lifecycle capture can find this exact session's end even when the
        // session was never registered, and can compare it with an after-create
        // census taken at the equivalent point.
        log.info(census("after-destroy", httpSession.getId()));
    }
}
