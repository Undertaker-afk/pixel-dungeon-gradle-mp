package com.watabou.pixeldungeon.multiplayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import org.json.JSONObject;

import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.PixelDungeon;
import com.watabou.pixeldungeon.actors.Actor;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.actors.hero.HeroClass;
import com.watabou.pixeldungeon.coop.gameplay.RemoteHero;
import com.watabou.pixeldungeon.Badges;
import com.watabou.pixeldungeon.levels.Level;
import com.watabou.pixeldungeon.levels.Room;
import com.watabou.pixeldungeon.scenes.GameScene;
import com.watabou.pixeldungeon.sprites.CharSprite;
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
	private final Map<String, RemoteHero> remoteHeroesByPeerId = new HashMap<String, RemoteHero>();
	private final Map<String, Deque<CoopEvent>> intentQueueByPeerId = new HashMap<String, Deque<CoopEvent>>();
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
					ensureRemoteHero( peerId );
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
		publishDespawn();
		peerDiscovery.stop();
		realtimeChannel.disconnect();
		clearRemoteHeroes();
		peerIds.clear();
		bannedPeerIds.clear();
		intentQueueByPeerId.clear();
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
		if (isHost()) {
			broadcastTurnOutcome( playerId, CoopEvent.Kind.MOVE, fromCell, toCell );
		}
	}

	public void publishAttack( int fromCell, int toCell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.attack( playerId, Dungeon.depth, fromCell, toCell ) );
		if (isHost()) {
			broadcastTurnOutcome( playerId, CoopEvent.Kind.ATTACK, fromCell, toCell );
		}
	}

	public void publishDespawn() {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.despawn( playerId, Dungeon.depth ) );
		GLog.i( "[Co-op][RemoteHero] local despawn published actor=%s depth=%d", playerId, Integer.valueOf( Dungeon.depth ) );
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
		if (event.kind == CoopEvent.Kind.TURN_OUTCOME) {
			applyTurnOutcome( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.DESPAWN) {
			despawnRemoteHero( event.actorId, "remote-event" );
			return;
		}
		if (event.depth != Dungeon.depth) {
			return;
		}

		if (event.kind == CoopEvent.Kind.MOVE || event.kind == CoopEvent.Kind.ATTACK || event.kind == CoopEvent.Kind.USE) {
			enqueueIntent( event );
			if (!isHost()) {
				RemoteHero remoteHero = ensureRemoteHero( event.actorId );
				if (remoteHero != null) {
					consumeIntentForRemoteHero( remoteHero );
				}
			}
		}
	}

	public CoopSimulationPolicy simulationPolicy() {
		return PixelDungeon.coopRuntimeSettings().simulationPolicy();
	}

	public boolean isLocalInputReady() {
		if (Dungeon.hero == null) {
			return false;
		}
		if (!PixelDungeon.coopEnabled()) {
			return Dungeon.hero.ready;
		}
		if (simulationPolicy() == CoopSimulationPolicy.LOCKSTEP) {
			return Dungeon.hero.ready && !hasPendingRemoteIntents();
		}
		return Dungeon.hero.ready;
	}

	public boolean resolveRemoteHeroTurn( RemoteHero remoteHero ) {
		if (remoteHero == null || !PixelDungeon.coopEnabled() || Dungeon.depth <= 0) {
			return false;
		}
		if (simulationPolicy() != CoopSimulationPolicy.HOST_AUTHORITATIVE && !isHost()) {
			return false;
		}
		return consumeIntentForRemoteHero( remoteHero );
	}

	private boolean hasPendingRemoteIntents() {
		for (Deque<CoopEvent> queue : intentQueueByPeerId.values()) {
			if (queue != null && !queue.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private void enqueueIntent( CoopEvent event ) {
		if (event == null || event.actorId == null) {
			return;
		}
		Deque<CoopEvent> queue = intentQueueByPeerId.get( event.actorId );
		if (queue == null) {
			queue = new LinkedList<CoopEvent>();
			intentQueueByPeerId.put( event.actorId, queue );
		}
		queue.addLast( event );
		List<CoopEvent> ordered = new ArrayList<CoopEvent>( queue );
		Collections.sort( ordered, new Comparator<CoopEvent>() {
			@Override
			public int compare( CoopEvent left, CoopEvent right ) {
				int byTimestamp = left.sentAtMs < right.sentAtMs ? -1 : (left.sentAtMs == right.sentAtMs ? 0 : 1);
				if (byTimestamp != 0) {
					return byTimestamp;
				}
				String leftPeer = left.actorId == null ? "" : left.actorId;
				String rightPeer = right.actorId == null ? "" : right.actorId;
				return leftPeer.compareTo( rightPeer );
			}
		} );
		queue.clear();
		queue.addAll( ordered );
	}

	private boolean consumeIntentForRemoteHero( RemoteHero remoteHero ) {
		Deque<CoopEvent> queue = intentQueueByPeerId.get( remoteHero.peerId );
		if (queue == null || queue.isEmpty()) {
			return false;
		}
		CoopEvent intent = queue.pollFirst();
		if (intent == null) {
			return false;
		}
		if (intent.kind == CoopEvent.Kind.MOVE) {
			applyRemoteMove( remoteHero, intent.fromCell, intent.toCell );
		} else if (intent.kind == CoopEvent.Kind.ATTACK) {
			applyRemoteAttack( remoteHero, intent.fromCell, intent.toCell );
		}
		if (isHost()) {
			broadcastTurnOutcome( remoteHero.peerId, intent.kind, intent.fromCell, intent.toCell );
		}
		return true;
	}

	private RemoteHero ensureRemoteHero( String peerId ) {
		if (peerId == null || peerId.length() == 0 || Dungeon.level == null) {
			return null;
		}
		RemoteHero remoteHero = remoteHeroesByPeerId.get( peerId );
		if (remoteHero != null) {
			return remoteHero;
		}
		int spawnCell = remoteSpawnCell();
		remoteHero = new RemoteHero( peerId, peerId, spawnCell );
		remoteHeroesByPeerId.put( peerId, remoteHero );
		GameScene.addRemoteHero( remoteHero );
		if (remoteHero.sprite != null) {
			remoteHero.sprite.showStatus( CharSprite.NEUTRAL, "SPAWN" );
		}
		GLog.i( "[Co-op][RemoteHero] spawn peer=%s cell=%d", peerId, Integer.valueOf( spawnCell ) );
		return remoteHero;
	}

	private int remoteSpawnCell() {
		if (Dungeon.level == null) {
			return 0;
		}
		if (isCellAvailable( Dungeon.level.entrance )) {
			return Dungeon.level.entrance;
		}
		if (Dungeon.hero != null && isCellAvailable( Dungeon.hero.pos )) {
			return Dungeon.hero.pos;
		}
		for (int i = 0; i < Level.LENGTH; i++) {
			if (isCellAvailable( i )) {
				return i;
			}
		}
		return Dungeon.level.entrance;
	}

	private void applyRemoteMove( RemoteHero remoteHero, int fromCell, int toCell ) {
		if (remoteHero == null) {
			return;
		}
		int safeFrom = remoteHero.pos;
		if (fromCell >= 0 && fromCell < Level.LENGTH) {
			safeFrom = fromCell;
		}
		if (!isValidMovement( remoteHero, safeFrom, toCell )) {
			GLog.w( "[Co-op][RemoteHero] rejected move peer=%s %d->%d", remoteHero.peerId, Integer.valueOf( safeFrom ), Integer.valueOf( toCell ) );
			return;
		}
		Actor.freeCell( remoteHero.pos );
		remoteHero.snapshotPosition( toCell );
		Actor.occupyCell( remoteHero );
		if (remoteHero.sprite != null) {
			remoteHero.sprite.move( safeFrom, toCell );
			remoteHero.sprite.showStatus( CharSprite.NEUTRAL, "MOVE" );
		}
		GLog.i( "[Co-op][RemoteHero] move peer=%s %d->%d", remoteHero.peerId, Integer.valueOf( safeFrom ), Integer.valueOf( toCell ) );
	}

	private void applyRemoteAttack( RemoteHero remoteHero, int fromCell, int toCell ) {
		if (remoteHero == null || toCell < 0 || toCell >= Level.LENGTH) {
			return;
		}
		if (fromCell >= 0 && fromCell < Level.LENGTH && remoteHero.pos != fromCell) {
			if (isCellAvailableForRemote( remoteHero, fromCell )) {
				Actor.freeCell( remoteHero.pos );
				remoteHero.snapshotPosition( fromCell );
				Actor.occupyCell( remoteHero );
				if (remoteHero.sprite != null) {
					remoteHero.sprite.place( fromCell );
				}
			}
		}
		if (remoteHero.sprite != null) {
			remoteHero.sprite.attack( toCell );
			remoteHero.sprite.showStatus( CharSprite.WARNING, "ATK" );
		}
		GLog.i( "[Co-op][RemoteHero] attack peer=%s from=%d to=%d", remoteHero.peerId, Integer.valueOf( remoteHero.pos ), Integer.valueOf( toCell ) );
	}

	private boolean isValidMovement( RemoteHero remoteHero, int fromCell, int toCell ) {
		if (toCell < 0 || toCell >= Level.LENGTH || fromCell < 0 || fromCell >= Level.LENGTH) {
			return false;
		}
		if (!Level.passable[toCell]) {
			return false;
		}
		if (!Level.adjacent( fromCell, toCell )) {
			return false;
		}
		return isCellAvailableForRemote( remoteHero, toCell );
	}

	private boolean isCellAvailable( int cell ) {
		if (cell < 0 || cell >= Level.LENGTH) {
			return false;
		}
		if (!Level.passable[cell]) {
			return false;
		}
		return Actor.findChar( cell ) == null;
	}

	private boolean isCellAvailableForRemote( RemoteHero remoteHero, int cell ) {
		if (cell < 0 || cell >= Level.LENGTH || !Level.passable[cell]) {
			return false;
		}
		Char occupant = Actor.findChar( cell );
		return occupant == null || occupant == remoteHero;
	}

	private void despawnRemoteHero( String peerId, String reason ) {
		RemoteHero remoteHero = remoteHeroesByPeerId.remove( peerId );
		if (remoteHero == null) {
			return;
		}
		intentQueueByPeerId.remove( peerId );
		GameScene.removeRemoteHero( remoteHero );
		GLog.i( "[Co-op][RemoteHero] despawn peer=%s reason=%s", peerId, reason );
	}

	private void clearRemoteHeroes() {
		for (String peerId : new ArrayList<String>( remoteHeroesByPeerId.keySet() )) {
			despawnRemoteHero( peerId, "disconnect" );
		}
		remoteHeroesByPeerId.clear();
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

	private void broadcastTurnOutcome( String actorId, CoopEvent.Kind actionKind, int fromCell, int toCell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		JSONObject payload = new JSONObject();
		payload.put( "actorId", actorId );
		payload.put( "action", actionKind == null ? "UNKNOWN" : actionKind.name() );
		payload.put( "from", fromCell );
		payload.put( "to", toCell );
		payload.put( "depth", Dungeon.depth );
		payload.put( "floorTransitionDepth", Dungeon.depth );
		RemoteHero remoteHero = remoteHeroesByPeerId.get( actorId );
		int actorPos = remoteHero != null ? remoteHero.pos : (Dungeon.hero == null ? -1 : Dungeon.hero.pos);
		payload.put( "actorPos", actorPos );
		payload.put( "hpDelta", 0 );
		payload.put( "death", false );
		realtimeChannel.send( CoopEvent.turnOutcome( playerId, Dungeon.depth, payload.toString() ) );
	}

	private void applyTurnOutcome( CoopEvent event ) {
		if (event == null || event.payload == null || event.payload.length() == 0) {
			return;
		}
		try {
			JSONObject payload = new JSONObject( event.payload );
			String actorId = payload.optString( "actorId", null );
			RemoteHero remoteHero = ensureRemoteHero( actorId );
			if (remoteHero == null) {
				return;
			}
			int actorPos = payload.optInt( "actorPos", remoteHero.pos );
			if (isCellAvailableForRemote( remoteHero, actorPos )) {
				Actor.freeCell( remoteHero.pos );
				remoteHero.snapshotPosition( actorPos );
				Actor.occupyCell( remoteHero );
				if (remoteHero.sprite != null) {
					remoteHero.sprite.place( actorPos );
				}
			}
			if (payload.optBoolean( "death", false )) {
				despawnRemoteHero( actorId, "turn-outcome-death" );
			}
		} catch (Exception ignored) {
			GLog.w( "[Co-op] Failed to apply TURN_OUTCOME payload from %s", event.actorId );
		}
	}
}
