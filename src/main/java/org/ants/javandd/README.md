## How to use `JavaBDD` version of NDD?

For some differences between NDD and BDD, you cannot directly use NDD with only factory changed. Here are the steps showing how to run your project with JavaBDD(NDD).

1. Prepare the parameters for node table size and cache size, also the cache ratio in NDD.

```java
int BDD_NODE_TABLE_SIZE;
int BDD_NODE_CACHE_SIZE;
int NDD_NODE_TABLE_SIZE;
int NDD_NODE_CACHE_RATIO;  // default 8
```

2. Create the `NDDFactory` with BDD parameters. Here is a demo in [Batfish](https://github.com/batfish/batfish)

```java
public class BDDPacket {
  public static BDDFactory defaultFactory(BiFunction<Integer, Integer, BDDFactory> init) {
    BDDFactory factory =
        init.apply(BDD_NODE_TABLE_SIZE, BDD_NODE_CACHE_SIZE);
    factory.setCacheRatio(NDD_NODE_CACHE_RATIO);
    return factory;
  }

  public BDDPacket() {
    this(defaultFactory(NDDFactory::init));
  }

  public BDDPacket(BDDFactory factory) {
    _factory = factory;
    ...
  }
}
```

3. NDD cannot grow up dynamically for its fields in every domain should already be computed and passed. So we use a brand new `setVarNum` method.

```java
  public BDDPacket(BDDFactory factory) {
    _factory = factory;
    int[] numNeeded = {
      IP_LENGTH, // primed/unprimed src/dst
            IP_LENGTH,
            IP_LENGTH,
            IP_LENGTH,
      PORT_LENGTH, // primed/unprimed src/dst
            PORT_LENGTH,
            PORT_LENGTH,
            PORT_LENGTH,
      IP_PROTOCOL_LENGTH,
      ICMP_CODE_LENGTH,
      ICMP_TYPE_LENGTH,
      TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
            TCP_FLAG_LENGTH,
      PACKET_LENGTH_LENGTH
    };
    ((NDDFactory) _factory).setVarNum(numNeeded, NDD_NODE_TABLE_SIZE);
  }
```

The array `numNeeded` passes every length of field to `NDDFactory` so that it can `declare` these 20 domains in total. To make a better reusage of BDD edges in NDD, each `IP` `PORT` and `TCP_FLAG` are separated.

4. Get every NDD node with `ithVar`. Notice that each NDD node presents one domain, with plenty of `XX_LENGTH` length BDD nodes on its edges.

> It is our first time to get access to the APIs in `JavaBDD`. If some are misunderstood, please contact us or Pull Request if you can. Your contribution to **N**etwork **D**ecision **D**iagram is much appreciated.
