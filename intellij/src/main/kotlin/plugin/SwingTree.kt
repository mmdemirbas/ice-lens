package plugin

import javax.swing.tree.DefaultMutableTreeNode
import model.GraphTree

/**
 * A [GraphTree.Item] as the Swing tree node the IDE's `Tree` draws, its children under it. The
 * item stays the node's `userObject`, so a selection hands the panel the node and the metadata
 * its rows are planned against; the tree itself is core's, shared with the command line.
 */
fun GraphTree.Item.toSwingNode(): DefaultMutableTreeNode =
    DefaultMutableTreeNode(this).also { node -> children.forEach { node.add(it.toSwingNode()) } }
