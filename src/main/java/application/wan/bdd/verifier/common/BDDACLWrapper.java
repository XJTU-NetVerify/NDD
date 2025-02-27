/*
 * Atomic Predicates Verifier
 * 
 * Copyright (c) 2013 UNIVERSITY OF TEXAS AUSTIN. All rights reserved. Developed
 * by: HONGKUN YANG and SIMON S. LAM http://www.cs.utexas.edu/users/lam/NRL/
 * Copyright (c) 2022 ANTS Lab, Xi'an Jiaotong University
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * with the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimers.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimers in the documentation
 * and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the UNIVERSITY OF TEXAS AUSTIN nor the names of the
 * developers may be used to endorse or promote products derived from this
 * Software without specific prior written permission.
 * 
 * 4. Any report or paper describing results derived from using any part of this
 * Software must cite the following publication of the developers: Hongkun Yang
 * and Simon S. Lam, Real-time Verification of Network Properties using Atomic
 * Predicates, IEEE/ACM Transactions on Networking, April 2016, Volume 24, No.
 * 2, pages 887-900 (first published March 2015, Digital Object Identifier:
 * 10.1109/TNET.2015.2398197).
 * 
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH
 * THE SOFTWARE.
 */

package application.wan.bdd.verifier.common;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

import jdd.bdd.*;

/**
 * Computes BDD for ACL rules and Forwardding rules
 * 
 * 
 * Note: The true, false, bdd variables, the negation of bdd variables: their
 * reference count are already set to maximal, so they will never be garbage
 * collected. And no need to worry about the reference count for them.
 *
 */
