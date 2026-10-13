package bdd;

import sre.ndd.NDD;
import jdd.zdd.ZDD;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.Graph;

import controlplane.network.NDDCPExecutor;
import controlplane.network.NDDL3Edge;
import controlplane.network.NDDPort;
import controlplane.network.NDDRouter;
import controlplane.network.State;
import datamodel.list.NDDSortedMultiList;
import datamodel.list.NDDSortedMultiListItem;
import main.Configuration;
import main.NDDController;
import main.SRELogger;
import multimap.HashMultimap;
import multimap.Multimap;
import util.TimeUtil;

public class NDDTCWrapper {
    // BDD bdd;
    NDDManager manager;

    NDDL3Edge[] links;
    public int[] linkVars;
    int[] linkFailureVars;
    int[] linkFields;
    int[] linkFieldWidths;
    int totalLinkBit; // 32 256
    int linksOfGroup = 32; // 16 32 108
    String linkOrderFingerprint;

    public NDDTCWrapper(NDDManager manager) {
        this.manager = manager;
        // this.bdd = manager.bdd;
        setLinkVars(NDDL3Edge.getAllL3Edge());
    }

    /** Projects the failure dimensions and returns an owned header-only region. */
    public int projectLinkFields(int borrowedRoot) {
        return NDD.ref(NDD.exist(borrowedRoot, linkFields));
    }

    public List<NDDL3Edge> sort(Set<NDDL3Edge> edges) {
        if (Configuration.SORT) {
            switch (Configuration.SORT_METHOD) {
                case RANDOM:
                    SRELogger.log(SRELogger.LEVEL.INFO, "random order");
                    return randomOrder(edges);
                case CONNECTIVITY:
                    SRELogger.log(SRELogger.LEVEL.INFO, "connectivity order");
                    return connectivityOrder(edges);
                case CONNECTIVITY_REVERSE:
                    SRELogger.log(SRELogger.LEVEL.INFO, "connectivity order reversed");
                    return connectivityOrderReverse(edges);
                case PROPAGATION:
                    SRELogger.log(SRELogger.LEVEL.INFO, "propagation order");
                    return propagationOrder(edges);
                case PROPAGATION_REVERSE:
                    SRELogger.log(SRELogger.LEVEL.INFO, "propagation order reversed");
                    return propagationOrderReverse(edges);
            }
            return propagationOrderReverse(edges);
        } else {
            return new ArrayList<>(edges);
        }
    }

    public List<NDDL3Edge> randomOrder(Set<NDDL3Edge> edges) {
        List<NDDL3Edge> list = new ArrayList<>(edges);
        Collections.shuffle(list);
        return list;
    }

    public List<NDDL3Edge> connectivityOrder(Set<NDDL3Edge> edges) {
        List<NDDL3Edge> list = new ArrayList<>(edges);
        list.sort(Comparator.comparing(NDDL3Edge::toString));
        for (NDDCPExecutor cpe : NDDController.cpExecutorStack) {
            HashMap<NDDL3Edge, Integer> map = new HashMap<>();
            boolean stop = false;
            int mf = 1;
            while (!stop) {
                Collection<Graph<NDDRouter, NDDL3Edge>> components = cpe.getL3Topology()
                        .getKEdgeComponent(mf + 1)
                        .stream()
                        .filter(c -> c.vertexSet().size() > 1)
                        .collect(Collectors.toSet());
                stop = components.isEmpty();
                if (!stop) {
                    for (Graph<NDDRouter, NDDL3Edge> c : components) {
                        for (NDDL3Edge e : c.edgeSet()) {
                            map.put(e, mf);
                        }
                    }
                }
                mf++;
            }
            map.forEach((l3Edge, cnt) -> l3Edge.setCnt(l3Edge.getCnt() + cnt));
        }
        return list
                .stream()
                .sorted(Comparator.comparing(NDDL3Edge::getCnt))
                .collect(Collectors.toList());
    }

    public List<NDDL3Edge> connectivityOrderReverse(Set<NDDL3Edge> edges) {
        List<NDDL3Edge> list = connectivityOrder(edges);
        Collections.reverse(list);
        return list;
    }

