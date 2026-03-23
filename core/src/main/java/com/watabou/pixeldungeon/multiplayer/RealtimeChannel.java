package com.watabou.pixeldungeon.multiplayer;

public interface RealtimeChannel {

	interface Listener {
		void onEvent( CoopEvent event );
	}

	void connect( String roomId, String playerId, Listener listener );

	int localPort();

	void addPeer( String peerId, String host, int port );

	void send( CoopEvent event );

	void disconnect();
}
