/**
 * Copyright (C) 2003-2026, ADempiere modernization Phase 5e.
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
 */

package org.adempiere.webui.session;

import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.web.cohort.CohortIdentity;
import org.compiere.model.MClient;
import org.compiere.model.MOrg;
import org.compiere.model.MRole;
import org.compiere.model.MUser;
import org.compiere.model.MWarehouse;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Language;
import org.compiere.util.Login;

import jakarta.servlet.http.HttpSession;

/**
 * Seeds a freshly created modern session from a verified Phase 5e handoff
 * ticket.
 *
 * <p>What it does <em>not</em> do is as important as what it does. It copies no
 * password, no encrypted credential, no ZK desktop, no component tree, no
 * carry-over and no legacy session attribute. It writes only the six identity
 * values the ticket carries and then runs ADempiere's own
 * {@link Login#validateLogin} and {@link Login#loadPreferences}, which are the
 * exact two calls {@code RolePanel.validateRoles()} makes at the end of an
 * ordinary role selection.
 *
 * <p>The consequence is that the modern session reaches
 * {@code AdempiereWebUI.loginCompleted()} through the same server-side state an
 * ordinary login produces, so every model validator, every preference and every
 * accounting default is applied by the product's own code rather than
 * reconstructed here.
 */
public final class CohortHandoff {

	/** Marks a session that a verified ticket bootstrapped. */
	public static final String BOOTSTRAPPED_ATTRIBUTE =
			"org.adempiere.webui.session.cohortBootstrapped";

	/**
	 * Marks a bootstrapped session whose user has logged out.
	 *
	 * <p>Read by {@link CohortHandoffFilter} on the very next routed request,
	 * which is what turns "the ZK application logged the user out" into "the
	 * container destroyed the session on both runtimes".
	 */
	public static final String ENDED_ATTRIBUTE =
			"org.adempiere.webui.session.cohortSessionEnded";

	private static final CLogger log = CLogger.getCLogger(CohortHandoff.class);

	private CohortHandoff() {
	}

	/** Whether this session was created by a verified Phase 5e handoff. */
	public static boolean bootstrapped(HttpSession session) {
		return session != null
				&& Boolean.TRUE.equals(session.getAttribute(BOOTSTRAPPED_ATTRIBUTE));
	}

	/**
	 * Applies {@code identity} to the session's ADempiere context.
	 *
	 * <p>This runs on an ordinary request thread <em>before</em> ZK has created
	 * an execution for the new session, so nothing has installed a
	 * {@link ServerContext} on it. Everything below - {@code MClient.get},
	 * {@code Login.validateLogin}, {@code Login.loadPreferences} and every model
	 * validator they call - reads {@code Env.getCtx()} somewhere, and
	 * {@code Env.getCtx()} on an uninstalled thread answers an empty throwaway
	 * {@code Properties}. The session's own context is therefore installed for
	 * the duration of the seeding and removed again in a {@code finally}, so a
	 * pooled request thread never carries this identity into the next request.
	 *
	 * @return {@code null} on success, or an operator-facing reason on failure
	 */
	static String seed(HttpSession session, CohortIdentity identity) {
		String sessionId = session.getId();
		Properties ctx = SessionManager.getSessionContext(sessionId);
		if (ctx == null) {
			return "no session context was registered";
		}
		Properties restore = ServerContext.getCurrentInstance();
		boolean hadContext = restore != null && !restore.isEmpty();
		try {
			ServerContext.setCurrentInstance(ctx);
			return apply(session, sessionId, ctx, identity);
		} catch (RuntimeException failure) {
			log.log(Level.SEVERE, "The Phase 5e handoff could not be applied", failure);
			return "the handed-over identity could not be applied";
		} finally {
			if (hadContext) {
				ServerContext.setCurrentInstance(restore);
			} else {
				ServerContext.dispose();
			}
		}
	}

