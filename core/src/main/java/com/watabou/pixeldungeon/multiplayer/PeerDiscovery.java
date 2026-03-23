package com.watabou.pixeldungeon.multiplayer;

public interface PeerDiscovery {

	interface Listener {
		void onPeer( String peerId, String host, int port );
	}

	void start( String roomId, String playerId, Listener listener );

	void announce( String roomId, String playerId, int port );

	void stop();
}
