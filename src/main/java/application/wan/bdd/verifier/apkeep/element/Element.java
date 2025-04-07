package application.wan.bdd.verifier.apkeep.element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import application.wan.bdd.exp.EvalDataplaneVerifier;
import application.wan.bdd.verifier.DPVerifier_Incre;
import application.wan.bdd.verifier.apkeep.core.APKeeper;
import application.wan.bdd.verifier.apkeep.core.ChangeItem;
import application.wan.bdd.verifier.apkeep.core.ChangeTuple;
import application.wan.bdd.verifier.apkeep.utils.PrefixItem;
import application.wan.bdd.verifier.apkeep.utils.UtilityTools;
import application.wan.bdd.verifier.common.BDDACLWrapper;

import application.wan.bdd.verifier.common.PositionTuple;
import application.wan.ndd.exp.EvalDataplaneVerifierNDDAP;
import javafx.util.Pair;
import org.ants.jndd.diagram.NDD;

enum Types {
	Transfer, Copy, Remove;
}

public abstract class Element {

	public String name; // element name

	static BDDACLWrapper bdd;
	APKeeper apc;

	public HashMap<String, HashSet<Integer>> port_aps_raw;

	boolean EnableLeve2 = true;

	Comparator<PrefixItem> byPriority = new Comparator<PrefixItem>() {
		@Override
		public int compare(PrefixItem p1, PrefixItem p2) {
			return p2.priority - p1.priority;
		}
	};

	public Element(String ename) {
		this.name = ename;
		port_aps_raw = new HashMap<String, HashSet<Integer>>();
	}

	public static void setBDDWrapper(BDDACLWrapper baw) {
		Element.bdd = baw;
	}

	public void SetAPC(APKeeper theapc) {
		apc = theapc;
		apc.AddElement(name, this);
	}

	public Set<String> getPorts() {
		return port_aps_raw.keySet();
	}

	public HashSet<Integer> get_port_aps(String portname) {
		if (port_aps_raw.containsKey(portname)) {
			return port_aps_raw.get(portname);
		} else {
			return null;
		}
	}

	public abstract void Initialize();

	public HashSet<Integer> UpdatePortPredicateMap(ArrayList<ChangeItem> changeset, String rule,
			HashMap<String, HashSet<Integer>> moved_batch_aps) {

		HashSet<Integer> moved_aps = new HashSet<Integer>();

		if (changeset.size() == 0) {
			return moved_aps;
		}

		HashSet<Integer> newAP = new HashSet<>();

		// create a new map
		if (moved_batch_aps.containsKey(name) == false) {
			HashSet<Integer> key_moved_aps = new HashSet<Integer>();
			moved_batch_aps.put(name, key_moved_aps);
		}
		HashSet<ChangeItem> tranferSet = new HashSet<ChangeItem>();
		HashSet<ChangeItem> notRemove = new HashSet<ChangeItem>();

		Iterator<ChangeItem> it = changeset.iterator();
		while (it.hasNext()) {
			ChangeItem item = it.next();
			String from_port = item.from_port;
			String to_port = item.to_port;
			int delta = item.delta;

			if (port_aps_raw.get(from_port).contains(delta)) {
				tranferSet.add(new ChangeItem(from_port, to_port, delta));
				if (from_port.equals(to_port)) {
					notRemove.add(new ChangeItem(from_port, to_port, delta));
				}
				moved_aps.add(delta);
				moved_batch_aps.get(name).add(delta);
				bdd.getBDD().deref(delta);
				continue;
			}

			HashSet<Integer> apset = new HashSet<Integer>(port_aps_raw.get(from_port));
			Iterator<Integer> ap_it = apset.iterator();
			while (ap_it.hasNext() && delta != BDDACLWrapper.BDDFalse) {
				int ap = ap_it.next();
				int intersect = bdd.getBDD().ref(bdd.getBDD().and(delta, ap));
				if (intersect != BDDACLWrapper.BDDFalse) {
					if (intersect != ap) {
						int dif = bdd.diff(ap, intersect);
						// split the AP in AP Set
						if (DPVerifier_Incre.getSplitNum) {
							newAP.remove(ap);
							newAP.add(intersect);
							newAP.add(dif);
						}
						apc.UpdateSplitAP(ap, dif, intersect);
						if (moved_aps.contains(ap)) {
							moved_aps.remove(ap);
							moved_aps.add(dif);
							moved_aps.add(intersect);
						}
						// update batch moved aps because of splitting up
						for (String key : moved_batch_aps.keySet()) {
							HashSet<Integer> key_aps = moved_batch_aps.get(key);
							if (key_aps.contains(ap)) {
								key_aps.remove(ap);
								key_aps.add(dif);
								key_aps.add(intersect);
							}
						}
						for (ChangeItem ci : tranferSet) {
							if (ci.delta == ap) {
								ci.delta = dif;
							}
						}
						for (ChangeItem ci : notRemove) {
							if (ci.delta == ap) {
								ci.delta = dif;
								notRemove.add(new ChangeItem(from_port, to_port, intersect));
								break;
							}
						}
						bdd.getBDD().deref(dif);
					}
					// locally transfer the AP from from_port to to_port
					tranferSet.add(new ChangeItem(from_port, to_port, intersect));
					if (from_port.equals(to_port)) {
						notRemove.add(new ChangeItem(from_port, to_port, intersect));
					}
					moved_aps.add(intersect);
					// update batch moved aps because of transfering
					moved_batch_aps.get(name).add(intersect);
					delta = bdd.diffto(delta, intersect);
					bdd.getBDD().deref(intersect);
				}
			}
			if (delta != BDDACLWrapper.BDDFalse) {
				System.out.println(delta);
				System.out.println(changeset);
				System.err.println("Delta not transferred");
			}
			apset = null;
		}

		for (ChangeItem ci : tranferSet) {
			RemoveOneAP(ci.from_port, ci.to_port, ci.delta);
		}

		for (ChangeItem ci : tranferSet) {
			CopyOneAP_noAPK(ci.from_port, ci.to_port, ci.delta);
			apc.UpdateTransferAP(new PositionTuple(name, ci.from_port), new PositionTuple(name, ci.to_port), ci.delta);
		}

		if (DPVerifier_Incre.getSplitNum) {
			DPVerifier_Incre.splitNum = newAP.size();
		}

		return moved_aps;
	}

