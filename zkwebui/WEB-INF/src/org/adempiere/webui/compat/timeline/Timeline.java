/******************************************************************************
 * ADempiere ERP & CRM Smart Business Solution                                *
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
package org.adempiere.webui.compat.timeline;

import org.zkoss.zk.ui.Component;
import org.zkoss.zul.Div;

/**
 * ADempiere-owned CE replacement for the {@code org.adempiere.webui.compat.timeline.Timeline}
 * ZK 3.x add-on component used by the resource schedule.
 *
 * <p>The add-on is not carried into the modern runtime. This adapter keeps the
 * server side component tree and the {@code uuid} contract the schedule feed
 * servlet depends on; the SIMILE timeline widget itself is re-introduced, or
 * replaced, by Phase 5g.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class Timeline extends Div
{
	private static final long serialVersionUID = -1291920927206434616L;

	/** Style class the Phase 5g theme work can target. */
	public static final String SCLASS = "adempiere-timeline";

	public Timeline()
	{
		setSclass(SCLASS);
	}

	@Override
	public void beforeChildAdded(Component child, Component refChild)
	{
		if (!(child instanceof Bandinfo))
			throw new UnsupportedOperationException(
					"Only Bandinfo is allowed in a Timeline, got " + child);
		super.beforeChildAdded(child, refChild);
	}
}
