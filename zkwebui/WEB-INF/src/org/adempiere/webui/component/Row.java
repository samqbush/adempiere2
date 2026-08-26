/******************************************************************************
 * Product: Posterita Ajax UI 												  *
 * Copyright (C) 2007 Posterita Ltd.  All Rights Reserved.                    *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * Posterita Ltd., 3, Draper Avenue, Quatre Bornes, Mauritius                 *
 * or via info@posterita.org or http://www.posterita.org/                     *
 *****************************************************************************/

package org.adempiere.webui.component;

import org.adempiere.webui.compat.ZkCompat;
import org.zkoss.zk.ui.Component;

/**
 *
 * @author  <a href="mailto:agramdass@gmail.com">Ashley G Ramdass</a>
 * @date    Feb 25, 2007
 * @version $Revision: 0.10 $
 */
public class Row extends org.zkoss.zul.Row
{
	/**
	 * 
	 */
	private static final long serialVersionUID = -3096460956090507074L;

	private String spans;

	private String widths;

	/**
	 * @return legacy comma separated column spans
	 */
	public String getSpans()
	{
		return spans;
	}

	/**
	 * ZK CE 10 removed {@code Row.spans}; see
	 * {@link ZkCompat#setSpans(org.zkoss.zul.Row, String)}.
	 *
	 * @param spans legacy comma separated column spans
	 */
	public void setSpans(String spans)
	{
		this.spans = spans;
		ZkCompat.setSpans(this, spans);
	}

	/**
	 * @return legacy comma separated cell widths
	 */
	public String getWidths()
	{
		return widths;
	}

	/**
	 * ZK CE 10 removed {@code Row.widths}; the widths are applied to the row
	 * children instead.
	 *
	 * @param widths legacy comma separated cell widths
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
		if (spans != null)
			ZkCompat.setSpans(this, spans);
		if (widths != null)
			ZkCompat.setWidths(this, widths);
	}
}
