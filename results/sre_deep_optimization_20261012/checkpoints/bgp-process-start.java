package controlplane.process.bgp;

import sre.ndd.NDD;
import multimap.HashMultimap;
import multimap.Multimap;
import multimap.Table;
import controlplane.network.NDDPort;
import controlplane.network.NDDRouter;
import controlplane.network.Port;
import controlplane.network.Router;
import controlplane.process.NDDProcess;
import controlplane.process.bgp.routeaggregation.NDDRouteAggregation;
import controlplane.process.bgp.routeaggregation.RouteAggregation;
import controlplane.process.bgp.routemap.NDDRouteMap;
import controlplane.process.bgp.routemap.RouteMap;
import controlplane.rib.NDDRib;
import controlplane.rib.Rib;
import controlplane.route.*;
import controlplane.route.builder.BgpRouteBuilder;
import controlplane.route.builder.NDDBgpRouteBuilder;
import controlplane.route.builder.NDDResolvedBgpRouteBuilder;
import controlplane.route.builder.ResolvedBgpRouteBuilder;
import datamodel.ipv4.Ip;
import datamodel.ipv4.Prefix;
import datamodel.list.NDDSortedMultiList;
import datamodel.list.NDDSortedMultiListItem;
import datamodel.list.SortedMultiList;
import main.Controller;
import main.NDDController;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.Function;

import bdd.NDDTCWrapper;

public class NDDBgpProcess implements NDDProcess {
    NDDRouter router;
    int asNumber;

    HashSet<Prefix> networks = new HashSet<>();

    Deque<NDDBgpRoute> inQueue = new LinkedList<>();
    HashSet<Prefix> suppressedPrefixes = new HashSet<>();
    Multimap<Integer, NDDBgpRoute> outQueue = HashMultimap.create();

    NDDRib rib;

    NDDRouteMap routeMapIn;
    NDDRouteMap routeMapOut;

    Vector<NDDRouteAggregation> routeAggregations;

    boolean valid = false;
    boolean br = false;
    boolean rr = false;

    boolean multipath = true;

    public NDDBgpProcess(NDDRouter router) {
        this.router = router;
        this.asNumber = router.getAsNumber();
        this.rib = new NDDRib(this);
    }

    public void setNetworks(HashSet<Prefix> networksToBeAdvertised) {
        this.networks = networksToBeAdvertised;
    }

    public void setRouteMapIn(NDDRouteMap routeMapIn) {
        this.routeMapIn = routeMapIn;
    }

    public void setRouteMapOut(NDDRouteMap routeMapOut) {
        this.routeMapOut = routeMapOut;
    }

    public void setRouteAggregations(Vector<NDDRouteAggregation> routeAggregations) {
        this.routeAggregations = routeAggregations;
    }

    public void setMultipath(boolean multipath) {
        this.multipath = multipath;
    }

    public void addNetwork(Prefix prefix) {
        this.networks.add(prefix);
    }

    public NDDRouter getRouter() {
        return router;
    }

    public HashSet<Prefix> getNetworks() {
        return networks;
    }