public class BDDACLWrapper implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7284490986562707221L;

	BDD aclBDD;

	/**
	 * the arrays store BDD variables.
	 */
	final static int protocolBits = 8;
	int[] protocol; // protocol[0] is the lowest bits
	final static int portBits = 16;
	int[] srcPort;
	int[] dstPort;
	final static int ipBits = 32;
	int[] srcIP;
	int[] dstIP;

	int DstIPField;
	int SrcIPField;
	int IPField;

	public int total_bits;

	/**
	 * for readability. In bdd: 0 is the false node 1 is the true node
	 */
	public final static int BDDFalse = 0;
	public final static int BDDTrue = 1;

	public BDDACLWrapper() {
		// aclBDD = new BDD(700000, 700000); //purdue
		// aclBDD = new BDD(400000, 400000); //stanford
		// aclBDD = new BDD(400000, 400000); //internet2
		// aclBDD = new BDD(350000, 350000);
		// aclBDD = new BDD(100000000, 10000000);
		aclBDD = new BDD(10000000, 10000000); // campus
		// aclBDD = new BDD(Parameters.BDD_TABLE_SIZE, Parameters.BDD_TABLE_SIZE);

		protocol = new int[protocolBits];
		srcPort = new int[portBits];
		dstPort = new int[portBits];
		srcIP = new int[ipBits];
		dstIP = new int[ipBits];

		/**
		 * will try more orders of variables
		 */
		DeclareSrcIP();
		DeclareDstIP();
		DeclareSrcPort();
		DeclareDstPort();
		DeclareProtocol();

		DstIPField = AndInBatch(dstIP);
		SrcIPField = AndInBatch(srcIP);
		IPField = aclBDD.ref(aclBDD.and(DstIPField, SrcIPField));

		total_bits = ipBits * 2 + portBits * 2 + protocolBits;
	}

	public BDD getBDD() {
		return aclBDD;
	}

	public HashMap<String, Integer> getfwdbdds(ArrayList<ForwardingRule> fws) {
		int alreadyfwded = BDDFalse;
		HashMap<String, Integer> fwdbdds = new HashMap<String, Integer>();
		int longestmatch = 32;

		// int prefixchk = encodeDstIPPrefix(2148270417L, 32);

		for (int i = longestmatch; i >= 0; i--) {
			for (int j = 0; j < fws.size(); j++) {
				ForwardingRule onefw = fws.get(j);
				if (onefw.getprefixlen() == i) {

					String iname = onefw.getiname();
					// int[] ipbin = Utility.CalBinRep(onefw.getdestip(),
					// ipBits);
					// int[] ipbinprefix = new int[onefw.getprefixlen()];
					// for(int k = 0; k < onefw.getprefixlen(); k ++)
					// {
					// ipbinprefix[k] = ipbin[k + ipBits -
					// onefw.getprefixlen()];
					// }
					// int entrybdd = EncodePrefix(ipbinprefix, dstIP, ipBits);
					int entrybdd = encodeDstIPPrefix(onefw.getdestip(), onefw.getprefixlen());

					int notalreadyfwded = aclBDD.not(alreadyfwded);
					aclBDD.ref(notalreadyfwded);
					int toadd = aclBDD.and(entrybdd, notalreadyfwded);
					aclBDD.ref(toadd);
					aclBDD.deref(notalreadyfwded);
					int altmp = aclBDD.or(alreadyfwded, entrybdd);
					aclBDD.ref(altmp);
					aclBDD.deref(alreadyfwded);
					alreadyfwded = altmp;
					onefw.setBDDRep(entrybdd);
					// aclBDD.deref(entrybdd);

					/*
					 * if(aclBDD.and(prefixchk, toadd) > 0) { System.out.println(onefw); }
					 */

					if (fwdbdds.containsKey(iname)) {
						int oldkey = fwdbdds.get(iname);
						int newkey = aclBDD.or(toadd, oldkey);
						aclBDD.ref(newkey);
						aclBDD.deref(toadd);
						aclBDD.deref(oldkey);
						fwdbdds.put(iname, newkey);
					} else {
						fwdbdds.put(iname, toadd);
					}
				}
			}
		}
		aclBDD.deref(alreadyfwded);
		return fwdbdds;
	}

	public HashMap<String, Integer> getfwdbdds_no_store(ArrayList<ForwardingRule> fws) {
		int alreadyfwded = BDDFalse;
		HashMap<String, Integer> fwdbdds = new HashMap<String, Integer>();
		int longestmatch = 32;

		// int prefixchk = encodeDstIPPrefix(2148270417L, 32);

		for (int i = longestmatch; i >= 0; i--) {
			for (int j = 0; j < fws.size(); j++) {
				ForwardingRule onefw = fws.get(j);
				if (onefw.getprefixlen() == i) {

					String iname = onefw.getiname();
					// int[] ipbin = Utility.CalBinRep(onefw.getdestip(),
					// ipBits);
					// int[] ipbinprefix = new int[onefw.getprefixlen()];
					// for(int k = 0; k < onefw.getprefixlen(); k ++)
					// {
					// ipbinprefix[k] = ipbin[k + ipBits -
					// onefw.getprefixlen()];
					// }
					// int entrybdd = EncodePrefix(ipbinprefix, dstIP, ipBits);
					int entrybdd = encodeDstIPPrefix(onefw.getdestip(), onefw.getprefixlen());

					int notalreadyfwded = aclBDD.not(alreadyfwded);
					aclBDD.ref(notalreadyfwded);
					int toadd = aclBDD.and(entrybdd, notalreadyfwded);
					aclBDD.ref(toadd);
					aclBDD.deref(notalreadyfwded);
					int altmp = aclBDD.or(alreadyfwded, entrybdd);
					aclBDD.ref(altmp);
					aclBDD.deref(alreadyfwded);
					alreadyfwded = altmp;
					// onefw.setBDDRep(entrybdd);
					aclBDD.deref(entrybdd);

					/*
					 * if(aclBDD.and(prefixchk, toadd) > 0) { System.out.println(onefw); }
					 */

					if (fwdbdds.containsKey(iname)) {
						int oldkey = fwdbdds.get(iname);
						int newkey = aclBDD.or(toadd, oldkey);
						aclBDD.ref(newkey);
						aclBDD.deref(toadd);
						aclBDD.deref(oldkey);
						fwdbdds.put(iname, newkey);
					} else {
						fwdbdds.put(iname, toadd);
					}
				}
			}
		}
		aclBDD.deref(alreadyfwded);
		return fwdbdds;
	}

	/**
	 * from shorted to longest
	 * 
	 * @param fws
	 * @return
	 */
	public HashMap<String, Integer> getfwdbdds_sorted_no_store(ArrayList<ForwardingRule> fws) {
		int alreadyfwded = BDDFalse;
		HashMap<String, Integer> fwdbdds = new HashMap<String, Integer>();

		// int prefixchk = encodeDstIPPrefix(2148270417L, 32);

		for (int j = fws.size() - 1; j >= 0; j--) {
			ForwardingRule onefw = fws.get(j);
			// System.out.println(j);

			String iname = onefw.getiname();
			// int[] ipbin = Utility.CalBinRep(onefw.getdestip(), ipBits);
			// int[] ipbinprefix = new int[onefw.getprefixlen()];
			// for(int k = 0; k < onefw.getprefixlen(); k ++)
			// {
			// ipbinprefix[k] = ipbin[k + ipBits - onefw.getprefixlen()];
			// }
			// int entrybdd = EncodePrefix(ipbinprefix, dstIP, ipBits);
			int entrybdd = encodeDstIPPrefix(onefw.getdestip(), onefw.getprefixlen());

			int notalreadyfwded = aclBDD.not(alreadyfwded);
			aclBDD.ref(notalreadyfwded);
			int toadd = aclBDD.and(entrybdd, notalreadyfwded);
			aclBDD.ref(toadd);
			aclBDD.deref(notalreadyfwded);
			int altmp = aclBDD.or(alreadyfwded, entrybdd);
			aclBDD.ref(altmp);
			aclBDD.deref(alreadyfwded);
			alreadyfwded = altmp;
			// onefw.setBDDRep(entrybdd);
			aclBDD.deref(entrybdd);

			/*
			 * if(aclBDD.and(prefixchk, toadd) > 0) { System.out.println(onefw); }
			 */

			if (fwdbdds.containsKey(iname)) {
				int oldkey = fwdbdds.get(iname);
				int newkey = aclBDD.or(toadd, oldkey);
				aclBDD.ref(newkey);
				aclBDD.deref(toadd);
				aclBDD.deref(oldkey);
				fwdbdds.put(iname, newkey);
			} else {
				fwdbdds.put(iname, toadd);
			}

		}

		aclBDD.deref(alreadyfwded);
		return fwdbdds;
	}

	/**
	 * bdd for each rule is computed
	 * 
	 * @param fws
	 * @return
	 */
	public HashMap<String, Integer> getfwdbdds2(ArrayList<ForwardingRule> fws) {
		int alreadyfwded = BDDFalse;

		HashMap<String, Integer> fwdbdds = new HashMap<String, Integer>();
		int longestmatch = 32;
		for (int i = longestmatch; i >= 0; i--) {
			for (int j = 0; j < fws.size(); j++) {
				ForwardingRule onefw = fws.get(j);
				if (onefw.getprefixlen() == i) {
					String iname = onefw.getiname();
					// int[] ipbin = Utility.CalBinRep(onefw.getdestip(),
					// ipBits);
					// int[] ipbinprefix = new int[onefw.getprefixlen()];
					// for(int k = 0; k < onefw.getprefixlen(); k ++)
					// {
					// ipbinprefix[k] = ipbin[k + ipBits -
					// onefw.getprefixlen()];
					// }
					// int entrybdd = EncodePrefix(ipbinprefix, dstIP, ipBits);
					int entrybdd = onefw.getBDDRep();
					int notalreadyfwded = aclBDD.not(alreadyfwded);
					aclBDD.ref(notalreadyfwded);
					int toadd = aclBDD.and(entrybdd, notalreadyfwded);
					aclBDD.ref(toadd);
					aclBDD.deref(notalreadyfwded);
					int altmp = aclBDD.or(alreadyfwded, entrybdd);
					aclBDD.ref(altmp);
					aclBDD.deref(alreadyfwded);
					alreadyfwded = altmp;

					if (fwdbdds.containsKey(iname)) {
						int oldkey = fwdbdds.get(iname);
						int newkey = aclBDD.or(toadd, oldkey);
						aclBDD.ref(newkey);
						aclBDD.deref(toadd);
						aclBDD.deref(oldkey);
						fwdbdds.put(iname, newkey);
					} else {
						fwdbdds.put(iname, toadd);
					}
				}
			}
		}
		aclBDD.deref(alreadyfwded);
		return fwdbdds;
	}

	public int encodeSrcIPPrefix(long ipaddr, int prefixlen) {
		int[] ipbin = Utility.CalBinRep(ipaddr, ipBits);
		int[] ipbinprefix = new int[prefixlen];
		for (int k = 0; k < prefixlen; k++) {
			ipbinprefix[k] = ipbin[k + ipBits - prefixlen];
		}
		int entrybdd = EncodePrefix(ipbinprefix, srcIP, ipBits);
		return entrybdd;
	}

	public int encodeDstIPPrefix(long ipaddr, int prefixlen) {
		int[] ipbin = Utility.CalBinRep(ipaddr, ipBits);
		int[] ipbinprefix = new int[prefixlen];
		for (int k = 0; k < prefixlen; k++) {
			ipbinprefix[k] = ipbin[k + ipBits - prefixlen];
		}
		int entrybdd = EncodePrefix(ipbinprefix, dstIP, ipBits);
		return entrybdd;
	}

	public void multipleref(int bddnode, int reftimes) {
		for (int i = 0; i < reftimes; i++) {
			aclBDD.ref(bddnode);
		}
	}

	/**
	 * 
	 * @param fwdbdds
	 * @return the set of fwdbdds which might be changed
	 */
	public HashSet<String> getDependencySet(ForwardingRule fwdr, HashMap<String, Integer> fwdbdds) {
		HashSet<String> ports = new HashSet<String>();
		int entrybdd = fwdr.getBDDRep();
		if (fwdbdds.keySet().contains(fwdr.getiname())) {
			int onebdd = fwdbdds.get(fwdr.getiname());
			if (entrybdd == aclBDD.and(entrybdd, onebdd)) {
				return ports;
			} else {
				ports.add(fwdr.getiname());
				for (String port : fwdbdds.keySet()) {
					if (!port.equals(fwdr.getiname())) {
						onebdd = fwdbdds.get(port);
						if (BDDFalse != aclBDD.and(entrybdd, onebdd)) {
							ports.add(port);
						}
					}
				}
			}
		} else {
			ports.add(fwdr.getiname());
			for (String port : fwdbdds.keySet()) {
				int onebdd = fwdbdds.get(port);
				if (BDDFalse != aclBDD.and(entrybdd, onebdd)) {
					ports.add(port);
				}
			}
		}

		return ports;
	}

	public int getlongP(ForwardingRule onefw, ArrayList<ForwardingRule> fws) {
		int longP = BDDFalse;

		for (ForwardingRule of : fws) {
			if (onefw.getprefixlen() <= of.getprefixlen()) {
				int tmp = aclBDD.or(longP, of.getBDDRep());
				aclBDD.deref(longP);
				aclBDD.ref(tmp);
				longP = tmp;
			}
		}

		return longP;
	}

	/**
	 * 
	 * @param subs     - has ip information
	 * @param rawBDD
	 * @param reftimes - the res need to be referenced for several times
	 * @return
	 */
	public int encodeACLin(ArrayList<Subnet> subs, int rawBDD, int reftimes) {
		// dest ip
		if (subs == null) {
			multipleref(rawBDD, reftimes);
			return rawBDD;
		}
		int destipbdd = encodeDstIPPrefixs(subs);
		int notdestip = aclBDD.not(destipbdd);
		aclBDD.ref(notdestip);
		int res = aclBDD.or(notdestip, rawBDD);

		multipleref(res, reftimes);
		aclBDD.deref(destipbdd);
		aclBDD.deref(notdestip);
		return res;
	}

	public int encodeACLout(ArrayList<Subnet> subs, int rawBDD, int reftimes) {
		if (subs == null) {
			multipleref(rawBDD, reftimes);
			return rawBDD;
		}
		// src ip
		int srcipbdd = encodeSrcIPPrefixs(subs);
		int notsrctip = aclBDD.not(srcipbdd);
		aclBDD.ref(notsrctip);
		int res = aclBDD.or(notsrctip, rawBDD);

		multipleref(res, reftimes);
		aclBDD.deref(srcipbdd);
		aclBDD.deref(notsrctip);
		return res;
	}

	public int encodeDstIPPrefixs(ArrayList<Subnet> subs) {
		int res = BDDFalse;
		for (int i = 0; i < subs.size(); i++) {
			Subnet onesub = subs.get(i);
			int dstipbdd = encodeDstIPPrefix(onesub.getipaddr(), onesub.getprefixlen());
			int tmp = aclBDD.or(res, dstipbdd);
			aclBDD.ref(tmp);
			aclBDD.deref(res);
			aclBDD.deref(dstipbdd);
			res = tmp;
		}
		return res;
	}

	public int encodeSrcIPPrefixs(ArrayList<Subnet> subs) {
		int res = BDDFalse;
		for (int i = 0; i < subs.size(); i++) {
			Subnet onesub = subs.get(i);
			int srcipbdd = encodeSrcIPPrefix(onesub.getipaddr(), onesub.getprefixlen());
			int tmp = aclBDD.or(res, srcipbdd);
			aclBDD.ref(tmp);
			aclBDD.deref(res);
			aclBDD.deref(srcipbdd);
			res = tmp;
		}
		return res;
	}

	/**
	 * 
	 * @return the size of bdd (in bytes)
	 */
	public long BDDSize() {
		return aclBDD.getMemoryUsage();
	}

	private void DeclareVars(int[] vars, int bits) {
		for (int i = bits - 1; i >= 0; i--) {
			vars[i] = aclBDD.createVar();
		}
	}

	// protocol is 8 bits
	private void DeclareProtocol() {
		DeclareVars(protocol, protocolBits);
	}

	private void DeclareSrcPort() {
		DeclareVars(srcPort, portBits);
	}

	private void DeclareDstPort() {
		DeclareVars(dstPort, portBits);
	}

	private void DeclareSrcIP() {
		DeclareVars(srcIP, ipBits);
	}

	private void DeclareDstIP() {
		DeclareVars(dstIP, ipBits);
	}

	/**
	 * @param vars - a list of bdd nodes that we do not need anymore
	 */
	public void DerefInBatch(int[] vars) {
		for (int i = 0; i < vars.length; i++) {
			aclBDD.deref(vars[i]);
		}
	}

	/**
	 * 
	 * @param acls - the acl that needs to be transformed to bdd
	 * @return a bdd node that represents the acl
	 */
	public int ConvertACLs(LinkedList<ACLRule> acls) {
		if (acls.size() == 0) {
			// no need to ref the false node
			return BDDFalse;
		}
		int res = BDDFalse;
		int denyBuffer = BDDFalse;
		int denyBufferNot = BDDTrue;
		for (int i = 0; i < acls.size(); i++) {
			ACLRule acl = acls.get(i);
			// g has been referenced
			int g = ConvertACLRule(acl);

			if (ACLRule.CheckPermit(acl)) {
				if (res == BDDFalse) {
					if (denyBuffer == BDDFalse) {
						res = g;
					} else {
						int tempnode = aclBDD.and(g, denyBufferNot);
						aclBDD.ref(tempnode);
						res = tempnode;
						aclBDD.deref(g);
					}
				} else {
					if (denyBuffer == BDDFalse) {
						// just combine the current res and g
						int tempnode = aclBDD.or(res, g);
						aclBDD.ref(tempnode);
						DerefInBatch(new int[] { res, g });
						res = tempnode;
					} else {
						// the general case
						int tempnode = aclBDD.and(g, denyBufferNot);
						aclBDD.ref(tempnode);
						aclBDD.deref(g);

						int tempnode2 = aclBDD.or(res, tempnode);
						aclBDD.ref(tempnode2);
						DerefInBatch(new int[] { res, tempnode });
						res = tempnode2;
					}
				}

			} else {
				/**
				 * combine to the denyBuffer
				 */
				if (denyBuffer == BDDFalse) {
					denyBuffer = g;
					denyBufferNot = aclBDD.not(g);
					aclBDD.ref(denyBufferNot);
				} else {
					int tempnode = aclBDD.or(denyBuffer, g);
					aclBDD.ref(tempnode);
					DerefInBatch(new int[] { denyBuffer, g });
					denyBuffer = tempnode;

					aclBDD.deref(denyBufferNot);
					denyBufferNot = aclBDD.not(denyBuffer);
					aclBDD.ref(denyBufferNot);
				}
			}
			// System.out.println(acl);
			// System.out.println(res);
		}
		/**
		 * we need to de-ref denyBuffer, denyBufferNot
		 */
		// DerefInBatch(new int[]{denyBuffer, denyBufferNot});
		aclBDD.deref(denyBufferNot);
		aclBDD.deref(denyBuffer);
		return res;
	}

	/**
	 * 
	 * @param aclr - an acl rule
	 * @return a bdd node representing this rule
	 */
	public int ConvertACLRule(ACLRule aclr) {
		/**
		 * protocol
		 */
		// no need to ref the true node
		int protocolNode = BDDTrue;
		if (aclr.protocolLower == null || aclr.protocolLower.equalsIgnoreCase("any")) {
			// do nothing, just a shortcut
		} else {
			Range r = ACLRule.convertProtocolToRange(aclr.protocolLower, aclr.protocolUpper);
			protocolNode = ConvertProtocol(r);
		}

		/**
		 * src port
		 */
		int srcPortNode = BDDTrue;
		if (aclr.sourcePortLower == null || aclr.sourcePortLower.equalsIgnoreCase("any")) {
			// do nothing, just a shortcut
		} else {
			Range r = ACLRule.convertPortToRange(aclr.sourcePortLower, aclr.sourcePortUpper);
			srcPortNode = ConvertSrcPort(r);
		}

		/**
		 * dst port
		 */
		int dstPortNode = BDDTrue;
		if (aclr.destinationPortLower == null || aclr.destinationPortLower.equalsIgnoreCase("any")) {
			// do nothing, just a shortcut
		} else {
			Range r = ACLRule.convertPortToRange(aclr.destinationPortLower, aclr.destinationPortUpper);
			dstPortNode = ConvertDstPort(r);
		}

		/**
		 * src IP
		 */
		int srcIPNode = ConvertIPAddress(aclr.source, aclr.sourceWildcard, srcIP);

		/**
		 * dst IP
		 */
		int dstIPNode = ConvertIPAddress(aclr.destination, aclr.destinationWildcard, dstIP);

		// put them together
		int[] fiveFields = { protocolNode, srcPortNode, dstPortNode, srcIPNode, dstIPNode };
		int tempnode = AndInBatch(fiveFields);
		// clean up internal nodes
		DerefInBatch(fiveFields);

		return tempnode;
	}

	// bdd1 and (not bdd2)
	public int diff(int bdd1, int bdd2) {
		int not2 = aclBDD.ref(aclBDD.not(bdd2));
		int diff = aclBDD.ref(aclBDD.and(bdd1, not2));
		aclBDD.deref(not2);

		return diff;
	}

	// bdd1 <- bdd1 and (not bdd2)
	public int diffto(int bdd1, int bdd2) {
		int not2 = aclBDD.ref(aclBDD.not(bdd2));
		int diff = aclBDD.ref(aclBDD.and(bdd1, not2));
		aclBDD.deref(not2);
		aclBDD.deref(bdd1);
		// System.out.println(bdd1 + ": " + aclBDD.getRef(bdd1));

		return diff;
	}

	/**
	 * @param bddnodes - an array of bdd nodes
	 * @return - the bdd node which is the AND of all input nodes all temporary
	 *         nodes are de-referenced. the input nodes are not de-referenced.
	 */
	public int AndInBatch(int[] bddnodes) {
		int tempnode = BDDTrue;
		for (int i = 0; i < bddnodes.length; i++) {
			if (i == 0) {
				tempnode = bddnodes[i];
				aclBDD.ref(tempnode);
			} else {
				if (bddnodes[i] == BDDTrue) {
					// short cut, TRUE does not affect anything
					continue;
				}
				if (bddnodes[i] == BDDFalse) {
					// short cut, once FALSE, the result is false
					// the current tempnode is useless now
					aclBDD.deref(tempnode);
					tempnode = BDDFalse;
					break;
				}
				int tempnode2 = aclBDD.and(tempnode, bddnodes[i]);
				aclBDD.ref(tempnode2);
				// do not need current tempnode
				aclBDD.deref(tempnode);
				// refresh
				tempnode = tempnode2;
			}
		}
		return tempnode;
	}

	/**
	 * @param bddnodes - an array of bdd nodes
	 * @return - the bdd node which is the AND of all input nodes all temporary
	 *         nodes are de-referenced. the input nodes are not de-referenced.
	 */
	public int AndInBatch(HashSet<Integer> bddnodes) {
		int count = 0;
		int tempnode = BDDFalse;
		for (int bddnode : bddnodes) {
			if (count == 0) {
				tempnode = bddnode;
				aclBDD.ref(tempnode);
			} else {
				if (bddnode == BDDFalse) {
					// short cut, FALSE does not affect anything
					continue;
				}
				if (bddnode == BDDTrue) {
					// short cut, once TRUE, the result is true
					// the current tempnode is useless now
					aclBDD.deref(tempnode);
					tempnode = BDDTrue;
					break;
				}
				int tempnode2 = aclBDD.and(tempnode, bddnode);
				aclBDD.ref(tempnode2);
				// do not need current tempnode
				aclBDD.deref(tempnode);
				// refresh
				tempnode = tempnode2;
			}
			count++;
		}
		return tempnode;
	}

	/**
	 * @param bddnodes - an array of bdd nodes
	 * @return - the bdd node which is the OR of all input nodes all temporary nodes
	 *         are de-referenced. the input nodes are not de-referenced.
	 */
	public int OrInBatch(int[] bddnodes) {
		int tempnode = BDDFalse;
		for (int i = 0; i < bddnodes.length; i++) {
			if (i == 0) {
				tempnode = bddnodes[i];
				aclBDD.ref(tempnode);
			} else {
				if (bddnodes[i] == BDDFalse) {
					// short cut, FALSE does not affect anything
					continue;
				}
				if (bddnodes[i] == BDDTrue) {
					// short cut, once TRUE, the result is true
					// the current tempnode is useless now
					aclBDD.deref(tempnode);
					tempnode = BDDTrue;
					break;
				}
				int tempnode2 = aclBDD.or(tempnode, bddnodes[i]);
				aclBDD.ref(tempnode2);
				// do not need current tempnode
				aclBDD.deref(tempnode);
				// refresh
				tempnode = tempnode2;
			}
		}
		return tempnode;
	}

	public int OrInBatch(HashSet<Integer> bddnodes) {
		int count = 0;
		int tempnode = BDDFalse;
		for (int bddnode : bddnodes) {
			if (count == 0) {
				tempnode = bddnode;
				aclBDD.ref(tempnode);
			} else {
				if (bddnode == BDDFalse) {
					// short cut, FALSE does not affect anything
					continue;
				}
				if (bddnode == BDDTrue) {
					// short cut, once TRUE, the result is true
					// the current tempnode is useless now
					aclBDD.deref(tempnode);
					tempnode = BDDTrue;
					break;
				}
				int tempnode2 = aclBDD.or(tempnode, bddnode);
				aclBDD.ref(tempnode2);
				// do not need current tempnode
				aclBDD.deref(tempnode);
				// refresh
				tempnode = tempnode2;
			}
			count++;
		}
		return tempnode;
	}

	/**
	 * @param IP address and mask
	 * @return the corresponding bdd node
	 */
	protected int ConvertIPAddress(String IP, String Mask, int[] vars) {
		int tempnode = BDDTrue;
		// case 1 IP = any
		if (IP == null || IP.equalsIgnoreCase("any")) {
			// return TRUE node
			return tempnode;
		}

		// binary representation of IP address
		int[] ipbin = Utility.IPBinRep(IP);
		// case 2 Mask = null
		if (Mask == null) {
			// no mask is working
			return EncodePrefix(ipbin, vars, ipBits);
		} else {
			int[] maskbin = Utility.IPBinRep(Mask);
			int numMasked = Utility.NumofNonZeros(maskbin);

			int[] prefix = new int[maskbin.length - numMasked];
			int[] varsUsed = new int[prefix.length];
			int ind = 0;
			for (int i = 0; i < maskbin.length; i++) {
				if (maskbin[i] == 0) {
					prefix[ind] = ipbin[i];
					varsUsed[ind] = vars[i];
					ind++;
				}
			}

			return EncodePrefix(prefix, varsUsed, prefix.length);
		}

	}

	/***
	 * convert a range of protocol numbers to a bdd representation
	 */
	protected int ConvertProtocol(Range r) {
		return ConvertRange(r, protocol, protocolBits);

	}

	/**
	 * convert a range of source port numbers to a bdd representation
	 */
	protected int ConvertSrcPort(Range r) {
		return ConvertRange(r, srcPort, portBits);
	}

	/**
	 * convert a range of destination port numbers to a bdd representation
	 */
	protected int ConvertDstPort(Range r) {
		return ConvertRange(r, dstPort, portBits);
	}

	/**
	 * 
	 * @param r    - the range
	 * @param vars - bdd variables used
	 * @param bits - number of bits in the representation
	 * @return the corresponding bdd node
	 */
	private int ConvertRange(Range r, int[] vars, int bits) {

		LinkedList<int[]> prefix = Utility.DecomposeInterval(r, bits);
		// System.out.println(vars.length);
		if (prefix.size() == 0) {
			return BDDTrue;
		}

		int tempnode = BDDTrue;
		for (int i = 0; i < prefix.size(); i++) {
			if (i == 0) {
				tempnode = EncodePrefix(prefix.get(i), vars, bits);
			} else {
				int tempnode2 = EncodePrefix(prefix.get(i), vars, bits);
				int tempnode3 = aclBDD.or(tempnode, tempnode2);
				aclBDD.ref(tempnode3);
				DerefInBatch(new int[] { tempnode, tempnode2 });
				tempnode = tempnode3;
			}
		}
		return tempnode;
	}

	/**
	 * 
	 * @param prefix -
	 * @param vars   - bdd variables used
	 * @param bits   - number of bits in the representation
	 * @return a bdd node representing the predicate e.g. for protocl, bits = 8,
	 *         prefix = {1,0,1,0}, so the predicate is protocol[4] and (not
	 *         protocol[5]) and protocol[6] and (not protocol[7])
	 */
	private int EncodePrefix(int[] prefix, int[] vars, int bits) {
		if (prefix.length == 0) {
			return BDDTrue;
		}

		int tempnode = BDDTrue;
		for (int i = 0; i < prefix.length; i++) {
			if (i == 0) {
				tempnode = EncodingVar(vars[bits - prefix.length + i], prefix[i]);
			} else {
				int tempnode2 = EncodingVar(vars[bits - prefix.length + i], prefix[i]);
				int tempnode3 = aclBDD.and(tempnode, tempnode2);
				aclBDD.ref(tempnode3);
				// do not need tempnode2, tempnode now
				// aclBDD.deref(tempnode2);
				// aclBDD.deref(tempnode);
				DerefInBatch(new int[] { tempnode, tempnode2 });
				// refresh tempnode 3
				tempnode = tempnode3;
			}
		}
		return tempnode;
	}

	/***
	 * return a bdd node representing the predicate on the protocol field
	 */
	private int EncodeProtocolPrefix(int[] prefix) {
		return EncodePrefix(prefix, protocol, protocolBits);
	}

	/**
	 * print out a graph for the bdd node var
	 */
	public void PrintVar(int var) {
		if (aclBDD.isValid(var)) {
			aclBDD.printDot(Integer.toString(var), var);
			System.out.println("BDD node " + var + " printed.");
		} else {
			System.err.println(var + " is not a valid BDD node!");
		}
	}

	/**
	 * return the size of the bdd tree
	 */
	public int getNodeSize(int bddnode) {
		int size = aclBDD.nodeCount(bddnode);
		if (size == 0) {// this means that it is only a terminal node
			size++;
		}
		return size;
	}

	/*
	 * cleanup the bdd after usage
	 */
	public void CleanUp() {
		aclBDD.cleanup();
	}

	/***
	 * var is a BDD variable if flag == 1, return var if flag == 0, return not var,
	 * the new bdd node is referenced.
	 */
	private int EncodingVar(int var, int flag) {
		if (flag == 0) {
			int tempnode = aclBDD.not(var);
			// no need to ref the negation of a variable.
			// the ref count is already set to maximal
			// aclBDD.ref(tempnode);
			return tempnode;
		}
		if (flag == 1) {
			return var;
		}

		// should not reach here
		System.err.println("flag can only be 0 or 1!");
		return -1;
	}

	// new added
	public int apply_rewrite(int old_pkt, int exists_quant, int new_val) {
		// System.out.println("new exist");
		int tmp1 = aclBDD.ref(aclBDD.exists(old_pkt, exists_quant));
		int res = aclBDD.ref(aclBDD.and(tmp1, new_val));
		aclBDD.deref(tmp1);
		return res;
	}

	public int orto(int bdd1, int bdd2) {
		int or = aclBDD.ref(aclBDD.or(bdd1, bdd2));
		aclBDD.deref(bdd1);
		return or;
	}

	public static void main(String[] args) throws IOException {

		/****
		 * it is to test the translation from an interval of port numbers to bdd
		 */
		/*
		 * BDDPacketSet bps = new BDDPacketSet(); int var = bps.ConvertProtocol(new
		 * Range(16,31)); bps.PrintVar(var); bps.CleanUp();
		 */

		/**
		 * test whether translate IP address correctly
		 */

		/*
		 * BDDPacketSet bps = new BDDPacketSet(); int var =
		 * bps.ConvertIPAddress("192.192.200.2", "0.0.255.255", bps.srcIP);
		 * bps.PrintVar(var); bps.CleanUp();
		 */

		/**
		 * test conversion of an inverval
		 */
		/*
		 * BDDACLWrapper bps = new BDDACLWrapper(); Range r = new Range(123,123); int
		 * bddnode = bps.ConvertDstPort(r); bps.PrintVar(bddnode);
		 */

		/**
		 * compare packet set conversion and bdd conversion this is the problem.
		 * actually you cannot convert the following things to a single range.
		 */

		/*
		 * Range r1 = PacketSet.convertIPtoIntegerRange("0.0.0.0", "255.255.255.0");
		 * System.out.println(r1);
		 * 
		 * Range r2 = PacketSet.convertIPtoIntegerRange("0.0.0.255", "255.255.255.0");
		 * System.out.println(r2);
		 */

		/******************
		 * test conversion for one rule
		 */

		/*
		 * NetworkConfig net = ParseTools.LoadNetwork("purdue.ser"); Hashtable<String,
		 * RouterConfig> routers = net.tableOfRouters; RouterConfig aRouter =
		 * routers.get("config822"); Hashtable<String, InterfaceConfig> interfaces =
		 * aRouter.tableOfInterfaceByNames; InterfaceConfig aInterface =
		 * interfaces.get("Vlan1000"); String aclName = aInterface.outFilters.get(0);
		 * System.out.println(aclName);
		 * 
		 * Hashtable<String, LinkedList<ACLRule>> tACLs = aRouter.tableOfACLs;
		 * LinkedList<ACLRule> acl = tACLs.get(aclName); acl.get(0).permitDeny = "deny";
		 * acl.get(3).permitDeny = "deny"; acl.get(8).permitDeny = "deny"; for(int i =
		 * 0; i < acl.size(); i ++) { System.out.println(acl.get(i)); }
		 * 
		 * BDDACLWrapper bps = new BDDACLWrapper(); int var = bps.ConvertACLs(acl);
		 * bps.PrintVar(var); bps.CleanUp();
		 */

		/***
		 * check the size of a single acl rule
		 */
		BDDACLWrapper bps = new BDDACLWrapper();
		System.out.println(bps.encodeDstIPPrefix(0, 0));

		/*
		 * StoreACL sa = StoreACL.LoadNetwork("purdue-ACLs.ser");
		 * LinkedList<LinkedList<ACLRule>> acllists = sa.ACLList;
		 * 
		 * 
		 * for(int i = 0; i < acllists.size(); i ++) { LinkedList<ACLRule> acls =
		 * acllists.get(i); for(int j = 0; j < acls.size(); j ++) { ACLRule aclr =
		 * acls.get(j); int aclrnode = bps.ConvertACLRule(aclr);
		 * System.out.println(bps.getNodeSize(aclrnode)); } }
		 */

		/**
		 * test group function
		 */
		/*
		 * ACLRule acl0 = new ACLRule(); acl0.permitDeny = "deny"; ACLRule acl1 = new
		 * ACLRule(); acl1.permitDeny = "deny"; ACLRule acl2 = new ACLRule();
		 * acl2.permitDeny = "deny"; ACLRule acl3 = new ACLRule(); acl3.permitDeny =
		 * "deny"; ACLRule acl4 = new ACLRule(); acl4.permitDeny = "deny";
		 * 
		 * LinkedList<ACLRule> acls = new LinkedList<ACLRule>(); acls.add(acl0);
		 * acls.add(acl1); acls.add(acl2); acls.add(acl3); acls.add(acl4);
		 * 
		 * LinkedList<int[]> grouped = BDDPacketSet.GroupACLRules(acls);
		 * 
		 * for(int i = 0; i < grouped.size(); i ++) {
		 * System.out.println(grouped.get(i)[0] + " " + grouped.get(i)[1]); }
		 */

		/**
		 * integer division...
		 */
		// System.out.println(3/2);

		/**
		 * test andinbatch and orinbatch
		 */
		/*
		 * BDDPacketSet bps = new BDDPacketSet(); int var1 = bps.OrInBatch(new
		 * int[]{0,1,0}); bps.PrintVar(var1); int var2 = bps.AndInBatch(new
		 * int[]{1,1,1}); bps.PrintVar(var2); bps.CleanUp();
		 */
	}
}