    public List<NDDL3Edge> propagationOrder(Set<NDDL3Edge> edges) {
        ArrayList<NDDL3Edge> list = new ArrayList<>();

        HashSet<NDDRouter> vi = new HashSet<>();
        HashSet<NDDRouter> cur = new HashSet<>();
        cur.addAll(NDDController.ospfValidRouters);
        cur.addAll(NDDController.bgpValidRouters);
        while (!cur.isEmpty()) {
            HashMap<NDDL3Edge, Integer> votes = new HashMap<>();
            HashSet<NDDRouter> nxt = new HashSet<>();
            for (NDDRouter router : cur) {
                for (NDDPort port : router.getPorts().values()) {
                    NDDPort linked = port.getLinkedPort();
                    if (linked != null && !vi.contains(linked.getRouter())) {
                        int v = votes.getOrDefault(linked.getLink(), 0) + 1;
                        votes.put(linked.getLink(), v);
                        if (!cur.contains(linked.getRouter())) {
                            nxt.add(linked.getRouter());
                        }
                    }
                }
            }
            list.addAll(votes.keySet().stream().sorted(Comparator.comparing(votes::get, Comparator.reverseOrder()))
                    .collect(Collectors.toList()));
            vi.addAll(cur);
            cur = nxt;
        }
        HashSet<NDDL3Edge> remain = new HashSet<>(edges);
        remain.removeAll(list);
        list.addAll(remain);

        return list;
    }

    public List<NDDL3Edge> propagationOrderReverse(Set<NDDL3Edge> edges) {
        List<NDDL3Edge> list = propagationOrder(edges);
        Collections.reverse(list);
        return list;
    }

    public void setLinkVars(Set<NDDL3Edge> edges) {
        totalLinkBit = (int) edges.stream().filter(e -> e.getState() == State.SYMBOLIC).count();
        int symbolicVarNum = totalLinkBit;
        if (symbolicVarNum <= 0) {
            throw new IllegalStateException("SRE requires at least one symbolic link");
        }

        switch (symbolicVarNum) {
            case 32:
                linksOfGroup = 16;
                break;
            case 256:
                linksOfGroup = 32;
                break;
            case 864:
                linksOfGroup = 108;
                break;
            case 4000:
                linksOfGroup = 250;
                break;
            default:
                linksOfGroup = Math.max(1, symbolicVarNum / 8);
                break;
        }

        int groupsOfLink = (symbolicVarNum + linksOfGroup - 1) / linksOfGroup;
        linkFields = new int[groupsOfLink];
        linkFieldWidths = new int[groupsOfLink];
        for (int group = 0; group < groupsOfLink; group++) {
            linkFieldWidths[group] = Math.min(linksOfGroup, symbolicVarNum - group * linksOfGroup);
        }

        NDD.LabelMode headerMode = Configuration.NDD_BACKEND_PROFILE.headerMode();
        NDD.LabelMode linkMode = Configuration.NDD_BACKEND_PROFILE.linkMode();

        if (Configuration.NDD_DOMAIN_ORDER == Configuration.NDDDomainOrder.HEADER_LINK) {
            NDDACLWrapper.Field.setPhysicalStart(0);
            declareHeaderFields(headerMode);
            declareLinkFields(linkMode);
        } else {
            declareLinkFields(linkMode);
            NDDACLWrapper.Field.setPhysicalStart(groupsOfLink);
            declareHeaderFields(headerMode);
        }
        NDD.generateFields();

        // Raw BDD helpers in the ACL encoder need one right-aligned 32-bit header variable pool,
        // not a flattening of every physical field.
        if (headerMode == NDD.LabelMode.BDD) {
            manager.bddVars = NDD.getBDDVars(NDDACLWrapper.Field.dstIP.fieldNum);
            manager.bddNotVars = NDD.getNotBDDVars(NDDACLWrapper.Field.dstIP.fieldNum);
        }

        List<NDDL3Edge> orderedLinks = sort(edges);
        linkOrderFingerprint = fingerprint(orderedLinks);
        links = new NDDL3Edge[symbolicVarNum];
        linkVars = new int[symbolicVarNum];
        linkFailureVars = new int[symbolicVarNum];

        int count = 0;
        boolean failureIsOne = linkMode == NDD.LabelMode.ZDD;
        for (NDDL3Edge link : orderedLinks) {
            if (link.getState() == State.SYMBOLIC) {
                int group = count / linksOfGroup;
                int bit = count % linksOfGroup;
                int positive = NDD.getVar(linkFields[group], bit);
                int negative = NDD.getNotVar(linkFields[group], bit);
                int up = failureIsOne ? negative : positive;
                int failure = failureIsOne ? positive : negative;
                link.setTC(NDD.ref(up));
                links[count] = link;
                linkVars[count] = up;
                linkFailureVars[count] = failure;
                count++;
            } else if (link.getState() == State.UP) {
                link.setTC(NDD.getTrue());
            } else {
                link.setTC(NDD.getFalse());
            }
        }

        System.out.println("ndd_field_layout:" + describeLayout(linkMode));
        System.out.println("link_variable_order_hash:" + linkOrderFingerprint);
        System.out.println("link_polarity:" + (failureIsOne ? "1=failure" : "1=up"));
        initMemos();
    }

