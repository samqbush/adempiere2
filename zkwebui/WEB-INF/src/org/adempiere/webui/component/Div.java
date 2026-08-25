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

/**
 * ADempiere {@code div} that keeps the ZK 3.6 {@code align} property removed by
 * ZK CE 10.
 *
 * @author ADempiere Phase 5d modernization
 */
public class Div extends org.zkoss.zul.Div
{
	private static final long serialVersionUID = 7092286648063311185L;

	private String align;

	/**
	 * @return legacy horizontal alignment
	 */
	public String getAlign()
	{
		return align;
	}

	/**
	 * @param align legacy horizontal alignment, rendered as the same client
	 *            attribute ZK 3.6 emitted
	 */
	public void setAlign(String align)
	{
		this.align = align;
		ZkCompat.setAlign(this, align);
	}
}
