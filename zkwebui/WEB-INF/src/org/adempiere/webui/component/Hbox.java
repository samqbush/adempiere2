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
package org.adempiere.webui.component;

import org.adempiere.webui.compat.ZkCompat;
import org.zkoss.zk.ui.Component;

/**
 * ADempiere horizontal box that keeps the ZK 3.6 {@code widths} property
 * removed by ZK CE 10.
 *
 * <p>ZK CE 10 sizes box children individually, so the legacy comma separated
 * list is distributed over the children. The value is reapplied whenever a
 * child is added, which keeps the ZK 3.6 behaviour of declaring the widths
 * before or after filling the box.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class Hbox extends org.zkoss.zul.Hbox
{
	private static final long serialVersionUID = 4477520102060639860L;

	private String widths;

	/**
	 * @return legacy comma separated child widths
	 */
	public String getWidths()
	{
		return widths;
	}

	/**
	 * @param widths legacy comma separated child widths
	 */
	public void setWidths(String widths)
	{
		this.widths = widths;
		ZkCompat.setWidths(this, widths);
	}

	@Override
	public void onChildAdded(Component child)
	{
		super.onChildAdded(child);
		if (widths != null)
			ZkCompat.setWidths(this, widths);
	}
}
