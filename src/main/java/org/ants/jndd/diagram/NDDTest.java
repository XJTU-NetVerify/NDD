package org.ants.jndd.diagram;

import org.checkerframework.checker.units.qual.degrees;

public class NDDTest {

    public static void printDotAllBDD(NDD a, String rootpath){
        
        if(a.isTerminal())return;
        if(a.edgeCount()==0)return;
        for(int i = 0; i < a.edgeCount(); i++){
            // NDD.getBDDEngine().printDot(rootpath + "/" + a.getField()+"_"+a.edgeLabel(i) + ".dot", a.edgeLabel(i));
            printDotAllBDD(a.edgeTarget(i), rootpath);
        }
    }
    public static void printDotSubNDD(NDD a, String rootpath){
        
        if(a.isTerminal())return;
        if(a.edgeCount()==0)return;
        for(int i = 0; i < a.edgeCount(); i++){
            NDD son = a.edgeTarget(i);
            NDD.printDot(rootpath + "/" + a.getField()+"_"+son.hashCode() + ".dot", son);
            printDotSubNDD(son, rootpath);
        }
    }
    
    public static void main(String[] args) {
        NDDManager manager = new NDDManager(20000, 20000, 2000);
        NDD a = manager.readOne().withRef();
        NDD b = manager.readZero().withRef();
        NDD c = manager.constant(4);
        int field1 = manager.declareField(5);
        int field2 = manager.declareField(5);
        int field3 = manager.declareField(5);
        manager.generateFields();
        NDD var1 = manager.ithVar(field1, 1).withRef();
        NDD var1_1 = manager.ithVar(field1, 3).withRef();
        NDD var2 = manager.ithVar(field2, 1).withRef();
        NDD var3 = manager.ithVar(field3, 1);

        NDD d = var1.or(var2);
        
        manager.printdot("output/dot/test1", d, false);
        NDD f = manager.constant(2);
        d = d.times(a);
        manager.printdot("output/dot/test2", var1_1, false);


    }
}
