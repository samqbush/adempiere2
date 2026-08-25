/******************************************************************************
 * Copyright (C) 2009 Low Heng Sin                                            *
 * Copyright (C) 2009 Idalica Corporation                                     *
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

import org.adempiere.webui.event.TokenEvent;
import org.zkoss.lang.Objects;
import org.zkoss.zk.au.AuRequest;
import org.adempiere.webui.compat.AuRequests;
import org.zkoss.zk.au.AuService;
import org.zkoss.zk.mesg.MZk;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.event.Events;

/**
 * Command class to handle user authentication token event
 * @author hengsin
 *
 */
public class TokenCommand implements AuService {

	private final String command;

	public TokenCommand(String command) {
		this.command = command;
	}

	/**
	 * @return the AU command this service answers
	 */
	public String getId() {
		return command;
	}

	/**
	 * ZK CE 10 dispatches custom AU commands through {@link AuService}
	 * instead of the removed {@code org.zkoss.zk.au.Command} registry.
	 *
	 * @param request AU request
	 * @param everError true when an error was already reported
	 * @return true when this service consumed the request
	 */
	public boolean service(AuRequest request, boolean everError) {
		if (!command.equals(request.getCommand()))
			return false;
		process(request);
		return true;
	}

	private void process(AuRequest request) {
		final String[] data = AuRequests.positionalData(request);

		final Component comp = request.getComponent();
		if (comp == null)
			throw new UiException(MZk.ILLEGAL_REQUEST_COMPONENT_REQUIRED, this);
		
		if (data == null || data.length < 2)
			throw new UiException(MZk.ILLEGAL_REQUEST_WRONG_DATA, new Object[] {
					Objects.toString(data), this });
		
		Events.postEvent(new TokenEvent(getId(), comp, data));
	}
}
