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

import org.zkoss.zul.DefaultTreeModel;
import org.zkoss.zul.TreeNode;

/**
 * ADempiere-owned replacement for {@code org.zkoss.zul.SimpleTreeModel}, which
 * ZK removed after 3.6.
 *
 * <p>It is a thin adapter over the ZK CE 10 {@link DefaultTreeModel} that keeps
 * the two ZK 3.6 call shapes the ADempiere tree code uses: the two argument
 * {@code getPath(parent, child)} and the node oriented
 * {@code fireEvent(node, from, to, type)}.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class SimpleTreeModel extends DefaultTreeModel<Object>
{
	private static final long serialVersionUID = -5216338269816201446L;

	public SimpleTreeModel(SimpleTreeNode root)
	{
		super(root);
	}

	@Override
	public SimpleTreeNode getRoot()
	{
		return (SimpleTreeNode) super.getRoot();
	}

	@Override
	public SimpleTreeNode getChild(TreeNode<Object> parent, int index)
	{
		return (SimpleTreeNode) super.getChild(parent, index);
	}

	/**
	 * ZK 3.6 resolved a tree path relative to an explicit parent. ZK CE 10
	 * always resolves from the model root, which is what every ADempiere caller
	 * passes as the parent.
	 *
	 * @param parent parent node, kept for the legacy signature
	 * @param child node to locate
	 * @return path from the root to {@code child}
	 */
	public int[] getPath(Object parent, Object child)
	{
		return getPath((TreeNode<Object>) child);
	}

	/**
	 * ZK 3.6 fired a tree data event for a node. ZK CE 10 fires it for the path
	 * of that node.
	 *
	 * @param node node whose children changed
	 * @param indexFrom first affected child index
	 * @param indexTo last affected child index
	 * @param evtType {@code TreeDataEvent} type
	 */
	public void fireEvent(Object node, int indexFrom, int indexTo, int evtType)
	{
		fireEvent(evtType, getPath((TreeNode<Object>) node), indexFrom, indexTo);
	}
}
