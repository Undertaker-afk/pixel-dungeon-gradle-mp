package com.watabou.pixeldungeon.multiplayer;

public class PeerEndpoint {

	public final String peerId;
	public final String udpHost;
	public final int udpPort;
	public final String relayRoutingKey;
	public final String relayUrl;

	public PeerEndpoint( String peerId, String udpHost, int udpPort, String relayRoutingKey, String relayUrl ) {
		this.peerId = peerId;
		this.udpHost = udpHost;
		this.udpPort = udpPort;
		this.relayRoutingKey = relayRoutingKey;
		this.relayUrl = relayUrl;
	}

	public boolean hasUdpEndpoint() {
		return udpHost != null && udpHost.length() > 0 && udpPort > 0;
	}

	public boolean hasRelayRoute() {
		return relayRoutingKey != null && relayRoutingKey.length() > 0;
	}
}