    private void declareHeaderFields(NDD.LabelMode headerMode) {
        for (int logical = 0; logical < NDDACLWrapper.Field.num; logical++) {
            NDDACLWrapper.Field field = NDDACLWrapper.Field.byLogicalIndex(logical);
            int physical = NDD.declareField(field.bitWidth, headerMode);
            if (physical != field.fieldNum) {
                throw new IllegalStateException("header field layout mismatch");
            }
        }
    }

    private void declareLinkFields(NDD.LabelMode linkMode) {
        for (int group = 0; group < linkFields.length; group++) {
            linkFields[group] = NDD.declareField(linkFieldWidths[group], linkMode);
        }
    }

    private String describeLayout(NDD.LabelMode linkMode) {
        StringBuilder result = new StringBuilder();
        for (int field = 0; field <= NDD.getFieldNum(); field++) {
            if (field > 0) result.append(',');
            NDDACLWrapper.Field header = NDDACLWrapper.Field.fromPhysicalField(field);
            if (header != null) {
                result.append(field).append(':').append(header.name()).append(':')
                        .append(header.bitWidth).append(':')
                        .append(NDD.getFieldLabelMode(field));
            } else {
                int group = indexOf(linkFields, field);
                result.append(field).append(":link").append(group).append(':')
                        .append(linkFieldWidths[group]).append(':').append(linkMode);
            }
        }
        return result.toString();
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        throw new IllegalArgumentException("field is neither a header nor link field: " + value);
    }

    private static String fingerprint(List<NDDL3Edge> orderedLinks) {
        long hash = 0xcbf29ce484222325L;
        for (NDDL3Edge link : orderedLinks) {
            if (link.getState() != State.SYMBOLIC) continue;
            String value = link.toString();
            for (int i = 0; i < value.length(); i++) {
                hash ^= value.charAt(i);
                hash *= 0x100000001b3L;
            }
        }
        return Long.toUnsignedString(hash, 16);
    }

    public NDDL3Edge getLink(int idx) {
        return links[idx];
    }

    /** Build the exact, backend-neutral constraint "number of failed links <= maxFailures". */
    public int encodeAtMostFailures(int maxFailures) {
        if (maxFailures < 0) return NDD.ref(NDD.getFalse());
        if (usesZddLinkFields()) return encodeZddAtMostFailures(maxFailures);

        int[] exact = new int[maxFailures + 1];
        exact[0] = NDD.ref(NDD.getTrue());

        for (int bit = 0; bit < linkVars.length; bit++) {
            int[] next = new int[maxFailures + 1];
            for (int failures = 0; failures <= maxFailures; failures++) {
                int remainsHealthy = exact[failures] == NDD.getFalse()
                        ? NDD.getFalse()
                        : NDD.ref(NDD.and(exact[failures], linkVars[bit]));
                int becomesFailed = failures == 0 || exact[failures - 1] == NDD.getFalse()
                        ? NDD.getFalse()
                        : NDD.ref(NDD.and(exact[failures - 1], linkFailureVars[bit]));
                next[failures] = orOwned(remainsHealthy, becomesFailed);
            }
            for (int value : exact) NDD.deref(value);
            exact = next;
        }

        int result = NDD.getFalse();
        for (int value : exact) {
            if (NDD.isFalse(value)) continue;
            if (NDD.isFalse(result)) {
                result = NDD.ref(value);
            } else {
                int merged = NDD.ref(NDD.or(result, value));
                NDD.deref(result);
                result = merged;
            }
        }
        for (int value : exact) NDD.deref(value);
        return result;
    }