    @Override
    public NDDRib getRib() {
        return rib;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean valid() {
        return valid;
    }

    public void begin() {
        if (valid) {
            /*
             * According to Cisco documentation, when using network command in BGP,
             * we have to check whether the prefix is already in the router's RIB.
             */
            networks.stream()
                    .filter(prefix -> prefix.isInterested() && router.getGlobalRib().getRib().containsKey(prefix))
                    .forEach(prefix -> {
                        NDDBgpRoute bgpRoute = new NDDBgpRouteBuilder()
                                .setPrefix(prefix)
                                .setNextHop(Ip.of("0.0.0.0"))
                                .setType(BgpRoute.BgpRouteType.NETWORK)
                                .setArriveTC(NDD.getTrue())
                                .setListTC(NDD.getTrue())
                                .build();
                        NDDResolvedBgpRoute resolvedBgpRoute = new NDDResolvedBgpRouteBuilder()
                                .setBgpRoute(bgpRoute)
                                .setOutPort(router.getBlackhole())
                                .setIgpCostToNextHop(0)
                                .setArriveTC(NDD.getTrue())
                                .setListTC(NDD.getTrue())
                                .build();
                        rib.insert(resolvedBgpRoute);
                        insertOutQueue(bgpRoute);
                    });
        }
    }

    /**
     * check if there exists loop
     * if so, drop
     * else, insert it into the inQueue
     */
    public void routeIn(NDDRouter from, NDDBgpRoute route) {
        if (routeMapIn != null) {
            route = routeMapIn.map(from.getRouterName(), route);
        }
        if (route != null) {
            boolean suppressed = false;
            for (NDDRouteAggregation ra : routeAggregations) {
                suppressed = ra.aggregate(route) | suppressed;
            }
            if (suppressed) {
                suppressedPrefixes.add(route.getPrefix());
            }
            inQueue.add(route);
        }
    }

    public boolean updateRIB() {
        HashSet<Prefix> prefixes = new HashSet<>();

        routeAggregations.forEach(NDDRouteAggregation::insertInQueue);
        List<NDDResolvedBgpRoute> rbrs = new ArrayList<>();
        while (!inQueue.isEmpty()) {
            NDDBgpRoute route = inQueue.pollFirst();
            prefixes.add(route.getPrefix());
            rbrs.addAll(combineIgp(route));
        }
        rbrs.sort(NDDResolvedBgpRoute::compareTo);

        rbrs.forEach(rbr -> rib.insert(rbr));

        // System.out.println("++++" + router.getRouterName());
        prefixes.forEach(prefix -> {
            NDDSortedMultiList list = rib.getRib().get(prefix);
            if (list != null) {
                NDDController.bddManager.getBDDTCWrapper().calTC(list, multipath);
                if (!suppressedPrefixes.contains(prefix)) {
                    list.flat().stream().map(item -> (NDDResolvedBgpRoute) item).filter(NDDDynamicRoute::dirty)
                            .forEach(r -> insertOutQueue(r.prepareOut()));
                }
            }
        });

        // try {
        // FileWriter fw = new
        // FileWriter("/home/augists/Cheney/SRE-Benchmark/variants/ndd/networks/exp/fattree/bgp/bgp_fattree08/OutNDD/test/"+router.getRouterName()+".txt",
        // false);
        // PrintWriter pw = new PrintWriter(fw);
        // prefixes.forEach(prefix -> {
        // NDDSortedMultiList list = rib.getRib().get(prefix);
        // if (list != null) {
        // // NDD.satCountTest = true;
        // list.flat().stream().map(item -> (NDDResolvedBgpRoute) item).forEach(r ->
        // printBgpRoute(pw, r));
        // // NDD.satCountTest = false;
        // // if(router.getRouterName().equals("aggregation-56"))
        // // {
        // // list.flat().stream().map(item -> (NDDResolvedBgpRoute) item).forEach(r ->
        // NDD.printOut(r.getArriveTC()));
        // // System.out.println();
        // // list.flat().stream().map(item -> (NDDResolvedBgpRoute) item).forEach(r ->
        // NDD.printOut(r.getListTC()));
        // //
        // NDD.bdd.printDot("/home/augists/Cheney/SRE-Benchmark/variants/ndd/networks/exp/fattree/bgp/bgp_fattree08/OutNDD/test/aPic/46",
        // 46);
        // // System.exit(0);
        // // }
        // }
        // });
        // } catch (IOException e) {
        // //TODO: handle exception
        // }

        rib.getRib().values().forEach(NDDSortedMultiList::resetIdx);

        return outQueue.isEmpty();
    }

    private void printBgpRoute(PrintWriter pw, NDDResolvedBgpRoute route) {
        pw.println(route.toString());
        pw.flush();
    }

    public HashSet<NDDResolvedBgpRoute> combineIgp(NDDBgpRoute bgpRoute) {
        HashSet<NDDResolvedBgpRoute> hs = new HashSet<>();
        if (bgpRoute.getOutPort() != null) {
            NDDResolvedBgpRoute newRoute = new NDDResolvedBgpRouteBuilder()
                    .setBgpRoute(bgpRoute)
                    .setOutPort(bgpRoute.getOutPort())
                    .setIgpCostToNextHop(0)
                    .setArriveTC(bgpRoute.getArriveTC())
                    .build();
            hs.add(newRoute);
            // if(router.getRouterName().equals("aggregation-56") ||
            // router.getRouterName().equals("core-3"))
            // {
            // System.out.println("Combine Igp: "+router.getRouterName()+"
            // "+newRoute.toString());
            // }
        } else {
            // (igp cost, out port) -> tc
            Table<Long, NDDPort, Integer> tbl = router.getGlobalRib().query(bgpRoute.getNextHop());
            tbl.cellSet().forEach(cell -> {
                NDDResolvedBgpRoute newRoute = new NDDResolvedBgpRouteBuilder()
                        .setBgpRoute(bgpRoute)
                        .setOutPort(cell.getColumnKey())
                        .setIgpCostToNextHop(Objects.requireNonNull(cell.getRowKey()))
                        .setArriveTC(NDD.ref(NDD.and(bgpRoute.getArriveTC(), Objects.requireNonNull(cell.getValue()))))
                        .build();
                hs.add(newRoute);
            });
        }
        return hs;
    }

    public void routeOut(Map<NDDBgpProcess, NDDBgpEdge> edges, Function<BgpNeighborConfig.NeighborType, Boolean> func) {
        // if(router.getRouterName().equals("core-3") ||
        // router.getRouterName().equals("aggregation-56"))
        // {
        // System.out.println("Route out:");
        // }
        Iterator<NDDBgpRoute> itr = outQueue.values().iterator();
        while (itr.hasNext()) {
            NDDBgpRoute route = itr.next();

            for (Map.Entry<NDDBgpProcess, NDDBgpEdge> entry : edges.entrySet()) {
                NDDBgpProcess p = entry.getKey();
                NDDBgpEdge e = entry.getValue();
                if (!NDD.isFalse(e.tc) && func.apply(e.src.neighborType)) {
                    NDDBgpRoute tmp = new NDDBgpRoute(route);
                    // apply RouteMapOut
                    if (routeMapOut != null) {
                        tmp = routeMapOut.map(p.router.getRouterName(), tmp);
                    }
                    // avoid propagation path loop and AS path loop
                    if (tmp != null && (!tmp.getPath().contains(p.router.getRouterName()))
                            && (!tmp.getASPath().contains(p.asNumber))) {
                        // if(router.getRouterName().equals("core-3") ||
                        // router.getRouterName().equals("aggregation-56"))
                        // {
                        // System.out.println(tmp.toString());
                        // }
                        NDDBgpExecutor.propagate(tmp, e);
                    }
                }
            }

            itr.remove();
        }
    }

    public void rrFinalRound() {
        rib.getRib().values().forEach(list -> list.flat().forEach(item -> {
            NDDResolvedBgpRoute rbr = (NDDResolvedBgpRoute) item;
            insertOutQueue(rbr.prepareOut());
        }));
    }

    public void setBR(boolean br) {
        this.br = br;
    }

    public boolean isBR() {
        return br;
    }

    public void setRR(boolean rr) {
        this.rr = rr;
    }

    public boolean isRR() {
        return rr;
    }

    public boolean isIR() {
        return !br && !rr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NDDBgpProcess that = (NDDBgpProcess) o;
        return asNumber == that.asNumber &&
                Objects.equals(router, that.router);
    }

    @Override
    public int hashCode() {
        return Objects.hash(router, asNumber);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("BgpProcess\n");
        networks.forEach(prefix -> builder.append(prefix.toString()));
        return builder.toString();
    }

    public void clear() {
        inQueue = null;
        outQueue = null;
        networks = null;
        rib.clear();
    }

    public void reset() {
        inQueue = new LinkedList<>();
        outQueue = HashMultimap.create();
        rib.reset();
    }

    public void insertOutQueue(NDDRoute route) {
        if (route instanceof NDDBgpRoute) {
            NDDBgpRoute tmp = (NDDBgpRoute) route;
            int hash = tmp.hashCode();
            if (outQueue.containsKey(hash)) {
                for (NDDBgpRoute bgpRoute : outQueue.get(hash)) {
                    if (bgpRoute.equals(tmp)) {
                        bgpRoute.setListTC(NDD.ref(NDD.or(bgpRoute.getListTC(), tmp.getListTC())));
                        return;
                    }
                }
            }
            outQueue.put(hash, tmp);
        }
    }
}
