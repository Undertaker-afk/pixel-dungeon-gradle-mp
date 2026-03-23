package com.watabou.pixeldungeon.multiplayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.PixelDungeon;
import com.watabou.pixeldungeon.actors.hero.HeroClass;
import com.watabou.pixeldungeon.Badges;
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
	private final Set<String> bannedPeerIds = new HashSet<String>();

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
				public void onPeer( String peerId ) {
					if (peerId == null || peerId.length() == 0 || playerId.equals( peerId ) || bannedPeerIds.contains( peerId )) {
						return;
					}
					realtimeChannel.addPeer( peerId );
				if (peerIds.add( peerId )) {
					GLog.i( "[Co-op] peer connected: %s", peerId );
				}
			}
		} );
		peerDiscovery.announce( buildLobbyAnnouncement(), playerId );

		connected = true;
		GLog.p( "[Co-op] Session ready in room '%s' (%s)", roomId, PixelDungeon.coopRole().name() );
	}

	public void disconnect() {
		if (!connected) {
			return;
		}
		peerDiscovery.stop();
		realtimeChannel.disconnect();
		peerIds.clear();
		bannedPeerIds.clear();
		connected = false;
	}

	public List<CoopLobby> knownLobbies() {
		return peerDiscovery.knownLobbies();
	}

	public void kickAllPlayers() {
		bannedPeerIds.addAll( peerIds );
		peerIds.clear();
		GLog.w( "[Co-op] All connected players were kicked from this local session." );
	}

	public void clearBans() {
		bannedPeerIds.clear();
		GLog.i( "[Co-op] Player bans cleared." );
	}

	private CoopLobby buildLobbyAnnouncement() {
		int maxPlayers = 6;
		int playerCount = Math.min( maxPlayers, 1 + peerIds.size() );
		boolean accepting = playerCount < maxPlayers;
		return new CoopLobby(
			roomId,
			playerId,
			playerCount,
			maxPlayers,
			accepting,
			System.currentTimeMillis(),
			enabledClassesCsv() );
	}

	private String enabledClassesCsv() {
		List<String> unlocked = new ArrayList<String>();
		for (HeroClass heroClass : HeroClass.values()) {
			if (heroClass == HeroClass.WARRIOR || Badges.isUnlocked( heroClass.masteryBadge() )) {
				unlocked.add( heroClass.name() );
			}
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < unlocked.size(); i++) {
			if (i > 0) {
				sb.append( "," );
			}
			sb.append( unlocked.get( i ) );
		}
		return sb.toString();
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
