package com.watabou.pixeldungeon.multiplayer;

public interface RealtimeChannel {

	interface Listener {
		void onEvent( CoopEvent event );
	}

	void connect( String roomId, String playerId, Listener listener );

	void addPeer( PeerEndpoint peerEndpoint );

	PeerEndpoint localEndpoint();

	void send( CoopEvent event );

	void disconnect();
}
