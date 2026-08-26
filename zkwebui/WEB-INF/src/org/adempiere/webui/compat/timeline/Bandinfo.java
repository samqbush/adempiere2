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
import java.util.TimeZone;

import org.zkoss.zul.Div;

/**
 * ADempiere-owned CE replacement for the {@code org.adempiere.webui.compat.timeline.Bandinfo}
 * ZK 3.x add-on component.
 *
 * <p>It holds the band configuration the resource schedule sets so that the
 * server side state, and the event source URL used by the timeline feed
 * servlet, are preserved without the ZK 3.x add-on. Client rendering is Phase
 * 5g work.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class Bandinfo extends Div
{
	private static final long serialVersionUID = -4224259069395100304L;

	/** Style class the Phase 5g theme work can target. */
	public static final String SCLASS = "adempiere-timeline-band";

	private String _intervalUnit;
	private int _intervalPixels;
	private TimeZone _timeZone;
	private String _syncWith;
	private boolean _showEventText = true;
	private Date _date;
	private String _eventSourceUrl;

	public Bandinfo()
	{
		setSclass(SCLASS);
	}

	public String getIntervalUnit()
	{
		return _intervalUnit;
	}

	public void setIntervalUnit(String intervalUnit)
	{
		_intervalUnit = intervalUnit;
	}

	public int getIntervalPixels()
	{
		return _intervalPixels;
	}

	public void setIntervalPixels(int intervalPixels)
	{
		_intervalPixels = intervalPixels;
	}

	public TimeZone getTimeZone()
	{
		return _timeZone;
	}

	public void setTimeZone(TimeZone timeZone)
	{
		_timeZone = timeZone;
	}

	public String getSyncWith()
	{
		return _syncWith;
	}

	public void setSyncWith(String syncWith)
	{
		_syncWith = syncWith;
	}

	public boolean isShowEventText()
	{
		return _showEventText;
	}

	public void setShowEventText(boolean showEventText)
	{
		_showEventText = showEventText;
	}

	public Date getDate()
	{
		return _date;
	}

	public void setDate(Date date)
	{
		_date = date;
	}

	public String getEventSourceUrl()
	{
		return _eventSourceUrl;
	}

	public void setEventSourceUrl(String eventSourceUrl)
	{
		_eventSourceUrl = eventSourceUrl;
	}
}
