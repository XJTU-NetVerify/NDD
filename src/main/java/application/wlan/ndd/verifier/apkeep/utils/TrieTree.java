package application.wlan.ndd.verifier.apkeep.utils;

import application.wlan.ndd.verifier.common.ForwardingRule;
import org.ants.jndd.diagram.NDD;

import java.util.ArrayList;

public class TrieTree {
	
	TrieTreeNode root;

	public TrieTree()
	{
		root = new TrieTreeNode(0,-1);
		root.children[2] = new TrieTreeNode(1,2);
		root.children[2].parent = root;
		root.children[2].AddPrefixItem(new PrefixItem (-1,"default", NDD.getTrue(),NDD.getTrue()));
	}
	
	public int[] PrefixLongToBin(long prefix, int prefixlen) 
	{
    	int[] bin = new int[32];
    	for (int i=0; i<32; i++) {
    		if (i >= prefixlen) {
    			bin[i] = 2;
    		}
    		else if((prefix & (1 << (32-i-1))) == 0) {
    			bin[i] = 0;
    		}
    		else {
    			bin[i] = 1;
    		}
    	}    
    	return bin;
	}
	
//	public void Init (BDDRuleItem default_rule_item)
//	{
//		//root.children[2].AddRuleItem(default_rule_item);
//	}
	
    public TrieTreeNode Insert (ForwardingRule newrule)
    {    	
    	long prefix = newrule.getdestip();
    	int prefixlen = newrule.getprefixlen();
    	int[] prefixbin = PrefixLongToBin(prefix, prefixlen);
    	return root.Insert(prefixbin);
    }

    public TrieTreeNode Insert (long destip, int prefixlen)
    {    	
    	int[] prefixbin = PrefixLongToBin(destip, prefixlen);
    	return root.Insert(prefixbin);
    }
    
    public TrieTreeNode Search (long destip, int prefixlen)
    {    	
    	int[] prefixbin = PrefixLongToBin(destip, prefixlen);
    	return root.Search(prefixbin);
    }
    
    public TrieTreeNode Search (ForwardingRule newrule)
    {    	
    	long prefix = newrule.getdestip();
    	int prefixlen = newrule.getprefixlen();
    	int[] prefixbin = PrefixLongToBin(prefix, prefixlen);
    	return root.Search(prefixbin);
    }	
	public ArrayList<TrieTreeNode> getAffectedNodes(int[] prefixbin) {
		// TODO Auto-generated method stub
		return root.getAffectedNodes(prefixbin);
	}
    
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		TrieTree tree = new TrieTree();
		
		Long ipprefix = Long.valueOf("737736704");
		int prefixlen = 23;
		String ipbinary = Long.toBinaryString(ipprefix);
    	System.out.println(ipbinary + ", " + ipbinary.length());

		int[] bin = tree.PrefixLongToBin(ipprefix, prefixlen);
    	for (int i=0; i<32; i++) {
    		System.out.print(bin[i]);
    	}
    	System.out.println("");
    	
    	tree.Insert(0,0).AddPrefixItem(new PrefixItem(0, "default"));
    	tree.Insert(0,0).AddPrefixItem(new PrefixItem(1, "drop"));
    	tree.Insert(0,1).AddPrefixItem(new PrefixItem(2, "0"));
    	tree.Insert(0,32).AddPrefixItem(new PrefixItem(3, "1"));
    	tree.Insert(1,32).AddPrefixItem(new PrefixItem(4, "2"));
    	tree.Insert(737736704, 23).AddPrefixItem(new PrefixItem(5, "3"));

    	System.out.println("after inserting");
    	tree.root.PrintTrie("");
    	
    	TrieTreeNode node = tree.Search(0,0);
    	System.out.println("seaching node " + node);
    	System.out.println("has ancestor " + node.GetAncestor());
    	System.out.println("has descendent " + node.GetDescendant());
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(0,"default")));
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(1,"drop")));
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(0,"drop")));
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(1,"default")));
    	
    	node = tree.Search(0,1);
    	System.out.println("seaching node " + node);
    	System.out.println("has ancestor " + node.GetAncestor());
    	System.out.println("has descendent " + node.GetDescendant());
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(2,"0")));
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(2,"default")));
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(3,"0")));
    	System.out.println("seaching rule " + node.HasPrefixItem(new PrefixItem(3,"default")));
    	
    	node = tree.Search(0,32);
    	System.out.println("seaching node " + node);
    	System.out.println("has ancestor " + node.GetAncestor());
    	System.out.println("has descendent " + node.GetDescendant());
    	
    	
    	node.Delete();
    	System.out.println("after deleting 1/32");
    	tree.root.PrintTrie("");
    	node = tree.Search(1,32);
    	node.Delete();
    	System.out.println("after deleting 0/32");
    	tree.root.PrintTrie("");
	}


}
