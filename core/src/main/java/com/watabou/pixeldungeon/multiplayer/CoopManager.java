package com.watabou.pixeldungeon.multiplayer;

import java.util.HashSet;
import java.util.Set;

import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.utils.GLog;

public class CoopManager {

	private static final CoopManager INSTANCE = new CoopManager();

	public static CoopManager instance() {
		return INSTANCE;
	}

	private final PeerDiscovery peerDiscovery = new Nostr4jPeerDiscovery();
	private final RealtimeChannel realtimeChannel = new JvmLibp2pRealtimeChannel();

	private boolean connected;
	private String roomId;
	private String playerId;
	private final Set<String> peerIds = new HashSet<String>();

	private CoopManager() {
	}

	public void ensureConnected( String configuredRoomId ) {
		if (connected) {
			return;
		}

		roomId = configuredRoomId;
		playerId = "hero-" + System.currentTimeMillis();

		realtimeChannel.connect( roomId, playerId, new RealtimeChannel.Listener() {
			@Override
			public void onEvent( CoopEvent event ) {
				handleIncoming( event );
			}
		} );

		peerDiscovery.start( roomId, playerId, new PeerDiscovery.Listener() {
			@Override
			public void onPeer( String peerId, String host, int port ) {
				if (peerId == null || peerId.length() == 0 || playerId.equals( peerId ) || port <= 0) {
					return;
				}
				realtimeChannel.addPeer( peerId, host, port );
				if (peerIds.add( peerId )) {
					GLog.i( "[Co-op] peer connected: %s (%s:%d)", peerId, host, port );
				}
			}
		} );
		peerDiscovery.announce( roomId, playerId, realtimeChannel.localPort() );

		connected = true;
		GLog.p( "[Co-op] Session ready in room '%s' on UDP %d", roomId, realtimeChannel.localPort() );
	}

	public void disconnect() {
		if (!connected) {
			return;
		}
		peerDiscovery.stop();
		realtimeChannel.disconnect();
		peerIds.clear();
		connected = false;
	}

	public void publishMove( int fromCell, int toCell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.move( playerId, Dungeon.depth, fromCell, toCell ) );
	}

	public void publishAttack( int fromCell, int toCell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.attack( playerId, Dungeon.depth, fromCell, toCell ) );
	}

	private void handleIncoming( CoopEvent event ) {
		if (!connected || event == null || playerId == null) {
			return;
		}
		if (playerId.equals( event.actorId )) {
			return;
		}
		if (event.depth != Dungeon.depth) {
			return;
		}

		if (event.kind == CoopEvent.Kind.MOVE) {
			GLog.i( "[Co-op] %s moved %d -> %d", event.actorId, event.fromCell, event.toCell );
		} else if (event.kind == CoopEvent.Kind.ATTACK) {
			GLog.i( "[Co-op] %s attacked toward %d", event.actorId, event.toCell );
		}
	}
}