    private boolean usesZddLinkFields() {
        if (linkFields.length == 0) return false;
        for (int field : linkFields) {
            if (NDD.getFieldLabelMode(field) != NDD.LabelMode.ZDD) return false;
        }
        return true;
    }

    /**
     * Build the Link failure budget directly as ZDD set families.  A selected ZDD
     * element means that the corresponding Link has failed, so an exact-cardinality
     * family is a natural edge label and needs no per-Link NDD operations.
     */
    private int encodeZddAtMostFailures(int maxFailures) {
        if (maxFailures >= linkVars.length) return NDD.ref(NDD.getTrue());

        ZDD zdd = NDD.getZDDEngine();
        int[][] exactByField = new int[linkFields.length][];
        for (int group = 0; group < linkFields.length; group++) {
            int limit = Math.min(maxFailures, linkFieldWidths[group]);
            int[] exact = new int[limit + 1];
            exact[0] = zdd.ref(1); // the family containing only the empty set
            int processed = 0;
            for (int bit = 0; bit < linkFieldWidths[group]; bit++) {
                int variable = NDD.getBackendVariableId(linkFields[group], bit);
                int upper = Math.min(limit, processed + 1);
                for (int failures = upper; failures >= 1; failures--) {
                    int withFailure = zdd.ref(zdd.change(exact[failures - 1], variable));
                    if (exact[failures] == 0) {
                        exact[failures] = withFailure;
                    } else {
                        int merged = zdd.ref(zdd.union(exact[failures], withFailure));
                        zdd.deref(exact[failures]);
                        zdd.deref(withFailure);
                        exact[failures] = merged;
                    }
                }
                processed++;
            }
            exactByField[group] = exact;
        }

        int[][] memo = new int[linkFields.length][maxFailures + 1];
        for (int[] row : memo) Arrays.fill(row, -1);
        int root = buildZddFailureBudget(0, maxFailures, exactByField, memo, zdd);
        int result = NDD.ref(root);

        for (int[] row : memo) {
            for (int node : row) if (node >= 0) NDD.deref(node);
        }
        for (int[] exact : exactByField) {
            for (int label : exact) zdd.deref(label);
        }
        return result;
    }

    /** Memo entries are owned NDD references; edge-map labels are owned ZDD references. */
    private int buildZddFailureBudget(int group, int remaining, int[][] exactByField,
            int[][] memo, ZDD zdd) {
        if (group == linkFields.length) return NDD.getTrue();
        if (memo[group][remaining] >= 0) return memo[group][remaining];

        Map<Integer, Integer> edges = new HashMap<>();
        int upper = Math.min(remaining, exactByField[group].length - 1);
        for (int failures = 0; failures <= upper; failures++) {
            int target = buildZddFailureBudget(group + 1, remaining - failures,
                    exactByField, memo, zdd);
            int label = exactByField[group][failures];
            Integer old = edges.get(target);
            if (old == null) {
                edges.put(target, zdd.ref(label));
            } else {
                int merged = zdd.ref(zdd.union(old, label));
                zdd.deref(old);
                edges.put(target, merged);
            }
        }

        int node = NDD.addAtField(linkFields[group], edges);
        for (int label : edges.values()) zdd.deref(label);
        memo[group][remaining] = NDD.ref(node);
        return node;
    }

