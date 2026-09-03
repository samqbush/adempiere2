/******************************************************************************
 * Product: ADempiere ERP & CRM Smart Business Solution                       *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.compiere.util;

import java.util.Properties;
import java.util.logging.Level;

/**
 * Phase 5g-1b diagnostic. Reads state, logs, and returns.
 *
 * <p>CI run 33683942292 produced the first scored modern Business Partner write
 * and left three deterministic business-value divergences from the frozen legacy
 * answer in {@code contracts/legacy-web-write-v1/}:
 * {@code C_BPartner.SalesRep_ID} null against {@code 101}, and
 * {@code AD_WF_Process} / {@code AD_WF_Activity} carrying {@code AD_Client_ID=11}
 * and {@code CreatedBy=101} against {@code 0}.
 *
 * <p>All three are about a <em>captured</em> context rather than the thread's
 * context at any one instant, and neither capture is observable from ZK's
 * lifecycle callbacks:
 *
 * <ul>
 * <li>{@link org.compiere.wf.MWorkflow#get} caches into a static,
 *     process-lifetime cache keyed only by workflow id, and
 *     {@code MWFProcess}'s new-record constructor is
 *     {@code super(wf.getCtx(), 0, trxName)}. The context that decides those
 *     workflow rows is whichever thread first missed that cache, possibly
 *     minutes earlier and on an unrelated flow.</li>
 * <li>{@code GridField.getDefault} resolves against {@code m_vo.ctx}, the
 *     context captured when the tab's field descriptors were built, not the
 *     context of the thread performing the save.</li>
 * </ul>
 *
 * <p>So this probe is called at those capture points. It installs nothing,
 * takes the context as a parameter, and is off unless
 * {@code -Dadempiere.phase5g.contextProbe=true} is set, which only the Phase
 * 5g-1b lanes do.
 *
 * <p>It answered both questions in CI run 33691649424 and is deliberately
 * <em>retained</em> rather than removed: the {@code SalesRep_ID} half is fixed,
 * but the workflow-attribution half is residual risk R14, whose oracle
 * increment {@code 5g-1a-y} has to re-take exactly this measurement on both
 * runtimes to decide what the frozen answer should say. Remove it when R14
 * closes.
 *
 * @author Phase 5g-1b
 */
public final class Phase5gContextProbe
{
	private static final CLogger log = CLogger.getCLogger(Phase5gContextProbe.class);

	private Phase5gContextProbe()
	{
	}

	/**
	 * Whether the probe is enabled for this JVM.
	 *
	 * <p>Callers check this before building an argument list, so a disabled
	 * probe costs one system-property read and no string concatenation.
	 *
	 * @return true when the lane asked for the probe
	 */
	public static boolean isEnabled()
	{
		return "true".equals(System.getProperty("adempiere.phase5g.contextProbe"));
	}

	/**
	 * Records the identity a context carries at a capture point.
	 *
	 * @param where the capture point, e.g. {@code MWorkflow.get-miss}
	 * @param detail what was captured, e.g. the workflow id
	 * @param ctx the context being captured; may be null
	 */
	public static void capture(String where, String detail, Properties ctx)
	{
		if (!isEnabled())
			return;
		try
		{
			StringBuilder line = new StringBuilder("phase5g-ctx where=").append(where)
				.append(" ").append(detail);
			if (ctx == null)
				line.append(" ctx=absent");
			else
				line.append(" ctx=id").append(System.identityHashCode(ctx))
					.append(" ctxSize=").append(ctx.size())
					.append(" client=").append(Env.getAD_Client_ID(ctx))
					.append(" user=").append(Env.getAD_User_ID(ctx))
					.append(" hashSalesRep=").append(ctx.getProperty("#SalesRep_ID"))
					.append(" dollarSalesRep=").append(ctx.getProperty("$SalesRep_ID"))
					.append(" prefSalesRep=").append(ctx.getProperty("P|SalesRep_ID"))
					.append(" pref123SalesRep=").append(ctx.getProperty("P123|SalesRep_ID"));
			line.append(" thread=").append(Thread.currentThread().getName());
			log.info(line.toString());
		}
		catch (RuntimeException probeFailure)
		{
			log.log(Level.INFO, "phase5g-ctx where=" + where + " probe-failed", probeFailure);
		}
	}
}
