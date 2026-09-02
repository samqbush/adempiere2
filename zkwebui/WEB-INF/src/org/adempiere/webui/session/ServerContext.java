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

package org.adempiere.webui.session;

import java.io.Serializable;
import java.util.Properties;

/**
 *
 * @author  <a href="mailto:agramdass@gmail.com">Ashley G Ramdass</a>
 * @date    Feb 25, 2007
 * @version $Revision: 0.10 $
 */
public final class ServerContext implements Serializable
{
    /**
	 * 
	 */
	private static final long serialVersionUID = -4686544952076576992L;

	private ServerContext()
    {
    }

    private static InheritableThreadLocal<Properties> context = new InheritableThreadLocal<>();

    /**
     * Stands in for an entry that was explicitly set to null.
     *
     * <p>{@code ThreadLocal.get()} cannot tell "this thread has no entry" from
     * "this thread's entry is null", and the two used to mean different things
     * here: an absent entry produced a fresh empty context through the old
     * {@code initialValue()} override, while {@link #setCurrentInstance(Properties)}
     * with a null argument -- which AdempiereWebUI and TimelineEventFeed both
     * reach, because SessionManager.getSessionContext returns null for a removed
     * session -- made {@link #getCurrentInstance()} return null. Recording the
     * explicit null as a marker keeps both behaviours exactly as they were,
     * including for a child thread, which inherits the marker by reference.
     */
    private static final Properties EXPLICIT_NULL = new Properties();

    /**
     * Get server context for current thread
     * @return ServerContext
     */
    public static Properties getCurrentInstance()
    {
        // Behaviour-preserving: this used to be an initialValue() override on
        // the thread local, which meant even a read installed an empty context.
        // Installing it here instead leaves get() available as a genuine peek
        // (see getIfPresent), and every caller of this method still receives an
        // empty Properties on a thread that had none.
        Properties current = context.get();
        if (current == null)
        {
            current = new Properties();
            context.set(current);
            return current;
        }
        return current == EXPLICIT_NULL ? null : current;
    }

    /**
     * The current thread's context if it has one, or null.
     *
     * <p>Unlike {@link #getCurrentInstance()} this never installs a context, so
     * a diagnostic can tell a thread holding no ADempiere identity apart from
     * one holding a genuine system context, and cannot give a thread an
     * inheritable context its children would otherwise not have shared. It does
     * leave the null-valued map entry that {@code ThreadLocal.get()} writes for
     * an absent entry, which is unobservable: every accessor here treats that
     * null exactly as it treats an absent entry, and a child inheriting it
     * still receives a fresh context.
     *
     * @return the context, or null when the thread has none
     */
    public static Properties getIfPresent()
    {
        Properties current = context.get();
        return current == EXPLICIT_NULL ? null : current;
    }
    
    /**
     * dispose server context for current thread
     */
    public static void dispose()
    {
        context.remove();
    }

    
    /**
     * Set server context for current thread
     * @param ctx
     */
    public static void setCurrentInstance(Properties ctx)
    {
        dispose();
        context.set(ctx == null ? EXPLICIT_NULL : ctx);
    }
}