    /** Both operands are owned references (or terminals); the returned nonterminal is owned. */
    private static int orOwned(int left, int right) {
        if (NDD.isFalse(left)) return right;
        if (NDD.isFalse(right)) return left;
        int result = NDD.ref(NDD.or(left, right));
        NDD.deref(left);
        NDD.deref(right);
        return result;
    }

    public TimeUtil timer = new TimeUtil();

    public void calTC(NDDSortedMultiList list, boolean ecmp) {
        if (list.flat().isEmpty())
            return;

        timer.begin();
        int innerPre = NDD.getFalse(), outerPre = NDD.getFalse();
        int o, i;
        for (o = 0; o < list.getOIdx(); o++) {
            for (NDDSortedMultiListItem item : list.get(o)) {
                outerPre = NDD.orTo(outerPre, item.getArriveTC());
            }
        }
        for (i = 0; i < list.getIIdx(); i++) {
            innerPre = NDD.orTo(innerPre, list.get(o).get(i).getArriveTC());
        }

        if (NDD.isTrue(outerPre)) {
            for (; i < list.get(o).size(); i++) {
                list.get(o).get(i).setListTC(NDD.getFalse());
            }
        } else {
            for (; i < list.get(o).size(); i++) {
                NDDSortedMultiListItem item = list.get(o).get(i);
                calTC(item, outerPre, innerPre, ecmp);
                innerPre = NDD.orTo(innerPre, item.getArriveTC());
            }
        }

        outerPre = manager.orDerefBoth(outerPre, innerPre);
        for (o = o + 1; o < list.size(); o++) {
            if (NDD.isTrue(outerPre)) {
                for (NDDSortedMultiListItem item : list.get(o)) {
                    item.setListTC(NDD.getFalse());
                }
            } else {
                innerPre = NDD.getFalse();
                for (NDDSortedMultiListItem item : list.get(o)) {
                    calTC(item, outerPre, innerPre, ecmp);
                    innerPre = NDD.orTo(innerPre, item.getArriveTC());
                }
                outerPre = manager.orDerefBoth(outerPre, innerPre);
            }
        }
        timer.end();
    }

    public void calTC(NDDSortedMultiListItem item, int outerPre, int innerPre, boolean ecmp) {
        timer.begin();
        boolean relativeToFailureBudget = NDDController.MF >= 0 && !Configuration.FILTER;
        if (NDD.isNative() && relativeToFailureBudget
                && Boolean.parseBoolean(System.getProperty("sre.ndd.selectFirst", "true"))) {
            // (budget \ outer) & arrive == (budget & arrive) \ outer.
            // Work on the constrained route before excluding prior routes,
            // avoiding a large complement relative to the whole budget.
            int tc = NDD.ref(NDD.and(manager.FILTER_BDD, item.getArriveTC()));
            tc = manager.minusDerefLeft(tc, outerPre);
            if (!ecmp) tc = manager.minusDerefLeft(tc, innerPre);
            item.setListTC(tc); timer.end(); return;
        }
        int available = relativeToFailureBudget
                ? NDD.ref(NDD.diff(manager.FILTER_BDD, outerPre))
                : NDD.ref(NDD.not(outerPre));
        int tc = NDD.andTo(available, item.getArriveTC());
        if (!ecmp) {
            if (relativeToFailureBudget) {
                tc = manager.minusDerefLeft(tc, innerPre);
            } else {
                int temp2 = NDD.ref(NDD.not(innerPre));
                tc = manager.andDerefBoth(tc, temp2);
            }
        }
        item.setListTC(relativeToFailureBudget ? tc : manager.filter(tc));
        timer.end();
    }

    HashMap<Integer, Integer> tcFailureMaxMemo;
    int mfMemoHit = 0;

    /**
     * @param tc - a bdd that consists of only link vars
     * @return the max link failure this topology condition can tolerate
     */
    public int getTCFailureMax(int tc) {
        if (!tcFailureMaxMemo.containsKey(tc)) {
            int k = Math.max(0, distanceToComplement(tc) - 1);
            tcFailureMaxMemo.put(tc, k);
        } else {
            if (!NDD.isFalse(tc) && !NDD.isTrue(tc))
                mfMemoHit++;
        }
        return tcFailureMaxMemo.get(tc);
    }

