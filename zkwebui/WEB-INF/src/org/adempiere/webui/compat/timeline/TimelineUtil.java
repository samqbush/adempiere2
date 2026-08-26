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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ADempiere-owned CE replacement for
 * {@code org.adempiere.webui.compat.timeline.TimelineUtil}.
 *
 * <p>The only method ADempiere used is the SIMILE timeline date format that the
 * resource schedule feed servlet writes into its XML payload, so the exact wire
 * format of the ZK 3.x add-on is reproduced here.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public final class TimelineUtil
{
	private static final String DATE_TIME_PATTERN = "MMM dd yyyy HH:mm:ss 'GMT'Z";

	private TimelineUtil()
	{
	}

	/**
	 * @param date date to format, may be null
	 * @return SIMILE timeline representation of {@code date}, or an empty
	 *         string when {@code date} is null
	 */
	public static String formatDateTime(Date date)
	{
		if (date == null)
			return "";
		return new SimpleDateFormat(DATE_TIME_PATTERN, Locale.US).format(date);
	}
}
