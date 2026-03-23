package com.watabou.pixeldungeon.multiplayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.PixelDungeon;
import com.watabou.pixeldungeon.actors.hero.HeroClass;
import com.watabou.pixeldungeon.Badges;
import com.watabou.pixeldungeon.levels.Level;
import com.watabou.pixeldungeon.levels.Room;
import com.watabou.pixeldungeon.utils.GLog;
import com.watabou.utils.Random;

public class CoopManager {

	private static final CoopManager INSTANCE = new CoopManager();
	private static final long LEVEL_SYNC_WAIT_MS = 5000L;

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
	private final Object levelSyncLock = new Object();
	private final Map<Integer, Long> levelSeedByDepth = new HashMap<Integer, Long>();
	private final Map<Integer, String> levelHashByDepth = new HashMap<Integer, String>();
	private long sessionSeedBase;

	private CoopManager() {
	}

	public void ensureConnected( String configuredRoomId ) {
		if (connected) {
			return;
		}

		roomId = configuredRoomId;
		playerId = "hero-" + System.currentTimeMillis();
		sessionSeedBase = System.currentTimeMillis() ^ (((long)roomId.hashCode()) << 21);

		realtimeChannel.connect( roomId, playerId, new RealtimeChannel.Listener() {
			@Override
			public void onEvent( CoopEvent event ) {
				handleIncoming( event );
			}
		} );

		peerDiscovery.start( roomId, playerId, new PeerDiscovery.Listener() {
			@Override
			public void onPeer( PeerEndpoint peerEndpoint ) {
				String peerId = peerEndpoint == null ? null : peerEndpoint.peerId;
				if (peerId == null || peerId.length() == 0 || playerId.equals( peerId ) || bannedPeerIds.contains( peerId )) {
					return;
				}
				realtimeChannel.addPeer( peerEndpoint );
				if (peerIds.add( peerId )) {
					GLog.i( "[Co-op] peer connected: %s", peerId );
					resendLevelSyncToLateJoiner( peerId );
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
		synchronized (levelSyncLock) {
			levelSeedByDepth.clear();
			levelHashByDepth.clear();
		}
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
			enabledClassesCsv(),
			realtimeChannel.localEndpoint() );
	}

	private String enabledClassesCsv() {
		StringBuilder sb = new StringBuilder();
		for (HeroClass heroClass : HeroClass.values()) {
			if (heroClass == HeroClass.WARRIOR || Badges.isUnlocked( heroClass.masteryBadge() )) {
				if (sb.length() > 0) {
					sb.append( "," );
				}
				sb.append( heroClass.name() );
			}
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

	public void prepareLevelSeed( int depth ) {
		if (!connected || !PixelDungeon.coopEnabled() || depth <= 0) {
			return;
		}
		Long seed = isHost() ? ensureHostLevelSeed( depth ) : waitForLevelSeed( depth );
		if (seed == null) {
			GLog.w( "[Co-op] Missing LEVEL_SYNC for depth %d; falling back to local RNG.", Integer.valueOf( depth ) );
			return;
		}
		Random.setSeed( seed.longValue() );
		GLog.i( "[Co-op] Applied canonical level seed depth=%d seed=%d", Integer.valueOf( depth ), seed );
	}

	public void onLevelCreated( int depth, Level level ) {
		if (!connected || !PixelDungeon.coopEnabled() || depth <= 0 || level == null) {
			return;
		}
		String hash = computeLevelHash( level );
		synchronized (levelSyncLock) {
			levelHashByDepth.put( Integer.valueOf( depth ), hash );
		}
		GLog.i( "[Co-op] Level hash depth=%d hash=%s", Integer.valueOf( depth ), hash );
		realtimeChannel.send( CoopEvent.levelHash( playerId, depth, hash ) );
	}

	private void handleIncoming( CoopEvent event ) {
		if (!connected || event == null || playerId == null) {
			return;
		}
		if (playerId.equals( event.actorId )) {
			return;
		}
		if (event.kind == CoopEvent.Kind.LEVEL_SYNC) {
			handleLevelSync( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.LEVEL_HASH) {
			handleLevelHash( event );
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

	private boolean isHost() {
		return PixelDungeon.coopRole() == CoopRole.DUNGEON_MASTER;
	}

	private Long ensureHostLevelSeed( int depth ) {
		Long seed;
		boolean created = false;
		synchronized (levelSyncLock) {
			seed = levelSeedByDepth.get( Integer.valueOf( depth ) );
			if (seed == null) {
				seed = Long.valueOf( nextSeedForDepth( depth ) );
				levelSeedByDepth.put( Integer.valueOf( depth ), seed );
				created = true;
			}
		}
		if (created) {
			GLog.i( "[Co-op] Host created canonical seed depth=%d seed=%d", Integer.valueOf( depth ), seed );
		}
		realtimeChannel.send( CoopEvent.levelSync( playerId, depth, seed.longValue() ) );
		return seed;
	}

	private Long waitForLevelSeed( int depth ) {
		synchronized (levelSyncLock) {
			Long seed = levelSeedByDepth.get( Integer.valueOf( depth ) );
			if (seed != null) {
				return seed;
			}
			long deadline = System.currentTimeMillis() + LEVEL_SYNC_WAIT_MS;
			while ((seed = levelSeedByDepth.get( Integer.valueOf( depth ) )) == null && System.currentTimeMillis() < deadline) {
				try {
					levelSyncLock.wait( 250L );
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			return seed;
		}
	}

	private long nextSeedForDepth( int depth ) {
		return sessionSeedBase ^ (depth * 0x9E3779B97F4A7C15L);
	}

	private void handleLevelSync( CoopEvent event ) {
		if (event.levelSeed == null || event.depth <= 0) {
			return;
		}
		synchronized (levelSyncLock) {
			levelSeedByDepth.put( Integer.valueOf( event.depth ), event.levelSeed );
			levelSyncLock.notifyAll();
		}
		GLog.i( "[Co-op] Received LEVEL_SYNC depth=%d seed=%d from %s", Integer.valueOf( event.depth ), event.levelSeed, event.actorId );
	}

	private void handleLevelHash( CoopEvent event ) {
		if (event.levelHash == null || event.depth <= 0) {
			return;
		}
		String localHash;
		synchronized (levelSyncLock) {
			localHash = levelHashByDepth.get( Integer.valueOf( event.depth ) );
		}
		if (localHash == null) {
			GLog.i( "[Co-op] Received remote level hash depth=%d from %s before local hash was ready: %s",
				Integer.valueOf( event.depth ), event.actorId, event.levelHash );
			return;
		}
		if (localHash.equals( event.levelHash )) {
			GLog.p( "[Co-op] Determinism OK depth=%d (%s)", Integer.valueOf( event.depth ), localHash );
		} else {
			GLog.w( "[Co-op] Determinism mismatch depth=%d local=%s remote=%s from=%s",
				Integer.valueOf( event.depth ), localHash, event.levelHash, event.actorId );
		}
	}

	private void resendLevelSyncToLateJoiner( String peerId ) {
		if (!isHost() || peerId == null) {
			return;
		}
		Long seed;
		synchronized (levelSyncLock) {
			seed = levelSeedByDepth.get( Integer.valueOf( Dungeon.depth ) );
		}
		if (seed == null || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.levelSync( playerId, Dungeon.depth, seed.longValue() ) );
		GLog.i( "[Co-op] Resent LEVEL_SYNC to peer=%s depth=%d seed=%d", peerId, Integer.valueOf( Dungeon.depth ), seed );
	}

	private String computeLevelHash( Level level ) {
		CRC32 crc = new CRC32();
		updateInt( crc, Dungeon.depth );
		updateInt( crc, level.entrance );
		updateInt( crc, level.exit );
		int[] map = level.map;
		if (map != null) {
			for (int tile : map) {
				updateInt( crc, tile );
			}
		}
		for (String roomSignature : roomSignatures( level )) {
			updateBytes( crc, roomSignature );
		}
		return Long.toHexString( crc.getValue() );
	}

	private List<String> roomSignatures( Level level ) {
		ArrayList<String> signatures = new ArrayList<String>();
		if (level == null) {
			return signatures;
		}
		try {
			Field roomsField = level.getClass().getSuperclass().getDeclaredField( "rooms" );
			roomsField.setAccessible( true );
			Object roomsObj = roomsField.get( level );
			if (!(roomsObj instanceof Set<?>)) {
				return signatures;
			}
			for (Object item : (Set<?>)roomsObj) {
				if (!(item instanceof Room)) {
					continue;
				}
				Room room = (Room)item;
				signatures.add( room.left + ":" + room.top + ":" + room.right + ":" + room.bottom + ":" + room.type.name() + ":" + room.connected.size() );
			}
			Collections.sort( signatures );
		} catch (Exception ignored) {
		}
		return signatures;
	}

	private void updateInt( CRC32 crc, int value ) {
		crc.update( (value >>> 24) & 0xFF );
		crc.update( (value >>> 16) & 0xFF );
		crc.update( (value >>> 8) & 0xFF );
		crc.update( value & 0xFF );
	}

	private void updateBytes( CRC32 crc, String value ) {
		if (value == null) {
			return;
		}
		for (int i = 0; i < value.length(); i++) {
			crc.update( value.charAt( i ) );
		}
	}
}
