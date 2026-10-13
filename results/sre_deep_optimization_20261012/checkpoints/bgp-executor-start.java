package controlplane.process.bgp;

import sre.ndd.NDD;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;


import controlplane.route.BgpRoute;
import controlplane.route.NDDBgpRoute;
import controlplane.route.NDDResolvedBgpRoute;
import controlplane.route.Path;
import controlplane.route.builder.NDDBgpRouteBuilder;
import datamodel.list.NDDSortedMultiList;
import main.Configuration;
import main.NDDController;
import main.SRELogger;
import util.MemorizeUtil;
import util.TimeUtil;

public class NDDBgpExecutor {
    public static int iteration = 0;
    public static int total = 0;
    public static int remain = 0;

    private static final boolean DEBUG_OUTPUT_ROUTES =
            Boolean.parseBoolean(System.getProperty("sre.debug.routes.output", "false"));

    public static void execute(NDDBgpTopology topology, NDDBgpProcess announcer) {
        announcer.begin();
        iteration = Integer.max(iterate(topology), iteration);
        finalRound(topology);
        int[] stats = cleanDeadRoutes(topology);
        total = stats[0];
        remain = stats[1];
    }

    public static void execute(NDDBgpTopology topology) {
        TimeUtil bgpTimer = new TimeUtil();
        bgpTimer.begin();

        // first round
        topology.edges.rowKeySet().forEach(NDDBgpProcess::begin);
        // CountRoutes(topology);
        OutputRoutes(topology, 0, "execute");

        // iterate
        int iteration = iterate(topology);

        // final round: reflector to client
        finalRound(topology);
        printBgpRib(topology);
        SRELogger.logCPExe(SRELogger.LEVEL.INFO, "[ITERATION] " + iteration);

        int[] stats = cleanDeadRoutes(topology);
        SRELogger.logCPExe(SRELogger.LEVEL.INFO, "[ROUTE] total: " + stats[0] + " remain: " + stats[1]);

        // if (Configuration.checkCorrectness)
        // OutputRoutes(topology, 10000, "execute");
        // CountRoutes(topology);

        bgpTimer.end(SRELogger.LEVEL.INFO, "");
    }

    public static void printBgpRib(NDDBgpTopology topology) {
        int sum1 = 0;
        int sum2 = 0;
        for (NDDBgpProcess process : topology.edges.rowKeySet()) {
            for (NDDSortedMultiList lists : process.getRib().getRib().values()) {
                for (ArrayList list : lists) {
                    sum1 += list.size();
                }
            }
            sum2 += process.outQueue.size();
        }
        // System.out.println("NDDBgpExecutor Bgp Routes:" + sum1);
        // System.out.println("NDDBgpExecutor Out Queue:" + sum2);
    }

    public static void CountRoutes(NDDBgpTopology topology) {
        int count = 0;
        for (NDDBgpProcess process : topology.edges.rowKeySet()) {
            for (NDDSortedMultiList lists : process.getRib().getRib().values()) {
                for (ArrayList list : lists) {
                    for (Object route : list) {
                        count++;
                        // double epsilon = 0.000001;
                        // if (Math.abs(NDD.satCount(((NDDResolvedBgpRoute) route).getArriveTC())) <
                        // epsilon
                        // && Math.abs(NDD.satCount(((NDDResolvedBgpRoute) route).getListTC())) <
                        // epsilon)
                        // System.out.println(((NDDResolvedBgpRoute)
                        // route).getArriveTC().vectors.toString());
                    }
                }
            }
        }
        System.out.println("count " + count);
    }

    // public static void OutputRoutes(NDDBgpTopology topology, int i, String s) {
    //     try {
    //         File file = NDDController.storage.output._outputBase
    //                 .resolve("test")
    //                 .resolve("routes_" + i + "_" + s)
    //                 .toFile();
    //         MemorizeUtil.creatFileIfAbsent(file);

