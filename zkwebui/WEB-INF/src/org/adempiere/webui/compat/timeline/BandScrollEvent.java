/******************************************************************************
 * ADempiere ERP & CRM Smart Business Solution                                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.adempiere.webui.compat.timeline;

import java.util.Date;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;

/**
 * ADempiere-owned CE replacement for
 * {@code org.adempiere.webui.compat.timeline.BandScrollEvent}.
 *
 * @author ADempiere Phase 5d modernization
 */
public class BandScrollEvent extends Event
{
	private static final long serialVersionUID = 7166080592348064118L;

	/** Event name kept identical to the ZK 3.x add-on. */
	public static final String ON_BAND_SCROLL = "onBandScroll";

	private final Date _min;
	private final Date _max;
	private final Date _center;

	public BandScrollEvent(String name, Component target, Date min, Date max, Date center)
	{
		super(name, target);
		_min = min;
		_max = max;
		_center = center;
	}

	public Date getMin()
	{
		return _min;
	}

	public Date getMax()
	{
		return _max;
	}

	public Date getCenter()
	{
		return _center;
	}
}
