package org.batfish.representation.f5_bigip;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.batfish.vendor.StructureType;

/** Named structure-types for F5 BIG-IP device */
@ParametersAreNonnullByDefault
public enum F5BigipStructureType implements StructureType {
  ACCESS_LIST("access-list"),
  BGP_NEIGHBOR("bgp neighbor"),
  BGP_PROCESS("bgp process"),
  DATA_GROUP("data-group"),
  DATA_GROUP_EXTERNAL("data-group external"),
  DATA_GROUP_INTERNAL("data-group internal"),
  DEVICE("device"),
  DEVICE_GROUP("device-group"),
  HA_GROUP("ha-group"),
  IMISH_INTERFACE("IMISH interface"),
  INTERFACE("interface"),
  MONITOR("monitor"),
  MONITOR_DNS("monitor dns"),
  MONITOR_GATEWAY_ICMP("monitor gateway-icmp"),
  MONITOR_HTTP("monitor http"),
  MONITOR_HTTPS("monitor https"),
  MONITOR_LDAP("monitor ldap"),
  MONITOR_TCP("monitor tcp"),
  NODE("node"),
  OSPF_PROCESS("router ospf"),
  PEER_GROUP("peer-group"),
  PERSISTENCE("persistence"),
  PERSISTENCE_COOKIE("persistence cookie"),
  PERSISTENCE_SOURCE_ADDR("persistence source-addr"),
  PERSISTENCE_SSL("persistence ssl"),
  POOL("pool"),
  PREFIX_LIST("prefix-list"),
  PROFILE("profile"),
  PROFILE_ANALYTICS("profile analytics"),
  PROFILE_CERTIFICATE_AUTHORITY("profile certificate-authority"),
  PROFILE_CLASSIFICATION("profile classification"),
  PROFILE_CLIENT_LDAP("profile client-ldap"),
  PROFILE_CLIENT_SSL("profile client-ssl"),
  PROFILE_DHCPV4("profile dhcpv4"),
  PROFILE_DHCPV6("profile dhcpv6"),
  PROFILE_DIAMETER("profile diameter"),
  PROFILE_DNS("profile dns"),
  PROFILE_FASTHTTP("profile fasthttp"),
  PROFILE_FASTL4("profile fastl4"),
  PROFILE_FIX("profile fix"),
  PROFILE_FTP("profile ftp"),
  PROFILE_GTP("profile gtp"),
  PROFILE_HTML("profile html"),
  PROFILE_HTTP("profile http"),
  PROFILE_HTTP2("profile http2"),
  PROFILE_HTTP_COMPRESSION("profile http-compression"),
  PROFILE_HTTP_PROXY_CONNECT("profile http-proxy-connect"),
  PROFILE_ICAP("profile icap"),
  PROFILE_ILX("profile ilx"),
  PROFILE_IPOTHER("profile ipother"),
  PROFILE_IPSECALG("profile ipsecalg"),
  PROFILE_MAP_T("profile map-t"),
  PROFILE_MQTT("profile mqtt"),
  PROFILE_NETFLOW("profile netflow"),
  PROFILE_OCSP_STAPLING_PARAMS("profile ocsp-stapling-params"),
  PROFILE_ONE_CONNECT("profile one-connect"),
  PROFILE_PCP("profile pcp"),
  PROFILE_PPTP("profile pptp"),
  PROFILE_QOE("profile qoe"),
  PROFILE_RADIUS("profile radius"),
  PROFILE_REQUEST_ADAPT("profile request-adapt"),
  PROFILE_REQUEST_LOG("profile request-log"),
  PROFILE_RESPONSE_ADAPT("profile response-adapt"),
  PROFILE_REWRITE("profile rewrite"),
  PROFILE_RTSP("profile rtsp"),
  PROFILE_SCTP("profile sctp"),
  PROFILE_SERVER_LDAP("profile server-ldap"),
  PROFILE_SERVER_SSL("profile server-ssl"),
  PROFILE_SIP("profile sip"),
  PROFILE_SMTPS("profile smtps"),
  PROFILE_SOCKS("profile socks"),
  PROFILE_SPLITSESSIONCLIENT("profile splitsessionclient"),
  PROFILE_SPLITSESSIONSERVER("profile splitsessionserver"),
  PROFILE_STATISTICS("profile statistics"),
  PROFILE_STREAM("profile stream"),
  PROFILE_TCP("profile tcp"),
  PROFILE_TCP_ANALYTICS("profile tcp-analytics"),
  PROFILE_TFTP("profile tftp"),
  PROFILE_TRAFFIC_ACCELERATION("profile traffic-acceleration"),
  PROFILE_UDP("profile udp"),
  PROFILE_WEB_ACCELERATION("profile web-acceleration"),
  PROFILE_WEB_SECURITY("profile web-security"),
  PROFILE_WEBSOCKET("profile websocket"),
  PROFILE_XML("profile xml"),
  ROUTE("route"),
  ROUTE_MAP("route-map"),
  RULE("rule"),
  SELF("self"),
  SNAT("snat"),
  SNAT_TRANSLATION("snat-translation"),
  SNATPOOL("snatpool"),
  TRAFFIC_GROUP("traffic-group"),
  TRUNK("trunk"),
  VIRTUAL("virtual"),
  VIRTUAL_ADDRESS("virtual-address"),
  VLAN("vlan"),
  VLAN_MEMBER_INTERFACE("vlan member interface");

  private final String _description;

  F5BigipStructureType(String description) {
    _description = description;
  }

  @Override
  public @Nonnull String getDescription() {
    return _description;
  }
}
