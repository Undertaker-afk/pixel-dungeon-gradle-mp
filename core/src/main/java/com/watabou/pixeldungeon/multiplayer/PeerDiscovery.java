package com.watabou.pixeldungeon.multiplayer;

import java.util.List;

public interface PeerDiscovery {

	interface Listener {
		void onPeer( PeerEndpoint peerEndpoint );
	}

	void start( String roomId, String playerId, Listener listener );

	void announce( CoopLobby lobby, String playerId );

	List<CoopLobby> knownLobbies();

	void stop();
}
