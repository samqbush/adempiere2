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
package org.adempiere.webui.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.zkoss.zk.au.AuRequest;

/**
 * Reads the ZK 3.6 positional AU payload out of a ZK CE 10 {@link AuRequest}.
 *
 * <p>ZK 3.6 delivered custom AU command arguments as a {@code String[]}. ZK CE
 * 10 delivers a {@code Map<String, Object>} whose shape depends on how the
 * client widget posted the event, so this helper normalizes the shapes ADempiere
 * can produce back into the positional array the command handlers expect. The
 * matching client side migration is Phase 5g work.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public final class AuRequests
{
	private AuRequests()
	{
	}

	/**
	 * @param request AU request, may be null
	 * @return positional data, never null
	 */
	public static String[] positionalData(AuRequest request)
	{
		if (request == null)
			return new String[0];
		Map<String, Object> data = request.getData();
		if (data == null || data.isEmpty())
			return new String[0];
		List<String> values = new ArrayList<String>();
		for (int index = 0; data.containsKey(String.valueOf(index)); index++)
			values.add(asString(data.get(String.valueOf(index))));
		if (!values.isEmpty())
			return values.toArray(new String[values.size()]);
		Object payload = data.containsKey("data") ? data.get("data") : null;
		if (payload instanceof Object[])
		{
			for (Object value : (Object[]) payload)
				values.add(asString(value));
			return values.toArray(new String[values.size()]);
		}
		if (payload instanceof Collection<?>)
		{
			for (Object value : (Collection<?>) payload)
				values.add(asString(value));
			return values.toArray(new String[values.size()]);
		}
		for (Object value : data.values())
			values.add(asString(value));
		return values.toArray(new String[values.size()]);
	}

	private static String asString(Object value)
	{
		return value == null ? null : value.toString();
	}
}