	private static String apply(
			HttpSession session,
			String sessionId,
			Properties ctx,
			CohortIdentity identity) {
		MClient client = MClient.get(ctx, identity.clientId());
		MOrg org = MOrg.get(ctx, identity.orgId());
		MUser user = MUser.get(ctx, identity.userId());
		MRole role = MRole.get(ctx, identity.roleId());
		if (client == null || org == null || user == null || role == null) {
			return "the handed-over identity does not resolve";
		}

		Language language = Language.getLanguage(identity.adLanguage());
		Env.setContext(ctx, Env.LANGUAGE, language.getAD_Language());
		Env.setContext(ctx, "#AD_Client_ID", identity.clientId());
		Env.setContext(ctx, "#AD_Client_Name", client.getName());
		Env.setContext(ctx, "#AD_User_ID", identity.userId());
		Env.setContext(ctx, "#AD_User_Name", user.getName());
		// Run 33691649424 measured this: the modern session context held 317
		// entries, client 11 and user 101, and no #SalesRep_ID at all, so
		// GridField.getDefault resolved no default for C_BPartner.SalesRep_ID
		// and the modern capture wrote null where the frozen legacy answer is
		// 101. Login sets #AD_User_Name, #AD_User_ID and #SalesRep_ID together
		// in both of its role loops (Login.java:423-425 and 519-521); this
		// method mirrors that block and had reproduced only the first two.
		// The loop's one other write, #SysAdmin, is read only by the Swing
		// client (APanel.java:466) and is deliberately not part of a web
		// handoff identity.
		Env.setContext(ctx, "#SalesRep_ID", identity.userId());
		Env.setContext(ctx, "#AD_Role_ID", identity.roleId());
		Env.setContext(ctx, "#AD_Role_Name", role.getName());
		Env.setContext(ctx, "#AD_Org_ID", identity.orgId());
		Env.setContext(ctx, "#AD_Org_Name", org.getName());

		KeyNamePair orgPair = new KeyNamePair(identity.orgId(), org.getName());
		KeyNamePair warehousePair = null;
		if (identity.warehouseId() > 0) {
			MWarehouse warehouse = MWarehouse.get(ctx, identity.warehouseId());
			if (warehouse == null) {
				return "the handed-over warehouse does not resolve";
			}
			warehousePair = new KeyNamePair(
					identity.warehouseId(), warehouse.getName());
		}

		Login login = new Login(ctx);
		String refused = login.validateLogin(orgPair);
		if (refused != null && !refused.isEmpty()) {
			// A model validator refused this login. The handoff must respect
			// that exactly as the ordinary role panel does.
			return "a login validator refused the handed-over identity";
		}
		String problem = login.loadPreferences(orgPair, warehousePair, null, null);
		if (problem != null && !problem.isEmpty()
				&& !"NoValidAcctInfo".equals(problem)) {
			return "preferences could not be loaded for the handed-over identity";
		}
		// The explicit session key, not the thread's: the preferences must be
		// reachable from AdempiereWebUI.loginCompleted(), which looks them up by
		// the HTTP session identifier.
		SessionManager.loadUserPreference(sessionId, identity.userId());
		session.setAttribute(BOOTSTRAPPED_ATTRIBUTE, Boolean.TRUE);
		// AdempiereWebUI.onCreate reads this the way the ordinary login path
		// writes it, so the desktop's own identity check behaves identically.
		session.setAttribute("Check_AD_User_ID", identity.userId());
		return null;
	}

	/**
	 * Records that a routed session ended, so the routed lane destroys it on
	 * both runtimes.
	 *
	 * <p>A routed logout is observed on the modern runtime and nowhere else,
	 * yet it ends a session that exists on two. The Tomcat 9 session holds the
	 * cohort affinity <em>and</em> the sticky cohort decision, so leaving it
	 * alive after a logout meant the next login on the same browser inherited a
	 * decision that was taken for a user who is no longer signed in: a user the
	 * configuration no longer selects stayed modern indefinitely.
	 *
	 * <p>Marking rather than invalidating here is deliberate. ZK is mid-execution
	 * when {@code AdempiereWebUI.logout()} runs and still has to send its own
	 * redirect; destroying the container session underneath it would abort that
	 * response. {@link CohortHandoffFilter} performs the invalidation on the
	 * next request, before the chain runs, and signals the router in the same
	 * response.
	 *
	 * <p>{@link #BOOTSTRAPPED_ATTRIBUTE} deliberately survives: it records that
	 * this HTTP session was created by a verified ticket, which is what lets
	 * {@link CohortHandoffFilter} keep serving the session between the logout
	 * and the destruction. What must not survive is the seeded <em>identity</em>.
	 * The desktop's own {@code Check_AD_User_ID} guard compares a window's user
	 * against it, and a logged-out session that still advertises the previous
	 * user is the exact cross-identity state that guard exists to catch.
	 */
	public static void loggedOut(HttpSession session) {
		if (session == null) {
			return;
		}
		try {
			session.removeAttribute("Check_AD_User_ID");
			if (bootstrapped(session)) {
				session.setAttribute(ENDED_ATTRIBUTE, Boolean.TRUE);
			}
		} catch (IllegalStateException destroyed) {
			// The session was invalidated by the same request. Nothing to forget.
			log.fine("A Phase 5e session was already destroyed at logout");
		}
	}

	/** Whether a routed session has been logged out and is awaiting destruction. */
	public static boolean ended(HttpSession session) {
		return session != null
				&& Boolean.TRUE.equals(session.getAttribute(ENDED_ATTRIBUTE));
	}
}
