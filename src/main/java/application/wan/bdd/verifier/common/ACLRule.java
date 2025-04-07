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

import java.io.Serializable;
import jdd.bdd.BDD;

public class ACLRule implements Serializable {

	private static final long serialVersionUID = 4261146103254314479L;

	// constants for ACL fields
	static int octetLength = 8;
	static final long octet1Multiplier = 16777216L;
	static final int octet2Multiplier = 65536;
	static final int octet3Multiplier = 256;
	static final int octet4Multiplier = 1;
	final static long minIntegerIP = 0L;
	/* 4294967295 is max value of an IP (255.255.255.255) in decimal value */
	final static long maxIntegerIP = 4294967295L;
	final static long minPort = 0L;
	final static long maxPort = 65535L;
	final static long minProtocol = 0;
	final static long maxProtocol = 255;

	public String accessList;
	public String accessListNumber;
	public String dynamic;
	public String dynamicName;
	public String timeout;
	public String timeoutMinutes;
	public String permitDeny;
	public String protocolLower;
	public String protocolUpper;
	public String source;
	public String sourceWildcard;
	public String sourcePortLower;
	public String sourcePortUpper;
	public String destination;
	public String destinationWildcard;
	public String destinationPortLower;
	public String destinationPortUpper;
	public String precedenceKeyword;
	public String precedence;
	public String tosKeyword;
	public String tos;
	public String logKeyword;
	public String logInput;
	public Boolean remark;

	public int priority;

	int val_bdd;
	int permit_bdd;
	int deny_bdd;
	boolean visible;

	public void set_visible(boolean visible) {
		this.visible = visible;
	}

	public boolean is_visible() {
		return visible;
	}

	public void insert_vals(int val_bdd, int permit_bdd, int deny_bdd) {
		this.val_bdd = val_bdd;
		this.permit_bdd = permit_bdd;
		this.deny_bdd = deny_bdd;
	}

	public int get_val_bdd() {
		return val_bdd;
	}

	/**
	 * deref the old bdd values
	 * 
	 * @param new_permit
	 * @param new_deny
	 * @param bdd
	 */
	public void update_bdds(int new_permit, int new_deny, BDD bdd) {
		bdd.deref(permit_bdd);
		bdd.deref(deny_bdd);
		this.permit_bdd = new_permit;
		this.deny_bdd = new_deny;
	}

	public int get_permit() {
		return permit_bdd;
	}

	public int get_deny() {
		return deny_bdd;
	}

	public ACLRule() {
		accessList = null;
		accessListNumber = null;
		dynamic = null;
		dynamicName = null;
		timeout = null;
		timeoutMinutes = null;
		permitDeny = null;
		protocolLower = null;
		protocolUpper = null;
		source = null;
		sourceWildcard = null;
		sourcePortLower = null;
		sourcePortUpper = null;
		destination = null;
		destinationWildcard = null;
		destinationPortLower = null;
		destinationPortUpper = null;
		precedenceKeyword = null;
		precedence = null;
		tosKeyword = null;
		tos = null;
		logKeyword = null;
		logInput = null;
		remark = false;

		priority = -1;
		visible = true;
		val_bdd = BDDACLWrapper.BDDFalse;
		permit_bdd = BDDACLWrapper.BDDFalse;
		deny_bdd = BDDACLWrapper.BDDFalse;
	}

	public ACLRule(String rulestr) {
		this();
		String[] tokens = rulestr.split(" ");
		accessList = tokens[0];
		accessListNumber = tokens[1];
		permitDeny = tokens[2];
		if (!tokens[3].equals("null")) {
			protocolLower = tokens[3];
		}
		if (!tokens[4].equals("null")) {
			protocolUpper = tokens[4];
		}
		if (!tokens[5].equals("null")) {
			source = tokens[5];
		}
		if (!tokens[6].equals("null")) {
			sourceWildcard = tokens[6];
		}
		if (!tokens[7].equals("null")) {
			sourcePortLower = tokens[7];
		}
		if (!tokens[8].equals("null")) {
			sourcePortUpper = tokens[8];
		}
		if (!tokens[9].equals("null")) {
			destination = tokens[9];
		}
		if (!tokens[10].equals("null")) {
			destinationWildcard = tokens[10];
		}
		if (!tokens[11].equals("null")) {
			destinationPortLower = tokens[11];
		}
		if (!tokens[12].equals("null")) {
			destinationPortUpper = tokens[12];
		}
		priority = Integer.valueOf(tokens[13]);
	}

	public String toString() {
		return accessList
				+ " "
				+ accessListNumber
				+ " "
				// + dynamic + " "
				// + dynamicName + " "
				// + timeout + " "
				// + timeoutMinutes + " "
				+ permitDeny + " " + protocolLower + " " + protocolUpper + " "
				+ source + " " + sourceWildcard + " " + sourcePortLower + " "
				+ sourcePortUpper + " " + destination + " "
				+ destinationWildcard + " " + destinationPortLower + " "
				+ destinationPortUpper + " " + priority
		// + precedenceKeyword + " "
		// + precedence + " "
		// + tosKeyword + " "
		// + tos + " "
		// + logKeyword + " "
		// + logInput + " "
		// + remark
		;
	}

	/**
	 * check whether the acl rule is a permit rule or a deny rule
	 */
	static boolean CheckPermit(ACLRule aclr) {
		if (aclr.permitDeny.equalsIgnoreCase("permit")) {
			return true;
		} else {
			return false;
		}
	}

	public String get_type() {
		return permitDeny;
	}

	public int getPriority() {
		return priority;
	}

	/**
	 * The following are helper functions to process ACL rules.
	 */

	static Range convertPortToRange(String inputLower, String inputUpper) {
		Range outputRange = new Range();
		if (inputLower == null || inputLower.equalsIgnoreCase("any")) {
			outputRange.lower = minPort;
			outputRange.upper = maxPort;
		} else {
			outputRange.lower = Long.parseLong(inputLower);
			if (inputUpper == null)
				outputRange.upper = maxPort;
			else
				outputRange.upper = Long.parseLong(inputUpper);
		}
		return outputRange;
	}

	static Range convertProtocolToRange(String inputLower, String inputUpper) {
		Range outputRange = new Range();
		if (inputLower == null || inputLower.equalsIgnoreCase("any")) {
			outputRange.lower = minProtocol;
			outputRange.upper = maxProtocol;
		} else {
			outputRange.lower = Long.parseLong(inputLower);
			if (inputUpper == null)
				outputRange.upper = maxProtocol;
			else
				outputRange.upper = Long.parseLong(inputUpper);
		}
		return outputRange;
	}

}