    //         List<NDDResolvedBgpRoute> routes = new ArrayList<>();
    //         for (NDDBgpProcess process : topology.edges.rowKeySet()) {
    //             for (NDDSortedMultiList lists : process.getRib().getRib().values()) {
    //                 for (ArrayList list : lists) {
    //                     for (Object route : list) {
    //                         routes.add((NDDResolvedBgpRoute) route);
    //                     }
    //                 }
    //             }
    //         }
    //         routes.sort(NDDResolvedBgpRoute::compareTo);

    //         try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
    //             for (NDDResolvedBgpRoute route : routes) {
    //                 writer.write(route.toString());
    //                 writer.newLine();
    //             }
    //         }
    //     } catch (IOException e) {
    //         e.printStackTrace();
    //     }
    // }

    public static void OutputRoutes(NDDBgpTopology topology, int i, String s) {
        try {
            File file = NDDController.storage.output._outputBase
                    .resolve("test")
                    .resolve("routes_" + i + "_" + s)
                    .toFile();
            MemorizeUtil.creatFileIfAbsent(file);

            List<String> lines = new ArrayList<>();
            for (NDDBgpProcess process : topology.edges.rowKeySet()) {
                for (NDDSortedMultiList lists : process.getRib().getRib().values()) {
                    for (ArrayList list : lists) {
                        for (Object route : list) {
                            lines.add(route.toString());
                        }
                    }
                }
            }
            Collections.sort(lines);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // public static int r = 3;

    public static int iterate(NDDBgpTopology topology) {
        boolean converged = false;
        int iteration = 0;
        long start = System.nanoTime();

        List<String> tmp = new LinkedList<>();

        while (!converged
                && iteration < Configuration.MAX_SRC_ROUNDS
                && (System.nanoTime() - start) / 1e9 < Configuration.SRC_TIMEOUT_SECONDS) {
            // bgp local step
            // System.out.println("Round " + (iteration + 1));
            // OutputRoutes(topology, iteration + 1, "before_execute");
            // int t = 0;
            for (NDDBgpProcess process : topology.edges.rowKeySet()) {
                process.updateRIB();
                // if (iteration + 1 == r)
                // OutputRoutes(topology, iteration + 1, t + process.router.getRouterName() +
                // "before");
                process.routeOut(topology.edges.row(process), x -> x != BgpNeighborConfig.NeighborType.REFLECTOR);
                // if (iteration + 1 == r)
                // OutputRoutes(topology, iteration + 1, t + process.router.getRouterName() +
                // "after");
                // t++;
            }
            converged = topology.edges.rowKeySet().stream()
                    .allMatch(process -> process.inQueue.isEmpty() && process.outQueue.isEmpty());
            iteration++;
            // CountRoutes(topology);
            // if (iteration == r)
            // OutputRoutes(topology, iteration, "after_execute");
            // System.out.println(converged);
        }

        int[] stats = getStats(topology);
        // NodeTableStats usage = NDDController.bddManager.getBDD().getStats();

        tmp.add(stats[0] + "\t" +
                stats[1] + "\t" +
                // String.format("%.2f", (usage.get_table_size() - usage.get_free_nodes_count())
                // * 100.0 / Integer.MAX_VALUE) + "\t" +
                // (usage.get_table_size() - usage.get_free_nodes_count()) + "\t" +
                (System.nanoTime() - start) / 1e9);
        try {
            File file = NDDController.storage.output._outputBase
                    .resolve("stats")
                    .resolve(NDDController.MF + ".csv")
                    .toFile();
            MemorizeUtil.creatFileIfAbsent(file);
            BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            for (String str : tmp) {
                writer.write(str + "\n");
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return iteration;
    }

    public static void finalRound(NDDBgpTopology topology) {
        topology.edges.rowKeySet().stream().filter(NDDBgpProcess::isRR).forEach(p -> {
            p.rrFinalRound();
            p.routeOut(topology.edges.row(p), x -> x == BgpNeighborConfig.NeighborType.REFLECTOR);
        });
        topology.edges.rowKeySet().stream().filter(NDDBgpProcess::isIR).forEach(NDDBgpProcess::updateRIB);
    }

    public static int[] cleanDeadRoutes(NDDBgpTopology topology) {
        int total = 0;
        int remain = 0;
        for (NDDBgpProcess process : topology.edges.rowKeySet()) {
            int[] statistic = process.rib.cleanDeadRoute();
            total += statistic[0];
            remain += statistic[1];
        }
        // topology.edges.rowKeySet().forEach(process -> process.rib.cleanDeadRoute());
        return new int[] { total, remain };
    }

    public static int[] getStats(NDDBgpTopology topology) {
        int total = 0;
        int valid = 0;
        for (NDDBgpProcess process : topology.edges.rowKeySet()) {
            int[] statistic = process.rib.getStats();
            total += statistic[0];
            valid += statistic[1];
        }
        return new int[] { total, valid };
    }

    public static void propagate(NDDBgpRoute route, NDDBgpEdge edge) {
        if (route.getType() == BgpRoute.BgpRouteType.FROM_IBGP_NEIGHBOR
                && edge.src.neighborType != BgpNeighborConfig.NeighborType.EBGP) {
            return;
        }
        if (route.getType() == BgpRoute.BgpRouteType.FROM_ROUTE_REFLECTOR) {
            return;
        }

        // if(edge.src.process.router.getRouterName().equals("aggregation-56") &&
        // edge.dst.process.router.getRouterName().equals("core-3"))
        // {
        // // NDD.checkCorrectness = true;
        // }
        int newTC = NDD.ref(NDD.and(route.getListTC(), edge.getTC()));
        // if(edge.src.process.router.getRouterName().equals("aggregation-56") &&
        // edge.dst.process.router.getRouterName().equals("core-3"))
        // {
        // // NDD.checkCorrectness = false;
        // System.out.println(NDD.satCount(route.getListTC())/Math.pow(2.0, 104)+"
        // "+NDD.satCount(edge.getTC())/Math.pow(2.0, 104)+"
        // "+NDD.satCount(newTC)/Math.pow(2.0, 104));
        // }

        if (!NDD.isFalse(newTC)) {
            Path<String> path = new Path<>(route.getPath());
            path.add(edge.src.process.router.getRouterName());

            NDDBgpRoute newRoute = new NDDBgpRouteBuilder()
                    .setPrefix(route.getPrefix())
                    .setNextHop(route.getNextHop())
                    .setArriveTC(newTC)
                    .setPath(path)
                    .setOrigin(route.getOrigin())
                    .setASPath(new Path<>(route.getASPath()))
                    .setType(edge.src.neighborType.bgpRouteType)
                    .build();

            /*
             * EBGP:
             * 1. Change "next hop"
             * 2. Extend "AS path"
             * IBGP:
             * set "local preference"
             */
            if (edge.src.neighborType == BgpNeighborConfig.NeighborType.EBGP) {
                newRoute.setNextHop(edge.src.localIp);
                newRoute.getASPath().add(edge.src.process.asNumber);
            } else {
                newRoute.setLocalPreference(route.getLocalPreference());
            }

            // if((edge.src.process.router.getRouterName().equals("edge-62") &&
            // edge.dst.process.router.getRouterName().equals("aggregation-56")) ||
            // (edge.src.process.router.getRouterName().equals("aggregation-56") &&
            // edge.dst.process.router.getRouterName().equals("core-3")))
            // {
            // System.out.println("Link var: "+edge.getTC()+" Edge: "+edge.toString());
            // System.out.println("Propogate: "+edge.src.process.router.getRouterName()+"
            // "+edge.dst.process.router.getRouterName()+" "+newRoute.toString());
            // }

            edge.dst.process.routeIn(edge.src.process.router, newRoute);
        }
    }
}