    /**
     * SRE-specific shortest-path query retained outside the NDD library. Edge cost is the minimum
     * number of true link bits accepted by the BDD label; reaching TRUE in the complement gives
     * the minimum number of failures that violates {@code tc}.
     */
    private static int distanceToComplement(int tc) {
        int complement = NDD.ref(NDD.not(tc));
        try {
            Map<Integer, Integer> distance = new HashMap<>();
            Set<Integer> visited = new HashSet<>();
            Map<Integer, Integer> labelCost = new HashMap<>();
            distance.put(complement, 0);

            while (true) {
                int current = NDD.getFalse();
                int currentDistance = Integer.MAX_VALUE;
                for (Map.Entry<Integer, Integer> entry : distance.entrySet()) {
                    if (!visited.contains(entry.getKey()) && entry.getValue() < currentDistance) {
                        current = entry.getKey();
                        currentDistance = entry.getValue();
                    }
                }
                if (currentDistance == Integer.MAX_VALUE) return Integer.MAX_VALUE;
                if (NDD.isTrue(current)) return currentDistance;

                visited.add(current);
                if (NDD.isFalse(current)) continue;
                int edgeCount = NDD.getEdgeCount(current);
                for (int edge = 0; edge < edgeCount; edge++) {
                    int target = NDD.getEdgeTarget(current, edge);
                    int field = NDD.getField(current);
                    int label = NDD.getEdgeLabel(current, edge);
                    int cost = labelCost.computeIfAbsent(label,
                            ignored -> minimumFailuresInLabel(field, label));
                    int candidate = currentDistance + cost;
                    if (candidate < distance.getOrDefault(target, Integer.MAX_VALUE)) {
                        distance.put(target, candidate);
                    }
                }
            }
        } finally {
            NDD.deref(complement);
        }
    }

    private static int minimumFailuresInLabel(int field, int label) {
        return NDD.minimumFailures(field, label);
    }

    private static int minimumZddSetSize(int label, Map<Integer, Integer> memo) {
        if (label == 0) return Integer.MAX_VALUE / 4;
        if (label == 1) return 0;
        Integer cached = memo.get(label);
        if (cached != null) return cached;
        int without = minimumZddSetSize(NDD.getZDDEngine().getLow(label), memo);
        int with = minimumZddSetSize(NDD.getZDDEngine().getHigh(label), memo);
        if (with < Integer.MAX_VALUE / 4) with++;
        int result = Math.min(without, with);
        memo.put(label, result);
        return result;
    }

    public int getMfMemoHit() {
        return mfMemoHit;
    }

    Multimap<Integer, byte[]> tcAllSatMemo;
    int allSatMemoHit = 0;

    // public Collection<byte[]> allSat(int tc) {
    // if (tc == 0) {
    // return new HashSet<>();
    // }
    // if (!tcAllSatMemo.containsKey(tc)) {
    // int var = bdd.getVar(tc);
    // int idx = var - BDDACLWrapper.totalHeaderBit;
    // for (byte[] oneSat : allSat(bdd.getLow(tc))) {
    // byte[] down = oneSat.clone();
    // down[idx] = 0;
    // tcAllSatMemo.put(tc, down);
    // }
    // for (byte[] oneSat : allSat(bdd.getHigh(tc))) {
    // byte[] up = oneSat.clone();
    // up[idx] = 1;
    // tcAllSatMemo.put(tc, up);
    // }
    // }
    // else {
    // if (tc != 1) allSatMemoHit++;
    // }
    // return tcAllSatMemo.get(tc);
    // }

    private void initMemos() {
        tcFailureMaxMemo = new HashMap<>();
        tcFailureMaxMemo.put(NDD.getTrue(), totalLinkBit);
        tcFailureMaxMemo.put(NDD.getFalse(), -1);

        tcAllSatMemo = HashMultimap.create();
        byte[] allSymbolic = new byte[totalLinkBit];
        Arrays.fill(allSymbolic, (byte) -1);
        tcAllSatMemo.put(NDD.getTrue(), allSymbolic);
    }
}