	public void UpdatePortPredicateMap(ArrayList<ChangeTuple> change_set, ArrayList<ChangeTuple> copyto_set,
			ArrayList<ChangeTuple> remove_set, HashMap<String, HashSet<Integer>> moved_batch_aps,
			HashMap<Integer, HashSet<Integer>> pred_aps,
			HashMap<Integer, HashMap<String, HashSet<String>>> remove_ports,
			HashMap<Integer, HashMap<String, HashSet<String>>> add_ports) {
		// create a new map
		if (moved_batch_aps.containsKey(name) == false) {
			HashSet<Integer> key_moved_aps = new HashSet<Integer>();
			moved_batch_aps.put(name, key_moved_aps);
		}
		long time_start = System.nanoTime();
		HashMap<Pair<HashSet<String>, HashSet<String>>, HashSet<Integer>> simplified = new HashMap<Pair<HashSet<String>, HashSet<String>>, HashSet<Integer>>();
		for (ChangeTuple ct : change_set) {
			Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
					ct.to_ports);
			if (simplified.containsKey(key)) {
				simplified.get(key).addAll(ct.delta_set);
			} else {
				simplified.put(key, ct.delta_set);
			}
		}
		change_set.clear();
		for (Pair<HashSet<String>, HashSet<String>> key : simplified.keySet()) {
			change_set.add(new ChangeTuple(key.getKey(), key.getValue(), simplified.get(key)));
		}
		// if(name.equals("config1646"))
		// {
		// System.out.println(change_set.size());
		// for(ChangeTuple entry : change_set)
		// {
		// System.out.println(entry.from_ports.size()+" "+entry.to_ports.size());
		// for(String from : entry.from_ports)
		// {
		// for(String to : entry.to_ports)
		// {
		// System.out.println(from+" "+to);
		// }
		// }
		// }
		// }
		simplified.clear();
		for (ChangeTuple ct : copyto_set) {
			Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
					ct.to_ports);
			if (simplified.containsKey(key)) {
				simplified.get(key).addAll(ct.delta_set);
			} else {
				simplified.put(key, ct.delta_set);
			}
		}
		copyto_set.clear();
		for (Pair<HashSet<String>, HashSet<String>> key : simplified.keySet()) {
			copyto_set.add(new ChangeTuple(key.getKey(), key.getValue(), simplified.get(key)));
		}
		simplified.clear();
		for (ChangeTuple ct : remove_set) {
			Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
					ct.to_ports);
			if (simplified.containsKey(key)) {
				simplified.get(key).addAll(ct.delta_set);
			} else {
				simplified.put(key, ct.delta_set);
			}
		}
		remove_set.clear();
		for (Pair<HashSet<String>, HashSet<String>> key : simplified.keySet()) {
			remove_set.add(new ChangeTuple(key.getKey(), key.getValue(), simplified.get(key)));
		}
		ArrayList<ChangeTuple> new_change_set = new ArrayList<ChangeTuple>();
		new_change_set.addAll(change_set);
		ArrayList<ChangeTuple> new_copyto_set = new ArrayList<ChangeTuple>();
		new_copyto_set.addAll(copyto_set);
		ArrayList<ChangeTuple> new_remove_set = new ArrayList<ChangeTuple>();
		new_remove_set.addAll(remove_set);
		while (!new_change_set.isEmpty() || !new_copyto_set.isEmpty() || !new_remove_set.isEmpty()) {
			if (!change_set.isEmpty())
				updatePPMChange(Types.Transfer, new_change_set, moved_batch_aps, pred_aps, remove_ports, add_ports);
			if (!copyto_set.isEmpty())
				updatePPMChange(Types.Copy, new_copyto_set, moved_batch_aps, pred_aps, remove_ports, add_ports);
			if (!remove_set.isEmpty())
				updatePPMChange(Types.Remove, new_remove_set, moved_batch_aps, pred_aps, remove_ports, add_ports);
		}
	}

	private void updatePPMChange(Types type, ArrayList<ChangeTuple> changeset,
			HashMap<String, HashSet<Integer>> moved_aps, HashMap<Integer, HashSet<Integer>> pred_aps,
			HashMap<Integer, HashMap<String, HashSet<String>>> remove_ports,
			HashMap<Integer, HashMap<String, HashSet<String>>> add_ports) {
		// create a new map
		if (moved_aps.containsKey(name) == false) {
			HashSet<Integer> key_moved_aps = new HashSet<Integer>();
			moved_aps.put(name, key_moved_aps);
		}
		HashMap<String, HashSet<Integer>> remove = new HashMap<String, HashSet<Integer>>();
		HashMap<String, HashSet<Integer>> add = new HashMap<String, HashSet<Integer>>();
		Iterator<ChangeTuple> it = changeset.iterator();
		ArrayList<ChangeTuple> processed = new ArrayList<ChangeTuple>();
		while (it.hasNext()) {
			ChangeTuple item = it.next();
			HashSet<String> from_ports = item.from_ports;
			HashSet<String> to_ports = item.to_ports;
			HashSet<Integer> retained_set = new HashSet<Integer>(item.delta_set);

			String from_port = from_ports.iterator().next();
			// fast track 1: delta is in the AP set of port from_port
			retained_set.retainAll(port_aps_raw.get(from_port));
			if (!retained_set.isEmpty()) {
				// System.out.println(name + "tag");
				// for(String s1 : from_ports)
				// {
				// // System.out.println(s1);
				// if(! s1.equals("default"))
				// {
				// System.out.println(name + "tag");
				// }
				// }
				// for(String s2 : from_ports)
				// {
				// System.out.println(s2);
				// }
				// for(int abdd : retained_set)
				// {
				// System.out.println(abdd);
				// }
				moved_aps.get(name).addAll(retained_set);
				bdd.DerefInBatch(UtilityTools.HashSetToArray(retained_set));
				if (type.equals(Types.Transfer) || type.equals(Types.Copy)) {
					for (String port : to_ports) {
						if (add.containsKey(port)) {
							add.get(port).addAll(retained_set);
						} else {
							add.put(port, new HashSet<Integer>(retained_set));
						}
					}
				}
				if (type.equals(Types.Transfer) || type.equals(Types.Remove)) {
					for (String port : from_ports) {
						if (remove.containsKey(port)) {
							remove.get(port).addAll(retained_set);
						} else {
							remove.put(port, new HashSet<Integer>(retained_set));
						}
					}
				}
			}
			HashSet<Integer> delta_set = new HashSet<Integer>(item.delta_set);
			delta_set.removeAll(retained_set);
			if (delta_set.isEmpty()) {
				processed.add(item);
				continue;
			}

			int[] delta_array = new int[delta_set.size()];
			int index = 0;
			for (Integer delta : delta_set) {
				delta_array[index] = delta.intValue();
				index++;
			}
			int delta = bdd.OrInBatch(delta_array);
			bdd.DerefInBatch(delta_array);
			// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
			// for(String p3 : item.to_ports)
			// {
			// for(int bdd3 : port_aps_raw.get(p3))
			// {
			// delta = bdd.diffto(delta,bdd3);
			// }
			// }
			HashSet<Integer> transfer_aps = new HashSet<Integer>();
			int pred = delta;
			if (pred_aps.containsKey(delta)) {
				transfer_aps.addAll(pred_aps.get(delta));
				moved_aps.get(name).addAll(pred_aps.get(delta));
				if (type.equals(Types.Transfer) || type.equals(Types.Copy)) {
					for (String port : to_ports) {
						if (add.containsKey(port)) {
							add.get(port).addAll(transfer_aps);
						} else {
							add.put(port, new HashSet<Integer>(transfer_aps));
						}
					}
				}
				if (type.equals(Types.Transfer) || type.equals(Types.Remove)) {
					for (String port : from_ports) {
						if (remove.containsKey(port)) {
							remove.get(port).addAll(transfer_aps);
						} else {
							remove.put(port, new HashSet<Integer>(transfer_aps));
						}
					}
				}
				processed.add(item);
				continue;
			}

			HashSet<Integer> apset = new HashSet<Integer>(port_aps_raw.get(from_port));
			HashMap<Integer, Pair<Integer, Integer>> split_tuple = new HashMap<Integer, Pair<Integer, Integer>>();
			Iterator<Integer> ap_it = apset.iterator();
			while (ap_it.hasNext() && delta != BDDACLWrapper.BDDFalse) {
				int ap = ap_it.next();
				int intersect = bdd.getBDD().ref(bdd.getBDD().and(delta, ap));
				if (intersect != BDDACLWrapper.BDDFalse) {
					if (intersect != ap) {
						int dif = bdd.diff(ap, intersect);
						// split the AP in AP Set
						split_tuple.put(ap, new Pair<Integer, Integer>(dif, bdd.getBDD().ref(intersect)));
					}
					// locally transfer the AP from from_port to to_port
					// update batch moved aps because of transfering
					transfer_aps.add(intersect);
					delta = bdd.diffto(delta, intersect);
					bdd.getBDD().deref(intersect);
				}
			}

			// way 1
			if (delta != BDDACLWrapper.BDDFalse) {
				// for(String p3 : item.to_ports)
				// {
				// for(int bdd3 : port_aps_raw.get(p3))
				// {
				// delta = bdd.diffto(delta,bdd3);
				// }
				// }
				// if(delta != BDDACLWrapper.BDDFalse)
				// {
				// continue;
				// }
				item.delta_set.removeAll(retained_set);
				continue;
			}
			processed.add(item);

			// way 2
			// processed.add(item);
			// if (delta != BDDACLWrapper.BDDFalse) {
			// for(String p3 : item.to_ports)
			// {
			// for(int bdd3 : port_aps_raw.get(p3))
			// {
			// delta = bdd.diffto(delta,bdd3);
			// }
			// }
			// if(delta != BDDACLWrapper.BDDFalse)
			// {
			// // System.out.println("tag");
			// processed.remove(item);
			// // continue;
			// }
			// // item.delta_set.removeAll(retained_set);
			// // continue;
			// }

			// way 3
			// if(delta != BDDACLWrapper.BDDFalse)
			// {
			// continue;
			// }
			// processed.add(item);

			// way 4
			// if (delta != BDDACLWrapper.BDDFalse) {
			// for(String p3 : item.to_ports)
			// {
			// for(int bdd3 : port_aps_raw.get(p3))
			// {
			// delta = bdd.diffto(delta,bdd3);
			// }
			// }
			// if(delta != BDDACLWrapper.BDDFalse)
			// {
			// // System.out.println(port_aps_raw.get(from_port).size());
			// continue;
			// }
			// }
			// processed.add(item);

			for (Integer ap : split_tuple.keySet()) {
				Pair<Integer, Integer> pair = split_tuple.get(ap);
				int dif = pair.getKey();
				int intersect = pair.getValue();
				apc.UpdateSplitAP(ap, dif, intersect);
				// update batch moved aps because of splitting up
				for (String key : moved_aps.keySet()) {
					HashSet<Integer> key_aps = moved_aps.get(key);
					if (key_aps.contains(ap)) {
						key_aps.remove(ap);
						key_aps.add(dif);
						key_aps.add(intersect);
					}
				}
				if (type.equals(Types.Transfer) || type.equals(Types.Copy)) {
					for (String key : add.keySet()) {
						HashSet<Integer> key_aps = add.get(key);
						if (key_aps.contains(ap)) {
							key_aps.remove(ap);
							key_aps.add(dif);
							key_aps.add(intersect);
						}
					}
				}
				if (type.equals(Types.Transfer) || type.equals(Types.Remove)) {
					for (String key : remove.keySet()) {
						HashSet<Integer> key_aps = remove.get(key);
						if (key_aps.contains(ap)) {
							key_aps.remove(ap);
							key_aps.add(dif);
							key_aps.add(intersect);
						}
					}
				}
				for (Integer key : pred_aps.keySet()) {
					HashSet<Integer> key_aps = pred_aps.get(key);
					if (key_aps.contains(ap)) {
						key_aps.remove(ap);
						key_aps.add(dif);
						key_aps.add(intersect);
					}
				}
				if (remove_ports.containsKey(ap)) {
					HashMap<String, HashSet<String>> ports = remove_ports.get(ap);
					remove_ports.put(dif, ports);
					HashMap<String, HashSet<String>> copy_ports = new HashMap<String, HashSet<String>>();
					for (String key : ports.keySet()) {
						copy_ports.put(key, new HashSet<String>(ports.get(key)));
					}
					remove_ports.put(intersect, copy_ports);
					remove_ports.remove(ap);
				}
				if (add_ports.containsKey(ap)) {
					HashMap<String, HashSet<String>> ports = add_ports.get(ap);
					add_ports.put(dif, ports);
					HashMap<String, HashSet<String>> copy_ports = new HashMap<String, HashSet<String>>();
					for (String key : ports.keySet()) {
						copy_ports.put(key, new HashSet<String>(ports.get(key)));
					}
					add_ports.put(intersect, copy_ports);
					add_ports.remove(ap);
				}
			}
			moved_aps.get(name).addAll(transfer_aps);
			if (type.equals(Types.Transfer) || type.equals(Types.Copy)) {
				for (String port : to_ports) {
					if (add.containsKey(port)) {
						add.get(port).addAll(transfer_aps);
					} else {
						add.put(port, new HashSet<Integer>(transfer_aps));
					}
				}
			}
			if (type.equals(Types.Transfer) || type.equals(Types.Remove)) {
				for (String port : from_ports) {
					if (remove.containsKey(port)) {
						remove.get(port).addAll(transfer_aps);
					} else {
						remove.put(port, new HashSet<Integer>(transfer_aps));
					}
				}
			}
			apset = null;
			pred_aps.put(pred, new HashSet<Integer>(transfer_aps));
		}
		changeset.removeAll(processed);
		if (type.equals(Types.Transfer) || type.equals(Types.Remove)) {
			for (String port : remove.keySet()) {
				RemoveAPs(port, remove.get(port));
				for (int ap : remove.get(port)) {
					if (remove_ports.containsKey(ap)) {
						if (remove_ports.get(ap).containsKey(name)) {
							remove_ports.get(ap).get(name).add(port);
						} else {
							HashSet<String> ports = new HashSet<String>();
							ports.add(port);
							remove_ports.get(ap).put(name, ports);
						}
					} else {
						HashMap<String, HashSet<String>> element_ports = new HashMap<String, HashSet<String>>();
						HashSet<String> ports = new HashSet<String>();
						ports.add(port);
						element_ports.put(name, ports);
						remove_ports.put(ap, element_ports);
					}
				}
			}
		}
		if (type.equals(Types.Transfer) || type.equals(Types.Copy)) {
			for (String port : add.keySet()) {
				AddAPs(port, add.get(port));
				for (int ap : add.get(port)) {
					if (add_ports.containsKey(ap)) {
						if (add_ports.get(ap).containsKey(name)) {
							add_ports.get(ap).get(name).add(port);
						} else {
							HashSet<String> ports = new HashSet<String>();
							ports.add(port);
							add_ports.get(ap).put(name, ports);
						}
					} else {
						HashMap<String, HashSet<String>> element_ports = new HashMap<String, HashSet<String>>();
						HashSet<String> ports = new HashSet<String>();
						ports.add(port);
						element_ports.put(name, ports);
						add_ports.put(ap, element_ports);
					}
				}
			}
		}
	}

	public void UpdateAPSetSplit(String portname, int origin, int parta, int partb, boolean isACL) {
		// LOG.INFO("spliting " + name + "-" + portname + ": " + origin + "->" + parta +
		// "," + partb);
		// LOG.log("spliting " + name + "-" + portname + ": " + origin + "->" + parta +
		// "," + partb);
		HashSet<Integer> apset = port_aps_raw.get(portname);
		if (!apset.contains(origin)) {
			System.err.println("spliting " + name + "-" + portname + ": " + origin + "->" + parta + "," + partb);
			System.err.println("Error2: " + apset);
			System.exit(1);
		}
		apset.remove(origin);
		apset.add(parta);
		apset.add(partb);
	}

	public void UpdateAPSetSplit(int origin, int parta, int partb, boolean isACL) {
		for (String port : port_aps_raw.keySet()) {
			if (port_aps_raw.get(port).contains(origin)) {
				UpdateAPSetSplit(port, origin, parta, partb, false);
			}
		}
	}

	void CopyOneAP_noAPK(String from_port, String to_port, int delta) {
		port_aps_raw.get(to_port).add(delta);
	}

	public void CopyOneAP(String from_port, String to_port, int delta) {
		// move the ap from from_port to to_port
		// LOG.INFO("transfering " + name + ": " + from_port + "-->" + to_port + ": " +
		// delta);
		// port_aps_raw.get(from_port).remove(delta);
		port_aps_raw.get(to_port).add(delta);

		// update the AP edge reference
		apc.UpdateCopyAP(new PositionTuple(name, from_port), new PositionTuple(name, to_port), delta);
	}

	public void AddAPs(String to_port, HashSet<Integer> aps) {
		// move the ap from from_port to to_port
		// LOG.INFO("transfering " + name + ": " + from_port + "-->" + to_port + ": " +
		// delta);
		port_aps_raw.get(to_port).addAll(aps);
	}

	public void RemoveOneAP(String from_port, String to_port, int delta) {
		// move the ap from from_port to to_port
		// LOG.INFO("transfering " + name + ": " + from_port + "-->" + to_port + ": " +
		// delta);
		if (port_aps_raw.get(from_port).contains(delta)) {
			port_aps_raw.get(from_port).remove(delta);
		}
	}

	public void RemoveAPs(String from_port, HashSet<Integer> aps) {
		// move the ap from from_port to to_port
		// LOG.INFO("transfering " + name + ": " + from_port + "-->" + to_port + ": " +
		// delta);
		for (int delta : aps) {
			RemoveOneAP(from_port, "default", delta);
		}
	}

	public void UpdateAPSetMergeBatch(String portname, int merged_ap, HashSet<Integer> aps, boolean isACL) {
		// LOG.INFO("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
		HashSet<Integer> apset = port_aps_raw.get(portname);
		if (!apset.containsAll(aps)) {
			System.err.println("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
			System.err.println(apset);
			System.err.println("Error: cannot merge " + aps + " into " + merged_ap);
			System.exit(1);
		}
		apset.removeAll(aps);
		apset.add(merged_ap);
	}

	public void UpdateAPSetMergeBatch(HashSet<String> ports, int merged_ap, HashSet<Integer> aps, boolean isACL) {
		// LOG.INFO("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
		for (String port : ports) {
			UpdateAPSetMergeBatch(port, merged_ap, aps, isACL);
		}
	}

	// new added
	public void UpdateAPSetMerge(String portname, int merged_ap, int ap1, int ap2, boolean isACL) {
		// LOG.INFO("merging " + name + "-" + portname + ": " + ap1 + "," + ap2 + "->" +
		// merged_ap);
		HashSet<Integer> apset = port_aps_raw.get(portname);
		if (!apset.contains(ap1) || !apset.contains(ap2)) {
			System.err.println("Error2: " + ap1 + " or " + ap2);
			System.exit(1);
		}
		apset.remove(ap1);
		apset.remove(ap2);
		apset.add(merged_ap);
	}
}