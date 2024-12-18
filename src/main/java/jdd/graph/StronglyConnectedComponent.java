
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * Algorithms to compute the Strongly Connected Components in a _directed_ graph.
 *
 *
 */

public class StronglyConnectedComponent {


	private static int num, tos;
	private static Node [] stack = null;
	private static Partition partition = null;

	/** Tarjan's SCC algorithm based on DFS labeling, complexity: O( |E| ) */
	public static Partition tarjan(Graph g, Node root) {
		AttributeExplorer.setAllNodesExtra1(g, -1);
		num = 0;
		tos = 0;
		if(stack == null || stack.length < g.numOfNodes())
			stack = new Node[g.numOfNodes()];

		Partition tmp = partition = new Partition(g);

		do {
			tarjan_visit(root);
			root = AttributeExplorer.findExtra1(g, -1); // this is a stupid O(|V|) call!
		} while(root != null);

		partition = null;
		return tmp;
	}

	private static int tarjan_visit(Node node) {
		stack[tos++] = node;
		int head = node.extra1 = num++;
		Edge succ = node.firstOut;
		while(succ != null) {
			Node n2 = succ.n2;
			succ = succ.next;
			int min = (n2.extra1 == -1) ? tarjan_visit(n2) : n2.extra1;
			if(min < head) head = min;
		}

		if(head == node.extra1) {
			partition.newPartition();
			Node n3 = null;
			do {
				n3 = stack[--tos];
				n3.extra1 = Integer.MAX_VALUE;
				partition.addToPartition(n3);
			} while(n3 != node);
		}
		return head;
	}


	// ---------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("StronglyConnectedComponent");

		Graph g = GraphIO.loadEdgeList("data/tarjan.pcg");
		Partition p = StronglyConnectedComponent.tarjan(g, g.findNode("V1") );

		// the partitions are : (V1) (V2) (V8) (V3..V7)
		Test.checkEquality( p.classes(), 4, "num of partitions");
		Test.check( ! p.inSamePartition(g.findNode("V1"), g.findNode("V2")));
		Test.check( ! p.inSamePartition(g.findNode("V8"), g.findNode("V2")));
		Test.check( p.inSamePartition(g.findNode("V7"), g.findNode("V3")));
		Test.check( p.inSamePartition(g.findNode("V7"), g.findNode("V5")));
		Test.check( p.inSamePartition(g.findNode("V4"), g.findNode("V6")));
		Test.check( p.inSamePartition(g.findNode("V4"), g.findNode("V3")));


		// the partitions are: (1 2 4 5) (3) (6) (7 8 9 10) (11 12 15) (13) (14)
		g = GraphIO.loadEdgeList("data/nuutila.pcg");
		p = StronglyConnectedComponent.tarjan(g, g.findNode("v1") );
		Test.checkEquality( p.classes(), 7, "num of partitions (2)");
		Test.check( p.inSamePartition(g.findNode("v1"), g.findNode("v2")));
		Test.check( p.inSamePartition(g.findNode("v8"), g.findNode("v7")));
		Test.check( !p.inSamePartition(g.findNode("v13"), g.findNode("v14")));

		Test.end();
	}
}
