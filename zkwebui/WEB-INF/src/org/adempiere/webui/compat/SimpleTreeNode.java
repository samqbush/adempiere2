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

import java.io.Serializable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.zkoss.zul.DefaultTreeModel;
import org.zkoss.zul.TreeNode;

/**
 * ADempiere-owned replacement for {@code org.adempiere.webui.compat.SimpleTreeNode}, which
 * ZK removed after 3.6.
 *
 * <p>The ZK CE 10 successor, {@code org.zkoss.zul.DefaultTreeNode}, keeps a
 * different contract in two places ADempiere depends on: a node built with an
 * empty child collection is not a leaf, and {@code insert} does not maintain
 * the parent link that {@code DefaultTreeModel.getPath} walks. This class keeps
 * the ZK 3.6 semantics that the ADempiere tree code was written against: a node
 * is a leaf when it has no children, {@link #getChildren()} is a live list, and
 * mutating that list maintains the parent link.</p>
 *
 * @author ADempiere Phase 5d modernization
 */
public class SimpleTreeNode implements TreeNode<Object>, Serializable
{
	private static final long serialVersionUID = 6134160940932826108L;

	private Object _data;
	private final List<TreeNode<Object>> _children = new ArrayList<TreeNode<Object>>();
	private TreeNode<Object> _parent;
	private DefaultTreeModel<Object> _model;

	public SimpleTreeNode(Object data)
	{
		this(data, null);
	}

	@SuppressWarnings("unchecked")
	public SimpleTreeNode(Object data, Collection<?> children)
	{
		_data = data;
		if (children != null)
		{
			for (Object child : children)
			{
				if (child instanceof TreeNode<?>)
					link((TreeNode<Object>) child);
			}
		}
	}

	public SimpleTreeNode(Object data, Collection<?> children, boolean loaded)
	{
		this(data, children);
	}

	private void link(TreeNode<Object> child)
	{
		_children.add(child);
		adopt(child);
	}

	private void adopt(TreeNode<Object> child)
	{
		if (child instanceof SimpleTreeNode)
			((SimpleTreeNode) child)._parent = this;
	}

	private void orphan(TreeNode<Object> child)
	{
		if (child instanceof SimpleTreeNode && ((SimpleTreeNode) child)._parent == this)
			((SimpleTreeNode) child)._parent = null;
	}

	public DefaultTreeModel<Object> getModel()
	{
		return _model;
	}

	public void setModel(DefaultTreeModel<Object> model)
	{
		_model = model;
	}

	public Object getData()
	{
		return _data;
	}

	public void setData(Object data)
	{
		_data = data;
	}

	/**
	 * @return live child list; adding or removing through this list maintains
	 *         the parent link, exactly as ZK 3.6 did
	 */
	public List<TreeNode<Object>> getChildren()
	{
		return new ChildList();
	}

	public TreeNode<Object> getChildAt(int index)
	{
		return _children.get(index);
	}

	public int getChildCount()
	{
		return _children.size();
	}

	public TreeNode<Object> getParent()
	{
		return _parent;
	}

	public int getIndex(TreeNode<Object> child)
	{
		return _children.indexOf(child);
	}

	public boolean isLeaf()
	{
		return _children.isEmpty();
	}

	public void insert(TreeNode<Object> child, int index)
	{
		_children.add(index, child);
		adopt(child);
	}

	public void add(TreeNode<Object> child)
	{
		link(child);
	}

	public void remove(int index)
	{
		orphan(_children.remove(index));
	}

	public void remove(TreeNode<Object> child)
	{
		if (_children.remove(child))
			orphan(child);
	}

	@Override
	public Object clone()
	{
		SimpleTreeNode clone = new SimpleTreeNode(_data);
		for (TreeNode<Object> child : _children)
			clone.link(child);
		return clone;
	}

	@Override
	public String toString()
	{
		return String.valueOf(_data);
	}

	/**
	 * Live view over the node children that keeps the parent link in sync.
	 */
	private final class ChildList extends AbstractList<TreeNode<Object>> implements Serializable
	{
		private static final long serialVersionUID = -6113470962131570017L;

		@Override
		public TreeNode<Object> get(int index)
		{
			return _children.get(index);
		}

		@Override
		public int size()
		{
			return _children.size();
		}

		@Override
		public void add(int index, TreeNode<Object> child)
		{
			insert(child, index);
		}

		@Override
		public TreeNode<Object> set(int index, TreeNode<Object> child)
		{
			TreeNode<Object> previous = _children.set(index, child);
			orphan(previous);
			adopt(child);
			return previous;
		}

		@Override
		public TreeNode<Object> remove(int index)
		{
			TreeNode<Object> previous = _children.remove(index);
			orphan(previous);
			return previous;
		}
	}
}
