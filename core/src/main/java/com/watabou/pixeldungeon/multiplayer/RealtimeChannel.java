package com.watabou.pixeldungeon.multiplayer;

public interface RealtimeChannel {

	interface Listener {
		void onEvent( CoopEvent event );
	}

	void connect( String roomId, String playerId, Listener listener );

	void addPeer( String peerId );

	void send( CoopEvent event );

	void disconnect();
}
